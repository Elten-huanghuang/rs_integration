package com.huanghuang.rsintegration.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** RS 存储维护、混合终端与万界归墟盘配置。 */
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
    public static final ForgeConfigSpec.BooleanValue FAST_DISK_MANIPULATOR;
    public static final ForgeConfigSpec.IntValue DISK_MANIPULATOR_BUDGET_MICROS;
    public static final ForgeConfigSpec.IntValue DISK_MANIPULATOR_MAX_OPS_PER_TICK;
    public static final ForgeConfigSpec.IntValue DISK_MANIPULATOR_MAX_ITEMS_PER_OPERATION;
    public static final ForgeConfigSpec.IntValue DISK_MANIPULATOR_NO_PROGRESS_COOLDOWN_TICKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("存储维护", "RS 原版存储维护。布尔开关在配置重新加载后生效；终端传输选项需要重启。")
                .push("storage");
        MERGE_CONNECTION_REBUILDS = builder.comment("合并重复连接缓存重建",
                "节点连接和解绑完成后，合并排队的重复缓存重建。数量增量不受影响。")
                .define("mergeConnectionRebuilds", true);
        VERIFY_SAVES = builder.comment("校验存储文件保存",
                "保存 RS 数据前写入并校验临时文件，成功替换后才清除 dirty。失败等待下次保存。")
                .define("verifySaves", true);
        REFRESH_OPEN_GRIDS = builder.comment("重建后刷新已打开的终端",
                "缓存完整重建后刷新打开的物品、流体及便携终端。会发送完整列表。")
                .define("refreshOpenGrids", true);
        EXPAND_GRID_TRANSFER = builder.comment("提高终端完整列表拆包上限",
                "提高终端完整列表消息的拆包上限，不改变客户端请求上限。需要重启。")
                .worldRestart()
                .define("expandGridTransfer", true);
        GRID_TRANSFER_PARTS = builder.comment("终端完整列表拆包数量上限",
                "终端完整列表允许的最大拆包数量；原版通常为 10。数值越大，传输和内存成本越高。需要重启。")
                .worldRestart()
                .defineInRange("gridTransferParts", 100, 10, 2000);
        CHECK_FLUID_CONTAINER = builder.comment("取出流体前检查容器",
                "流体终端扣减库存前，模拟检查一桶流体是否可提取及装桶。")
                .define("checkFluidContainer", true);
        builder.comment("磁盘操纵器高速搬运",
                "开启后按每 tick 时间预算分片搬运，避免原版每次只处理 1 个物品或 64 个物品。"
                        + "关闭时完全回退 RS 原版逻辑。")
                .push("diskManipulator");
        FAST_DISK_MANIPULATOR = builder.comment("启用磁盘操纵器高速搬运",
                        "开启后按每 tick 时间预算批量搬运；关闭后完全使用 RS 原版逻辑。")
                .define("enabled", true);
        DISK_MANIPULATOR_BUDGET_MICROS = builder.comment("所有磁盘操纵器共享的单 tick 时间预算（微秒）",
                "默认 1000 微秒，即 1 毫秒。数值越大搬运越快，但会占用更多服务器 tick 时间。")
                .defineInRange("budgetMicros", 1000, 100, 10000);
        DISK_MANIPULATOR_MAX_OPS_PER_TICK = builder.comment("单台磁盘操纵器每 tick 最大操作次数",
                "一次操作处理一个资源类型。全局时间预算仍会先于该上限生效。")
                .defineInRange("maxOperationsPerTick", 1024, 1, 8192);
        DISK_MANIPULATOR_MAX_ITEMS_PER_OPERATION = builder.comment("单次操作最大物品或流体数量",
                "默认使用 int 上限，以便整类数量一次搬运；外部存储较慢时可调小。")
                .defineInRange("maxItemsPerOperation", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
        DISK_MANIPULATOR_NO_PROGRESS_COOLDOWN_TICKS = builder.comment("无进展冷却 tick",
                "网络或磁盘暂时无法继续接收时，暂停该操纵器的重试，避免每 tick 重复扫描。")
                .defineInRange("noProgressCooldownTicks", 20, 1, 200);
        builder.pop();
        builder.pop();
        builder.comment("混合终端", "联网普通终端和合成终端的物品/流体混合显示。服务端决定是否启用，重新打开菜单生效。")
                .push("unifiedGrid");
        UNIFIED_GRID = builder.comment("启用物品 / 流体混合终端",
                "默认开启。关闭后使用 RS 原版单类型订阅、显示和取放路径。")
                .define("enabled", true);
        builder.pop();
        builder.comment("万界归墟盘", "物品/流体万界归墟盘。开关需重启，关闭不删除库存；条目容量调大后旧盘在下次加载时自动扩容，调小不缩容。载荷容量只影响新盘。")
                .push("unifiedDisk");
        UNIFIED_DISK = builder.comment("启用万界归墟盘",
                "默认开启。关闭后在创造栏和配方浏览器隐藏并禁用配方，保留旧盘与文件但不挂载和读写库存。需要重启。")
                .worldRestart()
                .define("enabled", true);
        DISK_ITEM_ENTRIES = builder.comment("统一盘物品种类上限",
                "每个万界归墟盘可存储的物品种类上限。调大后旧盘在下次加载时扩容，调小不缩容。")
                .defineInRange("maxItemEntries", 262144, 1, 262144);
        DISK_FLUID_ENTRIES = builder.comment("统一盘流体种类上限",
                "每个万界归墟盘可存储的流体种类上限。调大后旧盘在下次加载时扩容，调小不缩容。")
                .defineInRange("maxFluidEntries", 262144, 1, 262144);
        DISK_ENTRY_BYTES = builder.comment("单条目数据容量（字节）",
                "单个物品或流体条目序列化后的数据大小上限，单位为字节。")
                .defineInRange("maxPayloadBytesPerEntry", 1048576, 1024, 1048576);
        DISK_PAYLOAD_BYTES = builder.comment("单磁盘数据容量（字节）",
                "每个新建万界归墟盘的数据载荷容量上限，单位为字节。不改变旧盘容量。")
                .defineInRange("maxPayloadBytesPerDisk", 268435456, 1048576, 536870912);
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

    public static int diskManipulatorBudgetMicros() {
        return SPEC.isLoaded() ? DISK_MANIPULATOR_BUDGET_MICROS.get()
                : DISK_MANIPULATOR_BUDGET_MICROS.getDefault();
    }

    public static int diskManipulatorMaxOperationsPerTick() {
        return SPEC.isLoaded() ? DISK_MANIPULATOR_MAX_OPS_PER_TICK.get()
                : DISK_MANIPULATOR_MAX_OPS_PER_TICK.getDefault();
    }

    public static int diskManipulatorMaxItemsPerOperation() {
        return SPEC.isLoaded() ? DISK_MANIPULATOR_MAX_ITEMS_PER_OPERATION.get()
                : DISK_MANIPULATOR_MAX_ITEMS_PER_OPERATION.getDefault();
    }

    public static int diskManipulatorNoProgressCooldownTicks() {
        return SPEC.isLoaded() ? DISK_MANIPULATOR_NO_PROGRESS_COOLDOWN_TICKS.get()
                : DISK_MANIPULATOR_NO_PROGRESS_COOLDOWN_TICKS.getDefault();
    }
}
