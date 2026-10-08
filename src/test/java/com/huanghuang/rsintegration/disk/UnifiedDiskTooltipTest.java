package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskItem;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskFailure;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskTooltip;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskTooltipPackets;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskTooltipRequestPacket;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskTooltipResponsePacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskTooltipTest extends BootstrapTest {
    @TempDir Path directory;

    @Test void displayTotalsRemainExactAboveIntMaxAndSurviveReloadDeletionAndSimulation() throws Exception {
        var core = UnifiedDiskCoreTest.core(8);
        var first = UnifiedDiskCoreTest.variant(1);
        var second = UnifiedDiskCoreTest.variant(2);
        core.insert(first, Integer.MAX_VALUE, true); core.insert(second, Integer.MAX_VALUE, true);
        core.insertFluid(new FluidStack(Fluids.WATER, 1), 2500, true);
        var summary = UnifiedDiskSummary.from(core);
        assertEquals(4294967294L, summary.items()); assertEquals(2500, summary.fluids());
        assertEquals(2, summary.itemTypes()); assertEquals(Integer.MAX_VALUE, core.items.total());
        var files = new DiskFileStore(directory);
        files.save(DiskFileStore.Snapshot.freeze(core), null);
        var loaded = files.load(core.worldId, core.diskId);
        assertEquals(summary, UnifiedDiskSummary.from(loaded));
        loaded.extract(FrozenKey.Kind.ITEM, loaded.items.exactSlot(first), 100, false);
        assertEquals(summary, UnifiedDiskSummary.from(loaded));
        loaded.extract(FrozenKey.Kind.ITEM, loaded.items.exactSlot(first), Integer.MAX_VALUE, true);
        loaded.extract(FrozenKey.Kind.ITEM, loaded.items.exactSlot(second), 7, true);
        assertEquals(2147483640L, UnifiedDiskSummary.from(loaded).items());
        assertEquals(1, UnifiedDiskSummary.from(loaded).itemTypes());
    }

    @Test void packetsRoundTripLargeTotalsAndUnavailableWithoutChangingDiskIdentity() {
        UUID world = UUID.randomUUID(), disk = UUID.randomUUID();
        var summary = new UnifiedDiskSummary(562949953159168L, 4294967294L, 262144, 2, 262144, 262144);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new UnifiedDiskTooltipRequestPacket(world, disk);
            UnifiedDiskTooltipRequestPacket.encode(request, buf);
            assertEquals(request, UnifiedDiskTooltipRequestPacket.decode(buf));
            var failure = new UnifiedDiskFailure(Component.translatable("missing.item", "cthulhu_creatures:flesh_altar"),
                    Component.translatable("restore.mod", "cthulhu_creatures"));
            for (var response : List.of(new UnifiedDiskTooltipResponsePacket(world, disk, summary, null),
                    new UnifiedDiskTooltipResponsePacket(world, disk, null, null),
                    new UnifiedDiskTooltipResponsePacket(world, disk, null, failure))) {
                buf.clear(); UnifiedDiskTooltipResponsePacket.encode(response, buf);
                assertTrue(buf.readableBytes() < 1024);
                assertEquals(response, UnifiedDiskTooltipResponsePacket.decode(buf));
            }
        } finally { buf.release(); }
        assertThrows(IllegalArgumentException.class, () -> new UnifiedDiskSummary(1, 0, 0, 0, 8, 8));
    }

    @Test void failureIsRedAndShowsResourceAndRecoveryBeforeIdWithoutShift() {
        var failure = new UnifiedDiskFailure(Component.translatable("missing.item", "cthulhu_creatures:flesh_altar"),
                Component.translatable("restore.mod", "cthulhu_creatures"));
        List<Component> lines = new ArrayList<>();
        UnifiedDiskTooltip.append(lines, UUID.randomUUID(), new UnifiedDiskTooltip.View(null, true, false, failure));
        assertEquals(ChatFormatting.RED.getColor(), lines.get(0).getStyle().getColor().getValue());
        assertTrue(lines.get(0).getStyle().isBold());
        assertEquals("cthulhu_creatures:flesh_altar", ((TranslatableContents) lines.get(1).getContents()).getArgs()[0]);
        assertEquals("cthulhu_creatures", ((TranslatableContents) lines.get(2).getContents()).getArgs()[0]);
        assertEquals("item.rs_integration.unified_storage_disk.failure.preserved",
                ((TranslatableContents) lines.get(3).getContents()).getKey());
        assertEquals("item.rs_integration.unified_storage_disk.id", ((TranslatableContents) lines.get(4).getContents()).getKey());
    }

    @Test void missingFilesSuggestAdministratorRecoveryWithoutSendingFilesystemPaths() {
        var failure = UnifiedDiskFailure.from(new NoSuchFileException("D:/private/world/manifest.bin"));
        assertEquals("item.rs_integration.unified_storage_disk.failure.missing_file",
                ((TranslatableContents) failure.reason().getContents()).getKey());
        assertEquals("item.rs_integration.unified_storage_disk.failure.contact_admin",
                ((TranslatableContents) failure.action().getContents()).getKey());
        assertFalse(failure.reason().getString().contains("private"));
    }

    @Test void tooltipAlwaysShowsBothInventoriesAndIdWhileExpanded() {
        UUID disk = UUID.randomUUID();
        var summary = new UnifiedDiskSummary(4294967294L, 2500, 2, 1, 8, 8);
        List<Component> lines = new ArrayList<>();
        UnifiedDiskTooltip.append(lines, disk, new UnifiedDiskTooltip.View(summary, false, true));
        var stored = (TranslatableContents) lines.get(0).getContents();
        assertEquals("4,294,967,294", stored.getArgs()[0]);
        assertEquals("item.rs_integration.unified_storage_disk.stored_fluids",
                ((TranslatableContents) lines.get(1).getContents()).getKey());
        assertEquals(disk.toString(), ((TranslatableContents) lines.get(4).getContents()).getArgs()[0]);
        assertEquals(16, lines.size());
        lines.clear(); UnifiedDiskTooltip.append(lines, disk, new UnifiedDiskTooltip.View(null, true, false));
        assertEquals("item.rs_integration.unified_storage_disk.unavailable",
                ((TranslatableContents) lines.get(0).getContents()).getKey());
        lines.clear(); UnifiedDiskTooltip.append(lines, null, new UnifiedDiskTooltip.View(null, false, false));
        assertEquals("item.rs_integration.unified_storage_disk.uninitialized",
                ((TranslatableContents) lines.get(0).getContents()).getKey());
    }

    @Test void tooltipRequestsOnlyResolveDisksActuallyVisibleToPlayer() {
        UUID world = UUID.randomUUID(), disk = UUID.randomUUID();
        ItemStack physical = spy(new ItemStack(Items.STONE));
        UnifiedDiskItem item = mock(UnifiedDiskItem.class, CALLS_REAL_METHODS);
        doReturn(item).when(physical).getItem(); item.setIdentity(physical, disk, world);
        Player player = mock(Player.class);
        player.containerMenu = new AbstractContainerMenu(null, 0) {
            @Override public ItemStack quickMoveStack(Player owner, int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player owner) { return true; }
        };
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        assertFalse(UnifiedDiskTooltipPackets.visible(player, world, disk));
        Slot slot = mock(Slot.class); when(slot.getItem()).thenReturn(physical);
        player.containerMenu.slots.add(slot);
        assertTrue(UnifiedDiskTooltipPackets.visible(player, world, disk));
        assertFalse(UnifiedDiskTooltipPackets.visible(player, UUID.randomUUID(), disk));
        physical.getTag().putInt("Format", 2);
        assertFalse(UnifiedDiskTooltipPackets.visible(player, world, disk));
    }
}
