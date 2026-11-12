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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (taskId == null || ownerId == null || type == null || amount < 0L) {
            throw new IllegalArgumentException("材料对账记录字段无效");
        }
        UUID attemptId = UUID.randomUUID();
        pending.put(attemptId, new Pending(attemptId, taskId, ownerId, type, resourceId,
                identity, amount, reason == null ? "unknown" : reason, Math.max(0L, recordedAt)));
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
            return queueRefund(server, decision.attempt(), decision.refundAmount());
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error("[RSI-Materials] 核对退款 {} 入队失败，保留原结论等待重放",
                    decision.attempt().attemptId(), failure);
            return false;
        }
    }

    private boolean queueRefund(MinecraftServer server, Pending attempt, long refundAmount) {
        if (refundAmount <= 0L || attempt.resourceId() == null
                || attempt.type() == ResourceType.UNKNOWN) return false;
        long remaining = refundAmount;
        int partIndex = 0;
        while (remaining > 0L) {
            int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
            UUID refundPartId = UUID.nameUUIDFromBytes((attempt.attemptId() + ":" + partIndex++)
                    .getBytes(StandardCharsets.US_ASCII));
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

    /** 队列和核对档案分别存盘；启动时以相同退款 ID 补齐尚未落盘的队列。 */
    public void replayRefunds(MinecraftServer server) {
        for (Resolved entry : resolved.values()) {
            if (entry.resolution() == Resolution.REFUND) {
                tryQueueRefund(server, entry);
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
