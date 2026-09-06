package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiCheatDropPacketTest extends BootstrapTest {
    @Test
    void refusesItemsWithoutServerPermission() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 64);
        assertTrue(JeiCheatDropPacket.validatedDrop(stack, false).isEmpty());
        assertEquals(64, stack.getCount());
    }

    @Test
    void capsCountAtTheItemsOwnStackLimit() {
        assertEquals(64, JeiCheatDropPacket.validatedDrop(
                new ItemStack(Items.DIAMOND, 100), true).getCount());
        assertEquals(16, JeiCheatDropPacket.validatedDrop(
                new ItemStack(Items.ENDER_PEARL, 64), true).getCount());
        assertEquals(1, JeiCheatDropPacket.validatedDrop(
                new ItemStack(Items.DIAMOND_SWORD, 64), true).getCount());
    }

    @Test
    void fullStackRequestKeepsItemLimitAndDoesNotMutateJeiIngredient() {
        for (var item : new net.minecraft.world.item.Item[]{Items.DIAMOND, Items.ENDER_PEARL, Items.DIAMOND_SWORD}) {
            ItemStack displayed = new ItemStack(item);
            displayed.getOrCreateTag().putString("marker", "original");
            ItemStack request = displayed.copyWithCount(displayed.getMaxStackSize());
            ItemStack drop = JeiCheatDropPacket.validatedDrop(new JeiCheatDropPacket(request).stack(), true);
            assertEquals(displayed.getMaxStackSize(), drop.getCount());
            assertEquals("original", drop.getTag().getString("marker"));
            drop.getOrCreateTag().putString("marker", "changed");
            assertEquals("original", displayed.getTag().getString("marker"));
            assertEquals(1, displayed.getCount());
        }
    }

    @Test
    void preservesDisplayCountAndCopiesNbt() {
        ItemStack source = new ItemStack(Items.DIAMOND, 3);
        source.getOrCreateTag().putString("marker", "original");
        ItemStack drop = JeiCheatDropPacket.validatedDrop(source, true);
        assertEquals(3, drop.getCount());
        assertEquals("original", drop.getTag().getString("marker"));
        drop.getOrCreateTag().putString("marker", "changed");
        assertEquals("original", source.getTag().getString("marker"));
    }

    @Test
    void emptyAndNonPositiveStacksProduceNoDrop() {
        assertTrue(JeiCheatDropPacket.validatedDrop(ItemStack.EMPTY, true).isEmpty());
        assertTrue(JeiCheatDropPacket.validatedDrop(new ItemStack(Items.DIAMOND, 0), true).isEmpty());
        assertTrue(JeiCheatDropPacket.validatedDrop(new ItemStack(Items.DIAMOND, -1), true).isEmpty());
    }

    @Test
    void packetSnapshotsSourceAndRoundTripsCountAndNbt() {
        ItemStack source = new ItemStack(Items.DIAMOND_SWORD);
        source.getOrCreateTag().putBoolean("Unbreakable", true);
        JeiCheatDropPacket packet = new JeiCheatDropPacket(source);
        source.getOrCreateTag().putBoolean("Unbreakable", false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            JeiCheatDropPacket.encode(packet, buffer);
            ItemStack decoded = JeiCheatDropPacket.decode(buffer).stack();
            assertEquals(Items.DIAMOND_SWORD, decoded.getItem());
            assertEquals(1, decoded.getCount());
            assertTrue(decoded.getTag().getBoolean("Unbreakable"));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
