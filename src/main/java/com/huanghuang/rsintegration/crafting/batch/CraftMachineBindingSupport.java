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
    /** 原版机器家族的兜底类型：处理器把烹饪和切石配方都声明成它。 */
    private static final String VANILLA_FURNACE_TYPE_ID = "vanilla_furnace";
    /** 与兜底类型共用配方处理器、但各自有独立绑定类型和方块的原版机器。 */
    private static final Set<String> VANILLA_MACHINE_FAMILY = Set.of(
            "vanilla_blast_furnace", "vanilla_smoker", "vanilla_campfire", "vanilla_stonecutter");

    private CraftMachineBindingSupport() {}

    record PlanAvailability(
            boolean missingExecutionBinding,
            boolean executionMachineSupportsGui,
            Set<String> boundMachineTypes,
            boolean allExecutionMachinesLeased) {}

    static ModType resolveExecutionModType(ServerPlayer player, Recipe<?> recipe,
                                           @Nullable ResourceLocation dimension,
                                           @Nullable BlockPos position) {
        ModType resolved = resolveClaimedExecutionType(player, recipe, dimension, position);
        // 只能落到模组级兜底类型、且该类型没有任何可绑定方块的网格合成配方（如农夫乐事的面团），
        // 没有机器可绑；退回 generic 按模拟装配执行，避免预览和规划误报“没有可用的已绑定机器”。
        return BindingEventHandler.isBindlessGridRecipe(recipe, resolved) ? ModType.GENERIC : resolved;
    }

    private static ModType resolveClaimedExecutionType(ServerPlayer player, Recipe<?> recipe,
                                                       @Nullable ResourceLocation dimension,
                                                       @Nullable BlockPos position) {
        // 铜锅与烹饪锅共用同一个配方类，只能靠请求位置上的方块区分，因此要先判铜锅
        if (MinersDelightCopperPotSupport.isRequestedCopperPot(
                player, recipe, dimension, position)) {
            ModType copperPot = ModType.findById(ModIds.ID_MD_COPPER_POT);
            if (copperPot != null) return copperPot;
        }
        ModRecipeHandler claimed = ModRecipeHandlers.claimFor(recipe).winner();
        ModType claimedType = claimed == null ? null : claimed.modType(recipe);
        if (claimedType == null || claimedType == ModType.GENERIC) {
            return ModType.classifyRecipe(recipe);
        }
        // 原版烹饪与切石配方共用同一个处理器，只能声明家族兜底类型 vanilla_furnace；
        // 高炉、烟熏炉、营火、切石机的绑定类型各不相同，必须按配方类细分，
        // 否则会拿 vanilla_furnace 去查绑定，永远匹配不到玩家已经绑定的高炉或切石机。
        if (VANILLA_FURNACE_TYPE_ID.equals(claimedType.id())) {
            ModType classified = classifyQuietly(recipe);
            if (isVanillaMachineFamilyType(classified)) {
                // 旧存档可能把兜底类型本身绑到了机器上，此时保持原类型避免破坏既有绑定
                boolean claimedTypeBound = AltarBindingRegistry.hasBindingForRecipe(
                        player, recipe, claimedType);
                return preferVanillaFamilyType(claimedType, classified, claimedTypeBound);
            }
            return claimedType;
        }
        // 注册表里没有同模组子类型时直接沿用处理器声明的类型，常见路径不做全表前缀匹配
        if (!hasMoreSpecificType(claimedType)) return claimedType;
        ModType classified = classifyQuietly(recipe);
        // 配方处理器常只声明模组级兜底类型（如 farmersdelight、youkaishomecoming），
        // 这类类型没有可绑定的方块，绑定检查必定失败，此时以配方类能确定的子类型为准；
        // 但若旧存档已经把兜底类型绑定到机器上，则保持原类型避免破坏既有绑定。
        boolean claimedTypeBound = isSpecificSiblingOf(classified, claimedType)
                && AltarBindingRegistry.hasBindingForRecipe(player, recipe, claimedType);
        return preferSpecificExecutionType(claimedType, classified, claimedTypeBound);
    }

    /** 注册表中是否存在 {@code type} 的更具体子类型，例如 farmersdelight_cooking_pot。 */
    private static boolean hasMoreSpecificType(ModType type) {
        String prefix = type.id() + "_";
        for (ModType candidate : ModType.values()) {
            if (candidate.id().startsWith(prefix)) return true;
        }
        return false;
    }

    @Nullable
    private static ModType classifyQuietly(Recipe<?> recipe) {
        try {
            return ModType.classifyRecipe(recipe);
        } catch (RuntimeException failure) {
            // 分类只是加速绑定匹配的辅助信息，失败时退回处理器声明的类型
            RSIntegrationMod.LOGGER.debug("Unable to classify recipe {}", recipe.getId(), failure);
            return null;
        }
    }

    /**
     * 在“处理器声明的类型”和“配方类分类出的类型”之间选出真正能绑定机器的类型。
     *
     * @param claimedTypeBound 处理器声明的类型当前已有绑定机器
     */
    @Nullable
    static ModType preferSpecificExecutionType(@Nullable ModType claimedType,
                                               @Nullable ModType classified,
                                               boolean claimedTypeBound) {
        if (claimedType == null || claimedType == ModType.GENERIC) return classified;
        if (!isSpecificSiblingOf(classified, claimedType)) return claimedType;
        return claimedTypeBound ? claimedType : classified;
    }

    /** 判断 {@code type} 是否是原版机器家族里除兜底类型外的具体机器类型。 */
    static boolean isVanillaMachineFamilyType(@Nullable ModType type) {
        return type != null && VANILLA_MACHINE_FAMILY.contains(type.id());
    }

    /**
     * 在“原版家族兜底类型”和“配方类细分出的具体机器类型”之间选出能绑定机器的类型。
     *
     * @param claimedTypeBound 兜底类型当前已有绑定机器
     */
    @Nullable
    static ModType preferVanillaFamilyType(@Nullable ModType claimedType,
                                           @Nullable ModType classified,
                                           boolean claimedTypeBound) {
        if (claimedType == null || !VANILLA_FURNACE_TYPE_ID.equals(claimedType.id())) {
            return claimedType;
        }
        if (!isVanillaMachineFamilyType(classified)) return claimedType;
        return claimedTypeBound ? claimedType : classified;
    }

    /** 判断 {@code specific} 是否是 {@code coarser} 在同一模组下的更具体子类型。 */
    private static boolean isSpecificSiblingOf(@Nullable ModType specific, @Nullable ModType coarser) {
        return specific != null
                && coarser != null
                && specific != coarser
                && specific != ModType.GENERIC
                && specific.id().startsWith(coarser.id() + "_");
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
