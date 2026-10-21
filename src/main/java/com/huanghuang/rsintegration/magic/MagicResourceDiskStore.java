package com.huanghuang.rsintegration.magic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 统一盘附加的魔法资源库存记录。
 *
 * <p>按 {@link MagicResourceKind} 的枚举顺序写入非负数量，缺失的记录视为零。
 * 新增资源只能追加在枚举末尾，不能调整已有常量的顺序，否则旧存档会被读成
 * 另一种资源。旧档没有这个文件时全部按零处理。</p>
 */
public final class MagicResourceDiskStore {
    /** 归墟盘目录中的固定文件名。 */
    public static final String NAME = "magic-resources.bin";
    /** 1.5.x 及更早版本保存 Goety 灵魂的独立文件名。 */
    public static final String LEGACY_SOULS_NAME = "goety-souls.bin";
    /** 旧灵魂文件只包含一个 int。 */
    public static final int LEGACY_SOULS_BYTES = Integer.BYTES;
    /** 旧格式每种资源固定占 4 字节（int）。 */
    public static final int LEGACY_BYTES = Math.multiplyExact(MagicResourceKind.values().length, Integer.BYTES);
    /** 新格式每种资源固定占 8 字节（long）。 */
    public static final int MAX_BYTES = Math.multiplyExact(MagicResourceKind.values().length, Long.BYTES);

    private MagicResourceDiskStore() {}

    public static void write(DataOutputStream output, long[] amounts) throws IOException {
        if (amounts == null || amounts.length != MagicResourceKind.values().length) {
            throw new IOException("统一盘魔法资源记录长度非法");
        }
        for (int index = 0; index < amounts.length; index++) {
            if (amounts[index] < 0) {
                throw new IOException("统一盘" + MagicResourceKind.values()[index].id() + "数量非法");
            }
            output.writeLong(amounts[index]);
        }
    }

    public static long[] read(DataInputStream input) throws IOException {
        int remaining = input.available();
        if (remaining != LEGACY_BYTES && remaining != MAX_BYTES) {
            throw new IOException("统一盘魔法资源记录长度非法: " + remaining);
        }
        boolean legacy = remaining == LEGACY_BYTES;
        long[] amounts = new long[MagicResourceKind.values().length];
        for (int index = 0; index < amounts.length; index++) {
            long amount = legacy ? input.readInt() : input.readLong();
            if (amount < 0) {
                throw new IOException("统一盘" + MagicResourceKind.values()[index].id() + "记录非法");
            }
            amounts[index] = amount;
        }
        if (input.available() != 0) throw new IOException("统一盘魔法资源记录尾部非法");
        return amounts;
    }

    /** 读取旧版独立灵魂文件，供磁盘加载时迁移到统一魔法资源表。 */
    public static int readLegacySouls(DataInputStream input) throws IOException {
        int amount = input.readInt();
        if (input.available() != 0 || amount < 0) {
            throw new IOException("统一盘旧版灵魂记录非法");
        }
        return amount;
    }
}
