package io.github.stormino.megatron.mega;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MegaLinksTest {

    @Test
    void fileLinks() {
        var f = MegaLinks.parseFile("https://mega.nz/file/AbC-_123#KeY_-x9").orElseThrow();
        assertEquals("AbC-_123", f.handle());
        assertEquals("KeY_-x9", f.keyB64());
        var legacy = MegaLinks.parseFile("https://mega.nz/#!hAnDle!k,ey").orElseThrow();
        assertEquals("hAnDle", legacy.handle());
        assertEquals("k,ey", legacy.keyB64());
        assertTrue(MegaLinks.parseFile("https://example.com").isEmpty());
    }

    @Test
    void folderLinks() {
        var plain = MegaLinks.parseFolder("https://mega.nz/folder/FoLd#mAsTeR").orElseThrow();
        assertEquals(new MegaLinks.FolderLink("FoLd", "mAsTeR"), plain);
        var file = MegaLinks.parseFolder("https://mega.nz/folder/FoLd#mAsTeR/file/NoDe").orElseThrow();
        assertEquals(MegaLinks.SubKind.FILE, file.subKind());
        assertEquals("NoDe", file.subHandle());
        var sub = MegaLinks.parseFolder("https://mega.nz/folder/FoLd#mAsTeR/folder/SuB").orElseThrow();
        assertEquals(MegaLinks.SubKind.FOLDER, sub.subKind());
        var legacy = MegaLinks.parseFolder("https://mega.nz/#F!FoLd!mAsTeR!NoDe").orElseThrow();
        assertEquals(MegaLinks.SubKind.AUTO, legacy.subKind());
        // A folder link with a selected file is not a public file link.
        assertTrue(MegaLinks.parseFile("https://mega.nz/folder/FoLd#mAsTeR/file/NoDe").isEmpty());
        assertTrue(MegaLinks.isFolderLink("https://mega.nz/folder/FoLd#mAsTeR"));
    }

    @Test
    void folderJobRoundTrip() {
        String url = MegaLinks.buildFolderJobUrl("FoLd", "NoDe", "kEy8w", List.of("Share", "sub dir", "è.txt"), 42L);
        var job = MegaLinks.parseFolderJob(url).orElseThrow();
        assertEquals("FoLd", job.folderId());
        assertEquals("NoDe", job.nodeHandle());
        assertEquals("kEy8w", job.nodeKeyB64());
        assertEquals(List.of("Share", "sub dir", "è.txt"), job.relPath());
        assertEquals(42L, job.size());
        assertEquals("è.txt", job.fileName());
        assertFalse(MegaLinks.isFolderLink(url));
        assertTrue(MegaLinks.parseFile(url).isEmpty());
        assertTrue(MegaLinks.isDownloadable(url));
    }

    @Test
    void extractHandle() {
        assertEquals("AbC", MegaLinks.extractHandle("https://mega.nz/file/AbC#k").orElseThrow());
        assertEquals("AbC", MegaLinks.extractHandle("https://mega.nz/file/AbC").orElseThrow());
        assertEquals("NoDe", MegaLinks.extractHandle("https://mega.nz/folder/F#k/file/NoDe").orElseThrow());
        assertTrue(MegaLinks.extractHandle("https://mega.nz/folder/F#k").isEmpty());
    }
}
