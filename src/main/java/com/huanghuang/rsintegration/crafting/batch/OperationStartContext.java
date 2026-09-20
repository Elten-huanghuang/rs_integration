package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        @Nullable RepeatedOperationPlan repeatedOperationPlan) {

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
        validateMaterialReferences(materialPlan, materials);
        if (repeatedOperationPlan != null && (materialOwnership != MaterialOwnership.CHAIN_RESERVED
                || !materialPlan.entries().isEmpty() || !materials.isEmpty()
                || inputBufferPlan != null && inputBufferPlan.enabled())) {
            throw new IllegalArgumentException(
                    "repeated operation plan must be the sole chain-reserved material payload");
        }
    }

    public boolean buffered() {
        return inputBufferPlan != null && inputBufferPlan.enabled();
    }

    /** Ordered compatibility projection used only by legacy delegate entry points. */
    public List<ItemStack> legacyMaterials() {
        Map<String, ItemStack> byId = new LinkedHashMap<>();
        for (ReservedMaterial material : materials) {
            byId.put(material.entryId(), material.stack());
        }
        return materialPlan.entries().stream()
                .map(entry -> {
                    ItemStack stack = byId.get(entry.id());
                    if (stack == null || stack.isEmpty()) {
                        throw new IllegalStateException("missing reserved material " + entry.id());
                    }
                    return stack.copy();
                })
                .toList();
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
        List<ReservedMaterial> reserved = new java.util.ArrayList<>(plan.entries().size());
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
                .collect(java.util.stream.Collectors.toSet());
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
