package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MagicResourceDiskStoreTest extends BootstrapTest {
    @Test
    void readsLegacyIntRecordWithoutLosingOrder() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            for (MagicResourceKind kind : MagicResourceKind.values()) {
                output.writeInt(100 + kind.ordinal());
            }
        }

        long[] amounts = MagicResourceDiskStore.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertArrayEquals(new long[]{100, 101, 102}, amounts);
    }

    @Test
    void writesAndReadsLongRecord() throws Exception {
        long[] original = {Integer.MAX_VALUE + 1L, 17L, Long.MAX_VALUE};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            MagicResourceDiskStore.write(output, original);
        }

        long[] copy = MagicResourceDiskStore.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertArrayEquals(original, copy);
    }

    @Test
    void readsLegacyStandaloneSoulRecord() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(321);
        }

        int amount = MagicResourceDiskStore.readLegacySouls(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(321, amount);
    }
}
