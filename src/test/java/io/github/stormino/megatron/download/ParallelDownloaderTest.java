package io.github.stormino.megatron.download;

import io.github.stormino.megatron.BatchResult;
import io.github.stormino.megatron.LinkInfo;
import io.github.stormino.megatron.Megatron;
import io.github.stormino.megatron.mega.FolderExpanderTest;
import io.github.stormino.megatron.mega.MegaApiException;
import io.github.stormino.megatron.proxy.ProxyPool;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
class ParallelDownloaderTest {

    static final int MIB = 1024 * 1024;

    @TempDir
    Path dir;
    FakeMega mega;

    @BeforeEach
    void setUp() throws Exception {
        mega = new FakeMega(4);
    }

    @AfterEach
    void tearDown() throws Exception {
        mega.close();
    }

    private Megatron.Builder megatron(int chunkAttempts, int fileAttempts) {
        DownloadSettings settings = DownloadSettings.builder()
                .connectionsPerFile(3)
                .chunkSize(MIB)
                .chunkAttempts(chunkAttempts)
                .maxFailedChunks(10)
                .backoffMax(Duration.ZERO)
                .retryAfterCap(Duration.ZERO)
                .tooManyIpsBackoff(Duration.ZERO)
                .minFreeDiskBytes(0)
                .progressInterval(Duration.ofMillis(100))
                .build();
        ProxyPool.Settings pool = new ProxyPool.Settings(0, 5, -10, -20, 100, 0.5, Duration.ofMillis(200), true, 0.3, 2);
        return Megatron.builder()
                .proxies(mega.proxies())
                .apiUrl(FakeMega.API)
                .poolSettings(pool)
                .downloadSettings(settings)
                .fileAttempts(fileAttempts);
    }

    @Test
    void downloadsDecryptsAndSurvivesCdnErrors() throws Exception {
        var f = mega.addFile("HaNdLe", "movie.bin", 5 * MIB + 123, 1);
        mega.cdnScript.add(new MockResponse().setResponseCode(403).setHeader("Retry-After", "1"));
        mega.cdnScript.add(new MockResponse().setResponseCode(429));
        mega.cdnScript.add(new MockResponse().setResponseCode(200).setBody("not a range response"));
        AtomicInteger completedChunks = new AtomicInteger();
        DownloadListener listener = new DownloadListener() {
            @Override
            public void onChunkCompleted(String url, int idx, long bytes, io.github.stormino.megatron.proxy.ProxyInfo p,
                                         long ms) {
                completedChunks.incrementAndGet();
            }
        };
        try (Megatron m = megatron(8, 1).build()) {
            DownloadResult r = m.download(f.link(), dir, listener, new DownloadControl());
            assertEquals(dir.resolve("movie.bin"), r.path());
            assertArrayEquals(f.plain(), Files.readAllBytes(r.path()));
            assertEquals(6, completedChunks.get());
            assertFalse(Files.exists(dir.resolve("movie.bin.part")));
            assertFalse(Files.exists(dir.resolve("movie.bin.part.progress.json")));
            // Running again is a no-op.
            assertTrue(m.download(f.link(), dir).skipped());
        }
    }

    @Test
    void expiredCdnUrlIsReResolved() throws Exception {
        var f = mega.addFile("ExPiRe", "x.bin", 3 * MIB, 2);
        AtomicInteger seen = new AtomicInteger();
        mega.cdnOverride = range -> {
            if (seen.incrementAndGet() == 2) {
                mega.cdnGeneration = 2; // URLs of generation 1 now answer 503
            }
            return null;
        };
        try (Megatron m = megatron(8, 1).build()) {
            DownloadResult r = m.download(f.link(), dir);
            assertArrayEquals(f.plain(), Files.readAllBytes(r.path()));
        }
    }

    @Test
    void resumesOnlyMissingChunks() throws Exception {
        var f = mega.addFile("ReSuMe", "big.bin", 6 * MIB, 3);
        mega.cdnOverride = range -> range.startsWith("bytes=" + (3 * MIB)) || range.startsWith("bytes=" + (4 * MIB))
                ? new MockResponse().setResponseCode(500) : null;
        try (Megatron m = megatron(2, 1).build()) {
            assertThrows(DownloadException.class, () -> m.download(f.link(), dir));
        }
        assertTrue(Files.exists(dir.resolve("big.bin.part")));
        assertTrue(Files.exists(dir.resolve("big.bin.part.progress.json")));
        assertFalse(Files.exists(dir.resolve("big.bin")));

        mega.cdnOverride = range -> null;
        mega.cdnRanges.clear();
        try (Megatron m = megatron(2, 1).build()) {
            DownloadResult r = m.download(f.link(), dir);
            assertArrayEquals(f.plain(), Files.readAllBytes(r.path()));
            assertEquals(4L * MIB, r.resumedBytes());
        }
        assertEquals(2, mega.cdnRanges.size(), "only the two missing chunks are fetched again");
    }

    @Test
    void cancellationStopsAndKeepsPartialData() throws Exception {
        var f = mega.addFile("CaNcEl", "c.bin", 8 * MIB, 4);
        DownloadControl control = new DownloadControl();
        DownloadListener cancelAfterFirst = new DownloadListener() {
            @Override
            public void onChunkCompleted(String url, int idx, long bytes, io.github.stormino.megatron.proxy.ProxyInfo p,
                                         long ms) {
                control.cancel();
            }
        };
        try (Megatron m = megatron(8, 3).build()) {
            assertThrows(DownloadCancelledException.class, () -> m.download(f.link(), dir, cancelAfterFirst, control));
        }
        assertTrue(Files.exists(dir.resolve("c.bin.part")));
        assertFalse(Files.exists(dir.resolve("c.bin")));
    }

    @Test
    void corruptDataFailsMacCheckAndIsDiscarded() throws Exception {
        var f = mega.addFile("CoRrUpT", "bad.bin", 2 * MIB + 5, 5);
        mega.cdnOverride = range -> {
            if (!range.startsWith("bytes=0-")) {
                return null;
            }
            byte[] tampered = java.util.Arrays.copyOf(f.cipher(), MIB);
            tampered[1234] ^= 1;
            return new MockResponse().setResponseCode(206).setBody(new okio.Buffer().write(tampered));
        };
        try (Megatron m = megatron(8, 1).build()) {
            assertThrows(IntegrityException.class, () -> m.download(f.link(), dir));
        }
        assertFalse(Files.exists(dir.resolve("bad.bin")));
        assertFalse(Files.exists(dir.resolve("bad.bin.part")), "corrupt partial data is discarded");

        mega.cdnOverride = range -> null;
        try (Megatron m = megatron(8, 1).build()) {
            assertArrayEquals(f.plain(), Files.readAllBytes(m.download(f.link(), dir).path()));
        }
    }

    @Test
    void definitiveApiErrorsAreNotRetried() throws Exception {
        try (Megatron m = megatron(8, 3).build()) {
            MegaApiException e = assertThrows(MegaApiException.class,
                    () -> m.download("https://mega.nz/file/MiSsInG#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", dir));
            assertEquals(-9, e.apiCode());
        }
    }

    @Test
    void foldersAreExpandedIntoATree() throws Exception {
        String[] handles = {null, "F1", "F2", "F3", "F4"};
        byte[][] plains = new byte[5][];
        int[][] keys = new int[5][];
        for (int i = 1; i <= 4; i++) {
            plains[i] = new byte[1000 * i + 7];
            new java.util.Random(i).nextBytes(plains[i]);
            keys[i] = FakeMega.keyFor(plains[i], i);
            mega.files.put(handles[i], new FakeMega.StoredFile(handles[i], keys[i], "ignored", plains[i],
                    FakeMega.encrypt(keys[i], plains[i])));
        }
        var listing = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        listing.putArray("f").addAll(FolderExpanderTest.sampleNodes(keys));
        mega.folders.put("ROOT", listing);
        String master = io.github.stormino.megatron.mega.MegaCrypto.a32ToBase64(FolderExpanderTest.MASTER);
        try (Megatron m = megatron(8, 1).build()) {
            BatchResult result = m.downloadAll(List.of("https://mega.nz/folder/ROOT#" + master), dir,
                    DownloadListener.NONE, new DownloadControl());
            assertTrue(result.allSucceeded(), () -> result.failed().toString());
            assertEquals(4, result.completed().size());
        }
        assertArrayEquals(plains[1], Files.readAllBytes(dir.resolve("Share/a.txt")));
        assertArrayEquals(plains[3], Files.readAllBytes(dir.resolve("Share/A (2).TXT")));
        assertArrayEquals(plains[2], Files.readAllBytes(dir.resolve("Share/Sub/b.txt")));
        assertArrayEquals(plains[4], Files.readAllBytes(dir.resolve("Share/Sub/multi.bin")));
    }

    @Test
    void resolveDescribesAFileWithoutDownloading() throws Exception {
        var f = mega.addFile("InFo", "movie.mkv", 2 * MIB + 5, 7);
        try (Megatron m = megatron(8, 1).build()) {
            assertFalse(m.isReady());
            LinkInfo info = m.resolve(f.link());
            assertFalse(info.folder());
            assertEquals("movie.mkv", info.name());
            assertEquals(1, info.files().size());
            assertEquals(f.link(), info.files().get(0).url());
            assertEquals(2 * MIB + 5, info.totalBytes());
            assertTrue(info.files().get(0).folders().isEmpty());
            assertTrue(m.isReady(), "resolving through proxies warms the pool up");
        }
        assertTrue(mega.cdnRanges.isEmpty(), "resolve must not touch the CDN");
        assertTrue(Megatron.isMegaLink(f.link()));
        assertFalse(Megatron.isMegaLink("https://example.com/movie.mkv"));
        assertFalse(Megatron.isMegaLink(null));
    }

    @Test
    void resolveListsAFolderAndItsEntriesAreDownloadable() throws Exception {
        String[] handles = {null, "F1", "F2", "F3", "F4"};
        byte[][] plains = new byte[5][];
        int[][] keys = new int[5][];
        for (int i = 1; i <= 4; i++) {
            plains[i] = new byte[1000 * i + 7];
            new java.util.Random(i).nextBytes(plains[i]);
            keys[i] = FakeMega.keyFor(plains[i], i);
            mega.files.put(handles[i], new FakeMega.StoredFile(handles[i], keys[i], "ignored", plains[i],
                    FakeMega.encrypt(keys[i], plains[i])));
        }
        var listing = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        listing.putArray("f").addAll(FolderExpanderTest.sampleNodes(keys));
        mega.folders.put("ROOT", listing);
        String master = io.github.stormino.megatron.mega.MegaCrypto.a32ToBase64(FolderExpanderTest.MASTER);
        try (Megatron m = megatron(8, 1).build()) {
            LinkInfo info = m.resolve("https://mega.nz/folder/ROOT#" + master);
            assertTrue(info.folder());
            assertEquals("Share", info.name());
            assertEquals(4, info.files().size());
            assertEquals(0, info.truncated());
            var sub = info.files().stream().filter(e -> e.fileName().equals("b.txt")).findFirst().orElseThrow();
            assertEquals(List.of("Share", "Sub"), sub.folders());
            DownloadResult r = m.download(sub.url(), dir, "renamed.txt", DownloadListener.NONE, new DownloadControl());
            assertEquals(dir.resolve("renamed.txt"), r.path());
            assertArrayEquals(plains[2], Files.readAllBytes(r.path()));
        }
    }

    @Test
    void downloadsUnderACustomFileName() throws Exception {
        var f = mega.addFile("ReNaMe", "Some.Movie.2024.1080p.mkv", MIB + 3, 9);
        java.util.concurrent.atomic.AtomicReference<Path> target = new java.util.concurrent.atomic.AtomicReference<>();
        DownloadListener listener = new DownloadListener() {
            @Override
            public void onResolved(String url, String fileName, long size, Path t) {
                target.set(t);
            }
        };
        try (Megatron m = megatron(8, 1).build()) {
            DownloadResult r = m.download(f.link(), dir, "Some Movie (2024).mkv", listener, new DownloadControl());
            assertEquals(dir.resolve("Some Movie (2024).mkv"), r.path());
            assertEquals(r.path(), target.get());
            assertArrayEquals(f.plain(), Files.readAllBytes(r.path()));
            // Path separators cannot escape the output directory.
            DownloadResult r2 = m.download(f.link(), dir, "../escape.mkv", DownloadListener.NONE, new DownloadControl());
            assertEquals(dir, r2.path().getParent());
        }
    }
}
