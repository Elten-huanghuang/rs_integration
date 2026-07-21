package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceDiskVariantIdentityTest extends BootstrapTest {
    @Test
    void taczAmmoBoxStyleVariantsRemainDistinctDespiteSharingOneItemId() {
        List<ItemStack> variants = List.of(
                ammoBox(0, false, false),
                ammoBox(1, false, false),
                ammoBox(2, false, false),
                ammoBox(0, true, false),
                ammoBox(0, false, true));

        for (int i = 0; i < variants.size(); i++) {
            assertTrue(ResonanceStackIdentity.isSameVariant(variants.get(i), variants.get(i).copy()));
            for (int j = i + 1; j < variants.size(); j++) {
                assertFalse(ResonanceStackIdentity.isSameVariant(variants.get(i), variants.get(j)),
                        "variant " + i + " must not merge with variant " + j);
            }
        }
    }

    @Test
    void loadedAmmoIdentityAndCountArePartOfTheVariant() {
        ItemStack nineMillimeter = ammoBox(0, false, false);
        nineMillimeter.getOrCreateTag().putString("AmmoId", "tacz:9mm");
        nineMillimeter.getOrCreateTag().putInt("AmmoCount", 30);
        ItemStack rifle = ammoBox(0, false, false);
        rifle.getOrCreateTag().putString("AmmoId", "tacz:556x45");
        rifle.getOrCreateTag().putInt("AmmoCount", 30);

        assertFalse(ResonanceStackIdentity.isSameVariant(nineMillimeter, rifle));
        rifle.getOrCreateTag().putString("AmmoId", "tacz:9mm");
        assertTrue(ResonanceStackIdentity.isSameVariant(nineMillimeter, rifle));
        rifle.getOrCreateTag().putInt("AmmoCount", 29);
        assertFalse(ResonanceStackIdentity.isSameVariant(nineMillimeter, rifle));
    }

    private static ItemStack ammoBox(int level, boolean creative, boolean allTypeCreative) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt("Level", level);
        if (creative) tag.putBoolean("Creative", true);
        if (allTypeCreative) tag.putBoolean("AllTypeCreative", true);
        return stack;
    }
}
