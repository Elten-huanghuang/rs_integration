package com.huanghuang.rsintegration.mods.dungeonsdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.recipe.DungeonsDelightRecipeHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/** 复用烹饪锅的批量、并发、回滚及结算接口，保留怪物锅的原生加热判定。 */
public final class MonsterPotBatchDelegate extends CookingPotBatchDelegate {
    static final String BLOCK_ENTITY_CLASS =
            "net.yirmiri.dungeonsdelight.common.block.entity.MonsterPotBlockEntity";

    @Override
    protected boolean isSupportedRecipe(Recipe<?> candidate) {
        return candidate != null
                && DungeonsDelightRecipeHandler.RECIPE_CLASS.equals(candidate.getClass().getName());
    }

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return blockEntity != null && BLOCK_ENTITY_CLASS.equals(blockEntity.getClass().getName());
    }

    @Override
    protected IItemHandler getInventory(BlockEntity blockEntity) {
        try {
            Object value = blockEntity.getClass().getMethod("getInventory").invoke(blockEntity);
            return value instanceof IItemHandler handler ? handler : null;
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-MonsterPot] 无法读取怪物锅库存", exception);
            return null;
        }
    }

    @Override
    protected Class<?> ingredientRemainderOverridesClass(BlockEntity machine) {
        return machine.getClass();
    }

    @Override
    protected boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_MONSTER_POT_INPUT_BUFFER.get();
    }

    @Override
    protected int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.MONSTER_POT_INPUT_BUFFER_LIMIT.get());
    }

    @Override
    protected boolean supportsBufferedMachine(BlockEntity machine) {
        return isSupportedBlockEntity(machine);
    }

    @Override
    protected String outputPortId() {
        return "dungeonsdelight:monster_pot:output";
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe) {
        List<Component> warnings = new ArrayList<>();
        ItemStack container = getContainerItem(recipe, player.level().registryAccess());
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName()));
        }
        warnings.add(Component.translatable("rsi.dungeonsdelight.heat_warning"));
        return warnings;
    }
}
