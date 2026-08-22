package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RefinedStorageItemKeysTest extends BootstrapTest {
    @Test
    void identityMatchesRsItemAndTagSemantics() {
        ItemStack one = new ItemStack(Items.DIAMOND, 1);
        ItemStack many = new ItemStack(Items.DIAMOND, 64);

        assertEquals(RefinedStorageItemKeys.fromStack(one), RefinedStorageItemKeys.fromStack(many));

        many.getOrCreateTag().putString("variant", "tagged");
        assertNotEquals(RefinedStorageItemKeys.fromStack(one), RefinedStorageItemKeys.fromStack(many));
        assertNotEquals(RefinedStorageItemKeys.fromStack(one),
                RefinedStorageItemKeys.fromStack(new ItemStack(Items.GOLD_INGOT)));
    }

    @Test
    void nullAndEmptyTagsFollowNativeStackTagEquality() {
        ItemStack withoutTag = new ItemStack(Items.DIAMOND);
        ItemStack emptyTag = new ItemStack(Items.DIAMOND);
        emptyTag.setTag(new CompoundTag());

        assertEquals(ItemStack.isSameItemSameTags(withoutTag, emptyTag),
                RefinedStorageItemKeys.fromStack(withoutTag)
                        .equals(RefinedStorageItemKeys.fromStack(emptyTag)));
    }
}
