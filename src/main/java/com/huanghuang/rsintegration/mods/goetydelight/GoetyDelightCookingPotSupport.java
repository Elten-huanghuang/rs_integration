package com.huanghuang.rsintegration.mods.goetydelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RangedWrapper;
import net.minecraftforge.items.wrapper.RecipeWrapper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

/** 诅咒金属锅和神灵金属锅的配方判定与库存访问。 */
public final class GoetyDelightCookingPotSupport {
    public static final String WORKSTATION_RECIPE_CLASS =
            "net.v_black_cat.goetydelight.recipe.WorkstationCookingRecipe";
    static final ResourceLocation CURSED_POT = ResourceLocation.fromNamespaceAndPath("goetydelight", "cursed_ingot_pot");
    static final ResourceLocation APOCALYPTIUM_POT =
            ResourceLocation.fromNamespaceAndPath("goetydelight", "apocalyptium_cooking_pot");
    private static final int FOOD_SLOTS = 6;
    private static final int SOUL_SOURCE_SLOT = 9;
    private static final String CURSED_POT_ENTITY =
            "net.v_black_cat.goetydelight.block.CursedIngotPotBlockEntity";
    private static final String APOCALYPTIUM_POT_ENTITY =
            "net.v_black_cat.goetydelight.compat.goetyrevelation.block.ApocalyptiumCookingPotBlockEntity";

    private GoetyDelightCookingPotSupport() {}

    public static boolean isCookingPot(BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        String className = blockEntity.getClass().getName();
        return CURSED_POT_ENTITY.equals(className) || APOCALYPTIUM_POT_ENTITY.equals(className);
    }

    public static boolean isWorkstationRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && WORKSTATION_RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    public static boolean isNecroticRecipe(Recipe<?> recipe) {
        return isWorkstationRecipe(recipe) && Reflect.<Integer>invoke(recipe, "getSoulCost").orElse(0) > 0;
    }

    public static boolean isCompatibleBinding(Recipe<?> recipe, @Nullable String blockKey) {
        if (!isWorkstationRecipe(recipe)) return true;
        if (blockKey == null) return false;
        int separator = blockKey.indexOf("||");
        String description = separator < 0 ? blockKey : blockKey.substring(separator + 2);
        ResourceLocation pot = CURSED_POT.toLanguageKey("block").equals(description) ? CURSED_POT
                : APOCALYPTIUM_POT.toLanguageKey("block").equals(description) ? APOCALYPTIUM_POT : null;
        ResourceLocation workstation = Reflect.<ResourceLocation>invoke(recipe, "getWorkstation").orElse(null);
        return mayUseWorkstation(pot, workstation, isNecroticRecipe(recipe));
    }

    static boolean mayUseWorkstation(@Nullable ResourceLocation pot,
                                     @Nullable ResourceLocation workstation, boolean necrotic) {
        if (pot == null || workstation == null) return false;
        // 注入状态在方块实体上，绑定键只能预筛选锅的种类。
        return pot.equals(workstation) || necrotic
                || APOCALYPTIUM_POT.equals(pot) && CURSED_POT.equals(workstation);
    }

    public static boolean acceptsRecipe(@Nullable BlockEntity pot, Recipe<?> recipe) {
        if (!isWorkstationRecipe(recipe)) return true;
        return isCookingPot(pot) && Reflect.<Boolean>invokeExact(pot, "canUseWorkstation",
                new Class<?>[]{recipe.getClass()}, recipe).orElse(false);
    }

    public static boolean usesNecroticCooking(@Nullable BlockEntity pot, Recipe<?> recipe) {
        return isCookingPot(pot) && isNecroticRecipe(recipe)
                && Reflect.<Boolean>invoke(pot, "isNecroticInfused").orElse(false);
    }

    public static boolean canPowerNecroticCooking(BlockEntity pot) {
        // 亡灵注入的锅以至少 4 点灵魂能量代替热源，消耗仍由锅结算。
        return Reflect.<Integer>invoke(pot, "getSoulEnergyAvailable").orElse(0) >= 4;
    }

    public static boolean matchesNativeChoice(BlockEntity pot, Recipe<?> requested,
                                               List<ItemStack> materials, boolean hasServingContainer) {
        Method findRecipe = Reflect.findMethod(pot.getClass(), "findRecipe", new Class<?>[]{RecipeWrapper.class});
        if (findRecipe == null) return !isWorkstationRecipe(requested);
        int foodSlots = materials.size() - (hasServingContainer ? 1 : 0);
        if (foodSlots < 1 || foodSlots > FOOD_SLOTS) return false;
        ItemStackHandler preview = new ItemStackHandler(SOUL_SOURCE_SLOT);
        for (int slot = 0; slot < foodSlots; slot++) {
            preview.setStackInSlot(slot, materials.get(slot).copy());
        }
        return Reflect.<Recipe<?>>invokeExact(pot, "findRecipe",
                        new Class<?>[]{RecipeWrapper.class}, new RecipeWrapper(preview))
                .map(recipe -> recipe.getId().equals(requested.getId())).orElse(false);
    }

    public static IItemHandler getInventory(BlockEntity blockEntity) {
        if (!isCookingPot(blockEntity)) return null;
        try {
            Method method = blockEntity.getClass().getMethod("getInventory");
            Object value = method.invoke(blockEntity);
            return value instanceof IItemHandlerModifiable handler ? cookingSlots(handler) : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-GoetyDelight] 无法读取金属锅库存", exception);
            return null;
        }
    }

    static IItemHandler cookingSlots(IItemHandlerModifiable handler) {
        if (handler.getSlots() <= SOUL_SOURCE_SLOT) return null;
        // 配方发料、清理和回收只接触烹饪槽，灵魂能量由锅自行管理。
        return new RangedWrapper(handler, 0, SOUL_SOURCE_SLOT);
    }

    public static boolean matchesOutputIgnoringNbt(ItemStack actual, ItemStack expected) {
        return actual != null && expected != null
                && !actual.isEmpty() && !expected.isEmpty()
                && ItemStack.isSameItem(actual, expected)
                && actual.getCount() >= expected.getCount();
    }
}
