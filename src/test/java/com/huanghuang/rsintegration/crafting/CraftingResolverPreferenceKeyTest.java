package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftingResolverPreferenceKeyTest extends BootstrapTest {
    @Test
    void taggedVariantsHaveDistinctStablePreferenceKeys() {
        ItemStack first = new ItemStack(Items.DIAMOND);
        first.getOrCreateTag().putString("gem", "apotheosis:core/warlord");
        ItemStack same = first.copy();
        ItemStack other = new ItemStack(Items.DIAMOND);
        other.getOrCreateTag().putString("gem", "apotheosis:core/samurai");

        ResourceLocation firstKey = CraftingResolver.preferenceKey(first);
        assertEquals(firstKey, CraftingResolver.preferenceKey(same));
        assertNotEquals(firstKey, CraftingResolver.preferenceKey(other));
        assertTrue(CraftingResolver.isStackPreferenceKey(firstKey));
    }

    @Test
    void untaggedStacksKeepLegacyItemKey() {
        assertEquals(new ResourceLocation("minecraft", "diamond"),
                CraftingResolver.preferenceKey(new ItemStack(Items.DIAMOND)));
    }
}
