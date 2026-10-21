package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.mods.farmandcharm.LetsDoCookingPotRecipeHandler;
import com.huanghuang.rsintegration.mods.farmandcharm.LetsDoRoasterRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

/**
 * 田园乐事（farm_and_charm，整合包里叫"宴会锅"）与烛火晚宴（candlelight）的锅具接入。
 * <p>
 * 两者共用 farm_and_charm 定义的 {@code farm_and_charm:pot_cooking} 配方类型与同一套
 * 槽位布局，因此合并成一个模块、共用委托基类，只在 ModType 与绑定方块上区分：
 * <ul>
 *   <li>{@code farm_and_charm_cooking_pot} —— 烹饪锅；</li>
 *   <li>{@code farm_and_charm_roaster} —— 烘焙机（配方类型不同，单独一个 ModType）；</li>
 *   <li>{@code candlelight_cooking_pot} —— 大汤锅（汤锅 / 铜汤锅是同一个方块）。</li>
 * </ul>
 * 两个模组各自独立开关：candlelight 依赖 farm_and_charm 启动，但反过来不需要。
 */
public final class FarmAndCharmRSModule implements IModIntegration {

    public static final FarmAndCharmRSModule INSTANCE = new FarmAndCharmRSModule();

    private FarmAndCharmRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_FARM_AND_CHARM;
    }

    @Override
    public String modId() {
        return ModIds.FARM_AND_CHARM;
    }

    @Override
    public List<String> modIds() {
        return List.of(ModIds.FARM_AND_CHARM, ModIds.CANDLELIGHT, ModIds.BAKERY);
    }

    @Override
    public void registerModType() {
        // ── 烹饪锅（宴会锅） ──
        ModType.register(ModIds.ID_FC_COOKING_POT,
                new String[]{LetsDoCookingPotRecipeHandler.RECIPE_CLASS},
                new String[]{"cooking_pot"},
                new String[]{ModIds.ID_FC_COOKING_POT},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.farmandcharm.FarmAndCharmCookingPotBatchDelegate"));
        ModType.configureJei(ModIds.ID_FC_COOKING_POT,
                new String[][]{{"farm_and_charm:cooking_pot", ModIds.ID_FC_COOKING_POT}},
                new String[][]{{LetsDoCookingPotRecipeHandler.RECIPE_CLASS, ModIds.ID_FC_COOKING_POT}},
                "gui.rs_integration.jei.farm_and_charm_cooking_pot_craft");

        // ── 烘焙机 ──
        ModType.register(ModIds.ID_FC_ROASTER,
                new String[]{LetsDoRoasterRecipeHandler.RECIPE_CLASS},
                new String[]{"roaster"},
                new String[]{ModIds.ID_FC_ROASTER},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.farmandcharm.RoasterBlockBatchDelegate"));
        ModType.configureJei(ModIds.ID_FC_ROASTER,
                new String[][]{{"farm_and_charm:roaster", ModIds.ID_FC_ROASTER}},
                new String[][]{{LetsDoRoasterRecipeHandler.RECIPE_CLASS, ModIds.ID_FC_ROASTER}},
                "gui.rs_integration.jei.farm_and_charm_roaster_craft");

        // ── 大汤锅（复用 pot_cooking 配方类型） ──
        ModType.register(ModIds.ID_CANDLELIGHT_COOKING_POT,
                new String[]{LetsDoCookingPotRecipeHandler.RECIPE_CLASS},
                new String[]{"cooking_pot"},
                new String[]{ModIds.ID_CANDLELIGHT_COOKING_POT},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.farmandcharm.CandlelightCookingPotBatchDelegate"));
        ModType.configureJei(ModIds.ID_CANDLELIGHT_COOKING_POT,
                new String[][]{{"farm_and_charm:cooking_pot", ModIds.ID_CANDLELIGHT_COOKING_POT}},
                new String[][]{{LetsDoCookingPotRecipeHandler.RECIPE_CLASS,
                        ModIds.ID_CANDLELIGHT_COOKING_POT}},
                "gui.rs_integration.jei.candlelight_cooking_pot_craft");

        // ── 小烹饪锅（bakery，同样复用 pot_cooking 配方类型） ──
        ModType.register(ModIds.ID_BAKERY_COOKING_POT,
                new String[]{LetsDoCookingPotRecipeHandler.RECIPE_CLASS},
                new String[]{"small_cooking_pot"},
                new String[]{ModIds.ID_BAKERY_COOKING_POT},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.farmandcharm.BakeryCookingPotBatchDelegate"));
        ModType.configureJei(ModIds.ID_BAKERY_COOKING_POT,
                new String[][]{{"farm_and_charm:cooking_pot", ModIds.ID_BAKERY_COOKING_POT}},
                new String[][]{{LetsDoCookingPotRecipeHandler.RECIPE_CLASS,
                        ModIds.ID_BAKERY_COOKING_POT}},
                "gui.rs_integration.jei.bakery_cooking_pot_craft");
    }

    @Override
    public void registerBindingTargets() {
        // 方块类名与配方权限分开声明：配方属于 farm_and_charm，
        // 方块属于各自的模组，因此这里不按 modIds() 循环。
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.FARM_AND_CHARM, ModType.byId(ModIds.ID_FC_COOKING_POT),
                RSIntegrationConfig.ENABLE_FARM_AND_CHARM,
                List.of("net.satisfy.farm_and_charm.block.CookingPotBlock"),
                List.of(),
                ModIds.ID_FC_COOKING_POT, true
        ));

        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.FARM_AND_CHARM, ModType.byId(ModIds.ID_FC_ROASTER),
                RSIntegrationConfig.ENABLE_FARM_AND_CHARM,
                List.of("net.satisfy.farm_and_charm.block.RoasterBlock"),
                List.of(),
                ModIds.ID_FC_ROASTER, true
        ));

        // 烛火晚宴的汤锅与铜汤锅是同一个方块（candlelight:cooking_pot），
        // 因此只需登记一个方块类。
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.CANDLELIGHT, ModType.byId(ModIds.ID_CANDLELIGHT_COOKING_POT),
                RSIntegrationConfig.ENABLE_CANDLELIGHT,
                List.of("net.satisfy.candlelight.block.LargeCookingPotBlock"),
                List.of(),
                ModIds.ID_CANDLELIGHT_COOKING_POT, true
        ));

        // 烘焙屋的小烹饪锅：配方属于 farm_and_charm，方块属于 bakery，因此单独登记。
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.BAKERY, ModType.byId(ModIds.ID_BAKERY_COOKING_POT),
                RSIntegrationConfig.ENABLE_BAKERY,
                List.of("net.satisfy.bakery.block.SmallCookingPotBlock"),
                List.of(),
                ModIds.ID_BAKERY_COOKING_POT, true
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new LetsDoCookingPotRecipeHandler());
        ModRecipeHandlers.register(new LetsDoRoasterRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
