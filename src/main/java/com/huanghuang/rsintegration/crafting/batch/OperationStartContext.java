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
     * 仅在机器投料边界生成物理槽位视图；材料身份不由此视图反推。
     */
    public List<ItemStack> inputSlots() {
        Map<String, ItemStack> byId = new LinkedHashMap<>();
        for (ReservedMaterial material : materials) {
            byId.put(material.entryId(), material.stack());
        }

        int slotCount = 1 + materialPlan.entries().stream().mapToInt(MaterialPlan.Entry::inputSlot).max().orElse(-1);
        List<ItemStack> ordered = new ArrayList<>(
                Collections.nCopies(slotCount, ItemStack.EMPTY));
        for (MaterialPlan.Entry entry : materialPlan.entries()) {
            ordered.set(entry.inputSlot(), byId.get(entry.id()).copy());
        }
        return List.copyOf(ordered);
    }

    public static OperationStartContext chainReserved(
            ServerPlayer player, ExtractionLedger ledger, MaterialPlan plan,
            List<ReservedMaterial> reserved, @Nullable InputBufferPlan inputBufferPlan) {
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
            int operations, List<ItemStack> flatMaterials) {
        return new OperationStartContext(player, ledger, MaterialOwnership.CHAIN_RESERVED,
                MaterialPlan.none(), List.of(), InputBufferPlan.none(),
                new RepeatedOperationPlan(perOperationPlan, operations, flatMaterials));
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
        if (!suppliedIds.equals(knownIds)) throw new IllegalArgumentException("missing reserved materials");
    }
}
