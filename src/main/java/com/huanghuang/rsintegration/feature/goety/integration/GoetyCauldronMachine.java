package com.huanghuang.rsintegration.feature.goety.integration;

import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.api.machine.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 将 Goety 原生方块实体接入 RSI 机器与界面 API。 */
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
        // 配方顺序由编辑器管理，不向通用自动化接口暴露可变端口。
        MachineDescriptor descriptor = new MachineDescriptor(ID, 1L, Set.of(PROCESS),
                List.of(), List.of(), List.of(), MachineExecutionMode.CUSTOM, false,
                ConcurrencyCapabilities.exclusive(), new SupportScope(List.of(BlockPos.ZERO), List.of(), false), true);
        return MachineDescriptorResult.ready(descriptor);
    }

    @Override public Optional<MachineStateProvider> stateProvider() {
        return Optional.of((context, entity) -> {
            if (!(entity instanceof BrewCauldronBlockEntity cauldron)) return MachineState.invalid();
            ResourceLocation recipe = GoetyCauldronCompat.recipeId(cauldron);
            int total = Math.max(1, cauldron.totalCost);
            return new MachineState(true, true, GoetyCauldronCompat.isBusy(cauldron),
                    cauldron.soulTime, total, recipe, PROCESS, 0L, null);
        });
    }
}
