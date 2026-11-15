package io.github.stormino.megatron.mega;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.stormino.megatron.util.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the node listing of a shared folder into self-contained per-file jobs: decrypts node keys
 * and names, rebuilds the tree, sanitises every path segment and de-duplicates collisions.
 *
 * <p>Pure with respect to the network: it receives the already fetched nodes.
 */
public final class FolderExpander {

    private static final Logger log = LoggerFactory.getLogger(FolderExpander.class);

    static final int NODE_FILE = 0;
    static final int NODE_FOLDER = 1;
    static final int NODE_ROOT = 2;
    private static final int MAX_TREE_DEPTH = 64;

    private FolderExpander() {
    }

    /** One file of an expanded folder. */
    public record FolderFile(String nodeHandle, String keyB64, long size, List<String> relPath, String folderId) {
        public FolderFile {
            relPath = List.copyOf(relPath);
        }

        /** Internal job URL, downloadable like a public file link. */
        public String jobUrl() {
            return MegaLinks.buildFolderJobUrl(folderId, nodeHandle, keyB64, relPath, size);
        }

        public String fileName() {
            return relPath.get(relPath.size() - 1);
        }
    }

    /**
     * Result of a folder expansion.
     *
     * @param totalFiles files found before the {@code maxFiles} cap
     * @param skipped    files dropped because their key could not be decrypted or verified
     */
    public record FolderExpansion(String folderId, String folderName, List<FolderFile> files, int totalFiles,
                                  int folders, int skipped) {
        public FolderExpansion {
            files = List.copyOf(files);
        }

        public int truncated() {
            return totalFiles - files.size();
        }

        public long totalBytes() {
            return files.stream().mapToLong(FolderFile::size).sum();
        }

        public List<String> jobUrls() {
            return files.stream().map(FolderFile::jobUrl).toList();
        }
    }

    public static FolderExpansion expand(MegaLinks.FolderLink link, List<JsonNode> nodes, int maxFiles) {
        int[] master = MegaCrypto.base64ToA32(link.keyB64());
        return expand(link.folderId(), master, nodes, link.subKind(), link.subHandle(), maxFiles);
    }

    public static FolderExpansion expand(String folderId, int[] master, List<JsonNode> nodes,
                                         MegaLinks.SubKind subKind, String subHandle, int maxFiles) {
        if (master.length != 4) {
            throw new MegaApiException("Invalid folder key (" + master.length + " words)");
        }
        if (nodes.isEmpty()) {
            throw new MegaApiException("Folder listing is empty");
        }
        Map<String, JsonNode> byHandle = new LinkedHashMap<>();
        for (JsonNode n : nodes) {
            if (n.path("h").isTextual()) {
                byHandle.put(n.get("h").asText(), n);
            }
        }
        String root = resolveRoot(byHandle, folderId);
        if (root == null) {
            throw new MegaApiException("Folder root node not found");
        }

        Map<String, NodeKey> keys = new HashMap<>();
        byHandle.forEach((h, n) -> keys.put(h, nodeKeyAndName(n, folderId, master)));
        String folderName = keys.get(root).name != null ? keys.get(root).name : "folder_" + folderId;

        Set<String> allowed = null;
        if (subHandle != null) {
            JsonNode sub = byHandle.get(subHandle);
            if (sub == null) {
                throw new MegaApiException("Node " + subHandle + " is not in the folder");
            }
            MegaLinks.SubKind kind = subKind;
            if (kind == MegaLinks.SubKind.AUTO) {
                kind = sub.path("t").asInt(-1) == NODE_FILE ? MegaLinks.SubKind.FILE : MegaLinks.SubKind.FOLDER;
            }
            allowed = kind == MegaLinks.SubKind.FILE ? Set.of(subHandle) : descendants(byHandle, subHandle);
        }

        int folders = 0;
        int skipped = 0;
        List<FolderFile> raw = new ArrayList<>();
        List<List<String>> chains = new ArrayList<>();
        for (var e : byHandle.entrySet()) {
            String handle = e.getKey();
            int type = e.getValue().path("t").asInt(-1);
            if (type == NODE_FOLDER) {
                folders++;
                continue;
            }
            if (type != NODE_FILE || (allowed != null && !allowed.contains(handle))) {
                continue;
            }
            NodeKey nk = keys.get(handle);
            if (nk.key == null || nk.key.length < 8) {
                log.warn("Folder node {} has no decryptable key: skipped", handle);
                skipped++;
                continue;
            }
            if (!nk.verified) {
                // A guessed key would silently produce corrupt bytes after AES-CTR.
                log.warn("Folder node {}: key cannot be verified against attributes, skipped", handle);
                skipped++;
                continue;
            }
            int[] key8 = java.util.Arrays.copyOf(nk.key, 8);
            raw.add(new FolderFile(handle, MegaCrypto.a32ToBase64(key8), Math.max(0, e.getValue().path("s").asLong(0)),
                    List.of("x"), folderId));
            chains.add(pathSegments(byHandle, keys, handle, root, folderName));
        }

        // Deterministic order: by path, then handle.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> String.join("/", chains.get(i)).toLowerCase(Locale.ROOT))
                .thenComparing(i -> raw.get(i).nodeHandle()));
        List<List<String>> sortedChains = order.stream().map(chains::get).toList();
        List<List<String>> deduped = dedupPaths(sortedChains);

        List<FolderFile> files = new ArrayList<>();
        for (int j = 0; j < order.size(); j++) {
            FolderFile f = raw.get(order.get(j));
            files.add(new FolderFile(f.nodeHandle(), f.keyB64(), f.size(), deduped.get(j), folderId));
        }
        int total = files.size();
        if (maxFiles > 0 && total > maxFiles) {
            log.warn("Folder has {} files: limited to the first {}", total, maxFiles);
            files = files.subList(0, maxFiles);
        }
        return new FolderExpansion(folderId, FileNames.sanitizeFolderName(folderName, "folder_" + folderId), files,
                total, folders, skipped);
    }

    private record NodeKey(int[] key, String name, boolean verified) {
    }

    /**
     * The {@code k} field can hold several {@code owner:blob} entries separated by {@code /}, and the
     * owner does not necessarily match the folder id of the link. All candidates are tried and the
     * one that decrypts the attributes wins.
     */
    static List<int[]> keyCandidates(String kField, String folderId, int[] master) {
        List<int[]> preferred = new ArrayList<>();
        List<int[]> others = new ArrayList<>();
        if (kField == null || kField.isEmpty()) {
            return preferred;
        }
        for (String part : kField.split("/")) {
            int colon = part.indexOf(':');
            if (colon < 0 || colon == part.length() - 1) {
                continue;
            }
            String owner = part.substring(0, colon);
            try {
                int[] key = MegaCrypto.decryptKey(MegaCrypto.base64ToA32(part.substring(colon + 1)), master);
                (owner.equals(folderId) ? preferred : others).add(key);
            } catch (IllegalArgumentException ignored) {
                // undecryptable entry
            }
        }
        preferred.addAll(others);
        return preferred;
    }

    private static int[] attrKey(int[] nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        if (nodeKey.length >= 8) {
            return MegaCrypto.deriveFileKey(nodeKey).aesKey();
        }
        return nodeKey.length == 4 ? nodeKey : null;
    }

    private static String readName(byte[] blob, int[] key) {
        if (key == null || key.length != 4) {
            return null;
        }
        JsonNode attrs = MegaCrypto.decryptAttr(blob, key);
        if (attrs == null || !attrs.path("n").isTextual() || attrs.get("n").asText().isBlank()) {
            return null;
        }
        return attrs.get("n").asText();
    }

    private static NodeKey nodeKeyAndName(JsonNode node, String folderId, int[] master) {
        List<int[]> candidates = keyCandidates(node.path("k").asText(null), folderId, master);
        byte[] blob = null;
        if (node.path("a").isTextual() && !node.get("a").asText().isEmpty()) {
            try {
                blob = MegaCrypto.base64UrlDecode(node.get("a").asText());
            } catch (IllegalArgumentException ignored) {
                // not base64
            }
        }
        if (blob != null) {
            for (int[] key : candidates) {
                String name = readName(blob, attrKey(key));
                if (name != null) {
                    return new NodeKey(key, name, true);
                }
            }
            // Share root: attributes encrypted with the master key, no own k to verify.
            String name = readName(blob, master);
            if (name != null) {
                return new NodeKey(candidates.isEmpty() ? null : candidates.get(0), name, candidates.isEmpty());
            }
        }
        int[] first = candidates.isEmpty() ? null : candidates.get(0);
        // No attributes to verify against: accept only when there was nothing to choose from.
        return new NodeKey(first, null, blob == null && candidates.size() == 1);
    }

    /** Root handle: type-2 node, else the node whose handle equals the link id, else an orphan (folders first). */
    static String resolveRoot(Map<String, JsonNode> byHandle, String folderId) {
        for (var e : byHandle.entrySet()) {
            if (e.getValue().path("t").asInt(-1) == NODE_ROOT) {
                return e.getKey();
            }
        }
        if (byHandle.containsKey(folderId)) {
            return folderId;
        }
        List<String> orphans = new ArrayList<>();
        for (var e : byHandle.entrySet()) {
            String parent = e.getValue().path("p").asText(null);
            if (parent == null || !byHandle.containsKey(parent)) {
                orphans.add(e.getKey());
            }
        }
        if (orphans.isEmpty()) {
            return null;
        }
        List<String> folders = orphans.stream()
                .filter(h -> byHandle.get(h).path("t").asInt(-1) != NODE_FILE).sorted().toList();
        return folders.isEmpty() ? orphans.stream().sorted().findFirst().orElse(null) : folders.get(0);
    }

    private static Set<String> descendants(Map<String, JsonNode> byHandle, String root) {
        Map<String, List<String>> children = new HashMap<>();
        byHandle.forEach((h, n) -> {
            String p = n.path("p").asText(null);
            if (p != null) {
                children.computeIfAbsent(p, k -> new ArrayList<>()).add(h);
            }
        });
        Set<String> out = new HashSet<>();
        out.add(root);
        Deque<String> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            for (String c : children.getOrDefault(stack.pop(), List.of())) {
                if (out.add(c)) {
                    stack.push(c);
                }
            }
        }
        return out;
    }

    private static List<String> pathSegments(Map<String, JsonNode> byHandle, Map<String, NodeKey> keys,
                                             String handle, String root, String folderName) {
        List<String> chain = new ArrayList<>();
        String own = keys.get(handle).name;
        chain.add(own != null ? own : "mega_" + handle);
        String current = byHandle.get(handle).path("p").asText(null);
        Set<String> seen = new HashSet<>(Set.of(handle));
        int depth = 0;
        while (current != null && !current.equals(root) && byHandle.containsKey(current) && seen.add(current)
                && depth < MAX_TREE_DEPTH) {
            String name = keys.get(current).name;
            chain.add(name != null ? name : "folder_" + current);
            current = byHandle.get(current).path("p").asText(null);
            depth++;
        }
        chain.add(folderName);
        java.util.Collections.reverse(chain);
        return chain;
    }

    /**
     * Sanitises each segment and makes colliding paths unique with a {@code " (n)"} suffix.
     * Case-insensitive, because Windows and macOS filesystems are.
     */
    static List<List<String>> dedupPaths(List<List<String>> paths) {
        List<List<String>> clean = new ArrayList<>();
        for (List<String> p : paths) {
            List<String> c = new ArrayList<>();
            for (int i = 0; i < p.size() - 1; i++) {
                c.add(FileNames.sanitizeFolderName(p.get(i), "folder"));
            }
            c.add(FileNames.sanitizeFileName(p.get(p.size() - 1), "download"));
            clean.add(c);
        }
        Set<String> dirKeys = new HashSet<>();
        for (List<String> p : clean) {
            for (int i = 1; i < p.size(); i++) {
                dirKeys.add(String.join("/", p.subList(0, i)).toLowerCase(Locale.ROOT));
            }
        }
        Set<String> seen = new HashSet<>();
        List<List<String>> out = new ArrayList<>();
        for (List<String> path : clean) {
            String key = String.join("/", path).toLowerCase(Locale.ROOT);
            if (!seen.contains(key) && !dirKeys.contains(key)) {
                seen.add(key);
                out.add(path);
                continue;
            }
            List<String> head = path.subList(0, path.size() - 1);
            String name = path.get(path.size() - 1);
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            for (int n = 2; ; n++) {
                String suffix = " (" + n + ")";
                int budget = FileNames.MAX_FILE_NAME_LENGTH - suffix.length() - ext.length();
                String trimmed = budget > 0 ? stem.substring(0, Math.min(stem.length(), budget)).strip() : "";
                String candidate = FileNames.sanitizeFileName(trimmed + suffix + ext, "download" + suffix + ext);
                List<String> np = new ArrayList<>(head);
                np.add(candidate);
                String nk = String.join("/", np).toLowerCase(Locale.ROOT);
                if (!seen.contains(nk) && !dirKeys.contains(nk)) {
                    seen.add(nk);
                    out.add(np);
                    break;
                }
            }
        }
        return out;
    }
}
