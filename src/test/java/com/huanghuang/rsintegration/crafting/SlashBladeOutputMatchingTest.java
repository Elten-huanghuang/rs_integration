package com.huanghuang.rsintegration.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashBladeOutputMatchingTest {
    @Test
    void standaloneBladeAcceptsRuntimeNbt() {
        CompoundTag expected = new CompoundTag();
        expected.putBoolean("Unbreakable", true);
        CompoundTag actual = namedBlade("item.slashblade.slashblade_wood");
        actual.putString("Crafter", "runtime-player");
        actual.putInt("Damage", 17);
        actual.putLong("lastActionTime", 42L);

        assertTrue(MaterialMatcher.matchesSlashBladeOutput(
                new ResourceLocation("slashblade", "slashblade_wood"), expected, actual));
    }

    @Test
    void sharedNamedBladeRequiresStableIdentityButIgnoresRuntimeState() {
        CompoundTag expected = namedBlade("item.slashblade_addon.terra_blade");
        CompoundTag sameBlade = namedBlade("item.slashblade_addon.terra_blade");
        sameBlade.putString("Crafter", "runtime-player");
        sameBlade.putInt("Damage", 31);
        sameBlade.putLong("lastActionTime", 99L);
        CompoundTag otherBlade = namedBlade("item.slashblade_addon.wanderer");

        ResourceLocation shared = new ResourceLocation("slashblade", "slashblade");
        assertTrue(MaterialMatcher.matchesSlashBladeOutput(shared, expected, sameBlade));
        assertFalse(MaterialMatcher.matchesSlashBladeOutput(shared, expected, otherBlade));
    }

    @Test
    void sharedBladeWithoutDeclaredIdentityAcceptsAssembledIdentity() {
        CompoundTag expected = new CompoundTag();
        expected.putBoolean("Unbreakable", true);

        assertTrue(MaterialMatcher.matchesSlashBladeOutput(
                new ResourceLocation("slashblade", "slashblade"), expected,
                namedBlade("item.slashblade.any_runtime_blade")));
    }

    private static CompoundTag namedBlade(String translationKey) {
        CompoundTag root = new CompoundTag();
        CompoundTag bladeState = new CompoundTag();
        bladeState.putString("translationKey", translationKey);
        root.put("bladeState", bladeState);
        return root;
    }
}
