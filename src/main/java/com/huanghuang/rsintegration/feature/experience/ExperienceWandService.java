package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
import com.huanghuang.rsintegration.storage.StorageCapability;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageFluidOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.util.CuriosAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 所有经验与流体结算仅在服务端线程进行；每轮数量有界。 */
public final class ExperienceWandService {
    private static final int MB_PER_XP = ExperienceFluids.MILLIBUCKETS_PER_EXPERIENCE_POINT;
    private static final int MAX_TRANSFER_MB = 2_000_000_000;
    private static final int MENDING_MB_PER_CYCLE = 20 * MB_PER_XP;

    private ExperienceWandService() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)
                || !player.isAlive() || player.isSpectator()
                || player.tickCount % 5 != 0) return;
        ItemStack wand = selected(player);
        if (!wand.isEmpty()) tick(player, wand);
    }

    static ItemStack selected(ServerPlayer player) {
        // 同一玩家只运行一根杖，防止不同目标等级相互拉扯。
        if (ExperienceWandData.active(player.getMainHandItem())) return player.getMainHandItem();
        if (ExperienceWandData.active(player.getOffhandItem())) return player.getOffhandItem();
        for (ItemStack stack : player.getInventory().items) {
            if (ExperienceWandData.active(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    static StorageSession session(ServerPlayer player) {
        return StorageRestockSupport.resolve(player).map(CraftStorageEndpoint::session)
                .filter(session -> session.isAvailable()
                        && session.supports(StorageCapability.FLUID_STORAGE))
                .orElse(null);
    }

    static StorageFluidKey key(StorageSession session) {
        return StorageFluidKey.fromStack(session.reference().backendId(), fluid(1));
    }

    static void tick(ServerPlayer player, ItemStack wand) {
        if (!ExperiencePoints.supported(player) || ExperienceWandData.uncertain(wand) > 0L) return;
        StorageSession session = session(player);
        ExperienceWandSettings settings = ExperienceWandSettings.fromStack(wand);
        CompoundTag data = ExperienceWandData.tag(wand);
        long current = ExperiencePoints.current(player);
        if (data.contains(ExperienceWandData.PENDING_TARGET)) {
            long target = Math.max(0L, Math.min(ExperiencePoints.MAX_POINTS,
                    data.getLong(ExperienceWandData.PENDING_TARGET)));
            boolean moved = transfer(player, wand, session, current, target);
            if (!moved || ExperiencePoints.current(player) == target) {
                data.remove(ExperienceWandData.PENDING_TARGET);
            }
        } else {
            long target = ExperiencePoints.forLevel(settings.targetLevel());
            if (current > target && (settings.mode() == ExperienceWandSettings.Mode.STORE
                    || settings.mode() == ExperienceWandSettings.Mode.KEEP)
                    || current < target && (settings.mode() == ExperienceWandSettings.Mode.TAKE
                    || settings.mode() == ExperienceWandSettings.Mode.KEEP)) {
                transfer(player, wand, session, current, target);
            }
        }
        if (settings.mending() && ExperienceWandData.uncertain(wand) == 0L) {
            mend(player, wand, session);
        }
        player.getInventory().setChanged();
    }

    private static boolean transfer(ServerPlayer player, ItemStack wand,
                                    @Nullable StorageSession session, long current, long target) {
        if (current > target) return store(player, wand, session, current, target);
        if (current < target) return take(player, wand, session, current, target);
        return false;
    }

    private static boolean store(ServerPlayer player, ItemStack wand,
                                 @Nullable StorageSession session, long current, long target) {
        if (session == null) return false;
        flush(player, wand, session);
        if (ExperienceWandData.buffer(wand) >= MB_PER_XP
                || ExperienceWandData.uncertain(wand) > 0L) return false;
        int requested = (int) (Math.min(current - target, MAX_TRANSFER_MB / MB_PER_XP)
                * MB_PER_XP);
        StorageFluidOperationResult preview = session.insertFluid(player, fluid(requested), true);
        long accepted = accepted(preview, requested, StorageOperationMode.SIMULATE);
        if (accepted < MB_PER_XP) return false;
        long budget = accepted / MB_PER_XP;
        ExperiencePoints.State state = ExperiencePoints.plan(current - budget, false);
        long removed = current - state.points();
        if (removed <= 0L || removed > budget) return false;
        ExperiencePoints.apply(player, state);
        ExperienceWandData.buffer(wand, ExperienceWandData.buffer(wand) + removed * MB_PER_XP);
        flush(player, wand, session);
        return true;
    }

    private static boolean take(ServerPlayer player, ItemStack wand,
                                @Nullable StorageSession session, long current, long target) {
        long wanted = Math.min(target - current, MAX_TRANSFER_MB / MB_PER_XP);
        fillBuffer(player, wand, session, wanted * MB_PER_XP);
        long budget = Math.min(wanted, ExperienceWandData.buffer(wand) / MB_PER_XP);
        if (budget <= 0L) return false;
        ExperiencePoints.State state = ExperiencePoints.plan(current + budget, true);
        long added = state.points() - current;
        if (added <= 0L || added > budget) return false;
        ExperienceWandData.buffer(wand, ExperienceWandData.buffer(wand) - added * MB_PER_XP);
        ExperiencePoints.apply(player, state);
        return true;
    }

    private static void fillBuffer(ServerPlayer player, ItemStack wand,
                                   @Nullable StorageSession session, long wanted) {
        long buffered = ExperienceWandData.buffer(wand);
        if (buffered >= wanted || session == null) return;
        int requested = (int) Math.min(MAX_TRANSFER_MB, wanted - buffered);
        StorageFluidOperationResult result = session.extractFluidExact(
                player, key(session), requested, false);
        if (result.kind() != StorageOperationResult.Kind.EXTRACT
                || result.mode() != StorageOperationMode.PERFORM) return;
        long received = 0L;
        for (FluidStack stack : result.transferredFluids()) {
            if (stack.isFluidEqual(fluid(1))) received = Math.addExact(received, stack.getAmount());
            else retainRecovery(player, session, stack);
        }
        for (FluidStack stack : result.recoveryFluids()) retainRecovery(player, session, stack);
        // 只使用后端明确交付的液体，绝不把模拟量或请求量当成实际到账。
        ExperienceWandData.buffer(wand, Math.addExact(buffered, received));
    }

    private static void retainRecovery(ServerPlayer player, StorageSession session, FluidStack stack) {
        UnifiedGridFluidRecovery.get(player.server).retainRaw(player.getUUID(),
                stack.writeToNBT(new CompoundTag()), session.reference().networkId());
    }

    private static void flush(ServerPlayer player, ItemStack wand, StorageSession session) {
        int offered = (int) Math.min(MAX_TRANSFER_MB, ExperienceWandData.buffer(wand));
        if (offered <= 0 || ExperienceWandData.uncertain(wand) > 0L) return;
        // 先划出本次提交，结果不明时隔离该笔，禁止下一 tick 再次入库刷液体。
        ExperienceWandData.buffer(wand, ExperienceWandData.buffer(wand) - offered);
        try {
            StorageFluidOperationResult result = session.insertFluid(player, fluid(offered), false);
            long accepted = accepted(result, offered, StorageOperationMode.PERFORM);
            if (accepted < 0L) {
                ExperienceWandData.retainUncertain(wand, offered);
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Experience] 液态经验提交结果不明，已暂停经验杖：{}，{} mB，{}",
                        session.reference(), offered, result.diagnosticCode());
                return;
            }
            ExperienceWandData.buffer(wand, ExperienceWandData.buffer(wand) + offered - accepted);
        } catch (RuntimeException | LinkageError failure) {
            ExperienceWandData.retainUncertain(wand, offered);
            RSIntegrationMod.LOGGER.error("[RSI-Experience] 液态经验提交结果不明，已暂停经验杖", failure);
        }
    }

    private static long accepted(StorageFluidOperationResult result, int offered,
                                 StorageOperationMode mode) {
        if (result.kind() != StorageOperationResult.Kind.INSERT || result.mode() != mode
                || !result.transferKnown()
                || result.diagnosticCode() == StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION
                || result.diagnosticCode() == StorageDiagnosticCode.INVALID_NATIVE_RESPONSE
                || result.transferredAmount() < 0L || result.transferredAmount() > offered) return -1L;
        FluidStack remainder = result.remainder();
        if (remainder == null || !remainder.isEmpty() && !remainder.isFluidEqual(fluid(1))
                || (long) remainder.getAmount() + result.transferredAmount() != offered) return -1L;
        return result.transferredAmount();
    }

    private static void mend(ServerPlayer player, ItemStack wand, @Nullable StorageSession session) {
        List<ItemStack> equipment = new ArrayList<>();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : player.getAllSlots()) {
            if (seen.add(stack) && mendable(stack)) equipment.add(stack);
        }
        for (ItemStack stack : CuriosAccess.equippedStacks(player)) {
            if (seen.add(stack) && mendable(stack)) equipment.add(stack);
        }
        if (equipment.isEmpty()) return;
        int remaining = MENDING_MB_PER_CYCLE;
        int start = player.getRandom().nextInt(equipment.size());
        for (int index = 0; index < equipment.size() && remaining > 0; index++) {
            ItemStack stack = equipment.get((start + index) % equipment.size());
            double ratio = stack.getXpRepairRatio();
            int repair = (int) Math.min(stack.getDamageValue(),
                    Math.floor(remaining * ratio / MB_PER_XP));
            if (repair <= 0) continue;
            int cost = Math.max(1, (int) Math.ceil(repair * (double) MB_PER_XP / ratio));
            fillBuffer(player, wand, session, cost);
            int available = (int) Math.min(remaining, ExperienceWandData.buffer(wand));
            repair = (int) Math.min(repair, Math.floor(available * ratio / MB_PER_XP));
            if (repair <= 0) continue;
            cost = Math.max(1, (int) Math.ceil(repair * (double) MB_PER_XP / ratio));
            if (cost > available) continue;
            ExperienceWandData.buffer(wand, ExperienceWandData.buffer(wand) - cost);
            stack.setDamageValue(stack.getDamageValue() - repair);
            remaining -= cost;
        }
    }

    private static boolean mendable(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamaged()
                && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, stack) > 0
                && Float.isFinite(stack.getXpRepairRatio()) && stack.getXpRepairRatio() > 0.0F;
    }

    private static FluidStack fluid(int amount) {
        return new FluidStack(ExperienceFluids.EXPERIENCE.get(), amount);
    }
}
