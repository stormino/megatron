package io.github.stormino.megatron.mega;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FolderExpanderTest {

    static final ObjectMapper M = new ObjectMapper();
    public static final int[] MASTER = {0x11111111, 0x22222222, 0x33333333, 0x44444444};

    static ObjectNode folder(String h, String parent, String name, int[] key) {
        ObjectNode n = M.createObjectNode().put("h", h).put("t", 1);
        if (parent != null) {
            n.put("p", parent);
        }
        int[] attrKey = key == null ? MASTER : key;
        n.put("a", MegaCrypto.base64UrlEncode(MegaCrypto.encryptAttr("{\"n\":\"" + name + "\"}", attrKey)));
        if (key != null) {
            n.put("k", parent + ":" + MegaCrypto.a32ToBase64(MegaCrypto.encryptKey(key, MASTER)));
        }
        return n;
    }

    static ObjectNode file(String h, String parent, String name, int[] key8, long size, String kField) {
        int[] attrKey = MegaCrypto.deriveFileKey(key8).aesKey();
        return M.createObjectNode().put("h", h).put("t", 0).put("p", parent).put("s", size)
                .put("a", MegaCrypto.base64UrlEncode(MegaCrypto.encryptAttr("{\"n\":\"" + name + "\"}", attrKey)))
                .put("k", kField != null ? kField
                        : parent + ":" + MegaCrypto.a32ToBase64(MegaCrypto.encryptKey(key8, MASTER)));
    }

    static int[] key8(int seed) {
        java.util.Random r = new java.util.Random(seed);
        int[] k = new int[8];
        for (int i = 0; i < 8; i++) {
            k[i] = r.nextInt();
        }
        return k;
    }

    public static int[] key8Public(int seed) {
        return key8(seed);
    }

    public static List<JsonNode> sampleNodes() {
        return sampleNodes(new int[][]{null, key8(1), key8(2), key8(3), key8(4)});
    }

    /** {@code keys[1..4]} are the 8-word keys of F1..F4. */
    public static List<JsonNode> sampleNodes(int[][] keys) {
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(folder("ROOT", null, "Share", null));
        nodes.add(folder("SUB", "ROOT", "Sub", new int[]{5, 6, 7, 8}));
        nodes.add(file("F1", "ROOT", "a.txt", keys[1], 10, null));
        nodes.add(file("F2", "SUB", "b.txt", keys[2], 20, null));
        nodes.add(file("F3", "ROOT", "A.TXT", keys[3], 30, null));
        String wrong = MegaCrypto.a32ToBase64(MegaCrypto.encryptKey(key8(99), MASTER));
        String right = MegaCrypto.a32ToBase64(MegaCrypto.encryptKey(keys[4], MASTER));
        nodes.add(file("F4", "SUB", "multi.bin", keys[4], 40, "OTHER:" + wrong + "/XYZ:" + right));
        nodes.add(file("F5", "ROOT", "broken.bin", key8(5), 50, "ROOT:" + wrong));
        return nodes;
    }

    @Test
    void expandsTreeVerifiesKeysAndDeduplicates() {
        var exp = FolderExpander.expand("ROOT", MASTER, sampleNodes(), null, null, 0);
        assertEquals("Share", exp.folderName());
        assertEquals(2, exp.folders(), "root and Sub");
        assertEquals(1, exp.skipped(), "F5 has no verifiable key");
        List<String> paths = exp.files().stream().map(f -> String.join("/", f.relPath())).toList();
        assertEquals(List.of("Share/a.txt", "Share/A (2).TXT", "Share/Sub/b.txt", "Share/Sub/multi.bin"), paths);
        var multi = exp.files().get(3);
        assertEquals(MegaCrypto.a32ToBase64(key8(4)), multi.keyB64());
        var job = MegaLinks.parseFolderJob(multi.jobUrl()).orElseThrow();
        assertEquals(List.of("Share", "Sub", "multi.bin"), job.relPath());
        assertEquals(40L, job.size());
    }

    @Test
    void subFolderSelectionKeepsRootedPaths() {
        var exp = FolderExpander.expand("ROOT", MASTER, sampleNodes(), MegaLinks.SubKind.FOLDER, "SUB", 0);
        assertEquals(List.of("Share/Sub/b.txt", "Share/Sub/multi.bin"),
                exp.files().stream().map(f -> String.join("/", f.relPath())).toList());
        var single = FolderExpander.expand("ROOT", MASTER, sampleNodes(), MegaLinks.SubKind.AUTO, "F2", 0);
        assertEquals(1, single.files().size());
    }

    @Test
    void capsFileCount() {
        var exp = FolderExpander.expand("ROOT", MASTER, sampleNodes(), null, null, 2);
        assertEquals(2, exp.files().size());
        assertEquals(4, exp.totalFiles());
        assertEquals(2, exp.truncated());
    }
}
