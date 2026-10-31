package com.huanghuang.rsintegration.feature.goety.integration;

import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.api.machine.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.List;
import java.util.Set;

/** Advertises the real Goety block entity to RSI's machine and GUI APIs. */
public final class GoetyCauldronMachine implements MachineIntegration {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("rs_integration", "witch_cauldron");
    public static final ResourceLocation PROCESS = ResourceLocation.fromNamespaceAndPath("rs_integration", "brewing_editor");

    @Override public ResourceLocation id() { return ID; }
    @Override public List<MachineBinding> bindings() {
        return List.of(MachineBinding.blocks(Set.of(PROCESS), true,
                ResourceLocation.fromNamespaceAndPath("goety", "witch_cauldron")));
    }
    @Override public boolean supports(BlockEntity entity) { return entity instanceof BrewCauldronBlockEntity; }

    @Override public MachineDescriptorResult describe(MachineContext context, BlockEntity entity) {
        // The editor owns ordered brewing. Do not advertise mutable generic automation ports.
        MachineDescriptor descriptor = new MachineDescriptor(ID, 1L, Set.of(PROCESS),
                List.of(), List.of(), List.of(), MachineExecutionMode.CUSTOM, false,
                ConcurrencyCapabilities.exclusive(), new SupportScope(List.of(BlockPos.ZERO), List.of(), false), true);
        return MachineDescriptorResult.ready(descriptor);
    }

    @Override public java.util.Optional<MachineStateProvider> stateProvider() {
        return java.util.Optional.of((context, entity) -> {
            if (!(entity instanceof BrewCauldronBlockEntity cauldron)) return MachineState.invalid();
            ResourceLocation recipe = cauldron.recipeId;
            int total = Math.max(1, cauldron.totalCost);
            return new MachineState(true, true, cauldron.isBrewing || cauldron.isCrafting,
                    cauldron.soulTime, total, recipe, PROCESS, 0L, null);
        });
    }
}
