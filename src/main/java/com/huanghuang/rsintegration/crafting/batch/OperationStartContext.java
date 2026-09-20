package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structured handoff from the chain-owned reservation to a delegate start.
 *
 * <p>This is a data model only. Existing delegates continue through the legacy
 * start methods until they are migrated one at a time.</p>
 */
public record OperationStartContext(
        ServerPlayer player,
        ExtractionLedger ledger,
        MaterialOwnership materialOwnership,
        MaterialPlan materialPlan,
        List<ReservedMaterial> materials,
        @Nullable InputBufferPlan inputBufferPlan) {

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
    }

    public boolean buffered() {
        return inputBufferPlan != null && inputBufferPlan.enabled();
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
