package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.magic.MagicDiskStore;
import com.huanghuang.rsintegration.magic.MagicResourceDiagnostics;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.huanghuang.rsintegration.magic.MagicResourceReservations;
import com.huanghuang.rsintegration.magic.MagicResourceTaskLedger;
import com.huanghuang.rsintegration.magic.MagicResourceTaskReservation;
import com.huanghuang.rsintegration.magic.MagicStorageBackendHealth;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.mods.goety.GoetySoulTotemCrafting;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.magic.route.BackendResolution;
import com.huanghuang.rsintegration.magic.route.MagicScope;
import com.huanghuang.rsintegration.magic.route.MagicStorageBackend;
import com.huanghuang.rsintegration.magic.route.RsDiskBackend;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一个 Goety 物理操作对应的一次灵魂预算。
 *
 * <p>计划层使用 {@link #total()} 做预览和总预留；执行层按
 * {@link #intervalTicks()} 分批把磁盘灵魂补入机器的真实原生来源。预留不是扣费，
 * 只有真正从磁盘提取时才减少预留。</p>
 */
public final class GoetySoulCraftingTask implements AutoCloseable {
    public enum Mode {
        DARK_ALTAR_PER_SECOND,
        TOTAL_BATCHED,
        ONE_TIME
    }

    /**
     * 路由层接管执行侧预留的开关。
     *
     * <p>关闭时完全回到「按端点判 RS/BD」的旧空间判定。路由层判不出空间归属
     * （例如调用方只给了 legacy {@link INetwork}）时也会自动回退到旧判定。</p>
     */
    static final boolean USE_ROUTER = true;

    private final ServerPlayer player;
    private final INetwork network;
    private final Mode mode;
    private final int perCharge;
    private final int intervalTicks;
    private final int total;
    private final MagicResourceNetworkPool reservation;
    /** BD 预留的来源标识：预留按网络 ID 隔离，同一网络号可能被不同网络重建后复用。 */
    private final String bdSource;
    /** 任务级账本，用于把无法自动回收的灵魂量登记为待核对记录。 */
    @Nullable
    private final MagicResourceTaskLedger ledger;
    @Nullable
    private final UUID taskId;
    /** 本次任务选中的权威后端；路由层关闭或判不出空间归属时为 null。 */
    @Nullable
    private final SoulRoute soulRoute;
    private int lastProgress;
    private int pendingDiskInjected;
    private long lastTick;
    private long lastBrazierScan;
    private List<Object> brazierCandles = List.of();
    private boolean closed;
    @Nullable
    private GoetySoulSourceCoordinator directCoordinator;
    @Nullable
    private Object directAltar;
    private long startupAvailable;

    private GoetySoulCraftingTask(ServerPlayer player, @Nullable INetwork network,
                                  Mode mode, int perCharge, int intervalTicks,
                                  int total, MagicResourceNetworkPool reservation,
                                  String bdSource,
                                  @Nullable MagicResourceTaskLedger ledger,
                                  @Nullable UUID taskId,
                                  @Nullable SoulRoute soulRoute) {
        this.player = player;
        this.network = network;
        this.mode = mode;
        this.perCharge = Math.max(0, perCharge);
        this.intervalTicks = Math.max(1, intervalTicks);
        this.total = Math.max(0, total);
        this.reservation = reservation;
        this.bdSource = bdSource == null ? "" : bdSource;
        this.ledger = ledger;
        this.taskId = taskId;
        this.soulRoute = soulRoute;
        if (soulRoute != null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Magic] soul route 任务={} {} 预留={}",
                    mode, soulRoute.describe(), reservation == null ? 0 : reservation.remaining());
        }
    }

    public static @Nullable GoetySoulCraftingTask reserveDarkAltar(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint endpoint, Object recipe) {
        return reserveDarkAltar(player, network, endpoint, recipe, null);
    }

    public static @Nullable GoetySoulCraftingTask reserveDarkAltar(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint endpoint, Object recipe,
            @Nullable MagicResourceTaskReservation sharedReservation) {
        int perCharge = soulCost(recipe);
        int duration = duration(recipe);
        if (perCharge <= 0 || duration <= 0) return null;
        int total = GoetySoulTotemCrafting.ritualSoulCost(recipe);
        if (isAomTarotRitual(recipe)) {
            return reserve(player, network, endpoint, Mode.ONE_TIME, total, 20, total,
                    sharedReservation);
        }
        // 当前只接入标准“每秒扣一次”的 Dark Altar 仪式。特殊一次性仪式
        // （例如 Age of Mythology 接管的总量结算）继续走原机逻辑。
        if (total != saturatingMultiply(perCharge, duration)) return null;
        return reserve(player, network, endpoint, Mode.DARK_ALTAR_PER_SECOND,
                perCharge, 20, total, sharedReservation);
    }

    public static @Nullable GoetySoulCraftingTask reserveBrazier(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint endpoint, Object recipe) {
        return reserveBrazier(player, network, endpoint, recipe, null);
    }

    public static @Nullable GoetySoulCraftingTask reserveBrazier(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint endpoint, Object recipe,
            @Nullable MagicResourceTaskReservation sharedReservation) {
        int total = GoetySoulTotemCrafting.ritualSoulCost(recipe);
        if (total <= 0) return null;
        return reserve(player, network, endpoint, Mode.TOTAL_BATCHED,
                total, 20, total, sharedReservation);
    }

    private static @Nullable GoetySoulCraftingTask reserve(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint endpoint, Mode mode,
            int perCharge, int intervalTicks, int total,
            @Nullable MagicResourceTaskReservation sharedReservation) {
        if (player == null || total <= 0) return null;
        int networkId = bdNetworkId(endpoint);
        String bdSource = networkId >= 0
                ? BdReservationBridge.sourceOf(player, networkId) : "";
        // 预留与任务记录共用同一次解析结果，保证「预留的后端」就是「任务报告的后端」。
        SoulRoute soulRoute = routeFor(player, endpoint);
        MagicResourceNetworkPool reservation =
                Reservation.forRoute(player, network, soulRoute, total,
                        sharedReservation, bdSource);
        MagicResourceTaskLedger ledger = sharedReservation == null
                ? null : MagicResourceTaskLedger.get(player.server);
        UUID taskId = sharedReservation == null ? null : sharedReservation.taskId();
        warnOnRoute(player, endpoint, soulRoute);
        return new GoetySoulCraftingTask(player, network, mode, perCharge,
                intervalTicks, total, reservation, bdSource, ledger, taskId, soulRoute);
    }

    public int total() {
        return total;
    }

    public long remainingReservation() {
        if (directCoordinator != null) return directCoordinator.networkRemaining();
        return reservation == null ? 0 : reservation.remaining();
    }

    public Mode mode() {
        return mode;
    }

    /** 本次任务的路由结果；路由层未接管时为 null（例如只有 legacy INetwork）。 */
    @Nullable
    public SoulRoute soulRoute() {
        return soulRoute;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    /** RS/BD 端点的实时可用灵魂；未知端点返回 -1。 */
    public static long availableInEndpoint(ServerPlayer player,
                                           @Nullable CraftStorageEndpoint endpoint) {
        return availableInStorage(player, null, endpoint);
    }

    /**
     * 指定链已选中网络时的实时可用灵魂；未知来源返回 -1。
     *
     * <p>判定与取值已收敛到 {@link SoulPlanPreview#estimate}，预览和执行读同一行代码，
     * 避免同一函数在 RS 与 BD 两条路径上语义不同。这里保留 {@code network} 参数是为了
     * 不改变调用方签名；RS 侧的可用量本来就是“已挂载盘之和”，与该网络无关。</p>
     */
    public static long availableInStorage(ServerPlayer player,
                                          @Nullable INetwork network,
                                          @Nullable CraftStorageEndpoint endpoint) {
        SoulPlanPreview.Estimate estimate = SoulPlanPreview.estimate(player, endpoint, 0);
        if (estimate.source() == SoulPlanPreview.SourceKind.UNREACHABLE) {
            // 无端点时仍允许调用方用显式的 legacy INetwork 兜底，保持旧行为。
            return network != null
                    && (endpoint == null || CraftStorageEndpoints.isRefinedStorage(endpoint))
                    ? availableInRs(player) : -1;
        }
        return estimate.legacyAvailable();
    }

    /**
     * 处理运行期检测到的 BD 网络丢失（网络被解散或重建）。任务无法再向该网络退还
     * 预留，直接把剩余量登记为待核对记录并停止继续扣减；网络号仍保留在记录中，
     * 便于跨网络重连后按同一网络核对。
     */
    void onBdNetworkLost() {
        if (ledger == null) return;
        long leaked = BdReservationBridge.takeUnaccounted(reservation);
        if (leaked <= 0) return;
        ledger.archive(taskId, MagicResourceKind.SOUL, leaked,
                BdReservationBridge.parsedNetworkId(bdSource),
                MagicResourceTaskLedger.PendingReason.BD_NETWORK, gameTime());
    }

    /** 查询 BD 网络是否仍然存在，用于区分「网络已解散」与「网络拒收退还」。 */
    public static boolean bdNetworkExists(int networkId) {
        if (networkId < 0) return false;
        try {
            Class<?> type = Class.forName(
                    "com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection");
            Method call = type.getMethod("networkExists", int.class);
            return Boolean.TRUE.equals(call.invoke(null, networkId));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static int nativeAvailableDarkAltar(ServerPlayer player, Object altar) {
        if (player == null || altar == null) return 0;
        return new GoetyDarkAltarNativeSoulSource(altar, player).available();
    }

    public static int nativeAvailableBrazier(ServerPlayer player,
                                             BlockEntity brazier) {
        if (player == null || brazier == null) return 0;
        return new GoetyBrazierNativeSoulSource(player, brazier).available();
    }

    /** 归墟盘上灵魂的可用量；没有挂载任何盘时是 0（确实是空，不是读不出来）。 */
    private static long availableInRs(ServerPlayer player) {
        UnifiedDiskManager manager = UnifiedDiskManager.existing(player.server);
        if (manager == null) return 0;
        return MagicDiskStore.available(
                MagicDiskStore.mountedDisks(manager), MagicResourceKind.SOUL);
    }

    /**
     * 每个服务端 tick 由 delegate 调用；内部会按 intervalTicks 节流。
     */
    public void tick(ServerLevel level, BlockEntity machine) {
        if (closed || machine == null || reservation == null) return;
        if (directCoordinator != null) return;
        if (mode == Mode.ONE_TIME) return;
        long now = level.getGameTime();
        if (now - lastTick < 5L) return;
        lastTick = now;

        int progress = Reflect.getIntField(machine, GoetyReflection.F_CURRENT_TIME).orElse(0);
        settleProgress(progress);
        if (reservation.remaining() <= 0) return;

        if (mode == Mode.DARK_ALTAR_PER_SECOND) {
            supplyDarkAltar(machine);
        } else if (mode == Mode.TOTAL_BATCHED) {
            supplyBrazier(level, machine);
        }
    }

    private void settleProgress(int progress) {
        if (progress <= lastProgress) return;
        int delta = progress - lastProgress;
        int consumed = mode == Mode.DARK_ALTAR_PER_SECOND
                ? saturatingMultiply(delta, perCharge)
                : delta;
        int diskUsed = Math.min(consumed, pendingDiskInjected);
        pendingDiskInjected -= diskUsed;
        lastProgress = progress;
    }

    private void supplyDarkAltar(BlockEntity altar) {
        Object cage = Reflect.getField(altar, GoetyReflection.F_CURSED_CAGE_TILE).orElse(null);
        if (cage == null) return;
        int cageSouls = Reflect.<Integer>invoke(cage, GoetyReflection.M_GET_SOULS).orElse(0);
        if (cageSouls >= perCharge) return;

        int need = perCharge - cageSouls;
        int withdrawn = SaturatingLongMath.clampToInt(reservation.withdraw(need));
        if (withdrawn <= 0) return;
        int injected = injectIntoCage(cage, withdrawn);
        if (injected < withdrawn) reservation.refund(withdrawn - injected);
        pendingDiskInjected += injected;
    }

    /** 启动前先补入第一批灵魂，避免原机首个 tick 因 Cage 为空而立即停止仪式。 */
    public boolean prepareBeforeStart(Object altar) {
        if (closed || altar == null || reservation == null) return false;
        if (mode == Mode.DARK_ALTAR_PER_SECOND || mode == Mode.ONE_TIME) {
            GoetyDarkAltarNativeSoulSource nativeSource =
                    new GoetyDarkAltarNativeSoulSource(altar, player);
            Object cage = nativeSource.cage();
            if (cage == null) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Goety] Refusing direct soul start without a cursed cage: altar={}",
                        altar);
                return false;
            }
            startupAvailable = GoetySoulSourceCoordinator.combineLong(
                    reservation.remaining(), nativeSource.available());
            directCoordinator = GoetySoulSourceCoordinator.create(
                    total, reservation, nativeSource);
            if (directCoordinator == null) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Goety] Refusing direct soul start: required={} available={} reserved={}",
                        total, startupAvailable, reservation.remaining());
                return false;
            }
            directAltar = altar;
            DarkAltarSoulPort.attach(altar, cage, directCoordinator);
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Goety] Using RS/BD network soul source directly: mode={} reserved={} available={}",
                    mode, reservation.remaining(), startupAvailable);
            return true;
        }
        if (mode == Mode.TOTAL_BATCHED && altar instanceof BlockEntity brazier) {
            GoetyBrazierNativeSoulSource nativeSource =
                    new GoetyBrazierNativeSoulSource(player, brazier);
            List<Object> candles = nativeSource.candles();
            Object virtualCandle = null;
            int virtualCandleSpeed = 1;
            if (candles.isEmpty()) {
                virtualCandle = createVirtualCandlestick(brazier);
                if (virtualCandle == null) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Goety] Failed to create a recursive-craft virtual candlestick: {}",
                            brazier.getBlockPos());
                    return false;
                }
                virtualCandleSpeed = configuredVirtualCandleCount(total);
                candles = List.of(virtualCandle);
            }
            startupAvailable = GoetySoulSourceCoordinator.combineLong(
                    reservation.remaining(), nativeSource.available());
            directCoordinator = GoetySoulSourceCoordinator.create(
                    total, reservation, nativeSource);
            if (directCoordinator == null) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Goety] Refusing brazier soul start: required={} available={} reserved={}",
                        total, startupAvailable, reservation.remaining());
                return false;
            }
            directAltar = altar;
            BrazierSoulPort.attach(altar, directCoordinator, candles,
                    virtualCandle, virtualCandleSpeed);
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Goety] Using RS/BD network soul source directly for brazier: reserved={} available={} virtualCandleSpeed={}",
                    reservation.remaining(), startupAvailable, virtualCandleSpeed);
            return true;
        }
        return false;
    }

    static int virtualBrazierCandleCount(int soulCost, int configured) {
        return Math.max(1, Math.min(Math.max(0, soulCost),
                Math.max(1, configured)));
    }

    private static int configuredVirtualCandleCount(int soulCost) {
        int configured = 64;
        try {
            configured = RSIntegrationConfig.GOETY_BRAZIER_VIRTUAL_CANDLE_COUNT.get();
        } catch (RuntimeException ignored) {
            // 配置尚未加载时使用上限值。
        }
        return virtualBrazierCandleCount(soulCost, configured);
    }

    @Nullable
    private Object createVirtualCandlestick(BlockEntity brazier) {
        if (GoetyReflection.soulCandlestickBEClass == null
                || brazier.getLevel() == null) return null;
        try {
            Constructor<?> constructor = GoetyReflection.soulCandlestickBEClass
                    .getConstructor(BlockPos.class, BlockState.class);
            Object candle = constructor.newInstance(
                    brazier.getBlockPos(), brazier.getBlockState());
            if (candle instanceof BlockEntity candleEntity) {
                candleEntity.setLevel(brazier.getLevel());
            }
            return candle;
        } catch (ReflectiveOperationException
                 | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private void supplyBrazier(ServerLevel level, BlockEntity brazier) {
        long now = level.getGameTime();
        if (now - lastBrazierScan >= 40L) {
            lastBrazierScan = now;
            brazierCandles = findCandlesticks(level, brazier.getBlockPos());
        }
        if (brazierCandles.isEmpty()) return;

        List<CageSource> cages = new ArrayList<>();
        int drainPerTick = 0;
        for (Object candle : brazierCandles) {
            if (!(candle instanceof BlockEntity candleEntity)) continue;
            int drain = Reflect.<Integer>invoke(candle, "soulDrainAmount").orElse(1);
            if (aomLoaded()) drain = 20;
            drainPerTick += Math.max(1, drain);
            BlockEntity cage = level.getBlockEntity(candleEntity.getBlockPos().below());
            if (cage != null && GoetyReflection.cursedCageBEClass != null
                    && GoetyReflection.cursedCageBEClass.isInstance(cage)) {
                cages.add(new CageSource(cage, Math.max(1, drain)));
            }
        }
        if (cages.isEmpty()) return;

        int bufferTarget = Math.max(20, Math.min(total, drainPerTick * 20));
        int perCageTarget = Math.max(1, bufferTarget / cages.size());
        for (CageSource source : cages) {
            if (reservation.remaining() <= 0) break;
            int cageSouls = Reflect.<Integer>invoke(source.cage, GoetyReflection.M_GET_SOULS).orElse(0);
            if (cageSouls >= perCageTarget) continue;
            int need = perCageTarget - cageSouls;
            int withdrawn = SaturatingLongMath.clampToInt(reservation.withdraw(need));
            if (withdrawn <= 0) continue;
            int injected = injectIntoCage(source.cage, withdrawn);
            if (injected < withdrawn) reservation.refund(withdrawn - injected);
            pendingDiskInjected += injected;
        }
    }

    private static List<Object> findCandlesticks(ServerLevel level, BlockPos center) {
        if (GoetyReflection.soulCandlestickBEClass == null) return List.of();
        List<Object> result = new ArrayList<>();
        int range = 8;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-range, -range, -range),
                center.offset(range, range, range))) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (GoetyReflection.soulCandlestickBEClass.isInstance(be)) {
                result.add(be);
            }
        }
        return result;
    }

    private static boolean aomLoaded() {
        return ModList.get() != null && ModList.get().isLoaded("ageofmythology");
    }

    private static boolean isAomTarotRitual(Object recipe) {
        if (!aomLoaded() || recipe == null || GoetyReflection.ritualRecipeClass == null
                || !GoetyReflection.ritualRecipeClass.isInstance(recipe)) return false;
        try {
            Class<?> service = Class.forName(
                    "com.kurome.ageofmythology.tarot.awakening.upright.TarotRitualStartService");
            Method manages = Reflect.findMethod(service, "manages",
                    new Class<?>[]{GoetyReflection.ritualRecipeClass});
            return manages != null && Boolean.TRUE.equals(manages.invoke(null, recipe));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private record CageSource(Object cage, int drain) {}

    private static int injectIntoCage(Object cage, int amount) {
        if (amount <= 0) return 0;
        ItemStack stack = Reflect.<ItemStack>invoke(cage, "getItem").orElse(ItemStack.EMPTY);
        if (GoetySoulTotem.isTotem(stack)) {
            int accepted = GoetySoulTotem.insert(stack, amount, true);
            if (accepted > 0) Reflect.invoke(cage, "setItem", stack);
            return accepted;
        }

        Player owner = Reflect.<Player>invoke(cage, "getOwner").orElse(null);
        if (owner == null || GoetyReflection.seHelperClass == null) return 0;
        int before = staticInt("getSESouls", owner);
        if (before < 0 || !staticBoolean("getSEActive", owner)) return 0;
        if (!staticBoolean("increaseSESouls", owner, amount)) return 0;
        staticVoid("sendSEUpdatePacket", owner);
        int after = staticInt("getSESouls", owner);
        return Math.max(0, after - before);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (directAltar != null) {
            DarkAltarSoulPort.detach(directAltar);
            BrazierSoulPort.detach(directAltar);
            directAltar = null;
        }
        if (directCoordinator != null) {
            archiveLostBdReservation();
            directCoordinator.close();
            directCoordinator = null;
            return;
        }
        if (reservation != null) {
            archiveLostBdReservation();
            reservation.close();
        }
    }

    /**
     * 登记当前 BD 预留中无法退还的剩余量，并区分「网络已不存在」与「网络拒收」。
     * RS / 归墟盘预留不在此列：它总能随预留关闭退回盘上，不需要人工核对。
     */
    private void archiveLostBdReservation() {
        if (ledger == null) return;
        long leaked = BdReservationBridge.takeUnaccounted(reservation);
        if (leaked <= 0) return;
        int networkId = BdReservationBridge.parsedNetworkId(bdSource);
        MagicResourceTaskLedger.PendingReason reason = bdNetworkExists(networkId)
                ? MagicResourceTaskLedger.PendingReason.BD_TASK_CLOSED
                : MagicResourceTaskLedger.PendingReason.BD_NETWORK;
        ledger.archive(taskId, MagicResourceKind.SOUL, leaked, networkId, reason, gameTime());
    }

    /** 主世界游戏刻；玩家已经离线时返回 0，记录按“未知时间”保留。 */
    private long gameTime() {
        MinecraftServer server = player == null ? null : player.server;
        return server == null ? 0L : server.overworld().getGameTime();
    }

    public long startupAvailable() {
        return startupAvailable;
    }


    private static int soulCost(Object recipe) {
        return Reflect.<Integer>invoke(recipe, "getSoulCost").orElse(0);
    }

    private static int duration(Object recipe) {
        if (recipe == null) return 0;
        return Reflect.<Integer>invoke(recipe, "getDuration").orElse(0);
    }

    private static int saturatingMultiply(int left, int right) {
        long result = (long) Math.max(0, left) * Math.max(0, right);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int staticInt(String method, Object... args) {
        Method resolved = Reflect.findMethod(GoetyReflection.seHelperClass, method,
                parameterTypes(args));
        if (resolved == null) return -1;
        try {
            Object value = resolved.invoke(null, args);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    private static boolean staticBoolean(String method, Object... args) {
        Method resolved = Reflect.findMethod(GoetyReflection.seHelperClass, method,
                parameterTypes(args));
        if (resolved == null) return false;
        try {
            return Boolean.TRUE.equals(resolved.invoke(null, args));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void staticVoid(String method, Object... args) {
        Method resolved = Reflect.findMethod(GoetyReflection.seHelperClass, method,
                parameterTypes(args));
        if (resolved == null) return;
        try {
            resolved.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Ignore optional API differences.
        }
    }

    private static Class<?>[] parameterTypes(Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Player ? Player.class
                    : args[i] instanceof Integer ? int.class
                    : args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }

    private interface Reservation extends MagicResourceNetworkPool {
        static MagicResourceNetworkPool forRoute(
                ServerPlayer player, @Nullable INetwork network,
                @Nullable SoulRoute soulRoute, long requested,
                @Nullable MagicResourceTaskReservation sharedReservation,
                String bdSource) {
            if (USE_ROUTER && soulRoute != null) {
                MagicStorageBackend backend = soulRoute.backend();
                if (backend != null) {
                    // 归墟盘预留由任务级预留统一持有，避免与账本重复计数。
                    if (backend.descriptor().id().equals(RsDiskBackend.ID)
                            && sharedReservation != null) {
                        return new SharedReservation(
                                sharedReservation.pool(MagicResourceKind.SOUL));
                    }
                    // 归一化为通用预留接口：路由层只认通用池接口，
                    // 不因为「换了后端」就要求所有存量实现都改接口（扩展性硬标准）。
                    return new SharedReservation(
                            backend.reserve(MagicResourceKind.SOUL, requested, backend.bind()));
                }
            }
            // 开关关闭，或路由层判不出空间归属（例如只有 legacy INetwork）时的旧判定。
            CraftStorageEndpoint endpoint = soulRoute == null ? null : soulRoute.scope().endpoint();
            INetwork rsNetwork = resolveRsNetwork(player, network, endpoint);
            if (rsNetwork != null) {
                if (sharedReservation != null) {
                    return new SharedReservation(
                            sharedReservation.pool(MagicResourceKind.SOUL));
                }
                return MagicResourceReservations.reserve(
                        UnifiedDiskManager.existing(player.server),
                        MagicResourceKind.SOUL, requested);
            }
            int networkId = bdNetworkId(endpoint);
            return networkId >= 0
                    ? BdReservationBridge.reserve(player, networkId, requested, bdSource)
                    : EmptyReservation.INSTANCE;
        }
    }

    private static final class SharedReservation implements Reservation {
        private final MagicResourceNetworkPool pool;

        private SharedReservation(MagicResourceNetworkPool pool) {
            this.pool = pool;
        }

        @Override public long remaining() { return pool.remaining(); }
        @Override public long withdraw(long requested) { return pool.withdraw(requested); }
        @Override public void refund(long amount) { pool.refund(amount); }
        @Override public void close() { /* 由任务级预留统一关闭。 */ }
    }

    private static final class EmptyReservation implements Reservation {
        private static final EmptyReservation INSTANCE = new EmptyReservation();

        @Override public long remaining() { return 0; }
        @Override public long withdraw(long requested) { return 0; }
        @Override public void refund(long amount) {}
        @Override public void close() {}
    }

    /**
     * 灵魂在指定链路上的路由结果：解析出的权威后端 + 原始 scope。
     *
     * <p>没有链路端点、或路由层解析不到权威后端时返回 null，由调用方回退
     * 「按端点判 RS/BD」的旧判定；{@link #resolveRsNetwork} 认识的 legacy
     * {@link INetwork} 来源比 {@link MagicScope} 更宽，所以旧判定必须保留。</p>
     */
    @Nullable
    public static SoulRoute routeFor(ServerPlayer player,
                                     @Nullable CraftStorageEndpoint endpoint) {
        if (player == null) return null;
        MagicScope scope = MagicScope.of(player, endpoint);
        BackendResolution resolution = RSIntegrationMod.MAGIC_ROUTER
                .resolveCounted(MagicResourceKind.SOUL, scope, true);
        return resolution.resolved() ? new SoulRoute(scope, resolution) : null;
    }

    /**
     * 运行期的路由自检；只报警，不阻断任务。
     *
     * <p>放在运行期而不是启动期：同一个模组组合下，玩家此时连的是 RS 终端还是 BD
     * 终端决定了后端是否可达，启动期无法预知。启动期只检查「有没有注册后端」。</p>
     */
    private static void warnOnRoute(ServerPlayer player,
                                    @Nullable CraftStorageEndpoint endpoint,
                                    @Nullable SoulRoute route) {
        if (!USE_ROUTER) return;
        if (route == null) {
            if (endpoint != null) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Magic] 灵魂链路端点「{}」没有声明管辖它的后端；本次回退旧判定并可能取不到灵魂。",
                        MagicScope.of(player, endpoint).backendName());
            }
            return;
        }
        if (route.resolution().kind() == BackendResolution.Kind.UNREACHABLE) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Magic] 灵魂权威后端当前不可达；本次执行可能取不到灵魂。{}", route.describe());
            return;
        }
        if (!MagicStorageBackendHealth.hasUsableChannel(
                RSIntegrationMod.MAGIC_ROUTER, MagicResourceKind.SOUL)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Magic] 灵魂注册了后端但通道全是 NONE；没有任何一条路径能读到灵魂。");
        }
    }

    /**
     * 一次路由解析的完整结果。
     *
     * <p>执行侧与诊断命令共用它，避免「预留走 A 后端、报告说 B 后端」这类口径分裂。</p>
     */
    public record SoulRoute(MagicScope scope, BackendResolution resolution) {
        /** 已实例化的权威后端。 */
        public MagicStorageBackend backend() {
            return resolution.backend().instantiate(scope);
        }

        /** 权威后端的 id 字面量，用于日志与调试命令。 */
        public String backendId() {
            return resolution.backend().id().toString();
        }

        /** 供中文诊断输出的一行描述。 */
        public String describe() {
            return MagicResourceDiagnostics.describeRoute(scope, resolution);
        }
    }

    private static int bdNetworkId(@Nullable CraftStorageEndpoint endpoint) {
        if (!CraftStorageEndpoints.isBeyondDimensions(endpoint)) return -1;
        try {
            return Integer.parseInt(endpoint.session().reference().networkId());
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    @Nullable
    static INetwork resolveRsNetwork(ServerPlayer player,
                                     @Nullable INetwork network,
                                     @Nullable CraftStorageEndpoint endpoint) {
        INetwork legacy = CraftStorageEndpoints.legacyNetwork(endpoint);
        if (legacy != null) return legacy;
        if (endpoint != null && !CraftStorageEndpoints.isRefinedStorage(endpoint)) return null;
        if (network != null) return network;
        if (player == null || !CraftStorageEndpoints.isRefinedStorage(endpoint)) return null;
        return RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(player);
    }
}
