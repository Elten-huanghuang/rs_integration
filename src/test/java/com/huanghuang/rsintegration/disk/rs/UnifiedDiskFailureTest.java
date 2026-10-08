package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable.PageSnapshot;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Snapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskFailureTest extends BootstrapTest {
    @TempDir Path world;
    private MinecraftServer server;

    private byte[] missingPayload(FrozenKey.Kind kind) throws Exception {
        CompoundTag tag = new CompoundTag();
        if (kind == FrozenKey.Kind.ITEM) {
            tag.putString("id", "cthulhu_creatures:flesh_altar"); tag.putByte("Count", (byte) 1);
        } else {
            tag.putString("FluidName", "removed_mod:fluid"); tag.putInt("Amount", 1);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void missingResourcesKeepKindAndRegistryIdForLocalizedAdvice(FrozenKey.Kind kind) throws Exception {
        var missing = assertThrows(FrozenKey.MissingResourceException.class, () -> FrozenKey.load(kind, missingPayload(kind)));
        assertEquals(kind, missing.kind());
        assertEquals(new ResourceLocation(kind == FrozenKey.Kind.ITEM ? "cthulhu_creatures:flesh_altar" : "removed_mod:fluid"),
                missing.resource());
        UnifiedDiskFailure failure = UnifiedDiskFailure.from(missing);
        TranslatableContents reason = (TranslatableContents) failure.reason().getContents();
        assertEquals("item.rs_integration.unified_storage_disk.failure." + (kind == FrozenKey.Kind.ITEM
                ? "missing_item" : "missing_fluid"), reason.getKey());
        assertEquals(missing.resource().toString(), reason.getArgs()[0]);
        assertEquals(missing.resource().getNamespace(), ((TranslatableContents) failure.action().getContents()).getArgs()[0]);
    }

    @Test void missingItemPreservesBothInventoriesAndNotifiesOwnerOncePerLogin() throws Exception {
        DiskFileStore files = new DiskFileStore(world.resolve("data/rs_integration/unified_disks"));
        UUID worldId = files.worldIdentity(), disk = UUID.randomUUID(), owner = UUID.randomUUID();
        UnifiedDiskCore core = new UnifiedDiskCore(worldId, disk, owner, new UnifiedDiskCore.Limits(512, 512, 1048576, 268435456));
        core.insertItem(new ItemStack(Items.STONE), 2, true);
        core.insertFluid(new FluidStack(Fluids.WATER, 1), 548000, true);
        Snapshot snapshot = Snapshot.freeze(core);
        PageSnapshot page = snapshot.items().get(0);
        byte[][] keys = page.keys().clone(); keys[0] = missingPayload(FrozenKey.Kind.ITEM);
        PageSnapshot missingPage = new PageSnapshot(page.index(), page.keyRevision(), page.amountRevision(), keys,
                page.generations(), page.orders(), page.amounts());
        files.save(new Snapshot(worldId, disk, owner, core.limits, snapshot.itemPages(), snapshot.fluidPages(),
                List.of(missingPage), snapshot.fluids()), null);
        Path directory = world.resolve("data/rs_integration/unified_disks").resolve(disk.toString());
        List<Path> paths;
        try (var stream = Files.list(directory)) { paths = stream.sorted().toList(); }
        List<byte[]> original = paths.stream().map(path -> {
            try { return Files.readAllBytes(path); } catch (Exception e) { throw new AssertionError(e); }
        }).toList();

        server = mock(MinecraftServer.class); when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        ServerLevel level = mock(ServerLevel.class); when(level.getServer()).thenReturn(server);
        PlayerList players = mock(PlayerList.class); when(server.getPlayerList()).thenReturn(players);
        ServerPlayer player = mock(ServerPlayer.class);
        UnifiedDiskManager manager = UnifiedDiskManager.get(level);
        assertNull(manager.summary(disk));
        assertEquals("cthulhu_creatures:flesh_altar",
                ((TranslatableContents) manager.failure(disk).reason().getContents()).getArgs()[0]);
        manager.tick();
        verifyNoInteractions(player);
        when(players.getPlayer(owner)).thenReturn(player);
        manager.tick(); manager.tick(); manager.notifyFailure(disk, owner);
        verify(player, times(4)).sendSystemMessage(any(Component.class));
        manager.forgetTooltipPlayer(owner);
        manager.tick();
        verify(player, times(8)).sendSystemMessage(any(Component.class));
        manager.flush();
        UnifiedDiskManager.stop(server);
        for (int i = 0; i < paths.size(); i++) assertArrayEquals(original.get(i), Files.readAllBytes(paths.get(i)));
    }

    @Test void failureQueriesKeepEvictedHealthySummaryWithoutReloadingInventory() throws Exception {
        server = mock(MinecraftServer.class); when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        when(server.getTickCount()).thenReturn(1200);
        ServerLevel level = mock(ServerLevel.class); when(level.getServer()).thenReturn(server);
        UnifiedDiskManager manager = UnifiedDiskManager.get(level);
        UUID id = manager.create(UUID.randomUUID()).id();
        UnifiedDiskSummary summary = manager.summary(id);
        manager.tick();
        Files.delete(world.resolve("data/rs_integration/unified_disks").resolve(id.toString()).resolve("manifest.bin"));
        assertNull(manager.failure(id));
        assertEquals(summary, manager.summary(id));
        assertNull(manager.entry(id).core);
        assertNotNull(manager.failure(id));
        assertNull(manager.summary(id));
    }

    @AfterEach void stop() { if (server != null) UnifiedDiskManager.stop(server); }
}
