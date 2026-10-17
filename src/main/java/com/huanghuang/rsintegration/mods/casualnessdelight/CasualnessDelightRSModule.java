package com.huanghuang.rsintegration.mods.casualnessdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.DeepFryingRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** 随意乐事炸锅接入。 */
public final class CasualnessDelightRSModule implements IModIntegration {

    public static final CasualnessDelightRSModule INSTANCE = new CasualnessDelightRSModule();

    private CasualnessDelightRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_CASUALNESS_DELIGHT;
    }

    @Override
    public String modId() {
        return ModIds.CASUALNESS_DELIGHT;
    }

    @Override
    public void registerModType() {
        ModType.register(ModIds.ID_CD_DEEP_FRYING_PAN,
                new String[]{DeepFryingRecipeHandler.RECIPE_CLASS},
                new String[]{"deep_frying_pan"},
                new String[]{ModIds.ID_CD_DEEP_FRYING_PAN},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.casualnessdelight.DeepFryingPanBatchDelegate"))
                .confirmGraphExecution("炸锅由共享账本预留整叠输入，结果通过锅位附近的世界掉落捕获结算");
        ModType.configureJei(ModIds.ID_CD_DEEP_FRYING_PAN,
                new String[][]{{"casualness_delight:deep_frying", ModIds.ID_CD_DEEP_FRYING_PAN}},
                new String[][]{{DeepFryingRecipeHandler.RECIPE_CLASS, ModIds.ID_CD_DEEP_FRYING_PAN}},
                "gui.rs_integration.jei.cd_deep_frying_pan_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.CASUALNESS_DELIGHT, ModType.byId(ModIds.ID_CD_DEEP_FRYING_PAN), configFlag(),
                List.of("com.va11halla.casualness_delight.block.DeepFryingPan"),
                List.of("deep_frying_pan"), ModIds.ID_CD_DEEP_FRYING_PAN, true));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new DeepFryingRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}
}
