package io.github.stormino.megatron.download;

import java.util.ArrayList;
import java.util.List;

/**
 * Inclusive byte range {@code [start, end]} of a file. Identity is the range only (the index is
 * informational), so chunks loaded from a sidecar match freshly planned ones.
 */
record Chunk(int index, long start, long end) implements Comparable<Chunk> {

    long length() {
        return end - start + 1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chunk c && c.start == start && c.end == end;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(start) * 31 + Long.hashCode(end);
    }

    @Override
    public int compareTo(Chunk o) {
        return Long.compare(start, o.start);
    }

    /** Fixed-size split; chunk size is aligned down to the AES block (16 bytes). */
    static List<Chunk> split(long fileSize, long chunkSize) {
        long size = Math.max(16, chunkSize / 16 * 16);
        List<Chunk> out = new ArrayList<>();
        if (fileSize <= 0) {
            return out;
        }
        if (fileSize <= DownloadSettings.MIN_PARALLEL_BYTES) {
            out.add(new Chunk(0, 0, fileSize - 1));
            return out;
        }
        int i = 0;
        for (long start = 0; start < fileSize; start += size) {
            out.add(new Chunk(i++, start, Math.min(start + size - 1, fileSize - 1)));
        }
        return out;
    }
}
