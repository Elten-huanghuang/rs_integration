package com.huanghuang.rsintegration.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** 原版 RS 存储维护选项，与磁盘物品注册和容量无关。 */
public final class RSStorageConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue MERGE_CONNECTION_REBUILDS;
    public static final ForgeConfigSpec.BooleanValue VERIFY_SAVES;
    public static final ForgeConfigSpec.BooleanValue REFRESH_OPEN_GRIDS;
    public static final ForgeConfigSpec.BooleanValue EXPAND_GRID_TRANSFER;
    public static final ForgeConfigSpec.IntValue GRID_TRANSFER_PARTS;
    public static final ForgeConfigSpec.BooleanValue CHECK_FLUID_CONTAINER;
    public static final ForgeConfigSpec.BooleanValue UNIFIED_GRID;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("RS 原版存储维护。布尔开关在配置重新加载后生效；终端传输选项需要重启。")
                .push("storage");
        MERGE_CONNECTION_REBUILDS = builder.comment("节点连接和解绑完成后，合并排队的重复缓存重建。数量增量不受影响。")
                .define("mergeConnectionRebuilds", true);
        VERIFY_SAVES = builder.comment("保存 RS 数据前写入并校验临时文件，成功替换后才清除 dirty。失败等待下次保存。")
                .define("verifySaves", true);
        REFRESH_OPEN_GRIDS = builder.comment("缓存完整重建后刷新打开的物品、流体及便携终端。会发送完整列表。")
                .define("refreshOpenGrids", true);
        EXPAND_GRID_TRANSFER = builder.comment("提高终端完整列表消息的拆包上限，不改变客户端请求上限。需要重启。")
                .define("expandGridTransfer", true);
        GRID_TRANSFER_PARTS = builder.comment("终端完整列表允许的最大拆包数量；原版通常为 10。数值越大，传输和内存成本越高。需要重启。")
                .defineInRange("gridTransferParts", 100, 10, 2000);
        CHECK_FLUID_CONTAINER = builder.comment("流体终端扣减库存前，模拟检查一桶流体是否可提取及装桶。")
                .define("checkFluidContainer", true);
        builder.pop();
        builder.comment("联网普通终端和合成终端的物品/流体混合显示。服务端决定是否启用，重新打开菜单生效。")
                .push("unifiedGrid");
        UNIFIED_GRID = builder.comment("默认开启。关闭后使用 RS 原版单类型订阅、显示和取放路径。")
                .define("enabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    private RSStorageConfig() {}

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "rs_integration/storage.toml");
    }

    public static boolean enabled(ForgeConfigSpec.BooleanValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static int transferParts() {
        return SPEC.isLoaded() ? GRID_TRANSFER_PARTS.get() : GRID_TRANSFER_PARTS.getDefault();
    }
}
