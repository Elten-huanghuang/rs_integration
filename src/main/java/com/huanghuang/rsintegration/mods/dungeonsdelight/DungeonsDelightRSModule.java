package com.huanghuang.rsintegration.mods.dungeonsdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.mods.dungeonsdelight.DungeonsDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class DungeonsDelightRSModule implements IModIntegration {
    public static final DungeonsDelightRSModule INSTANCE = new DungeonsDelightRSModule();

    private DungeonsDelightRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_DUNGEONS_DELIGHT;
    }

    @Override
    public String modId() {
        return ModIds.DUNGEONS_DELIGHT;
    }

    @Override
    public void registerModType() {
        ModType.register(ModIds.ID_DD_MONSTER_POT,
                new String[]{DungeonsDelightRecipeHandler.RECIPE_CLASS},
                new String[]{"monster_pot"}, new String[]{ModIds.ID_DD_MONSTER_POT},
                MonsterPotBatchDelegate::new)
                .confirmGraphExecution("怪物锅食材及容器由共享账本预留，成品在独占机器槽内结算");
        ModType.configureJei(ModIds.ID_DD_MONSTER_POT,
                new String[][]{{"dungeonsdelight:monster_cooking", ModIds.ID_DD_MONSTER_POT}},
                new String[][]{{DungeonsDelightRecipeHandler.RECIPE_CLASS, ModIds.ID_DD_MONSTER_POT}},
                "gui.rs_integration.jei.dd_monster_pot_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.DUNGEONS_DELIGHT, ModType.byId(ModIds.ID_DD_MONSTER_POT), configFlag(),
                List.of("net.yirmiri.dungeonsdelight.common.block.MonsterPotBlock"),
                List.of("dungeonsdelight:monster_pot"), ModIds.ID_DD_MONSTER_POT, true));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new DungeonsDelightRecipeHandler());
    }

    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
