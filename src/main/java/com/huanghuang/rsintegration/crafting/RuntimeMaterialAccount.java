package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务实际持有的物品。节点账本只领取这里的物品，原始取料账本仅负责最终退回未使用部分。
 * 不接管产物存储端点，也不承诺崩溃后的事务重放。
 */
final class RuntimeMaterialAccount {
    private static final ThreadLocal<RuntimeMaterialAccount> CURRENT = new ThreadLocal<>();
    private final UUID craftId;
    private final UUID playerId;
    private final MinecraftServer server;
    private final CraftStorageEndpoint endpoint;
    private final Map<MaterialKey, Long> held = new LinkedHashMap<>();
    private final List<ExtractionLedger> acquisitions = new ArrayList<>();
    private boolean uncertain;
    private boolean closed;
    private boolean acquiring;

    RuntimeMaterialAccount(UUID craftId, UUID playerId, MinecraftServer server,
                           @Nullable CraftStorageEndpoint endpoint) {
        this.craftId = craftId;
        this.playerId = playerId;
        this.server = server;
        this.endpoint = endpoint;
    }

    @Nullable static RuntimeMaterialAccount current() { return CURRENT.get(); }

    /** 只在当前链的同步推进期间继承账户；嵌套开链和异常退出都会恢复原作用域。 */
    static Scope enter(@Nullable RuntimeMaterialAccount account) {
        RuntimeMaterialAccount previous = CURRENT.get();
        if (account == null) CURRENT.remove(); else CURRENT.set(account);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    interface Scope extends AutoCloseable { @Override void close(); }

    boolean belongsTo(ServerPlayer player) { return playerId.equals(player.getUUID()); }
    boolean usesEndpoint(@Nullable CraftStorageEndpoint candidate) {
        if (endpoint == candidate) return true;
        if (endpoint == null || candidate == null) return false;
        return endpoint.session().reference().equals(candidate.session().reference());
    }
    boolean uncertain() { return uncertain; }
    boolean usable() { return !closed && !uncertain && !acquiring; }
    void markTransferUncertain() {
        if (uncertain) return;
        uncertain = true;
        RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 流体交付结果不明，停止自动重试", craftId);
    }
    long count(MaterialKey key) { return held.getOrDefault(key, 0L); }

    @Nullable
    ExtractionLedger.ResonanceReturnTarget resonanceReturnTarget(ItemStack stack, Ingredient matcher) {
        for (ExtractionLedger acquisition : acquisitions) {
            var target = acquisition.resonanceReturnTarget(stack, matcher);
            if (target != null) return target;
        }
        return null;
    }

    Map<StackKey, Long> heldMaterials() {
        Map<StackKey, Long> result = new LinkedHashMap<>();
        held.forEach((key, amount) -> result.put(StackKey.of(key.toStack(1), true), amount));
        return result;
    }

    Map<StackKey, Long> available(ServerPlayer player) {
        Map<StackKey, Long> result = heldMaterials();
        try (Scope ignored = enter(null)) {
            MaterialSources.listAllAvailableFresh(player, endpoint).forEach(
                    (key, amount) -> result.merge(key, amount, MaterialSources::saturatedAdd));
        }
        return result;
    }

    /** 补足本次需求；确认缺料时整批回滚，不跨 tick 持有半份准入材料。 */
    boolean ensure(Map<MaterialKey, Long> required, List<Ingredient> reusable, ServerPlayer player) {
        if (!usable() || !belongsTo(player)) return false;
        Map<MaterialKey, Long> missing = new LinkedHashMap<>();
        required.forEach((key, amount) -> {
            long shortfall = amount - count(key);
            if (shortfall > 0) missing.put(key, shortfall);
        });
        if (missing.isEmpty()) return true;
        acquiring = true;
        ExtractionLedger acquisition = new ExtractionLedger();
        acquisition.setExternalMaterialAccess();
        acquisition.setStorageEndpoint(endpoint);
        try (Scope ignored = enter(null)) {
            for (Ingredient ingredient : reusable) acquisition.preferResonanceFor(ingredient);
            for (Map.Entry<MaterialKey, Long> entry : missing.entrySet()) {
                long remaining = entry.getValue();
                while (remaining > 0) {
                    int quantity = (int) Math.min(Integer.MAX_VALUE, remaining);
                    ItemStack reserved = acquisition.reserveExactAcrossNetworkAndInventory(
                            entry.getKey().toStack(1), quantity, endpoint, player);
                    if (reserved.getCount() != quantity) {
                        acquisition.close();
                        return false;
                    }
                    remaining -= quantity;
                }
            }
            if (!acquisition.commitToEndpoint(endpoint, player)) {
                if (acquisition.hasUncertainTransfers()) freeze(acquisition);
                return false;
            }
            Map<MaterialKey, Long> received = new LinkedHashMap<>();
            for (ItemStack stack : acquisition.committedFragments()) {
                received.merge(MaterialKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
            if (!received.equals(missing)) {
                acquisition.refundCommittedToEndpoint(endpoint, player);
                if (acquisition.hasUncertainTransfers()) freeze(acquisition);
                return false;
            }
            acquisitions.add(acquisition);
            for (ItemStack stack : acquisition.committedFragments()) add(stack);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            // 已开始提取后无法证明没有副作用，不把它当作普通缺料重试。
            if (acquisition.state() == ExtractionLedger.State.COMMITTING
                    || acquisition.isCommitted() || acquisition.hasUncertainTransfers()) freeze(acquisition);
            else acquisition.close();
            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 取料失败", craftId, failure);
            return false;
        } finally {
            acquiring = false;
        }
    }

    private void freeze(ExtractionLedger acquisition) {
        uncertain = true;
        if (!acquisitions.contains(acquisition)) acquisitions.add(acquisition);
        RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 材料转移结果不明，停止自动重试", craftId);
    }

    StorageOperationResult take(ItemStack template, int amount) {
        MaterialKey key = MaterialKey.of(template);
        int taken = usable() ? (int) Math.min(amount, count(key)) : 0;
        if (taken > 0) subtract(key, taken);
        return StorageOperationResult.extracted(StorageOperationMode.PERFORM, amount,
                taken == 0 ? List.of() : List.of(template.copyWithCount(taken)));
    }

    /** 节点退款和交给私有账本委托的中间产物只回到本任务。 */
    void add(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (closed) throw new IllegalStateException("材料账户已经关闭: " + craftId);
        held.merge(MaterialKey.of(stack), (long) stack.getCount(), Math::addExact);
    }

    private void subtract(MaterialKey key, long amount) {
        long remaining = count(key) - amount;
        if (remaining < 0) throw new IllegalStateException("材料账户透支");
        if (remaining == 0) held.remove(key); else held.put(key, remaining);
    }

    void close() {
        if (closed) return;
        closed = true;
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        try (Scope ignored = enter(null)) {
            for (ExtractionLedger acquisition : acquisitions) {
                if (!acquisition.isCommitted()) continue;
                List<ItemStack> remaining = new ArrayList<>();
                for (ItemStack original : acquisition.committedFragments()) {
                    MaterialKey key = MaterialKey.of(original);
                    int amount = (int) Math.min(original.getCount(), count(key));
                    if (amount > 0) {
                        remaining.add(original.copyWithCount(amount));
                        subtract(key, amount);
                    }
                }
                acquisition.retainCommittedRefunds(remaining);
                acquisition.refundCommittedToEndpoint(endpoint, online);
                uncertain |= acquisition.hasUncertainTransfers();
            }
            // 无原始外部账本的部分来自图内产物，退回失败时使用既有玩家恢复箱。
            boolean recoveryRetained = false;
            for (Map.Entry<MaterialKey, Long> entry : held.entrySet()) {
                long remaining = entry.getValue();
                while (remaining > 0) {
                    int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
                    ItemStack stack = entry.getKey().toStack(amount);
                    if (endpoint != null && online != null) {
                        try {
                            StorageOperationResult receipt = endpoint.insert(online, stack, false);
                            if (receipt.remainder().isEmpty()) {
                                uncertain = true;
                                stack = ItemStack.EMPTY;
                            } else stack = receipt.remainder().orElseThrow();
                        } catch (RuntimeException | LinkageError failure) {
                            uncertain = true;
                            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 余料退回结果不明", craftId, failure);
                            stack = ItemStack.EMPTY;
                        }
                    }
                    if (!stack.isEmpty()) {
                        CraftItemRecovery.get(server).retain(playerId, stack);
                        recoveryRetained = true;
                    }
                    remaining -= amount;
                }
            }
            held.clear();
            if (online != null && recoveryRetained) CraftItemRecovery.get(server).claim(online);
        }
    }
}
