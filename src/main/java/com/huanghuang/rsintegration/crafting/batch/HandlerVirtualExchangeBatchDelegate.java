package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.recipe.InstantRecipePolicy;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 由 {@link ModRecipeHandler} 显式声明并执行的确定性即时配方委托。
 *
 * <p>处理器必须返回 {@link InstantRecipePolicy.Mode#LEDGER_TRANSACTION}。
 * 该模式要求成本、主产物、副产物和材料角色都可直接投影到材料账本，
 * 不执行真实工作台装配或机器方块逻辑。</p>
 */
public final class HandlerVirtualExchangeBatchDelegate extends VirtualExchangeBatchDelegate {

    @Nullable
    private Recipe<?> recipe;
    @Nullable
    private ModRecipeHandler handler;

    @Override
    protected boolean validateExchangeAccess(ServerPlayer player, ResourceLocation recipeId,
                                             @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) found = FluidContainerCatalog.resolve(level, recipeId);
        if (found == null) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (HistoryStagesCompat.isRecipeLocked(found, player)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_stage_missing"));
            return false;
        }
        ModRecipeHandler resolvedHandler = ModRecipeHandlers.handlerFor(found);
        if (!supportsExchange(found, resolvedHandler, player, level)) {
            return false;
        }

        this.recipe = found;
        this.handler = resolvedHandler;
        setMachineDim(level.dimension().location());
        setMachineServer(player.server);
        RSIntegrationMod.LOGGER.debug(
                "[RSI-Instant] validated recipe={} handler={}",
                recipeId, resolvedHandler.getClass().getSimpleName());
        return true;
    }

    @Override
    protected VirtualExchangeRecipe resolveExchangeRecipe(
            ServerPlayer player, ResourceLocation recipeId) {
        if (recipe == null || handler == null) return null;
        return new HandlerExchange(recipe, handler);
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        if (recipe == null || handler == null || player == null) return false;
        return supportsExchange(recipe, handler, player, player.serverLevel());
    }

    private static boolean supportsExchange(Recipe<?> recipe, @Nullable ModRecipeHandler handler,
                                            @Nullable ServerPlayer player, ServerLevel level) {
        if (handler == null
                || !handler.instantRecipePolicy(recipe).ledgerTransaction()
                || !handler.isAvailableForPlanning(recipe, player)) {
            return false;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        return specs != null && !specs.stream().allMatch(IngredientSpec::isEmpty)
                && !ModRecipeHandlers.tryGetResultItem(
                recipe, level.registryAccess()).isEmpty();
    }

    private record HandlerExchange(Recipe<?> recipe, ModRecipeHandler handler)
            implements VirtualExchangeRecipe {

        @Override
        public List<IngredientSpec> costSpecs() {
            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            return specs == null ? List.of() : List.copyOf(specs);
        }

        @Override
        public List<ItemStack> resultItems(RegistryAccess access) {
            ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, access);
            return result.isEmpty() ? List.of() : List.of(result);
        }

        @Override
        public List<ItemStack> secondaryItems(RegistryAccess access) {
            return handler.getSecondaryOutputs(recipe, access);
        }
    }
}
