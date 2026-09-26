package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Optional-safe access to Miner's Delight copper-pot behavior. */
public final class MinersDelightCopperPotSupport {
    static final String COPPER_POT_BE =
            "com.sammy.minersdelight.content.block.copper_pot.CopperPotBlockEntity";
    private static final String COOKING_RECIPE =
            "vectorwing.farmersdelight.common.crafting.CookingPotRecipe";
    private static final ResourceLocation COPPER_CUP =
            new ResourceLocation(ModIds.MINERS_DELIGHT, "copper_cup");

    private static volatile Field conversionMapField;
    private static volatile boolean conversionMapProbed;

    private MinersDelightCopperPotSupport() {}

    public static boolean isCookingRecipe(Recipe<?> recipe) {
        return recipe != null && COOKING_RECIPE.equals(recipe.getClass().getName());
    }

    /** Miner's Delight exposes only four food-input slots in its copper pot. */
    public static boolean isCompatibleRecipe(Recipe<?> recipe) {
        if (!isCookingRecipe(recipe)) return false;
        int nonEmpty = 0;
        for (var ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty() && ++nonEmpty > 4) return false;
        }
        return true;
    }

    public static boolean isCopperPotType(@Nullable ModType type) {
        return type != null && ModIds.ID_MD_COPPER_POT.equals(type.id());
    }

    public static boolean isCopperPot(BlockEntity blockEntity) {
        return blockEntity != null && COPPER_POT_BE.equals(blockEntity.getClass().getName());
    }

    /**
     * Select the copper-pot route only for a server-verified copper-pot binding
     * or block entity at the coordinates carried by the JEI request.
     */
    public static boolean isRequestedCopperPot(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        if (!ModList.get().isLoaded(ModIds.MINERS_DELIGHT) || !isCookingRecipe(recipe)
                || dim == null || pos == null) return false;

        ModType copperType = ModType.findById(ModIds.ID_MD_COPPER_POT);
        if (copperType == null) return false;
        boolean boundAtPosition = AltarBindingRegistry.getBoundMachinesForType(player, copperType)
                .stream().anyMatch(machine -> dim.equals(machine.dim()) && pos.equals(machine.pos()));
        if (boundAtPosition) return true;

        ServerLevel level = player.getServer().getLevel(ResourceKey.create(
                Registries.DIMENSION, dim));
        return level != null && level.hasChunkAt(pos) && isCopperPot(level.getBlockEntity(pos));
    }

    public static ItemStack recipeResult(Recipe<?> recipe, @Nullable RegistryAccess access) {
        ItemStack result;
        try {
            result = ModRecipeHandlers.tryGetResultItem(recipe, access);
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
        return convertResult(result);
    }

    public static ItemStack adaptResult(@Nullable ModType type, ItemStack original) {
        return isCopperPotType(type) ? convertResult(original) : original;
    }

    public static List<IngredientSpec> adaptIngredientSpecs(@Nullable ModType type,
                                                            List<IngredientSpec> original,
                                                            Recipe<?> recipe,
                                                            @Nullable RegistryAccess access) {
        return isCopperPotType(type)
                ? adaptIngredientSpecs(original, recipe, access)
                : original;
    }

    public static ItemStack convertResult(ItemStack original) {
        if (original == null || original.isEmpty()) return ItemStack.EMPTY;
        Item convertedItem = conversionMap().get(original.getItem());
        if (convertedItem == null) return original.copy();
        int count = Math.min(original.getCount() * 2,
                convertedItem.getMaxStackSize(convertedItem.getDefaultInstance()));
        ItemStack converted = new ItemStack(convertedItem, count);
        if (original.hasTag()) converted.setTag(original.getTag().copy());
        return converted;
    }

    public static ItemStack requiredContainer(Recipe<?> recipe, @Nullable RegistryAccess access) {
        ItemStack original;
        try {
            original = ModRecipeHandlers.tryGetResultItem(recipe, access);
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
        if (!original.isEmpty() && conversionMap().containsKey(original.getItem())) {
            Item cup = BuiltInRegistries.ITEM.get(COPPER_CUP);
            ItemStack converted = convertResult(original);
            return cup == null || converted.isEmpty()
                    ? ItemStack.EMPTY : new ItemStack(cup, converted.getCount());
        }
        return CookingPotBatchDelegate.getContainerItem(recipe, access);
    }

    public static List<IngredientSpec> adaptIngredientSpecs(List<IngredientSpec> original,
                                                            Recipe<?> recipe,
                                                            @Nullable RegistryAccess access) {
        if (original == null) return null;
        ItemStack required = requiredContainer(recipe, access);
        ItemStack declared = FarmersDelightRecipeHandler.getOutputContainer(recipe);
        if (required.isEmpty()) return original;

        List<IngredientSpec> adapted = new ArrayList<>(original);
        if (declared.isEmpty()) {
            boolean alreadyPresent = adapted.stream().anyMatch(spec ->
                    !spec.isEmpty() && spec.count() >= required.getCount()
                            && spec.ingredient().test(required));
            if (!alreadyPresent) adapted.add(new IngredientSpec(
                    Ingredient.of(required.copyWithCount(1)),
                    required.getCount()));
            return List.copyOf(adapted);
        }
        if (ItemStack.isSameItemSameTags(required, declared)) return original;
        for (int i = adapted.size() - 1; i >= 0; i--) {
            IngredientSpec spec = adapted.get(i);
            if (spec.count() == declared.getCount() && spec.ingredient().test(declared)) {
                adapted.remove(i);
                break;
            }
        }
        adapted.add(new IngredientSpec(
                Ingredient.of(required.copyWithCount(1)),
                required.getCount()));
        return List.copyOf(adapted);
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe) {
        List<Component> warnings = new ArrayList<>();
        ItemStack container = requiredContainer(recipe, player.level().registryAccess());
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName()));
        }
        warnings.add(Component.translatable("rsi.farmersdelight.heat_warning"));
        return warnings;
    }

    @SuppressWarnings("unchecked")
    private static Map<Item, Item> conversionMap() {
        probeConversionMap();
        if (conversionMapField == null) return Map.of();
        try {
            Object value = conversionMapField.get(null);
            return value instanceof Map<?, ?> map ? (Map<Item, Item>) map : Map.of();
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-MinersDelight] Cannot read cup conversion map", exception);
            return Map.of();
        }
    }

    private static synchronized void probeConversionMap() {
        if (conversionMapProbed) return;
        conversionMapProbed = true;
        try {
            Class<?> listener = Class.forName(
                    "com.sammy.minersdelight.logic.CupConversionReloadListener");
            conversionMapField = listener.getField("BOWL_TO_CUP");
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-MinersDelight] Cup conversion map is unavailable", exception);
        }
    }
}
