package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Resolves the two JEI-only CrockPot birdcage egg displays on the server. */
public final class BirdcageEggCatalog {
    public static final ResourceLocation MEAT_EGG_ID =
            new ResourceLocation("rs_integration", "crockpot_birdcage/meat_egg");
    public static final ResourceLocation MONSTER_MEAT_EGG_ID =
            new ResourceLocation("rs_integration", "crockpot_birdcage/monster_meat_egg");

    private static final ResourceLocation DISPLAY_EGG_ID =
            new ResourceLocation("crockpot", "parrot_egg_blue");

    private BirdcageEggCatalog() {}

    @Nullable
    public static Recipe<?> resolve(ServerLevel level, ResourceLocation id) {
        boolean monsterMeat;
        if (MEAT_EGG_ID.equals(id)) {
            monsterMeat = false;
        } else if (MONSTER_MEAT_EGG_ID.equals(id)) {
            monsterMeat = true;
        } else {
            return null;
        }
        List<ItemStack> matching = matchingMeat(level, monsterMeat);
        if (matching.isEmpty()) return null;
        Item displayItem = ForgeRegistries.ITEMS.getValue(DISPLAY_EGG_ID);
        if (displayItem == null) return null;
        return new BirdcageEggRecipe(id, Ingredient.of(matching.stream()), monsterMeat,
                new ItemStack(displayItem));
    }

    @Nullable
    public static Object foodValuesFor(ItemStack input, ServerLevel level) {
        try {
            Class<?> definition = CrockPotReflection.foodValuesDefinitionClass;
            if (definition == null) return null;
            Method method = definition.getMethod("getFoodValues", ItemStack.class,
                    Level.class);
            return method.invoke(null, input, level);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static List<ItemStack> matchingMeat(ServerLevel level, boolean monsterMeat) {
        List<ItemStack> result = new ArrayList<>();
        try {
            Class<?> categoryClass = CrockPotReflection.foodCategoryClass;
            Class<?> definitionClass = CrockPotReflection.foodValuesDefinitionClass;
            Class<?> valuesClass = CrockPotReflection.foodValuesClass;
            if (categoryClass == null || definitionClass == null || valuesClass == null) return result;
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object meat = Enum.valueOf((Class<? extends Enum>) categoryClass.asSubclass(Enum.class), "MEAT");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object monster = Enum.valueOf((Class<? extends Enum>) categoryClass.asSubclass(Enum.class), "MONSTER");
            Method matchedItems = definitionClass.getMethod("getMatchedItems", categoryClass,
                    Level.class);
            Method getFoodValues = definitionClass.getMethod("getFoodValues", ItemStack.class,
                    Level.class);
            Method hasCategory = valuesClass.getMethod("has", categoryClass);
            Object raw = matchedItems.invoke(null, meat, level);
            if (!(raw instanceof Collection<?> items)) return result;
            for (Object candidate : items) {
                if (!(candidate instanceof ItemStack stack) || stack.isEmpty()) continue;
                Object values = getFoodValues.invoke(null, stack, level);
                boolean isMonster = values != null && Boolean.TRUE.equals(hasCategory.invoke(values, monster));
                if (isMonster == monsterMeat) result.add(stack.copyWithCount(1));
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return List.of();
        }
        return result;
    }
}
