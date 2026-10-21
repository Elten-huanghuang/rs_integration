package com.huanghuang.rsintegration.mods.bossesdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.mods.bossesdelight.BossesDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * BOMD 乐事（bosses_delight）灵魂锅接入。
 * <p>
 * 灵魂锅的热源是下方方块的冷源标签（{@code bosses_delight:cold_sources}：冰 / 浮冰 / 蓝冰），
 * 配方类型独立（{@code bosses_delight:soul_cooking}），因此不能复用农夫乐事烹饪锅那套判定。
 */
public final class BossesDelightRSModule implements IModIntegration {

    public static final BossesDelightRSModule INSTANCE = new BossesDelightRSModule();

    private BossesDelightRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_BOSSES_DELIGHT;
    }

    @Override
    public String modId() {
        return ModIds.BOSSES_DELIGHT;
    }

    @Override
    public void registerModType() {
        ModType.register(ModIds.ID_BD_SOUL_COOKING_POT,
                new String[]{BossesDelightRecipeHandler.RECIPE_CLASS},
                new String[]{"soul_cooking_pot"},
                new String[]{ModIds.ID_BD_SOUL_COOKING_POT},
                SoulCookingPotBatchDelegate::new)
                .confirmGraphExecution("灵魂锅食材与装盛容器由共享账本预留，成品在机器输出槽结算");
        ModType.configureJei(ModIds.ID_BD_SOUL_COOKING_POT,
                new String[][]{{"bosses_delight:soul_cooking", ModIds.ID_BD_SOUL_COOKING_POT}},
                new String[][]{{BossesDelightRecipeHandler.RECIPE_CLASS,
                        ModIds.ID_BD_SOUL_COOKING_POT}},
                "gui.rs_integration.jei.bd_soul_cooking_pot_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.BOSSES_DELIGHT, ModType.byId(ModIds.ID_BD_SOUL_COOKING_POT), configFlag(),
                List.of("io.github.rcneg.bossesdelight.blocks.SoulCookingPotBlock"),
                List.of("bosses_delight:soul_cooking_pot"), ModIds.ID_BD_SOUL_COOKING_POT, true));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new BossesDelightRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}
}
