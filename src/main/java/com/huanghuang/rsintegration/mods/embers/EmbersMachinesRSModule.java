package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** 余烬三种物理加工机器，各自只接受对应的配方和绑定方块。 */
public final class EmbersMachinesRSModule implements IModIntegration {
    public static final EmbersMachinesRSModule INSTANCE = new EmbersMachinesRSModule();

    private EmbersMachinesRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_EMBERS_MACHINES;
    }

    @Override public String modId() { return ModIds.EMBERS; }

    @Override public void registerModType() {
        register(ModIds.ID_EMBERS_MELTER, "IMeltingRecipe", "melter", "melting",
                EmbersMelterBatchDelegate.class.getName());
        register(ModIds.ID_EMBERS_MIXER, "IMixingRecipe", "mixer_centrifuge", "mixing",
                EmbersMixerBatchDelegate.class.getName());
        register(ModIds.ID_EMBERS_STAMPER, "IStampingRecipe", "stamper", "stamping",
                EmbersStamperBatchDelegate.class.getName());
    }

    private static void register(String id, String recipeName, String blockName,
                                 String jeiName, String delegateClass) {
        String recipeClass = "com.rekindled.embers.recipe." + recipeName;
        String implementation = "com.rekindled.embers.recipe." + switch (jeiName) {
            case "melting" -> "MeltingRecipe";
            case "mixing" -> "MixingRecipe";
            default -> "StampingRecipe";
        };
        ModType.register(id, new String[]{implementation}, new String[]{blockName},
                new String[]{id}, ModType.delegateSupplier(delegateClass))
                .requireFlatExecution("余烬机器与外部流体罐、印模和物理产物共用状态");
        ModType.configureJei(id,
                new String[][]{{"embers:" + jeiName, id}},
                new String[][]{{implementation, id}},
                "gui.rs_integration.jei." + id);
    }

    @Override public void registerBindingTargets() {
        target(ModIds.ID_EMBERS_MELTER, "com.rekindled.embers.block.MelterBlock");
        target(ModIds.ID_EMBERS_MIXER, "com.rekindled.embers.block.MixerCentrifugeBlock");
        target(ModIds.ID_EMBERS_STAMPER, "com.rekindled.embers.block.StamperBlock");
    }

    private static void target(String type, String blockClass) {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.EMBERS, ModType.byId(type), RSIntegrationConfig.ENABLE_EMBERS_MACHINES,
                List.of(blockClass), type, false));
    }

    @Override public void registerRecipeHandler() {
        ModRecipeHandlers.register(new EmbersMeltingRecipeHandler());
        ModRecipeHandlers.register(new EmbersMixingRecipeHandler());
        ModRecipeHandlers.register(new EmbersStampingRecipeHandler());
    }

    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
