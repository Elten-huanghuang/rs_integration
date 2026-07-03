package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.EidolonRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class EidolonRSModule implements IModIntegration {

    public static final EidolonRSModule INSTANCE = new EidolonRSModule();

    private EidolonRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_EIDOLON;
    }

    @Override
    public String modId() {
        return "eidolon";
    }

    @Override
    public void registerModType() {
        ModType.register("eidolon",
                new String[]{"elucent.eidolon."},
                new String[]{"eidolon"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.eidolon.EidolonBatchDelegate"));
        ModType.configureJei("eidolon",
                new String[][]{{"eidolon:crucible", "crucible"}, {"eidolon:worktable", "worktable"}, {"eidolon:rituals", "ritual"}},
                new String[][]{{"elucent.eidolon.recipe.WorktableRecipe", "worktable"}, {"elucent.eidolon.recipe.ItemRitualRecipe", "ritual"}, {"elucent.eidolon.recipe.GenericRitualRecipe", "ritual"}, {"elucent.eidolon.", "crucible"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "eidolon", ModType.byId("eidolon"), RSIntegrationConfig.ENABLE_EIDOLON, List.of(
                "elucent.eidolon.common.block.WorktableBlock"
        ), "eidolon"));
        // Crucible and Brazier are in-world ritual blocks, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "eidolon", ModType.byId("eidolon"), RSIntegrationConfig.ENABLE_EIDOLON, List.of(
                "elucent.eidolon.common.block.CrucibleBlock",
                "elucent.eidolon.common.block.BrazierBlock"
        ), "eidolon_ritual", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new EidolonRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        ModCraftNetworkHandlers.registerEidolon();
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Eidolon RS module common init done.");
    }
}
