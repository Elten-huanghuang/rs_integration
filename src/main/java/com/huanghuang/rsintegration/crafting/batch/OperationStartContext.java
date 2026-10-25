package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

/**
 * Structured handoff from the chain-owned reservation to a delegate start.
 *
 * <p>Existing delegates are reached through {@link IBatchDelegate#startOperation}
 * while new delegates can consume the stable material and slot identities directly.</p>
 */
public record OperationStartContext(
        ServerPlayer player,
        ExtractionLedger ledger,
        MaterialOwnership materialOwnership,
        MaterialPlan materialPlan,
        List<ReservedMaterial> materials,
        @Nullable InputBufferPlan inputBufferPlan,
        @Nullable RepeatedOperationPlan repeatedOperationPlan,
        @Nullable UUID transactionId,
        @Nullable UUID operationId,
        int attempt) {

    public enum MaterialOwnership {
        CHAIN_RESERVED,
        DELEGATE_EXTRACTED
    }

    public OperationStartContext {
        if (player == null) throw new IllegalArgumentException("player is required");
        if (ledger == null) throw new IllegalArgumentException("ledger is required");
        materialOwnership = materialOwnership == null
                ? MaterialOwnership.CHAIN_RESERVED : materialOwnership;
        materialPlan = materialPlan == null ? MaterialPlan.none() : materialPlan;
        materials = materials == null ? List.of() : List.copyOf(materials);
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if ((transactionId == null) != (operationId == null)) {
            throw new IllegalArgumentException(
                    "transaction and operation ids must be provided together");
        }
        validateMaterialReferences(materialPlan, materials);
        if (repeatedOperationPlan != null && (materialOwnership != MaterialOwnership.CHAIN_RESERVED
                || !materialPlan.entries().isEmpty() || !materials.isEmpty()
                || inputBufferPlan != null && inputBufferPlan.enabled())) {
            throw new IllegalArgumentException(
                    "repeated operation plan must be the sole chain-reserved material payload");
        }
    }

    public OperationStartContext(
            ServerPlayer player,
            ExtractionLedger ledger,
            MaterialOwnership materialOwnership,
            MaterialPlan materialPlan,
            List<ReservedMaterial> materials,
            @Nullable InputBufferPlan inputBufferPlan,
            @Nullable RepeatedOperationPlan repeatedOperationPlan
    ) {
        this(player, ledger, materialOwnership, materialPlan, materials,
                inputBufferPlan, repeatedOperationPlan, null, null, 1);
    }

    public OperationStartContext withOperationIdentity(
            UUID transactionId,
            UUID operationId,
            int attempt
    ) {
        return new OperationStartContext(
                player, ledger, materialOwnership, materialPlan, materials,
                inputBufferPlan, repeatedOperationPlan,
                transactionId, operationId, attempt);
    }

    public boolean buffered() {
        return inputBufferPlan != null && inputBufferPlan.enabled();
    }

    /**
     * Ordered compatibility projection used only by legacy delegate entry points.
     * Sparse plans retain empty placeholders so delegates that consume fixed input
     * slots receive the same layout that {@code getRequiredMaterials()} declared.
     */
    public List<ItemStack> legacyMaterials() {
        Map<String, ItemStack> byId = new LinkedHashMap<>();
        for (ReservedMaterial material : materials) {
            byId.put(material.entryId(), material.stack());
        }

        boolean hasExplicitSlots = materialPlan.entries().stream()
                .anyMatch(entry -> entry.inputSlot() != null);
        if (!hasExplicitSlots) {
            return materialPlan.entries().stream()
                    .map(entry -> reservedStack(byId, entry).copy())
                    .toList();
        }

        int slotCount = materialPlan.entries().size();
        for (MaterialPlan.Entry entry : materialPlan.entries()) {
            if (entry.inputSlot() != null) {
                slotCount = Math.max(slotCount, entry.inputSlot() + 1);
            }
        }
        List<ItemStack> ordered = new ArrayList<>(
                Collections.nCopies(slotCount, ItemStack.EMPTY));
        int nextSlot = 0;
        for (MaterialPlan.Entry entry : materialPlan.entries()) {
            int slot = entry.inputSlot() == null ? nextFreeSlot(ordered, nextSlot) : entry.inputSlot();
            if (slot < 0 || slot >= ordered.size()) {
                throw new IllegalStateException("invalid input slot for " + entry.id());
            }
            if (!ordered.get(slot).isEmpty()) {
                throw new IllegalStateException("duplicate input slot for " + entry.id());
            }
            ordered.set(slot, reservedStack(byId, entry).copy());
            nextSlot = slot + 1;
        }
        return List.copyOf(ordered);
    }

    /**
     * Adapts an existing ordered reservation to stable material identities.
     * Compact lists map directly to plan order; lists retaining empty legacy
     * slots use each entry's recorded compatibility index.
     */
    public static OperationStartContext chainReserved(
            ServerPlayer player, ExtractionLedger ledger, MaterialPlan materialPlan,
            List<ItemStack> orderedMaterials, @Nullable InputBufferPlan inputBufferPlan) {
        MaterialPlan plan = materialPlan == null ? MaterialPlan.none() : materialPlan;
        List<ItemStack> ordered = orderedMaterials == null ? List.of() : orderedMaterials;
        List<ReservedMaterial> reserved = new ArrayList<>(plan.entries().size());
        boolean compact = ordered.size() == plan.entries().size()
                && ordered.stream().allMatch(stack -> stack != null && !stack.isEmpty());
        for (int index = 0; index < plan.entries().size(); index++) {
            MaterialPlan.Entry entry = plan.entries().get(index);
            int sourceIndex = compact ? index
                    : entry.inputSlot() == null ? -1 : entry.inputSlot();
            if (sourceIndex < 0 || sourceIndex >= ordered.size()) {
                throw new IllegalArgumentException("missing ordered material for " + entry.id());
            }
            ItemStack stack = ordered.get(sourceIndex);
            if (stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("empty ordered material for " + entry.id());
            }
            reserved.add(new ReservedMaterial(entry.id(), stack));
        }
        InputBufferPlan buffer = inputBufferPlan == null
                ? InputBufferPlan.none()
                : inputBufferPlan.withResolvedMaterials(reserved);
        if (inputBufferPlan != null && inputBufferPlan.enabled() && !buffer.enabled()) {
            throw new IllegalArgumentException("input buffer could not bind reserved materials");
        }
        return new OperationStartContext(player, ledger, MaterialOwnership.CHAIN_RESERVED,
                plan, reserved, buffer, null);
    }

    private static ItemStack reservedStack(Map<String, ItemStack> byId, MaterialPlan.Entry entry) {
        ItemStack stack = byId.get(entry.id());
        if (stack == null || stack.isEmpty()) {
            throw new IllegalStateException("missing reserved material " + entry.id());
        }
        return stack;
    }

    private static int nextFreeSlot(List<ItemStack> ordered, int start) {
        for (int slot = Math.max(0, start); slot < ordered.size(); slot++) {
            if (ordered.get(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** Unified outer-group handoff for an operation-major material matrix. */
    public static OperationStartContext repeatedChainReserved(
            ServerPlayer player, ExtractionLedger ledger, MaterialPlan perOperationPlan,
            int operations, int legacyStride, List<ItemStack> flatMaterials) {
        return new OperationStartContext(player, ledger, MaterialOwnership.CHAIN_RESERVED,
                MaterialPlan.none(), List.of(), InputBufferPlan.none(),
                new RepeatedOperationPlan(perOperationPlan, operations,
                        legacyStride, flatMaterials));
    }

    /** Unified start context for delegates that intentionally acquire their own materials. */
    public static OperationStartContext delegateExtracted(
            ServerPlayer player, ExtractionLedger ledger) {
        return new OperationStartContext(player, ledger, MaterialOwnership.DELEGATE_EXTRACTED,
                MaterialPlan.none(), List.of(), InputBufferPlan.none(), null);
    }

    public record ReservedMaterial(String entryId, ItemStack stack) {
        public ReservedMaterial {
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("reserved material entry id must not be blank");
            }
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            if (stack.isEmpty()) throw new IllegalArgumentException("reserved material stack must not be empty");
        }
    }

    private static void validateMaterialReferences(MaterialPlan plan,
                                                   List<ReservedMaterial> materials) {
        Set<String> knownIds = plan.entries().stream().map(MaterialPlan.Entry::id)
                .collect(Collectors.toSet());
        Set<String> suppliedIds = new HashSet<>();
        for (ReservedMaterial material : materials) {
            if (!knownIds.contains(material.entryId())) {
                throw new IllegalArgumentException("unknown reserved material entry id "
                        + material.entryId());
            }
            if (!suppliedIds.add(material.entryId())) {
                throw new IllegalArgumentException("duplicate reserved material entry id "
                        + material.entryId());
            }
        }
    }
}
