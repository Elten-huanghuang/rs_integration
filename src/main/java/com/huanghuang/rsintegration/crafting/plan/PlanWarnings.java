package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleBatchDelegate;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.aether.AetherFurnaceBatchDelegate;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksBatchDelegate;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksToolStationBatchDelegate;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsPlanWarnings;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.eidolon.EidolonBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.SkilletBatchDelegate;
import com.huanghuang.rsintegration.mods.immortalersdelight.EnchantalCoolerBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyBatchDelegate;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.ClibanoBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.FaBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetySoulTotemCrafting;
import com.huanghuang.rsintegration.mods.malum.MalumBatchDelegate;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageRecipeWrapper;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageRuntime;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmAltarBatchDelegate;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.ferment.FermentationTankBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.moka.MokaPotBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.steamer.SteamerBatchDelegate;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class PlanWarnings {
    private PlanWarnings() {}

    public record Result(List<Component> warnings, boolean blocksExecution) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    public static int botaniaManaCost(Recipe<?> recipe) {
        int cost = botaniaInt(recipe, "vazkii.botania.api.recipe.ManaInfusionRecipe", "getManaToConsume");
        if (cost >= 0) return cost;
        cost = botaniaInt(recipe, "vazkii.botania.api.recipe.RunicAltarRecipe", "getManaUsage");
        if (cost >= 0) return cost;
        cost = botaniaInt(recipe, "vazkii.botania.api.recipe.TerrestrialAgglomerationRecipe", "getMana");
        if (cost >= 0) return cost;
        cost = botaniaInt(recipe, "vazkii.botania.api.recipe.BotanicalBreweryRecipe", "getManaUsage");
        return Math.max(0, cost);
    }

    public static int arsSourceCost(Recipe<?> recipe) {
        return ArsPlanWarnings.sourceCost(recipe);
    }

    public static int goetyRitualSoulCost(Recipe<?> recipe) {
        return GoetySoulTotemCrafting.ritualSoulCost(recipe);
    }

    public static int goetyTotemSoulCost(Recipe<?> recipe) {
        return recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe crafting
                ? GoetySoulTotemCrafting.soulCostPerCraft(crafting) : 0;
    }

    private static int botaniaInt(Object recipe, String typeName, String methodName) {
        if (!implementsNamedType(recipe, typeName)) return -1;
        try {
            Object value = recipe.getClass().getMethod(methodName).invoke(recipe);
            return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
        } catch (ReflectiveOperationException exception) {
            return 0;
        }
    }

    private static boolean implementsNamedType(Object value, String typeName) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(typeName)) return true;
            for (Class<?> contract : type.getInterfaces()) {
                if (namedInterface(contract, typeName)) return true;
            }
        }
        return false;
    }

    private static boolean namedInterface(Class<?> type, String typeName) {
        if (type.getName().equals(typeName)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (namedInterface(parent, typeName)) return true;
        }
        return false;
    }

    /**
     * Collects mod-specific plan warnings. Returns unresolved {@link Component}s
     * because this runs server-side, where {@code rsi.*} translation keys cannot
     * be resolved — see {@link PlanResponse#modWarnings()}.
     */
    public static List<Component> collect(String typeId, ServerPlayer player, Recipe<?> recipe,
                                        @Nullable ResourceLocation dim, @Nullable BlockPos pos) {
        return collectResult(typeId, player, recipe, dim, pos).warnings();
    }

    /**
     * Collects warnings together with whether the current machine state is known
     * to be unusable. Most integration warnings are informational; Goety ritual
     * prerequisites are authoritative because starting anyway can consume inputs.
     */
    public static Result collectResult(String typeId, ServerPlayer player, Recipe<?> recipe,
                                       @Nullable ResourceLocation dim, @Nullable BlockPos pos) {
        return collectResult(typeId, player, recipe, dim, pos, null);
    }

    /**
     * Collect warnings using the endpoint already selected by the plan.
     * Mod-specific checks may otherwise resolve the first backend again and
     * report availability from a different network than execution will use.
     */
    public static Result collectResult(String typeId, ServerPlayer player, Recipe<?> recipe,
                                       @Nullable ResourceLocation dim, @Nullable BlockPos pos,
                                       @Nullable CraftStorageEndpoint endpoint) {
        List<Component> warnings = new ArrayList<>();
        boolean blocksExecution = false;
        switch (typeId) {
            case ModIds.AETHER:
            case "aether_freezer":
            case "aether_incubator":
            case "aether_altar":
                warnings.addAll(AetherFurnaceBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.GOETY:
            case "goety_brazier":
                GoetyBatchDelegate.PlanPrerequisiteCheck goetyCheck =
                        GoetyBatchDelegate.checkPlanPrerequisites(player, recipe, dim, pos);
                warnings.addAll(goetyCheck.warnings());
                blocksExecution = goetyCheck.blocked();
                break;
            case ModIds.FORBIDDEN_ARCANUS:
                warnings.addAll(FaBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FA_CLIBANO:
                warnings.addAll(ClibanoBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.WIZARDS_REBORN:
                warnings.addAll(WRBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_ARS_IMBUEMENT:
            case ModIds.ID_ARS_APPARATUS:
            case ModIds.ID_ARS_SCRIBES_TABLE:
                warnings.addAll(ArsPlanWarnings.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.MALUM:
                warnings.addAll(MalumBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.EIDOLON:
                warnings.addAll(EidolonBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_EMBERS_ALCHEMY:
                warnings.addAll(EreAlchemyBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_AETHERWORKS_ANVIL:
                warnings.addAll(AetherworksBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_AETHERWORKS_TOOL_STATION:
                warnings.addAll(AetherworksToolStationBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.CROCKPOT:
                warnings.addAll(CrockPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FR_KETTLE:
                warnings.addAll(FRKettleBatchDelegate
                        .getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.FARMINGFORBLOCKHEADS:
                warnings.addAll(MarketBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.TOUHOU_LITTLE_MAID:
                warnings.addAll(TlmAltarBatchDelegate.getPlanWarnings(
                        player, recipe, dim, pos, endpoint));
                break;
            case ModIds.IMMORTERS_DELIGHT:
                warnings.addAll(EnchantalCoolerBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FD_COOKING_POT:
                warnings.addAll(CookingPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FD_SKILLET:
                warnings.addAll(SkilletBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FD_CUTTING_BOARD:
                var cuttingBoardCheck = com.huanghuang.rsintegration.mods.farmersdelight
                        .CuttingBoardBatchDelegate.getPlanCheck(player, recipe, endpoint);
                warnings.addAll(cuttingBoardCheck.warnings());
                blocksExecution = cuttingBoardCheck.blocksExecution();
                break;
            case ModIds.ID_YHK_MOKA:
                warnings.addAll(MokaPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_YHK_STEAMER:
                warnings.addAll(SteamerBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_YHK_FERMENT:
                warnings.addAll(FermentationTankBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "pmmo_salvage":
                if (recipe instanceof PmmoSalvageRecipeWrapper salvage) {
                    PmmoSalvageRuntime.Eligibility eligibility =
                            PmmoSalvageRuntime.eligibility(player, salvage);
                    if (eligibility.state() == PmmoSalvageRuntime.EligibilityState.LEVEL_TOO_LOW) {
                        warnings.add(Component.translatable("rsi.pmmo.warn.level_required",
                                PmmoSalvageRuntime.requirementSummary(eligibility)));
                    } else if (eligibility.state() == PmmoSalvageRuntime.EligibilityState.LOOKUP_FAILED) {
                        warnings.add(Component.translatable("rsi.pmmo.warn.level_lookup_failed"));
                    }
                }
                break;
            case ModIds.ID_AVARITIA_CRAFTING:
            case ModIds.ID_AVARITIA_COMPRESSOR:
            case ModIds.ID_AVARITIA_SMITHING:
                break;
            default:
                break;
        }
        return new Result(warnings, blocksExecution);
    }
}
