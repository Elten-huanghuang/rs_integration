package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 持久化记录无法确认去向的任务材料，并提供管理员人工结算入口。 */
public final class MaterialTransferAuditLedger extends SavedData {
    private static final String DATA_NAME = "rs_integration_material_transfer_audits";

    public enum ResourceType { ITEM, FLUID, UNKNOWN }
    public enum Resolution { CONSUMED, REFUND }

    public record Pending(UUID attemptId, UUID taskId, UUID ownerId, ResourceType type,
                         @Nullable ResourceLocation resourceId, CompoundTag identity,
                         long amount, String reason, long recordedAt) {
        public Pending {
            identity = identity == null ? new CompoundTag() : identity.copy();
        }

        @Override public CompoundTag identity() { return identity.copy(); }
    }

    public record Resolved(Pending attempt, Resolution resolution, long refundAmount,
                           @Nullable UUID resolvedBy, long resolvedAt) {}

    /** 与恢复队列保存在同一份 SavedData，领取后也保留首次退款决定。 */
    public static final class RefundJournal {
        private final Map<UUID, Resolved> decisions = new LinkedHashMap<>();

        public RefundJournal() {}

        public RefundJournal(CompoundTag tag) {
            for (Tag value : tag.getList("refundDecisions", Tag.TAG_COMPOUND)) {
                Resolved decision = readResolution((CompoundTag) value);
                if (decision != null && decision.resolution() == Resolution.REFUND) {
                    decisions.putIfAbsent(decision.attempt().attemptId(), decision);
                }
            }
        }

        @Nullable public Resolved decision(UUID attemptId) { return decisions.get(attemptId); }
        public List<Resolved> decisions() { return List.copyOf(decisions.values()); }

        /** 旧版只有去重 ID、无法还原原决定时，禁止猜测金额或改判已消费。 */
        public boolean hasLegacyRefund(UUID attemptId, Set<UUID> processedIds) {
            return !decisions.containsKey(attemptId) && processedIds.contains(partId(attemptId, 0));
        }

        public boolean lock(Resolved decision, Set<UUID> processedIds) {
            UUID id = decision.attempt().attemptId();
            Resolved previous = decisions.get(id);
            if (previous != null) return previous.equals(decision);
            if (decision.resolution() != Resolution.REFUND || hasLegacyRefund(id, processedIds)) return false;
            decisions.put(id, decision);
            return true;
        }

        public void save(CompoundTag tag) {
            ListTag entries = new ListTag();
            decisions.values().forEach(decision -> entries.add(writeResolution(decision)));
            tag.put("refundDecisions", entries);
        }

        public static UUID partId(UUID attemptId, int partIndex) {
            return UUID.nameUUIDFromBytes((attemptId + ":" + partIndex).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Resolved> resolved = new LinkedHashMap<>();
    private final Map<UUID, Resolved> refundIntents = new LinkedHashMap<>();

    public MaterialTransferAuditLedger() {}

    MaterialTransferAuditLedger(CompoundTag tag) {
        for (Tag value : tag.getList("pending", Tag.TAG_COMPOUND)) {
            Pending attempt = readPending((CompoundTag) value);
            if (attempt != null) pending.put(attempt.attemptId(), attempt);
        }
        for (Tag value : tag.getList("resolved", Tag.TAG_COMPOUND)) {
            Resolved entry = readResolution((CompoundTag) value);
            if (entry != null) {
                resolved.put(entry.attempt().attemptId(), entry);
                pending.remove(entry.attempt().attemptId());
            }
        }
        for (Tag value : tag.getList("refundIntents", Tag.TAG_COMPOUND)) {
            Resolved entry = readResolution((CompoundTag) value);
            if (entry != null && entry.resolution() == Resolution.REFUND
                    && entry.attempt().equals(pending.get(entry.attempt().attemptId()))) {
                refundIntents.put(entry.attempt().attemptId(), entry);
            }
        }
    }

    public static MaterialTransferAuditLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MaterialTransferAuditLedger::new, MaterialTransferAuditLedger::new, DATA_NAME);
    }

    public UUID recordUnknownItem(UUID taskId, UUID ownerId, ItemStack stack,
                                  long amount, String reason, long recordedAt) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return recordUnknown(taskId, ownerId, ResourceType.ITEM, itemId,
                stack.getTag(), amount, reason, recordedAt);
    }

    public UUID recordUnknownFluid(UUID taskId, UUID ownerId, FluidStack stack,
                                   long amount, String reason, long recordedAt) {
        ResourceLocation fluidId = stack == null || stack.isEmpty()
                ? null : ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        return recordUnknown(taskId, ownerId, ResourceType.FLUID, fluidId,
                stack == null ? null : stack.getTag(), amount, reason, recordedAt);
    }

    public UUID recordUnknown(UUID taskId, UUID ownerId, ResourceType type,
                              @Nullable ResourceLocation resourceId, @Nullable CompoundTag identity,
                              long amount, String reason, long recordedAt) {
        return recordUnknown(UUID.randomUUID(), taskId, ownerId, type, resourceId, identity,
                amount, reason, recordedAt);
    }

    /** 同一转移动作沿用同一 ID，重复通知不新增记录，也不覆盖人工结论。 */
    public UUID recordUnknown(UUID attemptId, UUID taskId, UUID ownerId, ResourceType type,
                              @Nullable ResourceLocation resourceId, @Nullable CompoundTag identity,
                              long amount, String reason, long recordedAt) {
        if (attemptId == null || taskId == null || ownerId == null || type == null || amount < 0L) {
            throw new IllegalArgumentException("材料对账记录字段无效");
        }
        Pending next = new Pending(attemptId, taskId, ownerId, type, resourceId,
                identity, amount, reason == null ? "unknown" : reason, Math.max(0L, recordedAt));
        Pending previous = pending.get(attemptId);
        if (previous == null && resolved.containsKey(attemptId)) previous = resolved.get(attemptId).attempt();
        if (previous != null) {
            if (!previous.taskId().equals(next.taskId()) || !previous.ownerId().equals(next.ownerId())
                    || previous.type() != next.type() || !Objects.equals(previous.resourceId(), next.resourceId())
                    || !previous.identity().equals(next.identity()) || previous.amount() != next.amount()) {
                throw new IllegalArgumentException("同一转移 ID 的材料证据冲突: " + attemptId);
            }
            return attemptId;
        }
        pending.put(attemptId, next);
        setDirty();
        return attemptId;
    }

    public List<Pending> pendingAttempts() { return List.copyOf(pending.values()); }
    public List<Resolved> resolvedAttempts() { return List.copyOf(resolved.values()); }
    @Nullable public Pending pendingAttempt(UUID attemptId) { return pending.get(attemptId); }
    @Nullable public Resolved resolvedAttempt(UUID attemptId) { return resolved.get(attemptId); }
    @Nullable public Resolved refundIntent(UUID attemptId) { return refundIntents.get(attemptId); }
    public boolean hasPendingForTask(UUID taskId) {
        return pending.values().stream().anyMatch(attempt -> attempt.taskId().equals(taskId));
    }

    /** 退款先锁定人工结论，再以 attemptId 幂等入队；中途失败只能按原数量重放。 */
    public boolean resolve(MinecraftServer server, UUID attemptId, Resolution resolution,
                           long refundAmount, @Nullable UUID resolvedBy, long resolvedAt) {
        Pending attempt = pending.get(attemptId);
        if (attempt == null || resolution == null) return false;
        try {
            Resolved receipt = recoveryDecision(server, attempt);
            if (receipt != null) {
                if (!receipt.attempt().equals(attempt)) return false;
                refundIntents.put(attemptId, receipt);
                setDirty();
            }
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error("[RSI-Materials] 核对 {} 无法确认原退款决定，保留待核对状态", attemptId, failure);
            return false;
        }
        Resolved decision = refundIntents.get(attemptId);
        if (decision != null && (resolution != Resolution.REFUND
                || decision.refundAmount() != refundAmount)) return false;
        if (resolution == Resolution.REFUND) {
            if (refundAmount <= 0L || refundAmount > attempt.amount()
                    || attempt.resourceId() == null || attempt.type() == ResourceType.UNKNOWN) return false;
            if (attempt.type() == ResourceType.ITEM
                    && BuiltInRegistries.ITEM.get(attempt.resourceId()) == Items.AIR) return false;
            if (decision == null) {
                decision = new Resolved(attempt, resolution, refundAmount, resolvedBy, Math.max(0L, resolvedAt));
                refundIntents.put(attemptId, decision);
                setDirty();
            }
            if (!tryQueueRefund(server, decision)) return false;
        } else if (refundAmount != 0L) return false;
        if (decision == null) {
            decision = new Resolved(attempt, resolution, refundAmount, resolvedBy, Math.max(0L, resolvedAt));
        }
        pending.remove(attemptId);
        refundIntents.remove(attemptId);
        resolved.put(attemptId, decision);
        setDirty();
        return true;
    }

    private boolean tryQueueRefund(MinecraftServer server, Resolved decision) {
        try {
            return queueRefund(server, decision);
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error("[RSI-Materials] 核对退款 {} 入队失败，保留原结论等待重放",
                    decision.attempt().attemptId(), failure);
            return false;
        }
    }

    @Nullable
    private Resolved recoveryDecision(MinecraftServer server, Pending attempt) {
        if (attempt.type() == ResourceType.ITEM) {
            CraftItemRecovery recovery = CraftItemRecovery.get(server);
            if (recovery.hasLegacyRefund(attempt.attemptId())) {
                throw new IllegalStateException("旧版退款只有去重记录，需人工核对原退款数量");
            }
            return recovery.refundDecision(attempt.attemptId());
        }
        if (attempt.type() == ResourceType.FLUID) {
            UnifiedGridFluidRecovery recovery = UnifiedGridFluidRecovery.get(server);
            if (recovery.hasLegacyRefund(attempt.attemptId())) {
                throw new IllegalStateException("旧版流体退款只有去重记录，需人工核对原退款数量");
            }
            return recovery.refundDecision(attempt.attemptId());
        }
        return null;
    }

    private boolean queueRefund(MinecraftServer server, Resolved decision) {
        Pending attempt = decision.attempt();
        long refundAmount = decision.refundAmount();
        if (refundAmount <= 0L || attempt.resourceId() == null
                || attempt.type() == ResourceType.UNKNOWN) return false;
        // 决定、分片进度和可领取余额在同一 SavedData 中保存，不能分别落盘。
        if (attempt.type() == ResourceType.ITEM) {
            if (!CraftItemRecovery.get(server).lockRefundDecision(decision)) return false;
        } else if (!UnifiedGridFluidRecovery.get(server).lockRefundDecision(decision)) return false;
        long remaining = refundAmount;
        int partIndex = 0;
        while (remaining > 0L) {
            int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
            UUID refundPartId = RefundJournal.partId(attempt.attemptId(), partIndex++);
            if (attempt.type() == ResourceType.ITEM) {
                Item item = BuiltInRegistries.ITEM.get(attempt.resourceId());
                if (item == null || item == Items.AIR) return false;
                ItemStack stack = new ItemStack(item, amount);
                if (!attempt.identity().isEmpty()) stack.setTag(attempt.identity());
                CraftItemRecovery.get(server).retainOnce(attempt.ownerId(), stack, refundPartId);
            } else {
                CompoundTag fluid = new CompoundTag();
                fluid.putString("FluidName", attempt.resourceId().toString());
                fluid.putInt("Amount", amount);
                if (!attempt.identity().isEmpty()) fluid.put("Tag", attempt.identity());
                UnifiedGridFluidRecovery.get(server).retainRawOnce(attempt.ownerId(),
                        fluid, null, refundPartId);
            }
            remaining -= amount;
        }
        return true;
    }

    /** 先从恢复队列还原首次决定，再按稳定分片 ID 补齐余额；领取过的分片不会重发。 */
    public void replayRefunds(MinecraftServer server) {
        List<Resolved> receipts = new ArrayList<>(CraftItemRecovery.get(server).refundDecisions());
        receipts.addAll(UnifiedGridFluidRecovery.get(server).refundDecisions());
        for (Resolved receipt : receipts) {
            UUID id = receipt.attempt().attemptId();
            Pending existing = pending.get(id);
            if (existing == null && resolved.containsKey(id)) existing = resolved.get(id).attempt();
            if (existing != null && !existing.equals(receipt.attempt())) {
                RSIntegrationMod.LOGGER.error("[RSI-Materials] 退款 {} 的材料身份冲突，停止重放", id);
                continue;
            }
            pending.put(id, receipt.attempt());
            resolved.remove(id);
            refundIntents.put(id, receipt);
            setDirty();
        }
        for (Resolved entry : List.copyOf(resolved.values())) {
            if (entry.resolution() == Resolution.REFUND) {
                UUID id = entry.attempt().attemptId();
                pending.put(id, entry.attempt());
                refundIntents.put(id, entry);
                resolved.remove(id);
                setDirty();
            }
        }
        for (Resolved entry : List.copyOf(refundIntents.values())) {
            resolve(server, entry.attempt().attemptId(), Resolution.REFUND, entry.refundAmount(),
                    entry.resolvedBy(), entry.resolvedAt());
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag serializedPending = new ListTag();
        pending.values().forEach(attempt -> serializedPending.add(writePending(attempt)));
        tag.put("pending", serializedPending);
        ListTag serializedResolved = new ListTag();
        resolved.values().forEach(entry -> serializedResolved.add(writeResolution(entry)));
        tag.put("resolved", serializedResolved);
        ListTag serializedIntents = new ListTag();
        refundIntents.values().forEach(entry -> serializedIntents.add(writeResolution(entry)));
        tag.put("refundIntents", serializedIntents);
        return tag;
    }

    private static CompoundTag writeResolution(Resolved entry) {
        CompoundTag value = new CompoundTag();
        value.put("attempt", writePending(entry.attempt()));
        value.putString("resolution", entry.resolution().name());
        value.putLong("refundAmount", entry.refundAmount());
        if (entry.resolvedBy() != null) value.putUUID("resolvedBy", entry.resolvedBy());
        value.putLong("resolvedAt", entry.resolvedAt());
        return value;
    }

    @Nullable
    private static Resolved readResolution(CompoundTag tag) {
        Pending attempt = readPending(tag.getCompound("attempt"));
        if (attempt == null || !tag.contains("resolution", Tag.TAG_STRING)) return null;
        try {
            Resolution resolution = Resolution.valueOf(tag.getString("resolution"));
            long refundAmount = tag.getLong("refundAmount");
            if (resolution == Resolution.REFUND
                    ? refundAmount <= 0L || refundAmount > attempt.amount()
                    : refundAmount != 0L) return null;
            UUID resolver = tag.hasUUID("resolvedBy") ? tag.getUUID("resolvedBy") : null;
            return new Resolved(attempt, resolution, refundAmount, resolver, tag.getLong("resolvedAt"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static CompoundTag writePending(Pending attempt) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("attemptId", attempt.attemptId());
        tag.putUUID("taskId", attempt.taskId());
        tag.putUUID("ownerId", attempt.ownerId());
        tag.putString("type", attempt.type().name());
        if (attempt.resourceId() != null) tag.putString("resourceId", attempt.resourceId().toString());
        tag.put("identity", attempt.identity());
        tag.putLong("amount", attempt.amount());
        tag.putString("reason", attempt.reason());
        tag.putLong("recordedAt", attempt.recordedAt());
        return tag;
    }

    @Nullable
    private static Pending readPending(CompoundTag tag) {
        if (!tag.hasUUID("attemptId") || !tag.hasUUID("taskId") || !tag.hasUUID("ownerId")
                || !tag.contains("type", Tag.TAG_STRING)) return null;
        try {
            ResourceType type = ResourceType.valueOf(tag.getString("type"));
            ResourceLocation resourceId = tag.contains("resourceId", Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(tag.getString("resourceId")) : null;
            if (tag.contains("resourceId", Tag.TAG_STRING) && resourceId == null) return null;
            long amount = tag.getLong("amount");
            if (amount < 0L) return null;
            return new Pending(tag.getUUID("attemptId"), tag.getUUID("taskId"), tag.getUUID("ownerId"),
                    type, resourceId, tag.getCompound("identity"), amount,
                    tag.getString("reason"), tag.getLong("recordedAt"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
