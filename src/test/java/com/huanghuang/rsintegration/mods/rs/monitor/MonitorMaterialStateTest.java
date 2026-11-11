package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.MaterialCellStyle;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MonitorMaterialStateTest extends BootstrapTest {
    @Test void unchangedMaterialsSendNothingAndChangesUseDeltaWithRemovals() {
        var server = new MonitorMaterialState();
        var client = new MonitorMaterialState();
        UUID session = UUID.randomUUID(), craft = UUID.randomUUID();
        var a = row(new ItemStack(Items.DIAMOND), 9_000_000_000L);
        var b = row(new ItemStack(Items.IRON_INGOT), 10);
        var first = new CraftMaterialView(List.of(a, b), true);
        assertTrue(server.shouldSample(craft, 1));
        assertFalse(server.shouldSample(craft, 1));
        assertTrue(server.shouldSample(craft, 2));
        var full = server.update(4, session, craft, first);
        assertTrue(full.full());
        assertTrue(client.accept(full));
        assertEquals(first, client.view(craft));
        assertNull(server.update(4, session, craft, first));
        var next = new CraftMaterialView(List.of(row(new ItemStack(Items.DIAMOND), 9_000_000_001L)), false);
        var delta = server.update(4, session, craft, next);
        assertFalse(delta.full());
        assertEquals(1, delta.changed().size());
        assertEquals(List.of(b.material()), delta.removed());
        assertTrue(client.accept(delta));
        assertEquals(next, client.view(craft));
        assertFalse(client.accept(full));
        assertFalse(client.accept(delta));
    }

    @Test void deltaRequiresMatchingCraftAndBaselineAndSwitchingBackUsesFull() {
        var server = new MonitorMaterialState();
        var client = new MonitorMaterialState();
        UUID session = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var view = new CraftMaterialView(List.of(row(new ItemStack(Items.DIAMOND), 5)), true);
        assertTrue(client.accept(server.update(1, session, a, view)));
        assertFalse(client.accept(new RsMonitorMaterialsPacket(1, session, a, 3, 2, false, List.of(), List.of(), true)));
        assertFalse(client.accept(new RsMonitorMaterialsPacket(1, session, b, 2, 1, false, List.of(), List.of(), true)));
        var other = server.update(1, session, b, CraftMaterialView.EMPTY);
        assertTrue(other.full());
        assertTrue(client.accept(other));
        assertEquals(CraftMaterialView.EMPTY, client.view(a));
        var back = server.update(1, session, a, view);
        assertTrue(back.full());
        assertTrue(client.accept(back));
        assertEquals(view, client.view(a));
    }

    @Test void materialPacketKeepsNbtFluidIdentityLongQuantitiesAndUncertainty() {
        var tagged = new ItemStack(Items.DIAMOND);
        tagged.getOrCreateTag().putString("variant", "charged");
        var fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 1));
        var rows = List.of(row(tagged, Long.MAX_VALUE), row(fluid, 9_000_000_000L));
        var packet = new RsMonitorMaterialsPacket(4, UUID.randomUUID(), UUID.randomUUID(), 7, 6, true, rows, List.of(), false);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            assertEquals(packet, RsMonitorMaterialsPacket.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
        var view = new CraftMaterialView.Builder();
        view.held(List.of(tagged.copyWithCount(3), new ItemStack(Items.DIAMOND, 4), fluid.copyWithCount(250)));
        assertEquals(3, view.build().rows().size());
        assertEquals(250, view.build().rows().get(2).available());
    }

    @Test void malformedCountsAndDuplicateKeysCannotCreateAmbiguousUpdates() {
        UUID session = UUID.randomUUID(), craft = UUID.randomUUID();
        var row = row(new ItemStack(Items.DIAMOND), 1);
        assertThrows(IllegalArgumentException.class, () -> new CraftMaterialView.Row(row.material(), -1, 0, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new RsMonitorMaterialsPacket(1, session, craft, 1, 0,
                true, List.of(row, row), List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new RsMonitorMaterialsPacket(1, session, craft, 2, 1,
                false, List.of(row), List.of(row.material()), true));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1).writeUUID(session).writeUUID(craft).writeVarLong(1).writeVarLong(0);
            buffer.writeBoolean(true).writeBoolean(true);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> RsMonitorMaterialsPacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void partialStockIsStillMissingUnlessTheCraftingPlanCoversTheGap() {
        assertEquals(MaterialCellStyle.BLOCKED, MaterialCellStyle.preview(5, 10, 2));
        assertEquals(MaterialCellStyle.SCHEDULED, MaterialCellStyle.preview(5, 10, 0));
        assertEquals(MaterialCellStyle.STORED, MaterialCellStyle.preview(10, 10, 0));
        assertEquals(MaterialCellStyle.BLOCKED, MaterialCellStyle.preview(0, 10, 10));
    }

    private static CraftMaterialView.Row row(ItemStack stack, long available) {
        return new CraftMaterialView.Row(MaterialKey.of(stack), available, 2, 3, 4, 5, 6, true);
    }
}
