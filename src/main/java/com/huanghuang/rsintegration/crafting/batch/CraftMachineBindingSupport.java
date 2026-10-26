package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 执行机器类型解析、绑定检查和机器专用输出路由。 */
final class CraftMachineBindingSupport {
    private CraftMachineBindingSupport() {}

    record PlanAvailability(
            boolean missingExecutionBinding,
            boolean executionMachineSupportsGui,
            Set<String> boundMachineTypes,
            boolean allExecutionMachinesLeased) {}

    static ModType resolveExecutionModType(ServerPlayer player, Recipe<?> recipe,
                                           @Nullable ResourceLocation dimension,
                                           @Nullable BlockPos position) {
        ModRecipeHandler claimed = ModRecipeHandlers.claimFor(recipe).winner();
        if (claimed != null
                && claimed.modType() != ModType.GENERIC) {
            return claimed.modType();
        }
        if (MinersDelightCopperPotSupport.isRequestedCopperPot(
                player, recipe, dimension, position)) {
            ModType copperPot = ModType.findById(ModIds.ID_MD_COPPER_POT);
            if (copperPot != null) return copperPot;
        }
        return ModType.classifyRecipe(recipe);
    }

    static boolean hasBindingForExecutionType(ServerPlayer player, Recipe<?> recipe,
                                              @Nullable ModType executionType) {
        if (executionType != null && ModIds.ID_MD_COPPER_POT.equals(executionType.id())) {
            return AltarBindingRegistry.hasAnyBindingForType(player, executionType);
        }
        return AltarBindingRegistry.hasBindingForRecipe(player, recipe, executionType);
    }

    /** Consumer 预检必须排除所有候选机器均被活动订单租用的情况。 */
    static boolean hasIdleBindingForExecutionType(
            ServerPlayer player,
            Recipe<?> recipe,
            @Nullable ModType executionType
    ) {
        if (executionType == null) return true;
        List<AltarBindingRegistry.BoundMachine> machines =
                ModIds.ID_MD_COPPER_POT.equals(executionType.id())
                        ? AltarBindingRegistry.getBoundMachinesForType(
                        player, executionType)
                        : AltarBindingRegistry.getBoundMachinesForRecipe(
                        player, executionType, recipe.getId());
        return !machines.isEmpty()
                && !AsyncCraftManager.getInstance()
                .areAllMachinesLeased(
                        machines, executionType.id());
    }

    static boolean isRecipeAvailableForPlanning(ServerPlayer player, Recipe<?> recipe) {
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(recipe).winner();
        return handler == null || handler.isAvailableForPlanning(recipe, player);
    }

    static void logBindingRejection(String phase, ServerPlayer player, Recipe<?> recipe,
                                    @Nullable ModType executionType,
                                    @Nullable ResourceLocation dimension,
                                    @Nullable BlockPos position) {
        ResourceLocation recipeTypeId = recipe.getType() == null
                ? null : ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        ResourceLocation serializerId = recipe.getSerializer() == null
                ? null : BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        RSIntegrationMod.LOGGER.warn(
                "[RSI-Binding] rejected phase={} recipe={} class={} recipeType={} serializer={} "
                        + "modType={} craftingRecipe={} physicalMachine={} player={} dim={} pos={}",
                phase, recipe.getId(), recipe.getClass().getName(), recipeTypeId, serializerId,
                executionType == null ? "null" : executionType.id(),
                recipe instanceof CraftingRecipe,
                CraftExecutionPolicy.isPhysicalMachineRecipe(recipe, executionType),
                player.getGameProfile().getName(), dimension, position);
    }

    static ItemStack routeRecipeOutput(@Nullable ModType executionType, ItemStack output) {
        return executionType != null && ModIds.ID_MD_COPPER_POT.equals(executionType.id())
                ? MinersDelightCopperPotSupport.convertResult(output)
                : output;
    }

    static PlanAvailability inspectPlanAvailability(
            ServerPlayer player, ResourceLocation recipeId,
            @Nullable ModType recipeModType, List<PlanStep> steps,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position) {
        boolean missingExecutionBinding = false;
        for (PlanStep step : steps) {
            Recipe<?> stepRecipe = CraftRecipeResolver.resolve(
                    player.serverLevel(), step.recipeId());
            if (stepRecipe == null) continue;
            ModType stepType = step.modType() != null
                    ? step.modType() : ModType.classifyRecipe(stepRecipe);
            if (CraftExecutionPolicy.requiresBoundMachine(
                    stepRecipe, stepType)
                    && !hasBindingForExecutionType(
                    player, stepRecipe, stepType)) {
                missingExecutionBinding = true;
                break;
            }
        }

        boolean executionMachineSupportsGui = false;
        if (dimension != null && position != null) {
            ServerLevel executionLevel = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dimension));
            if (executionLevel != null) {
                executionMachineSupportsGui =
                        BindingEventHandler.supportsGuiAt(
                                executionLevel, position);
            }
        }

        Set<String> boundMachineTypes = new LinkedHashSet<>();
        for (ModType type : ModType.values()) {
            if (AltarBindingRegistry.hasAnyBindingForType(player, type)) {
                boundMachineTypes.add(type.id());
            }
        }

        boolean allExecutionMachinesLeased = false;
        if (recipeModType != null
                && boundMachineTypes.contains(recipeModType.id())) {
            List<AltarBindingRegistry.BoundMachine> executionMachines =
                    AltarBindingRegistry.getBoundMachinesForRecipe(
                            player, recipeModType, recipeId);
            allExecutionMachinesLeased = !executionMachines.isEmpty()
                    && AsyncCraftManager.getInstance()
                    .areAllMachinesLeased(
                            executionMachines, recipeModType.id());
        }
        return new PlanAvailability(
                missingExecutionBinding, executionMachineSupportsGui,
                boundMachineTypes, allExecutionMachinesLeased);
    }
}
