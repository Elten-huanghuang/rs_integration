package com.huanghuang.rsintegration.mods.summoningrituals;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

import java.util.List;

/** Better Summoning Rituals 的手动祭坛准备联动。 */
public final class SummoningRitualsRSModule implements IModIntegration {
    public static final SummoningRitualsRSModule INSTANCE = new SummoningRitualsRSModule();

    private SummoningRitualsRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_SRFIX;
    }

    @Override
    public String modId() {
        return ModIds.SRFIX;
    }

    @Override
    public void registerModType() {
        // 此联动依赖 Better Summoning Rituals 的补丁行为；未安装 srfix 时完全不注册。
        if (!ModList.get().isLoaded(ModIds.SRFIX)) return;
        ModType type = ModType.register(ModIds.ID_SUMMONING_RITUALS,
                new String[]{SummoningRitualRecipeHandler.RECIPE_CLASS},
                new String[]{"summoningrituals"},
                new String[]{ModIds.ID_SUMMONING_RITUALS},
                SummoningRitualAltarBatchDelegate::new);
        type.requireFlatExecution("manual altar preparation does not publish a craft output");
        ModType.configureJei(ModIds.ID_SUMMONING_RITUALS,
                new String[][]{{"summoningrituals:altar", ModIds.ID_SUMMONING_RITUALS}},
                new String[][]{{SummoningRitualRecipeHandler.RECIPE_CLASS,
                        ModIds.ID_SUMMONING_RITUALS}},
                "gui.rs_integration.jei.summoning_rituals_prepare");
    }

    @Override
    public void registerBindingTargets() {
        if (!ModList.get().isLoaded(ModIds.SRFIX)) return;
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.SRFIX, ModType.byId(ModIds.ID_SUMMONING_RITUALS),
                RSIntegrationConfig.ENABLE_SRFIX,
                List.of("com.almostreliable.summoningrituals.altar.AltarBlock"),
                List.of("summoningrituals:altar", "summoningrituals:indestructible_altar"),
                ModIds.ID_SUMMONING_RITUALS, false));
    }

    @Override
    public void registerRecipeHandler() {
        if (!ModList.get().isLoaded(ModIds.SRFIX)) return;
        ModRecipeHandlers.register(new SummoningRitualRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Better Summoning Rituals RS module common init done.");
    }
}
