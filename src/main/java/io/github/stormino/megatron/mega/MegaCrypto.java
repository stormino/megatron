package io.github.stormino.megatron.mega;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Mega cryptographic primitives. Pure helpers: no I/O, no global state.
 *
 * <p>Mega keys are handled as "a32" arrays: big-endian 32-bit words.
 */
public final class MegaCrypto {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] ZERO_IV = new byte[16];

    private MegaCrypto() {
    }

    /** Decodes Mega's base64url flavour (no padding, legacy keys may contain commas). */
    public static byte[] base64UrlDecode(String data) {
        String cleaned = data.replace(",", "").replace('+', '-').replace('/', '_').replace("=", "");
        return Base64.getUrlDecoder().decode(cleaned);
    }

    /** Encodes bytes as base64url without padding. */
    public static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] a32ToBytes(int[] a32) {
        ByteBuffer buf = ByteBuffer.allocate(a32.length * 4);
        for (int w : a32) {
            buf.putInt(w);
        }
        return buf.array();
    }

    public static int[] bytesToA32(byte[] bytes) {
        int padded = (bytes.length + 3) / 4 * 4;
        ByteBuffer buf = ByteBuffer.allocate(padded);
        buf.put(bytes);
        buf.flip();
        int[] out = new int[padded / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getInt();
        }
        return out;
    }

    public static int[] base64ToA32(String s) {
        return bytesToA32(base64UrlDecode(s));
    }

    public static String a32ToBase64(int[] a32) {
        return base64UrlEncode(a32ToBytes(a32));
    }

    /**
     * Decrypts a Mega attribute blob (AES-CBC, zero IV) and returns the JSON object, or
     * {@code null} if the key is wrong or the blob is malformed.
     *
     * <p>Only the first JSON object is parsed: a file renamed on Mega can leave residual
     * ciphertext of the previous name after the terminator, which would make a strict parse fail.
     */
    public static JsonNode decryptAttr(byte[] data, int[] key) {
        if (data == null || data.length == 0 || data.length % 16 != 0 || key == null || key.length != 4) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(a32ToBytes(key), "AES"), new IvParameterSpec(ZERO_IV));
            String text = new String(cipher.doFinal(data), StandardCharsets.UTF_8);
            if (!text.startsWith("MEGA{\"")) {
                return null;
            }
            try (JsonParser parser = MAPPER.getFactory().createParser(text.substring(4))) {
                JsonNode node = MAPPER.readTree(parser);
                return node != null && node.isObject() ? node : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decrypts a node key with the shared-folder master key. Mega uses independent 16-byte
     * AES blocks (ECB), not one CBC run over the whole key.
     *
     * @return 4 words for a folder node, 8 words for a file node
     */
    public static int[] decryptKey(int[] encrypted, int[] master) {
        if (encrypted == null || encrypted.length == 0 || encrypted.length % 4 != 0) {
            throw new IllegalArgumentException("Encrypted key must be a non-empty multiple of 4 words");
        }
        if (master == null || master.length != 4) {
            throw new IllegalArgumentException("Master key must be 4 words");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(a32ToBytes(master), "AES"));
            return bytesToA32(cipher.doFinal(a32ToBytes(encrypted)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    /** Encrypts a key with a master key (inverse of {@link #decryptKey}); mainly useful for tests. */
    public static int[] encryptKey(int[] plain, int[] master) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(a32ToBytes(master), "AES"));
            return bytesToA32(cipher.doFinal(a32ToBytes(plain)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    /** Encrypts an attribute object (inverse of {@link #decryptAttr}); mainly useful for tests. */
    public static byte[] encryptAttr(String json, int[] key) {
        byte[] plain = ("MEGA" + json).getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[(plain.length + 15) / 16 * 16];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(a32ToBytes(key), "AES"), new IvParameterSpec(ZERO_IV));
            return cipher.doFinal(padded);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    /**
     * Derives the AES-CTR key and nonce from an 8-word file key: {@code k = raw[0..3] xor raw[4..7]},
     * {@code nonce = raw[4], raw[5]}, {@code metaMac = raw[6], raw[7]}.
     */
    public static FileKey deriveFileKey(int[] raw) {
        if (raw == null || raw.length < 8) {
            throw new IllegalArgumentException("File key must have at least 8 words, got "
                    + (raw == null ? 0 : raw.length));
        }
        int[] k = {raw[0] ^ raw[4], raw[1] ^ raw[5], raw[2] ^ raw[6], raw[3] ^ raw[7]};
        long nonce = ((long) raw[4] << 32) | (raw[5] & 0xFFFFFFFFL);
        long metaMac = ((long) raw[6] << 32) | (raw[7] & 0xFFFFFFFFL);
        return new FileKey(k, nonce, metaMac);
    }

    /**
     * Builds an AES-CTR cipher positioned at {@code offset} bytes into the file payload.
     *
     * @param offset must be a multiple of 16
     */
    public static Cipher ctrCipherAt(FileKey key, long offset) {
        if (offset % 16 != 0) {
            throw new IllegalArgumentException("CTR offset must be 16-byte aligned: " + offset);
        }
        ByteBuffer iv = ByteBuffer.allocate(16);
        iv.putLong(key.nonce());
        iv.putLong(offset / 16);
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(a32ToBytes(key.aesKey()), "AES"),
                    new IvParameterSpec(iv.array()));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    /**
     * Key material of a file: 4-word AES key, 64-bit CTR nonce and the expected 64-bit meta-MAC
     * (see {@link MegaMac}).
     */
    public record FileKey(int[] aesKey, long nonce, long metaMac) {
    }
}
