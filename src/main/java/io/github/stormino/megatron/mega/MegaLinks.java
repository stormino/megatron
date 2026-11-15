package io.github.stormino.megatron.mega;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and building of public Mega URLs: single files, folders (optionally with a selected
 * node), legacy formats, and the library's internal self-contained "folder job" form produced
 * when a folder is expanded.
 *
 * <p>Patterns are tried from the most specific to the most generic.
 */
public final class MegaLinks {

    public static final String MEGA_PREFIX = "https://mega.nz/";

    private static final String H = "[A-Za-z0-9_-]+";
    private static final String K = "[A-Za-z0-9_,-]+";

    /** Internal form: {@code /folder/<id>/file/<node>?p=<b64url(rel_path)>[&s=<size>]#<key8w>}. */
    private static final Pattern FOLDER_JOB = Pattern.compile(
            "/folder/(" + H + ")/file/(" + H + ")\\?p=(" + H + ")(?:&s=(\\d+))?#(" + K + ")");
    private static final Pattern FOLDER_FILE = Pattern.compile("/folder/(" + H + ")#(" + K + ")/file/(" + H + ")");
    private static final Pattern FOLDER_SUB = Pattern.compile("/folder/(" + H + ")#(" + K + ")/folder/(" + H + ")");
    private static final Pattern FOLDER = Pattern.compile("/folder/(" + H + ")#(" + K + ")");
    private static final Pattern LEGACY_FOLDER = Pattern.compile("#F!(" + H + ")!(" + K + ")(?:!(" + H + "))?");
    private static final Pattern FILE = Pattern.compile("/file/(" + H + ")#(" + K + ")");
    private static final Pattern LEGACY_FILE = Pattern.compile("#!(" + H + ")!(" + K + ")");
    private static final Pattern FILE_HANDLE_ONLY = Pattern.compile("/file/(" + H + ")");

    private MegaLinks() {
    }

    /** Public link to a single file. */
    public record FileLink(String handle, String keyB64) {
    }

    /** What a folder link points at inside the folder. */
    public enum SubKind {
        /** A single file inside the folder. */
        FILE,
        /** A sub-folder. */
        FOLDER,
        /** Legacy format: the node type is discovered at listing time. */
        AUTO
    }

    /** Public link to a folder, optionally with a selected node. */
    public record FolderLink(String folderId, String keyB64, SubKind subKind, String subHandle) {
        public FolderLink(String folderId, String keyB64) {
            this(folderId, keyB64, null, null);
        }
    }

    /**
     * Self-contained job for one file of an expanded folder: carries everything needed to resolve
     * it on any proxy, at any retry, without listing the folder again.
     *
     * @param relPath relative path, file name as last segment, rooted at the shared folder's name
     * @param size    expected size in bytes, or {@code null} if unknown
     */
    public record FolderJobLink(String folderId, String nodeHandle, String nodeKeyB64, List<String> relPath, Long size) {
        public FolderJobLink {
            relPath = List.copyOf(relPath);
        }

        public String fileName() {
            return relPath.isEmpty() ? "" : relPath.get(relPath.size() - 1);
        }
    }

    public static String buildFolderJobUrl(String folderId, String nodeHandle, String nodeKeyB64,
                                           List<String> relPath, Long size) {
        if (isBlank(folderId) || isBlank(nodeHandle) || isBlank(nodeKeyB64)) {
            throw new IllegalArgumentException("folderId, nodeHandle and nodeKeyB64 are required");
        }
        if (relPath == null || relPath.isEmpty()) {
            throw new IllegalArgumentException("relPath must not be empty");
        }
        String packed = MegaCrypto.base64UrlEncode(String.join("/", relPath).getBytes(StandardCharsets.UTF_8));
        String sizePart = size != null && size >= 0 ? "&s=" + size : "";
        return MEGA_PREFIX + "folder/" + folderId + "/file/" + nodeHandle + "?p=" + packed + sizePart + "#" + nodeKeyB64;
    }

    public static Optional<FolderJobLink> parseFolderJob(String url) {
        if (url == null || url.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = FOLDER_JOB.matcher(url);
        if (!m.find()) {
            return Optional.empty();
        }
        String rel;
        try {
            rel = new String(MegaCrypto.base64UrlDecode(m.group(3)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        List<String> segments = Arrays.stream(rel.split("/")).filter(s -> !s.isEmpty()).toList();
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        Long size = m.group(4) != null ? Long.parseLong(m.group(4)) : null;
        return Optional.of(new FolderJobLink(m.group(1), m.group(2), m.group(5), segments, size));
    }

    /** Parses a folder link (with optional selected node). Does not match internal job URLs. */
    public static Optional<FolderLink> parseFolder(String url) {
        if (url == null || url.isEmpty() || parseFolderJob(url).isPresent()) {
            return Optional.empty();
        }
        Matcher m = FOLDER_FILE.matcher(url);
        if (m.find()) {
            return Optional.of(new FolderLink(m.group(1), m.group(2), SubKind.FILE, m.group(3)));
        }
        m = FOLDER_SUB.matcher(url);
        if (m.find()) {
            return Optional.of(new FolderLink(m.group(1), m.group(2), SubKind.FOLDER, m.group(3)));
        }
        m = FOLDER.matcher(url);
        if (m.find()) {
            return Optional.of(new FolderLink(m.group(1), m.group(2)));
        }
        m = LEGACY_FOLDER.matcher(url);
        if (m.find()) {
            String sub = m.group(3);
            return Optional.of(new FolderLink(m.group(1), m.group(2), sub != null ? SubKind.AUTO : null, sub));
        }
        return Optional.empty();
    }

    /** Parses a single-file link (new or legacy format). Folder forms never match. */
    public static Optional<FileLink> parseFile(String url) {
        if (url == null || url.isEmpty() || parseFolderJob(url).isPresent() || parseFolder(url).isPresent()) {
            return Optional.empty();
        }
        Matcher m = FILE.matcher(url);
        if (m.find()) {
            return Optional.of(new FileLink(m.group(1), m.group(2)));
        }
        m = LEGACY_FILE.matcher(url);
        if (m.find()) {
            return Optional.of(new FileLink(m.group(1), m.group(2)));
        }
        return Optional.empty();
    }

    /** True only for folder links that still need expanding (not for already-expanded jobs). */
    public static boolean isFolderLink(String url) {
        return parseFolder(url).isPresent();
    }

    /** True for anything the library can download directly: file links and folder jobs. */
    public static boolean isDownloadable(String url) {
        return parseFolderJob(url).isPresent() || parseFile(url).isPresent();
    }

    /**
     * Extracts the file handle from a link without network access (useful for de-duplication).
     * Empty for unexpanded folder links and unrecognised URLs.
     */
    public static Optional<String> extractHandle(String url) {
        if (url == null || url.isEmpty()) {
            return Optional.empty();
        }
        Optional<FolderJobLink> job = parseFolderJob(url);
        if (job.isPresent()) {
            return Optional.of(job.get().nodeHandle());
        }
        Optional<FolderLink> folder = parseFolder(url);
        if (folder.isPresent()) {
            FolderLink f = folder.get();
            return f.subKind() == SubKind.FILE ? Optional.of(f.subHandle()) : Optional.empty();
        }
        Matcher m = FILE_HANDLE_ONLY.matcher(url);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        m = LEGACY_FILE.matcher(url);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
