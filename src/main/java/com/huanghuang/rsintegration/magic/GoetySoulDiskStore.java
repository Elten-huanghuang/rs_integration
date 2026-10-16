package com.huanghuang.rsintegration.magic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** 统一盘附加的 Goety 灵魂库存；旧格式没有该记录时视为零。 */
public final class GoetySoulDiskStore {
    public static final String NAME = "goety-souls.bin";

    private GoetySoulDiskStore() {}

    public static void write(DataOutputStream output, int amount) throws IOException {
        if (amount < 0) throw new IOException("统一盘灵魂数量非法");
        output.writeInt(amount);
    }

    public static int read(DataInputStream input) throws IOException {
        int amount = input.readInt();
        if (input.available() != 0 || amount < 0) throw new IOException("统一盘灵魂记录非法");
        return amount;
    }
}
