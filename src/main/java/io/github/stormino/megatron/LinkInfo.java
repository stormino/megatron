package io.github.stormino.megatron;

import java.util.List;
import java.util.Objects;

/**
 * What a Mega link points to, obtained without downloading anything: a single file, or the files of
 * a shared folder.
 *
 * @param url        the link as given
 * @param folder     {@code true} for folder links
 * @param name       file name, or folder name for folder links
 * @param files      downloadable files (exactly one for file links)
 * @param totalFiles files in the folder before {@code maxFolderFiles} truncation
 */
public record LinkInfo(String url, boolean folder, String name, List<Entry> files, int totalFiles) {

    public LinkInfo {
        Objects.requireNonNull(url, "url");
        files = List.copyOf(files);
    }

    /** Sum of the sizes of {@link #files()}. */
    public long totalBytes() {
        return files.stream().mapToLong(Entry::size).sum();
    }

    /** Files left out because the folder exceeds {@code maxFolderFiles}. */
    public int truncated() {
        return Math.max(0, totalFiles - files.size());
    }

    /**
     * One downloadable file.
     *
     * @param url      link to pass to {@link Megatron#download}: the file link itself, or the folder
     *                 job URL for files inside a folder
     * @param fileName sanitized file name
     * @param size     size in bytes
     * @param folders  folders between the shared root and the file (empty for file links); for a
     *                 folder link the first element is the shared folder itself
     */
    public record Entry(String url, String fileName, long size, List<String> folders) {
        public Entry {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(fileName, "fileName");
            folders = List.copyOf(folders);
        }
    }
}
