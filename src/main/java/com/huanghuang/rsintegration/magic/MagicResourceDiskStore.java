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
    /** 每种资源固定占 4 字节（int）。 */
    public static final int MAX_BYTES = Math.multiplyExact(MagicResourceKind.values().length, 4);

    private MagicResourceDiskStore() {}

    public static void write(DataOutputStream output, int[] amounts) throws IOException {
        if (amounts == null || amounts.length != MagicResourceKind.values().length) {
            throw new IOException("统一盘魔法资源记录长度非法");
        }
        for (int index = 0; index < amounts.length; index++) {
            if (amounts[index] < 0) {
                throw new IOException("统一盘" + MagicResourceKind.values()[index].id() + "数量非法");
            }
            output.writeInt(amounts[index]);
        }
    }

    public static int[] read(DataInputStream input) throws IOException {
        int[] amounts = new int[MagicResourceKind.values().length];
        for (int index = 0; index < amounts.length; index++) {
            int amount = input.readInt();
            if (amount < 0) {
                throw new IOException("统一盘" + MagicResourceKind.values()[index].id() + "记录非法");
            }
            amounts[index] = amount;
        }
        if (input.available() != 0) throw new IOException("统一盘魔法资源记录尾部非法");
        return amounts;
    }
}
