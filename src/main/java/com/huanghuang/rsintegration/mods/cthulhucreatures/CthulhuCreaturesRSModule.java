package com.huanghuang.rsintegration.mods.cthulhucreatures;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class CthulhuCreaturesRSModule implements IModIntegration {
    public static final CthulhuCreaturesRSModule INSTANCE = new CthulhuCreaturesRSModule();
    public static final String PACKAGE = "cn.blockforge.generated.nirvanabossboss7206f.";
    public static final String BLOCK_ID = "cthulhu_creatures:flesh_altar";

    private CthulhuCreaturesRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_CTHULHU_CREATURES;
    }

    @Override
    public String modId() {
        return ModIds.CTHULHU_CREATURES;
    }

    @Override
    public void registerModType() {
        ModType.register(ModIds.ID_CTHULHU_FLESH_ALTAR,
                new String[]{FleshAltarRecipeHandler.RECIPE_CLASS},
                new String[]{"block.cthulhu_creatures.flesh_altar"},
                new String[]{ModIds.ID_CTHULHU_FLESH_ALTAR},
                FleshAltarBatchDelegate::new)
                .confirmGraphExecution("血肉祭坛按槽位消耗确定数量材料，产物由机器租约隔离并回收");
        ModType.configureJei(ModIds.ID_CTHULHU_FLESH_ALTAR,
                new String[][]{{BLOCK_ID}},
                new String[][]{{FleshAltarRecipeHandler.RECIPE_CLASS}},
                "gui.rs_integration.jei.cthulhu_flesh_altar_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                modId(), ModType.byId(ModIds.ID_CTHULHU_FLESH_ALTAR), configFlag(),
                List.of(PACKAGE + "FleshAltarBlock"), ModIds.ID_CTHULHU_FLESH_ALTAR, true));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new FleshAltarRecipeHandler());
    }

    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
