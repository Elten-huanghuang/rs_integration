package com.huanghuang.rsintegration.voidupgrade;

import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class CompiledVoidRules {
    private final Set<ResourceLocation> itemsIgnoringNbt = new HashSet<>();
    private final Map<ResourceLocation, List<CompoundTag>> itemsMatchingNbt = new HashMap<>();
    private final Set<String> mods = new HashSet<>();
    private final List<TagKey<Item>> tags = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private final EnumSet<EquipmentSlot> equipment = EnumSet.noneOf(EquipmentSlot.class);

    static CompiledVoidRules compile(List<VoidUpgradeConfig> configs) {
        CompiledVoidRules result = new CompiledVoidRules();
        for (VoidUpgradeConfig config : configs) {
            for (VoidUpgradeRule rule : config.rules()) result.add(rule, config.matchNbt());
        }
        return result;
    }

    boolean isEmpty() {
        return itemsIgnoringNbt.isEmpty() && itemsMatchingNbt.isEmpty() && mods.isEmpty()
                && tags.isEmpty() && names.isEmpty() && equipment.isEmpty();
    }

    boolean matches(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        if (itemsIgnoringNbt.contains(id) || mods.contains(id.getNamespace())) return true;
        List<CompoundTag> variants = itemsMatchingNbt.get(id);
        if (variants != null) {
            for (CompoundTag variant : variants) {
                if (Objects.equals(variant, stack.getTag())) return true;
            }
        }
        for (TagKey<Item> tag : tags) {
            if (stack.is(tag)) return true;
        }
        if (!names.isEmpty()) {
            String displayName = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (displayName.contains(name)) return true;
            }
        }
        if (stack.getItem() instanceof ArmorItem armor
                && equipment.contains(armor.getEquipmentSlot())) return true;
        return false;
    }

    private void add(VoidUpgradeRule rule, boolean matchNbt) {
        switch (rule.type()) {
            case ITEM -> {
                ResourceLocation id = ResourceLocation.tryParse(rule.value());
                if (id == null) return;
                if (matchNbt) {
                    itemsMatchingNbt.computeIfAbsent(id, unused -> new ArrayList<>())
                            .add(rule.itemNbt() == null ? null : rule.itemNbt().copy());
                } else {
                    itemsIgnoringNbt.add(id);
                }
            }
            case TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(rule.value());
                if (id != null) tags.add(TagKey.create(Registries.ITEM, id));
            }
            case MOD -> mods.add(rule.value());
            case NAME -> names.add(rule.value());
            case EQUIPMENT -> {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (slot.getName().equals(rule.value())) equipment.add(slot);
                }
            }
        }
    }
}
