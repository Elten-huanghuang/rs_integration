package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.MaterialTransferAuditLedger.ResourceType;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageFluidOperationResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务实际持有的物品和流体。节点账本只领取这里的资源，原始取料账本负责退回未使用部分。
 * 不接管产物存储端点，也不承诺崩溃后的事务重放。
 */
final class RuntimeMaterialAccount {
    private static final ThreadLocal<RuntimeMaterialAccount> CURRENT = new ThreadLocal<>();
    private final UUID craftId;
    private final UUID playerId;
    private final MinecraftServer server;
    private final CraftStorageEndpoint endpoint;
    private final Map<MaterialKey, Long> heldItems = new LinkedHashMap<>();
    private final Map<TaskFluidKey, Long> heldFluids = new LinkedHashMap<>();
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
    boolean closed() { return closed; }
    boolean usable() { return !closed && !uncertain && !acquiring; }
    void markTransferUncertain() {
        markTransferUncertain(ResourceType.UNKNOWN, null, null, 0L,
                "材料转移结果不明");
    }

    void markTransferUncertain(FluidStack fluid, long amount, String reason) {
        if (fluid == null || fluid.isEmpty()) {
            markTransferUncertain(ResourceType.UNKNOWN, null, null, amount, reason);
        } else {
            markTransferUncertain(ResourceType.FLUID, null, fluid, amount, reason);
        }
    }

    void markTransfersUncertain(List<FluidStack> fluids, String reason) {
        if (uncertain) return;
        uncertain = true;
        Map<TaskFluidKey, Long> amounts = new LinkedHashMap<>();
        for (FluidStack fluid : fluids) {
            if (fluid != null && !fluid.isEmpty()) {
                amounts.merge(TaskFluidKey.of(fluid), (long) fluid.getAmount(), Math::addExact);
            }
        }
        if (amounts.isEmpty()) recordUnknown(ResourceType.UNKNOWN, null, null, 0L, reason);
        amounts.forEach((key, amount) -> recordUnknown(ResourceType.FLUID, null, key.toStack(1), amount, reason));
        RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 流体转移结果不明，停止自动重试", craftId);
    }

    private void markTransferUncertain(ResourceType type, @Nullable ItemStack item,
                                       @Nullable FluidStack fluid, long amount, String reason) {
        if (uncertain) return;
        uncertain = true;
        recordUnknown(type, item, fluid, amount, reason);
        RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 材料转移结果不明，停止自动重试", craftId);
    }
    long count(MaterialKey key) {
        ItemStack stack = key.toStack(1);
        if (InkFluidSupport.isToken(stack)) {
            FluidStack fluid = InkFluidSupport.fluid(stack);
            return fluid.isEmpty() ? 0L : heldFluids.getOrDefault(TaskFluidKey.of(fluid), 0L);
        }
        return heldItems.getOrDefault(key, 0L);
    }

    Map<TaskFluidKey, Long> heldFluids() {
        return Map.copyOf(heldFluids);
    }

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
        heldItems.forEach((key, amount) -> result.put(StackKey.of(key.toStack(1), true), amount));
        heldFluids.forEach((key, amount) -> {
            ItemStack token = InkFluidSupport.token(key.toStack(1));
            if (!token.isEmpty()) result.merge(StackKey.of(token, true), amount, MaterialSources::saturatedAdd);
        });
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
                if (acquisition.hasUncertainTransfers()) {
                    freeze(acquisition, "材料准入提取结果不明");
                }
                return false;
            }
            Map<MaterialKey, Long> received = new LinkedHashMap<>();
            for (ItemStack stack : acquisition.committedFragments()) {
                received.merge(MaterialKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
            if (!received.equals(missing)) {
                acquisition.refundCommittedToEndpoint(endpoint, player);
                if (acquisition.hasUncertainTransfers()) {
                    freeze(acquisition, "材料准入回滚结果不明");
                }
                return false;
            }
            acquisitions.add(acquisition);
            for (ItemStack stack : acquisition.committedFragments()) add(stack);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            // 已开始提取后无法证明没有副作用，不把它当作普通缺料重试。
            if (acquisition.state() == ExtractionLedger.State.COMMITTING
                    || acquisition.isCommitted() || acquisition.hasUncertainTransfers()) {
                freeze(acquisition, "材料准入异常，转移结果不明");
            } else acquisition.close();
            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 取料失败", craftId, failure);
            return false;
        } finally {
            acquiring = false;
        }
    }

    private void freeze(ExtractionLedger acquisition, String reason) {
        uncertain = true;
        List<ExtractionLedger.UncertainMaterialTransfer> transfers =
                acquisition.uncertainMaterialTransfers();
        if (transfers.isEmpty()) {
            recordUnknown(ResourceType.UNKNOWN, null, null, 0L, reason);
        } else {
            for (ExtractionLedger.UncertainMaterialTransfer transfer : transfers) {
                recordUnknownStack(transfer.stack(), transfer.amount(), reason);
            }
        }
        if (!acquisitions.contains(acquisition)) acquisitions.add(acquisition);
        RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 材料转移结果不明，停止自动重试", craftId);
    }

    private void recordUnknown(ResourceType type, @Nullable ItemStack item,
                               @Nullable FluidStack fluid, long amount, String reason) {
        try {
            MaterialTransferAuditLedger ledger = MaterialTransferAuditLedger.get(server);
            long now = server.overworld().getGameTime();
            if (type == ResourceType.ITEM && item != null && !item.isEmpty()) {
                ledger.recordUnknownItem(craftId, playerId, item, amount, reason, now);
            } else if (type == ResourceType.FLUID && fluid != null && !fluid.isEmpty()) {
                ledger.recordUnknownFluid(craftId, playerId, fluid, amount, reason, now);
            } else {
                ledger.recordUnknown(craftId, playerId, ResourceType.UNKNOWN, null,
                        new CompoundTag(), amount, reason, now);
            }
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 未知转移记录无法持久化", craftId, failure);
        }
    }

    private void recordUnknownStack(ItemStack stack, long amount, String reason) {
        if (InkFluidSupport.isToken(stack)) {
            recordUnknown(ResourceType.FLUID, null, InkFluidSupport.fluid(stack), amount, reason);
        } else {
            recordUnknown(ResourceType.ITEM, stack, null, amount, reason);
        }
    }

    StorageOperationResult take(ItemStack template, int amount) {
        if (InkFluidSupport.isToken(template)) {
            FluidStack requested = InkFluidSupport.fluid(template);
            List<FluidStack> taken = takeFluid(requested, amount, false);
            List<ItemStack> tokens = taken.stream()
                    .map(fluid -> InkFluidSupport.token(template.getItem(), fluid))
                    .toList();
            return StorageOperationResult.extracted(StorageOperationMode.PERFORM, amount, tokens);
        }
        MaterialKey key = MaterialKey.of(template);
        int taken = usable() ? (int) Math.min(amount, heldItems.getOrDefault(key, 0L)) : 0;
        if (taken > 0) subtractItem(key, taken);
        return StorageOperationResult.extracted(StorageOperationMode.PERFORM, amount,
                taken == 0 ? List.of() : List.of(template.copyWithCount(taken)));
    }

    List<FluidStack> takeFluid(FluidStack template, long amount, boolean simulate) {
        if (!usable() || template == null || template.isEmpty() || amount <= 0L) return List.of();
        TaskFluidKey key = TaskFluidKey.of(template);
        long available = heldFluids.getOrDefault(key, 0L);
        if (available < amount) return List.of();
        List<FluidStack> fragments = splitFluid(template, amount);
        if (!simulate) subtractFluid(key, amount);
        return fragments;
    }

    private static List<FluidStack> splitFluid(FluidStack template, long amount) {
        List<FluidStack> fragments = new ArrayList<>();
        long remaining = amount;
        while (remaining > 0L) {
            int part = (int) Math.min(Integer.MAX_VALUE, remaining);
            FluidStack fragment = template.copy();
            fragment.setAmount(part);
            fragments.add(fragment);
            remaining -= part;
        }
        return List.copyOf(fragments);
    }

    /** 节点退款和交给私有账本委托的中间产物只回到本任务。 */
    void add(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (closed) throw new IllegalStateException("材料账户已经关闭: " + craftId);
        if (InkFluidSupport.isToken(stack)) {
            FluidStack fluid = InkFluidSupport.fluid(stack);
            if (!fluid.isEmpty()) addFluid(fluid);
            return;
        }
        heldItems.merge(MaterialKey.of(stack), (long) stack.getCount(), Math::addExact);
    }

    void addFluid(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (closed) throw new IllegalStateException("材料账户已经关闭: " + craftId);
        heldFluids.merge(TaskFluidKey.of(stack), (long) stack.getAmount(), Math::addExact);
    }

    private void subtractItem(MaterialKey key, long amount) {
        long remaining = heldItems.getOrDefault(key, 0L) - amount;
        if (remaining < 0) throw new IllegalStateException("材料账户透支");
        if (remaining == 0) heldItems.remove(key); else heldItems.put(key, remaining);
    }

    private void subtractFluid(TaskFluidKey key, long amount) {
        long remaining = heldFluids.getOrDefault(key, 0L) - amount;
        if (remaining < 0L) throw new IllegalStateException("任务流体账户透支");
        if (remaining == 0L) heldFluids.remove(key); else heldFluids.put(key, remaining);
    }

    private static ItemStack confirmedItemRemainder(StorageOperationResult receipt, ItemStack input) {
        if (receipt == null || receipt.kind() != StorageOperationResult.Kind.INSERT
                || receipt.mode() != StorageOperationMode.PERFORM
                || receipt.requestedAmount() != input.getCount()
                || receipt.transferredAmount().isEmpty() || receipt.remainder().isEmpty()) {
            throw new IllegalStateException("物品退款回执无法确认实际转移");
        }
        ItemStack remainder = receipt.remainder().orElseThrow();
        long transferred = receipt.transferredAmount().orElseThrow();
        if (transferred < 0L || transferred > input.getCount()
                || transferred + remainder.getCount() != input.getCount()
                || (!remainder.isEmpty() && !MaterialKey.of(input).equals(MaterialKey.of(remainder)))) {
            throw new IllegalStateException("物品退款回执的身份或数量不符");
        }
        return remainder;
    }

    private static FluidStack confirmedFluidRemainder(StorageFluidOperationResult receipt, FluidStack input) {
        if (receipt == null || receipt.kind() != StorageOperationResult.Kind.INSERT
                || receipt.mode() != StorageOperationMode.PERFORM || !receipt.transferKnown()
                || receipt.requestedAmount() != input.getAmount()) {
            throw new IllegalStateException("流体退款回执无法确认实际转移");
        }
        FluidStack remainder = receipt.remainder() == null ? FluidStack.EMPTY : receipt.remainder().copy();
        long transferred = receipt.transferredAmount();
        if (transferred < 0L || transferred > input.getAmount()
                || transferred + remainder.getAmount() != input.getAmount()
                || !receipt.transferredFluids().isEmpty() || !receipt.recoveryFluids().isEmpty()
                || (!remainder.isEmpty() && !TaskFluidKey.of(input).equals(TaskFluidKey.of(remainder)))) {
            throw new IllegalStateException("流体退款回执的身份或数量不符");
        }
        return remainder;
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
                        if (InkFluidSupport.isToken(original)) {
                            FluidStack fluid = InkFluidSupport.fluid(original);
                            subtractFluid(TaskFluidKey.of(fluid), amount);
                        } else {
                            subtractItem(key, amount);
                        }
                    }
                }
                int previousUnknownTransfers = acquisition.uncertainMaterialTransfers().size();
                acquisition.retainCommittedRefunds(remaining);
                acquisition.refundCommittedToEndpoint(endpoint, online);
                if (acquisition.hasUncertainTransfers()) {
                    uncertain = true;
                    List<ExtractionLedger.UncertainMaterialTransfer> transfers =
                            acquisition.uncertainMaterialTransfers();
                    for (int index = previousUnknownTransfers; index < transfers.size(); index++) {
                        ExtractionLedger.UncertainMaterialTransfer transfer = transfers.get(index);
                        recordUnknownStack(transfer.stack(), transfer.amount(), "准入材料退款结果不明");
                    }
                    if (transfers.isEmpty()) {
                        recordUnknown(ResourceType.UNKNOWN, null, null, 0L,
                                "准入材料退款结果不明");
                    }
                }
            }
            // 无原始外部账本的部分来自图内产物，分别按资源类型退回。
            boolean itemRecoveryRetained = false;
            boolean fluidRecoveryRetained = false;
            for (Map.Entry<MaterialKey, Long> entry : heldItems.entrySet()) {
                long remaining = entry.getValue();
                while (remaining > 0) {
                    int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
                    ItemStack stack = entry.getKey().toStack(amount);
                    if (endpoint != null && online != null) {
                        try {
                            StorageOperationResult receipt = endpoint.insert(online, stack.copy(), false);
                            stack = confirmedItemRemainder(receipt, stack);
                        } catch (RuntimeException | LinkageError failure) {
                            uncertain = true;
                            recordUnknown(ResourceType.ITEM, stack, null, stack.getCount(),
                                    "物品余料退回异常");
                            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 余料退回结果不明", craftId, failure);
                            stack = ItemStack.EMPTY;
                        }
                    }
                    if (!stack.isEmpty()) {
                        try {
                            CraftItemRecovery.get(server).retain(playerId, stack);
                            itemRecoveryRetained = true;
                        } catch (RuntimeException | LinkageError failure) {
                            uncertain = true;
                            recordUnknown(ResourceType.ITEM, stack, null, stack.getCount(), "物品恢复队列写入失败");
                            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 物品恢复记录失败", craftId, failure);
                        }
                    }
                    remaining -= amount;
                }
            }
            heldItems.clear();
            for (Map.Entry<TaskFluidKey, Long> entry : heldFluids.entrySet()) {
                long remaining = entry.getValue();
                while (remaining > 0L) {
                    int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
                    FluidStack stack = entry.getKey().toStack(amount);
                    if (endpoint != null && online != null && !stack.isEmpty()) {
                        try {
                            StorageFluidOperationResult receipt = endpoint.session().insertFluid(online, stack.copy(), false);
                            stack = confirmedFluidRemainder(receipt, stack);
                        } catch (RuntimeException | LinkageError failure) {
                            uncertain = true;
                            recordUnknown(ResourceType.FLUID, null, stack, stack.getAmount(),
                                    "流体余料退回异常");
                            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 流体余料退回结果不明", craftId, failure);
                            stack = FluidStack.EMPTY;
                        }
                    }
                    if (!stack.isEmpty()) {
                        try {
                            UnifiedGridFluidRecovery recovery = UnifiedGridFluidRecovery.get(server);
                            recovery.retainRaw(playerId, stack.writeToNBT(new CompoundTag()), null);
                            fluidRecoveryRetained = true;
                        } catch (RuntimeException | LinkageError failure) {
                            uncertain = true;
                            recordUnknown(ResourceType.FLUID, null, stack, stack.getAmount(), "流体恢复队列写入失败");
                            RSIntegrationMod.LOGGER.error("[RSI-Materials] 任务 {} 流体恢复记录失败", craftId, failure);
                        }
                    }
                    remaining -= amount;
                }
            }
            heldFluids.clear();
            if (online != null && itemRecoveryRetained) CraftItemRecovery.get(server).claim(online);
            if (online != null && fluidRecoveryRetained) UnifiedGridFluidRecovery.get(server).claim(online);
        }
    }
}
