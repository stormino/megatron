package io.github.stormino.megatron.mega;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MegaMacTest {

    private static final int[] AES = {0x0A0B0C0D, 0x01020304, 0x7FFFFFFF, 0x80000000};
    private static final long NONCE = 0x1122334455667788L;

    private static long mac(byte[] data) throws Exception {
        return MegaMac.metaMac(new ByteArrayInputStream(data), data.length, new MegaCrypto.FileKey(AES, NONCE, 0));
    }

    @Test
    void anyBitFlipChangesTheMac() throws Exception {
        // Sizes around Mega's chunk boundaries: 128K, 128K+256K, ..., and past the 1 MiB steady state.
        for (int size : new int[]{0, 1, 16, 0x20000, 0x20000 + 1, 0x60000, 0x480000, 0x480000 + 0x100000 + 3}) {
            byte[] data = new byte[size];
            new Random(size).nextBytes(data);
            long base = mac(data);
            assertEquals(base, mac(data.clone()), "deterministic");
            if (size > 0) {
                data[size - 1] ^= 0x40;
                assertNotEquals(base, mac(data), "last byte of size " + size);
            }
        }
    }

    @Test
    void packedKeyRoundTrips() {
        int[] raw = MegaMac.packFileKey(AES, NONCE, 0xCAFEBABEDEADBEEFL);
        MegaCrypto.FileKey k = MegaCrypto.deriveFileKey(raw);
        assertArrayEquals(AES, k.aesKey());
        assertEquals(NONCE, k.nonce());
        assertEquals(0xCAFEBABEDEADBEEFL, k.metaMac());
    }
}
