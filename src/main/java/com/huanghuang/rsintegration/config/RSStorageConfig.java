package com.huanghuang.rsintegration.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** RS 存储维护、混合终端与统一磁盘配置。 */
public final class RSStorageConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue MERGE_CONNECTION_REBUILDS;
    public static final ForgeConfigSpec.BooleanValue VERIFY_SAVES;
    public static final ForgeConfigSpec.BooleanValue REFRESH_OPEN_GRIDS;
    public static final ForgeConfigSpec.BooleanValue EXPAND_GRID_TRANSFER;
    public static final ForgeConfigSpec.IntValue GRID_TRANSFER_PARTS;
    public static final ForgeConfigSpec.BooleanValue CHECK_FLUID_CONTAINER;
    public static final ForgeConfigSpec.BooleanValue UNIFIED_GRID;
    public static final ForgeConfigSpec.BooleanValue UNIFIED_DISK;
    public static final ForgeConfigSpec.IntValue DISK_ITEM_ENTRIES;
    public static final ForgeConfigSpec.IntValue DISK_FLUID_ENTRIES;
    public static final ForgeConfigSpec.IntValue DISK_ENTRY_BYTES;
    public static final ForgeConfigSpec.IntValue DISK_PAYLOAD_BYTES;

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
        builder.comment("物品/流体统一磁盘。开关需重启，关闭不删除库存；条目容量调大后旧盘在下次加载时自动扩容，调小不缩容。载荷容量只影响新盘。")
                .push("unifiedDisk");
        UNIFIED_DISK = builder.comment("默认开启。关闭后在创造栏和配方浏览器隐藏并禁用配方，保留旧盘与文件但不挂载和读写库存。需要重启。")
                .define("enabled", true);
        DISK_ITEM_ENTRIES = builder.defineInRange("maxItemEntries", 262144, 1, 262144);
        DISK_FLUID_ENTRIES = builder.defineInRange("maxFluidEntries", 262144, 1, 262144);
        DISK_ENTRY_BYTES = builder.defineInRange("maxPayloadBytesPerEntry", 1048576, 1024, 1048576);
        DISK_PAYLOAD_BYTES = builder.defineInRange("maxPayloadBytesPerDisk", 268435456, 1048576, 536870912);
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

    public static int diskLimit(ForgeConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
