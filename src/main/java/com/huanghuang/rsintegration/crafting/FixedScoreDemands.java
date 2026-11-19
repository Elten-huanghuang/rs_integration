package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CandidateEngine.IngredientDemand;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 仅缓存最终评分输入的分组，每次命中都要核对当前声明。 */
record FixedScoreDemands(List<Slot> slots, List<IngredientDemand> demands) {
    record Slot(IngredientSpec spec, Item item, @Nullable CompoundTag tag) {}

    @Nullable
    static FixedScoreDemands capture(List<IngredientSpec> specs, Collection<IngredientDemand> groups) {
        var slots = new ArrayList<Slot>(specs.size());
        for (var spec : specs) {
            var ingredient = spec.ingredient();
            if (ingredient.getClass() != Ingredient.class && ingredient.getClass() != StrictNBTIngredient.class) return null;
            var options = ingredient.getItems();
            if (spec.isEmpty() || options.length != 1 || options[0].isEmpty()) return null;
            var tag = options[0].getTag();
            slots.add(new Slot(spec, options[0].getItem(), tag == null ? null : tag.copy()));
        }
        return new FixedScoreDemands(List.copyOf(slots), List.copyOf(groups));
    }

    boolean matches(List<IngredientSpec> current) {
        if (slots.size() != current.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            Slot slot = slots.get(i);
            IngredientSpec spec = current.get(i);
            if (spec != slot.spec() || spec.isEmpty()) return false;
            var options = spec.ingredient().getItems();
            if (options.length != 1 || options[0].isEmpty() || options[0].getItem() != slot.item()
                    || !Objects.equals(slot.tag(), options[0].getTag())) return false;
        }
        return true;
    }
}
