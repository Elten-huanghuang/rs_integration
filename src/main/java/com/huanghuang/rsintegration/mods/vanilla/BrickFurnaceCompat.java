package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional Brick Furnace access without linking its classes into common signatures. */
public final class BrickFurnaceCompat {
    private static final String BE_BASE =
            "cech12.brickfurnace.blockentity.AbstractBrickFurnaceBlockEntity";
    private static final String CONFIG = "cech12.brickfurnace.config.ServerConfig";

    private static volatile boolean probed;
    private static Field vanillaRecipesEnabled;
    private static Field cookTimeFactor;
    private static Method isRecipeNotBlacklisted;
    private static Method getRecipe;
    private static Field currentRecipe;
    private static Field failedMatch;

    private BrickFurnaceCompat() {}

    public static boolean isBrickFurnace(BlockEntity be) {
        Class<?> type = be != null ? be.getClass() : null;
        while (type != null) {
            if (BE_BASE.equals(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    public static Eligibility canExecute(BlockEntity be, Recipe<?> recipe) {
        if (!isBrickFurnace(be)) return Eligibility.allow();
        ResourceLocation typeId = recipe != null && recipe.getType() != null
                ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()) : null;
        if (typeId == null) return Eligibility.denied("recipe type is unavailable");
        if ("brickfurnace".equals(typeId.getNamespace())) return Eligibility.allow();
        if (!"minecraft".equals(typeId.getNamespace())) {
            return Eligibility.denied("unsupported recipe namespace");
        }
        if (!probe()) return Eligibility.denied("Brick Furnace config is unavailable");
        try {
            Object enabledValue = vanillaRecipesEnabled.get(null);
            boolean enabled = (boolean) enabledValue.getClass().getMethod("get").invoke(enabledValue);
            if (!enabled) return Eligibility.denied("Brick Furnace vanilla recipes are disabled");
            boolean allowed = (boolean) isRecipeNotBlacklisted.invoke(null, recipe.getId());
            return allowed ? Eligibility.allow()
                    : Eligibility.denied("recipe is blacklisted by Brick Furnace");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-BrickFurnace] Config check failed", exception);
            return Eligibility.denied("Brick Furnace config check failed");
        }
    }

    @Nullable
    public static AbstractCookingRecipe resolvedRecipe(BlockEntity be) {
        if (!isBrickFurnace(be) || !probe()) return null;
        try {
            return (AbstractCookingRecipe) getRecipe.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-BrickFurnace] Recipe probe failed", exception);
            return null;
        }
    }

    /** Clear Brick Furnace's identity-based recipe cache after automation changes slot 0. */
    public static void invalidateRecipeCache(BlockEntity be) {
        if (!isBrickFurnace(be) || !probe()) return;
        try {
            currentRecipe.set(be, null);
            failedMatch.set(be, ItemStack.EMPTY);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-BrickFurnace] Recipe cache reset failed", exception);
        }
    }

    public static int effectiveCookTicks(BlockEntity be, AbstractCookingRecipe recipe) {
        if (!isBrickFurnace(be)) return recipe.getCookingTime();
        ResourceLocation typeId = recipe.getType() != null
                ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()) : null;
        if (typeId != null && "brickfurnace".equals(typeId.getNamespace())) {
            return recipe.getCookingTime();
        }
        return scale(recipe.getCookingTime(), factor());
    }

    public static int effectiveBurnTicks(BlockEntity be, ItemStack fuel, RecipeType<?> recipeType) {
        int raw = ForgeHooks.getBurnTime(fuel, recipeType);
        if (!isBrickFurnace(be)) return raw;
        CookingMachineFamily family = CookingMachineFamily.fromBlock(be.getBlockState().getBlock());
        double multiplier = factor();
        if (family == CookingMachineFamily.BLAST_FURNACE
                || family == CookingMachineFamily.SMOKER) multiplier *= 0.5D;
        return scale(raw, multiplier);
    }

    static int scale(int ticks, double multiplier) {
        if (ticks <= 0 || multiplier <= 0) return 0;
        double scaled = ticks * multiplier;
        return scaled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    private static double factor() {
        if (!probe()) return 0;
        try {
            Object value = cookTimeFactor.get(null);
            return ((Number) value.getClass().getMethod("get").invoke(value)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-BrickFurnace] Cook-time factor check failed", exception);
            return 0;
        }
    }

    private static boolean probe() {
        if (probed) return probeComplete();
        synchronized (BrickFurnaceCompat.class) {
            if (probed) return probeComplete();
            try {
                ClassLoader loader = BrickFurnaceCompat.class.getClassLoader();
                Class<?> config = Class.forName(CONFIG, false, loader);
                Class<?> beBase = Class.forName(BE_BASE, false, loader);
                vanillaRecipesEnabled = config.getField("VANILLA_RECIPES_ENABLED");
                cookTimeFactor = config.getField("COOK_TIME_FACTOR");
                isRecipeNotBlacklisted = config.getMethod("isRecipeNotBlacklisted", ResourceLocation.class);
                getRecipe = beBase.getMethod("getRecipe");
                currentRecipe = beBase.getDeclaredField("curRecipe");
                currentRecipe.setAccessible(true);
                failedMatch = beBase.getDeclaredField("failedMatch");
                failedMatch.setAccessible(true);
            } catch (ReflectiveOperationException | LinkageError exception) {
                RSIntegrationMod.LOGGER.warn("[RSI-BrickFurnace] Compatibility probe failed", exception);
            } finally {
                probed = true;
            }
        }
        return probeComplete();
    }

    private static boolean probeComplete() {
        return vanillaRecipesEnabled != null && cookTimeFactor != null
                && isRecipeNotBlacklisted != null && getRecipe != null
                && currentRecipe != null && failedMatch != null;
    }

    public record Eligibility(boolean allowed, String detail) {
        static Eligibility allow() { return new Eligibility(true, ""); }
        static Eligibility denied(String detail) { return new Eligibility(false, detail); }
    }
}
