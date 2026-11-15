package io.github.stormino.megatron.mega;

/**
 * A Mega file resolved to a temporary CDN URL.
 *
 * @param handle   file (or folder node) handle
 * @param key      AES-CTR key material of the payload
 * @param cdnUrl   temporary download URL (expires; re-resolve when the CDN starts refusing it)
 * @param size     size in bytes
 * @param fileName sanitised file name
 */
public record ResolvedFile(String handle, MegaCrypto.FileKey key, String cdnUrl, long size, String fileName) {
}
