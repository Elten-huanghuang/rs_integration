package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/** Executes shared Farmer's Delight cooking recipes in Miner's Delight's copper pot. */
public final class MinersDelightCopperPotBatchDelegate extends CookingPotBatchDelegate {

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return MinersDelightCopperPotSupport.isCopperPot(blockEntity);
    }

    @Override
    protected IItemHandler getInventory(BlockEntity blockEntity) {
        try {
            Method method = blockEntity.getClass().getMethod("getInventory");
            Object value = method.invoke(blockEntity);
            return value instanceof IItemHandler handler ? handler : null;
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-MinersDelight] Cannot access copper-pot inventory", exception);
            return null;
        }
    }

    @Override
    protected int inputSlots() { return 4; }

    @Override
    protected int mealDisplaySlot() { return 4; }

    @Override
    protected int containerSlot() { return 5; }

    @Override
    protected int outputSlot() { return 6; }

    @Override
    protected int inventorySize() { return 7; }

    @Override
    protected ItemStack getRequiredContainer(Recipe<?> recipe, @Nullable RegistryAccess access) {
        return MinersDelightCopperPotSupport.requiredContainer(recipe, access);
    }

    @Override
    protected ItemStack getExpectedRecipeResult(Recipe<?> recipe, @Nullable RegistryAccess access) {
        return MinersDelightCopperPotSupport.recipeResult(recipe, access);
    }

    @Override
    protected boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_MINERS_DELIGHT_COPPER_POT_INPUT_BUFFER.get();
    }

    @Override
    protected int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.MINERS_DELIGHT_COPPER_POT_INPUT_BUFFER_LIMIT.get());
    }

    @Override
    protected boolean supportsBufferedMachine(BlockEntity machine) {
        return MinersDelightCopperPotSupport.isCopperPot(machine);
    }

    @Override
    protected boolean supportsBufferedContainer(ItemStack required, ItemStack declared) {
        // Native cup conversion intentionally replaces the recipe's declared bowl.
        return required.isEmpty() || !declared.isEmpty();
    }

    @Override
    protected String outputPortId() {
        return "miners_delight:copper_pot:output";
    }
}
