package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.recipe.FluidRequirement;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineEnergyPort;
import com.huanghuang.rsintegration.api.machine.MachineFluidPort;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@ApiStatus.AvailableSince("1.0.0")
public final class IntegrationFingerprints {
    private IntegrationFingerprints() {}

    public static String recipeDefinition(RecipeView recipe) {
        if (recipe == null) throw new IllegalArgumentException("recipe is required");
        StringBuilder canonical = new StringBuilder("v1|");
        appendResourceLocation(canonical, recipe.recipeId());
        appendResourceLocation(canonical, recipe.processId());
        appendMaterials(canonical, recipe.materials());
        appendFluids(canonical, recipe.fluids());
        appendOutputs(canonical, recipe.outputs());
        appendFlags(canonical, recipe.flags());
        return "v1:" + digest(canonical.toString());
    }

    public static String resourceIdentity(IntegrationResourceKey resource) {
        if (resource == null) throw new IllegalArgumentException("resource is required");
        StringBuilder canonical = new StringBuilder("resource|");
        appendResourceKey(canonical, resource);
        return digest(canonical.toString());
    }

    public static String machineDescriptor(MachineDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        StringBuilder canonical = new StringBuilder("machine-v1|");
        appendResourceLocation(canonical, descriptor.machineId());
        appendLong(canonical, descriptor.descriptorRevision());
        appendInt(canonical, descriptor.acceptedProcesses().size());
        descriptor.acceptedProcesses().stream()
                .map(ResourceLocation::toString)
                .sorted()
                .forEach(process -> appendString(canonical, process));
        appendItemPorts(canonical, descriptor.itemPorts());
        appendFluidPorts(canonical, descriptor.fluidPorts());
        appendEnergyPorts(canonical, descriptor.energyPorts());
        appendString(canonical, descriptor.executionMode().name());
        appendBoolean(canonical, descriptor.multiPreload());
        appendConcurrency(canonical, descriptor.concurrency());
        appendSupportScope(canonical, descriptor.supportScope());
        appendBoolean(canonical, descriptor.hasGui());
        return "machine-v1:" + digest(canonical.toString());
    }

    private static void appendMaterials(
            StringBuilder output, List<MaterialRequirement> materials) {
        appendInt(output, materials.size());
        for (MaterialRequirement material : materials) {
            appendResourceLocation(output, material.entryId());
            appendInt(output, material.ingredient().alternatives().size());
            material.ingredient().alternatives().forEach(alternative ->
                    appendResourceKey(output, alternative.resource()));
            appendString(output, material.ingredient().matchMode().name());
            appendLong(output, material.amount());
            appendConsumption(output, material.consumption());
            appendString(output, material.inputGroup());
            appendNullableInt(output, material.inputSlot());
            appendBoolean(output, material.recursive());
        }
    }

    private static void appendFluids(
            StringBuilder output, List<FluidRequirement> fluids) {
        appendInt(output, fluids.size());
        for (FluidRequirement fluid : fluids) {
            appendResourceLocation(output, fluid.entryId());
            appendInt(output, fluid.fluid().alternatives().size());
            fluid.fluid().alternatives().forEach(resource ->
                    appendResourceKey(output, resource));
            appendString(output, fluid.fluid().matchMode().name());
            appendLong(output, fluid.amount());
            appendConsumption(output, fluid.consumption());
            appendString(output, fluid.inputGroup());
            appendNullableInt(output, fluid.inputSlot());
            appendBoolean(output, fluid.recursive());
        }
    }

    private static void appendOutputs(
            StringBuilder output, List<OutputDeclaration> outputs) {
        appendInt(output, outputs.size());
        for (OutputDeclaration declaration : outputs) {
            appendResourceLocation(output, declaration.portId());
            appendString(output, declaration.outputGroup());
            appendResourceKey(output, declaration.resource());
            appendLong(output, declaration.amount().min());
            appendLong(output, declaration.amount().max());
            appendString(output, declaration.outputKind().name());
            appendProbability(output, declaration.probability());
            appendBoolean(output, declaration.dynamicIdentity());
            appendBoolean(output, declaration.targetProducing());
            appendNullableResourceLocation(output, declaration.sourceInputEntryId());
        }
    }

    private static void appendProbability(
            StringBuilder output, OutputProbability probability) {
        appendString(output, probability.mode().name());
        appendNullableDouble(output, probability.chance());
        appendInt(output, probability.maxAttempts());
        appendString(output, probability.rollGroup());
    }

    private static void appendConsumption(
            StringBuilder output, InputConsumption consumption) {
        appendString(output, consumption.kind().name());
        appendInt(output, consumption.damagePerUse());
        appendLong(output, consumption.fuelBurnTicksPerOperation());
        appendNullableResourceLocation(output, consumption.linkedOutputPortId());
    }

    private static void appendFlags(StringBuilder output, RecipeFlags flags) {
        appendBoolean(output, flags.recursivePlanning());
        appendBoolean(output, flags.targetProduction());
        appendBoolean(output, flags.deterministicOutput());
        appendBoolean(output, flags.runtimeDependentIdentity());
        appendBoolean(output, flags.requiresFuel());
        appendBoolean(output, flags.supportsBatching());
        appendBoolean(output, flags.orderedInputs());
    }

    private static void appendItemPorts(
            StringBuilder output, List<MachineItemPort> ports) {
        appendInt(output, ports.size());
        ports.stream()
                .sorted(Comparator.comparing(MachineItemPort::portId))
                .forEach(port -> {
                    appendString(output, port.portId());
                    appendInt(output, port.slot());
                    appendNullableEnum(output, port.side());
                    appendString(output, port.direction().name());
                    appendString(output, port.access().name());
                    appendNullableString(output, port.inputGroup());
                    appendNullableString(output, port.outputGroup());
                    appendInt(output, port.capacity());
                });
    }

    private static void appendFluidPorts(
            StringBuilder output, List<MachineFluidPort> ports) {
        appendInt(output, ports.size());
        ports.stream()
                .sorted(Comparator.comparing(MachineFluidPort::portId))
                .forEach(port -> {
                    appendString(output, port.portId());
                    appendInt(output, port.tank());
                    appendNullableEnum(output, port.side());
                    appendString(output, port.direction().name());
                    appendString(output, port.access().name());
                    appendNullableString(output, port.inputGroup());
                    appendNullableString(output, port.outputGroup());
                    appendLong(output, port.capacity());
                });
    }

    private static void appendEnergyPorts(
            StringBuilder output, List<MachineEnergyPort> ports) {
        appendInt(output, ports.size());
        ports.stream()
                .sorted(Comparator.comparing(MachineEnergyPort::capabilityId)
                        .thenComparing(port -> String.valueOf(port.side()))
                        .thenComparing(port -> port.direction().name()))
                .forEach(port -> {
                    appendString(output, port.capabilityId());
                    appendNullableEnum(output, port.side());
                    appendString(output, port.direction().name());
                    appendLong(output, port.capacity());
                });
    }

    private static void appendConcurrency(
            StringBuilder output, ConcurrencyCapabilities concurrency) {
        appendString(output, concurrency.materials().name());
        appendString(output, concurrency.outputOwnership().name());
        appendString(output, concurrency.cleanup().name());
        appendString(output, concurrency.sideEffects().name());
        appendString(output, concurrency.preparation().name());
    }

    private static void appendSupportScope(
            StringBuilder output, SupportScope scope) {
        appendPositions(output, scope.supportOffsets());
        appendPositions(output, scope.adjacentBlocks());
        appendBoolean(output, scope.structural());
    }

    private static void appendPositions(
            StringBuilder output, List<BlockPos> positions) {
        appendInt(output, positions.size());
        positions.stream()
                .sorted(Comparator.comparingInt((BlockPos position) -> position.getX())
                        .thenComparingInt(position -> position.getY())
                        .thenComparingInt(position -> position.getZ()))
                .forEach(position -> {
                    appendInt(output, position.getX());
                    appendInt(output, position.getY());
                    appendInt(output, position.getZ());
                });
    }

    private static void appendResourceKey(
            StringBuilder output, IntegrationResourceKey resource) {
        appendString(output, resource.kind().name());
        appendResourceLocation(output, resource.id());
        appendTag(output, resource.identity());
    }

    private static void appendTag(StringBuilder output, Tag tag) {
        output.append(tag.getId()).append('{');
        if (tag instanceof ByteTag value) {
            output.append(value.getAsByte());
        } else if (tag instanceof ShortTag value) {
            output.append(value.getAsShort());
        } else if (tag instanceof IntTag value) {
            output.append(value.getAsInt());
        } else if (tag instanceof LongTag value) {
            output.append(value.getAsLong());
        } else if (tag instanceof FloatTag value) {
            output.append(Integer.toUnsignedString(
                    Float.floatToIntBits(value.getAsFloat())));
        } else if (tag instanceof DoubleTag value) {
            output.append(Long.toUnsignedString(
                    Double.doubleToLongBits(value.getAsDouble())));
        } else if (tag instanceof StringTag value) {
            appendString(output, value.getAsString());
        } else if (tag instanceof ByteArrayTag value) {
            appendInt(output, value.getAsByteArray().length);
            for (byte element : value.getAsByteArray()) output.append(element).append(';');
        } else if (tag instanceof IntArrayTag value) {
            appendInt(output, value.getAsIntArray().length);
            for (int element : value.getAsIntArray()) output.append(element).append(';');
        } else if (tag instanceof LongArrayTag value) {
            appendInt(output, value.getAsLongArray().length);
            for (long element : value.getAsLongArray()) output.append(element).append(';');
        } else if (tag instanceof ListTag value) {
            appendInt(output, value.size());
            for (Tag element : value) appendTag(output, element);
        } else if (tag instanceof CompoundTag value) {
            List<String> keys = new ArrayList<>(value.getAllKeys());
            keys.sort(String::compareTo);
            appendInt(output, keys.size());
            for (String key : keys) {
                appendString(output, key);
                appendTag(output, value.get(key));
            }
        } else {
            throw new IllegalArgumentException(
                    "unsupported NBT tag type: " + tag.getClass().getName());
        }
        output.append('}');
    }

    private static void appendNullableInt(StringBuilder output, Integer value) {
        appendBoolean(output, value != null);
        if (value != null) appendInt(output, value);
    }

    private static void appendNullableDouble(StringBuilder output, Double value) {
        appendBoolean(output, value != null);
        if (value != null) {
            output.append(Long.toUnsignedString(
                    Double.doubleToLongBits(value))).append(';');
        }
    }

    private static void appendNullableString(StringBuilder output, String value) {
        appendBoolean(output, value != null);
        if (value != null) appendString(output, value);
    }

    private static void appendNullableEnum(
            StringBuilder output, Enum<?> value) {
        appendBoolean(output, value != null);
        if (value != null) appendString(output, value.name());
    }

    private static void appendNullableResourceLocation(
            StringBuilder output, ResourceLocation value) {
        appendBoolean(output, value != null);
        if (value != null) appendResourceLocation(output, value);
    }

    private static void appendResourceLocation(
            StringBuilder output, ResourceLocation value) {
        appendString(output, value.toString());
    }

    private static void appendString(StringBuilder output, String value) {
        output.append(value.length()).append(':').append(value).append(';');
    }

    private static void appendInt(StringBuilder output, int value) {
        output.append(value).append(';');
    }

    private static void appendLong(StringBuilder output, long value) {
        output.append(value).append(';');
    }

    private static void appendBoolean(StringBuilder output, boolean value) {
        output.append(value ? '1' : '0').append(';');
    }

    private static String digest(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
