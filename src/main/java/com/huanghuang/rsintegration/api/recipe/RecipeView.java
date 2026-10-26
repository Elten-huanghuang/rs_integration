package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationFingerprints;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个 live recipe 的不可变公共投影。
 *
 * <p>所有列表都防御性复制。构造时会校验 entry ID、output port、材料与输出关联、
 * flags 以及有序槽位是否一致。配方成功执行后，机器只能结算本视图声明且执行器实际
 * 观察到的输出。</p>
 */
public record RecipeView(
        ResourceLocation recipeId,
        ResourceLocation processId,
        List<MaterialRequirement> materials,
        List<FluidRequirement> fluids,
        List<OutputDeclaration> outputs,
        RecipeFlags flags
) {
    public RecipeView {
        if (recipeId == null) throw new IllegalArgumentException("recipeId is required");
        if (processId == null) throw new IllegalArgumentException("processId is required");
        materials = materials == null ? List.of() : List.copyOf(materials);
        fluids = fluids == null ? List.of() : List.copyOf(fluids);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        if (flags == null) throw new IllegalArgumentException("flags is required");
        validateSemantics(materials, fluids, outputs, flags);
    }

    /**
     * 返回根据当前视图内容重新计算的稳定 fingerprint。
     */
    public String definitionFingerprint() {
        return IntegrationFingerprints.recipeDefinition(this);
    }

    private static void validateSemantics(
            List<MaterialRequirement> materials,
            List<FluidRequirement> fluids,
            List<OutputDeclaration> outputs,
            RecipeFlags flags
    ) {
        Set<ResourceLocation> inputIds = new HashSet<>();
        Map<ResourceLocation, OutputDeclaration> outputsById = new HashMap<>();
        for (MaterialRequirement material : materials) {
            if (!inputIds.add(material.entryId())) {
                throw new IllegalArgumentException(
                        "duplicate input entry id: " + material.entryId());
            }
        }
        for (FluidRequirement fluid : fluids) {
            if (!inputIds.add(fluid.entryId())) {
                throw new IllegalArgumentException(
                        "duplicate input entry id: " + fluid.entryId());
            }
        }
        for (OutputDeclaration output : outputs) {
            if (outputsById.put(output.portId(), output) != null) {
                throw new IllegalArgumentException(
                        "duplicate output port id: " + output.portId());
            }
        }

        Set<ResourceLocation> linkedOutputs = new HashSet<>();
        for (MaterialRequirement material : materials) {
            validateLink(material.consumption(), outputsById, linkedOutputs);
        }
        for (FluidRequirement fluid : fluids) {
            validateLink(fluid.consumption(), outputsById, linkedOutputs);
        }
        for (OutputDeclaration output : outputs) {
            ResourceLocation sourceId = output.sourceInputEntryId();
            if (sourceId != null && !inputIds.contains(sourceId)) {
                throw new IllegalArgumentException(
                        "output source input does not exist: " + sourceId);
            }
        }

        boolean targetProducing = outputs.stream().anyMatch(
                OutputDeclaration::targetProducing);
        boolean runtimeIdentity = outputs.stream().anyMatch(
                OutputDeclaration::dynamicIdentity);
        boolean probabilistic = outputs.stream().anyMatch(output ->
                output.probability().mode() != OutputProbabilityMode.GUARANTEED);
        boolean requiresFuel = materials.stream().anyMatch(material ->
                material.consumption().kind() == InputConsumption.Kind.FUEL)
                || fluids.stream().anyMatch(fluid ->
                fluid.consumption().kind() == InputConsumption.Kind.FUEL);

        if (flags.targetProduction() != targetProducing) {
            throw new IllegalArgumentException(
                    "targetProduction flag does not match output declarations");
        }
        if (flags.runtimeDependentIdentity() != runtimeIdentity) {
            throw new IllegalArgumentException(
                    "runtimeDependentIdentity flag does not match output declarations");
        }
        if (flags.deterministicOutput() && probabilistic) {
            throw new IllegalArgumentException(
                    "deterministic recipe cannot declare probabilistic outputs");
        }
        if (flags.requiresFuel() != requiresFuel) {
            throw new IllegalArgumentException(
                    "requiresFuel flag does not match input declarations");
        }
        if (flags.orderedInputs()) {
            validateOrderedSlots(materials, fluids);
        }
    }

    private static void validateLink(
            InputConsumption consumption,
            Map<ResourceLocation, OutputDeclaration> outputsById,
            Set<ResourceLocation> linkedOutputs
    ) {
        ResourceLocation portId = consumption.linkedOutputPortId();
        if (portId == null) return;
        OutputDeclaration output = outputsById.get(portId);
        if (output == null) {
            throw new IllegalArgumentException(
                    "linked output port does not exist: " + portId);
        }
        if (!linkedOutputs.add(portId)) {
            throw new IllegalArgumentException(
                    "output port is linked by multiple inputs: " + portId);
        }
        if (consumption.kind() == InputConsumption.Kind.TRANSFORMED
                && output.outputKind() != OutputKind.TRANSFORMED) {
            throw new IllegalArgumentException(
                    "transformed input must link a TRANSFORMED output");
        }
        if (consumption.kind() == InputConsumption.Kind.CONTAINER_RETURNED
                && output.outputKind() != OutputKind.CONTAINER
                && output.outputKind() != OutputKind.REMAINDER) {
            throw new IllegalArgumentException(
                    "container input must link a CONTAINER or REMAINDER output");
        }
    }

    private static void validateOrderedSlots(
            List<MaterialRequirement> materials,
            List<FluidRequirement> fluids
    ) {
        Set<String> slots = new HashSet<>();
        for (MaterialRequirement material : materials) {
            if (material.inputSlot() == null) continue;
            String key = material.inputGroup() + ":" + material.inputSlot();
            if (!slots.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate ordered input slot: " + key);
            }
        }
        for (FluidRequirement fluid : fluids) {
            if (fluid.inputSlot() == null) continue;
            String key = fluid.inputGroup() + ":" + fluid.inputSlot();
            if (!slots.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate ordered input slot: " + key);
            }
        }
    }
}
