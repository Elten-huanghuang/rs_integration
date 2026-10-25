package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public record ItemIngredientView(
        List<ItemAlternative> alternatives,
        ResourceMatchMode matchMode
) {
    public ItemIngredientView {
        if (alternatives == null || alternatives.isEmpty()) {
            throw new IllegalArgumentException("at least one item alternative is required");
        }
        alternatives = List.copyOf(new LinkedHashSet<>(alternatives));
        if (matchMode == null) {
            throw new IllegalArgumentException("matchMode is required");
        }
        if (matchMode == ResourceMatchMode.PARTIAL_IDENTITY
                && alternatives.stream().anyMatch(alternative ->
                alternative.resource().identity().isEmpty())) {
            throw new IllegalArgumentException(
                    "partial identity requires a non-empty template");
        }
    }

    public static ItemIngredientView anyOf(ItemStack... stacks) {
        return of(ResourceMatchMode.ANY_IDENTITY, stacks);
    }

    public static ItemIngredientView exactOf(ItemStack... stacks) {
        return of(ResourceMatchMode.EXACT_IDENTITY, stacks);
    }

    public static ItemIngredientView partialOf(ItemStack... stacks) {
        return of(ResourceMatchMode.PARTIAL_IDENTITY, stacks);
    }

    public static ItemIngredientView of(
            ResourceMatchMode mode,
            ItemStack... stacks
    ) {
        if (stacks == null || stacks.length == 0) {
            throw new IllegalArgumentException("stacks are required");
        }
        List<ItemAlternative> alternatives = Arrays.stream(stacks)
                .map(IntegrationResourceKey::item)
                .map(ItemAlternative::new)
                .toList();
        return new ItemIngredientView(alternatives, mode);
    }
}
