package io.github.stormino.megatron.mega;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Mega file integrity check (meta-MAC).
 *
 * <p>The plaintext is split into Mega's fixed MAC chunks (128 KiB, 256 KiB, ... up to 1 MiB, then
 * 1 MiB each). Each chunk gets an AES-CBC-MAC with IV {@code [n0, n1, n0, n1]}; the chunk MACs
 * are folded with AES into a file MAC, which is condensed to 64 bits and compared with words 6-7
 * of the file key.
 */
public final class MegaMac {

    private static final int FIRST_CHUNK = 0x20000;
    private static final int MAX_CHUNK = 0x100000;

    private MegaMac() {
    }

    /** True if the plaintext at {@code file} matches the meta-MAC carried by {@code key}. */
    public static boolean verify(Path file, MegaCrypto.FileKey key) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
            return metaMac(in, Files.size(file), key) == key.metaMac();
        }
    }

    /** Computes the 64-bit meta-MAC of {@code size} plaintext bytes read from {@code in}. */
    public static long metaMac(InputStream in, long size, MegaCrypto.FileKey key) throws IOException {
        try {
            SecretKeySpec aes = new SecretKeySpec(MegaCrypto.a32ToBytes(key.aesKey()), "AES");
            int n0 = (int) (key.nonce() >>> 32);
            int n1 = (int) key.nonce();
            IvParameterSpec iv = new IvParameterSpec(MegaCrypto.a32ToBytes(new int[]{n0, n1, n0, n1}));
            Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
            Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, aes);

            byte[] fileMac = new byte[16];
            byte[] buf = new byte[MAX_CHUNK];
            long pos = 0;
            int chunk = FIRST_CHUNK;
            boolean last = false;
            while (!last) {
                int len;
                if (pos + chunk < size) {
                    len = chunk;
                } else {
                    len = (int) (size - pos);
                    last = true;
                }
                readFully(in, buf, len);
                int padded = (len + 15) / 16 * 16;
                java.util.Arrays.fill(buf, len, padded, (byte) 0);
                // CBC-MAC of an empty chunk (0-byte file) is the IV itself.
                byte[] out = iv.getIV();
                if (padded > 0) {
                    cbc.init(Cipher.ENCRYPT_MODE, aes, iv);
                    out = cbc.doFinal(buf, 0, padded);
                } else {
                    padded = 16;
                }
                for (int i = 0; i < 16; i++) {
                    fileMac[i] ^= out[padded - 16 + i];
                }
                fileMac = ecb.doFinal(fileMac);
                pos += len;
                if (chunk < MAX_CHUNK) {
                    chunk += FIRST_CHUNK;
                }
            }
            int[] w = MegaCrypto.bytesToA32(fileMac);
            return ((long) (w[0] ^ w[1]) << 32) | ((w[2] ^ w[3]) & 0xFFFFFFFFL);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new IOException("file shorter than expected");
            }
            off += n;
        }
    }

    /**
     * Builds the 8-word file key Mega would publish for this AES key, nonce and meta-MAC
     * (inverse of {@link MegaCrypto#deriveFileKey}); mainly useful for tests.
     */
    public static int[] packFileKey(int[] aesKey, long nonce, long metaMac) {
        int n0 = (int) (nonce >>> 32);
        int n1 = (int) nonce;
        int m0 = (int) (metaMac >>> 32);
        int m1 = (int) metaMac;
        return new int[]{aesKey[0] ^ n0, aesKey[1] ^ n1, aesKey[2] ^ m0, aesKey[3] ^ m1, n0, n1, m0, m1};
    }
}
