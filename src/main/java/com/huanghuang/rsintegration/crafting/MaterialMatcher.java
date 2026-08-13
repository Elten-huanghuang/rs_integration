package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.Objects;

/** Shared matching semantics for graph planning and runtime material ownership. */
public final class MaterialMatcher {
    private static final ResourceLocation SHARED_SLASHBLADE =
            new ResourceLocation("slashblade", "slashblade");
    private static final String SLASHBLADE_ITEM_CLASS =
            "mods.flammpfeil.slashblade.item.ItemSlashBlade";

    private MaterialMatcher() {}

    public static boolean matchesIngredient(Ingredient ingredient, ItemStack stack) {
        return IngredientMatcher.test(Objects.requireNonNull(ingredient, "ingredient"),
                Objects.requireNonNull(stack, "stack"));
    }

    public static boolean matchesExact(MaterialKey material, ItemStack stack) {
        Objects.requireNonNull(material, "material");
        if (stack == null || stack.isEmpty() || stack.getItem() != material.item()) return false;
        if (!isSlashBladeItem(material.item())) return MaterialKey.of(stack).equals(material);

        CompoundTag expectedTag = parseTag(material.tag());
        if (material.tag() != null && expectedTag == null) return false;
        return matchesSlashBladeOutput(BuiltInRegistries.ITEM.getKey(material.item()),
                expectedTag, stack.getTag());
    }

    /**
     * Match a physical machine result against its recipe declaration. A tagless
     * declaration means the recipe promises an item type, while integrations may
     * still attach runtime state such as drink purity. Tagged declarations remain
     * exact so distinct recipe variants cannot satisfy each other.
     */
    public static boolean matchesOutputDeclaration(MaterialKey declared, ItemStack stack) {
        Objects.requireNonNull(declared, "declared");
        if (stack == null || stack.isEmpty() || stack.getItem() != declared.item()) return false;
        if (declared.tag() == null || matchesExact(declared, stack)) return true;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!new ResourceLocation("irons_spellbooks", "scroll").equals(itemId)) return false;
        try {
            return com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog
                    .sameSpellScroll(declared.toStack(1), stack);
        } catch (LinkageError ignored) {
            return false;
        }
    }

    /**
     * SlashBlade assembly adds mutable capability data after the recipe result is
     * declared. Standalone blade items are identified by item id; the shared blade
     * item additionally retains its stable named-blade translation key.
     */
    static boolean matchesSlashBladeOutput(ResourceLocation itemId,
                                           @Nullable CompoundTag expectedTag,
                                           @Nullable CompoundTag actualTag) {
        Objects.requireNonNull(itemId, "itemId");
        if (!SHARED_SLASHBLADE.equals(itemId)) return true;

        String expectedIdentity = bladeIdentity(expectedTag);
        return expectedIdentity == null
                || expectedIdentity.equals(bladeIdentity(actualTag));
    }

    private static boolean isSlashBladeItem(Item item) {
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
            if (SLASHBLADE_ITEM_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    @Nullable
    private static CompoundTag parseTag(@Nullable String serialized) {
        if (serialized == null || serialized.isBlank()) return null;
        try {
            return TagParser.parseTag(serialized);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String bladeIdentity(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("bladeState", Tag.TAG_COMPOUND)) return null;
        String identity = tag.getCompound("bladeState").getString("translationKey");
        return identity.isBlank() ? null : identity;
    }

    public static boolean sameRuntimeFragment(ItemStack first, ItemStack second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty()
                && ItemStack.isSameItemSameTags(first, second);
    }

    /** Capture accepts exact NBT when specified and otherwise conservatively accepts the same item. */
    public static boolean matchesCaptureExpectation(MaterialKey expected, ItemStack stack) {
        return expected != null && matchesOutputDeclaration(expected, stack);
    }
}
