package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TickSimulatorTest extends BootstrapTest {
    @Test
    void passiveTickSnapshotDoesNotShareTaggedStacksWithDiskDelegate() {
        ItemStack first = charm("apotheosis:first", 1);
        ItemStack second = charm("apotheosis:second", 2);
        List<ItemStack> snapshot = TickSimulator.snapshotStacks(List.of(first, second));

        // Model an RS extraction mutating/reusing the delegate-owned stack objects.
        first.getOrCreateTag().putString("Potion", "apotheosis:removed");
        second.getOrCreateTag().putString("Potion", "apotheosis:removed");

        assertEquals("apotheosis:first", snapshot.get(0).getTag().getString("Potion"));
        assertEquals("apotheosis:second", snapshot.get(1).getTag().getString("Potion"));
        assertEquals(1, snapshot.get(0).getTag().getInt("RSISlot"));
        assertEquals(2, snapshot.get(1).getTag().getInt("RSISlot"));
    }

    @Test
    void potionCharmKeepsIdentityButAcceptsDurabilityConsumption() {
        ItemStack before = new ItemStack(Items.DIAMOND_SWORD);
        before.getOrCreateTag().putString("Potion", "apotheosis:test_effect");
        before.getOrCreateTag().putBoolean("charm_enabled", true);
        before.getOrCreateTag().putInt("RSISlot", 7);

        ItemStack after = before.copy();
        after.getOrCreateTag().putString("Potion", "apotheosis:wrong_effect");
        after.setDamageValue(12);

        ItemStack preserved = PotionCharmMutationPolicy.preserveIdentity(before, after);

        assertEquals("apotheosis:test_effect", preserved.getTag().getString("Potion"));
        assertEquals(12, preserved.getDamageValue());
        assertFalse(preserved.getTag().contains("RSISlot"));
    }

    private static ItemStack charm(String potion, int slot) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.getOrCreateTag().putString("Potion", potion);
        stack.getOrCreateTag().putInt("RSISlot", slot);
        return stack;
    }
}
