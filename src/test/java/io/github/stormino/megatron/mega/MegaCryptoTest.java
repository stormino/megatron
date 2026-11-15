package io.github.stormino.megatron.mega;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MegaCryptoTest {

    @Test
    void base64RoundTripAndLegacyCommas() {
        byte[] data = {1, 2, 3, (byte) 0xFB, (byte) 0xFF};
        String enc = MegaCrypto.base64UrlEncode(data);
        assertArrayEquals(data, MegaCrypto.base64UrlDecode(enc));
        assertArrayEquals(data, MegaCrypto.base64UrlDecode(enc.substring(0, 2) + "," + enc.substring(2)));
    }

    @Test
    void a32RoundTrip() {
        int[] a = {0x01020304, 0xFFFFFFFF, 0, 0x7F000001};
        assertArrayEquals(a, MegaCrypto.bytesToA32(MegaCrypto.a32ToBytes(a)));
        assertArrayEquals(a, MegaCrypto.base64ToA32(MegaCrypto.a32ToBase64(a)));
    }

    @Test
    void deriveFileKeyXorsHalvesAndBuildsNonce() {
        int[] raw = {1, 2, 3, 4, 0x10, 0x20, 0x30, 0x40};
        MegaCrypto.FileKey k = MegaCrypto.deriveFileKey(raw);
        assertArrayEquals(new int[]{1 ^ 0x10, 2 ^ 0x20, 3 ^ 0x30, 4 ^ 0x40}, k.aesKey());
        assertEquals((0x10L << 32) | 0x20L, k.nonce());
        assertThrows(IllegalArgumentException.class, () -> MegaCrypto.deriveFileKey(new int[4]));
    }

    @Test
    void ctrAtOffsetMatchesContinuousStream() throws Exception {
        MegaCrypto.FileKey key = MegaCrypto.deriveFileKey(new int[]{11, 22, 33, 44, -1, 0x7FFFFFFF, 77, 88});
        byte[] data = new byte[4096];
        new Random(1).nextBytes(data);
        byte[] whole = MegaCrypto.ctrCipherAt(key, 0).doFinal(data);
        Cipher mid = MegaCrypto.ctrCipherAt(key, 1024);
        byte[] part = mid.doFinal(data, 1024, 2048);
        for (int i = 0; i < part.length; i++) {
            assertEquals(whole[1024 + i], part[i]);
        }
        assertThrows(IllegalArgumentException.class, () -> MegaCrypto.ctrCipherAt(key, 10));
    }

    @Test
    void attrRoundTripIgnoresTrailingGarbage() {
        int[] key = {5, 6, 7, 8};
        byte[] blob = MegaCrypto.encryptAttr("{\"n\":\"caffè.mkv\"}", key);
        JsonNode attrs = MegaCrypto.decryptAttr(blob, key);
        assertEquals("caffè.mkv", attrs.get("n").asText());
        assertNull(MegaCrypto.decryptAttr(blob, new int[]{1, 1, 1, 1}));
        // Residue of an older, longer name after the JSON terminator.
        byte[] withResidue = MegaCrypto.encryptAttr("{\"n\":\"a\"}xxxxxxxxxxxxxxxxxxxxxxxxxx", key);
        assertEquals("a", MegaCrypto.decryptAttr(withResidue, key).get("n").asText());
    }

    @Test
    void keyEncryptionIsBlockwise() {
        int[] master = {9, 8, 7, 6};
        int[] plain = {1, 2, 3, 4, 1, 2, 3, 4};
        int[] enc = MegaCrypto.encryptKey(plain, master);
        // ECB: identical plaintext blocks give identical ciphertext blocks.
        assertArrayEquals(java.util.Arrays.copyOf(enc, 4), java.util.Arrays.copyOfRange(enc, 4, 8));
        assertArrayEquals(plain, MegaCrypto.decryptKey(enc, master));
    }
}
