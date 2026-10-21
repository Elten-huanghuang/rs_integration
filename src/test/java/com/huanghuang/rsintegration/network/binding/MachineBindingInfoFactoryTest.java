package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.network.binding.MachineBindingService.PlayerEndpoint;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MachineBindingInfoFactoryTest extends BootstrapTest {
    @Test
    void mapsEndpointWithStableEndpointIdentity() {
        UUID bookId = UUID.randomUUID();
        MachineEndpointId endpointId = MachineEndpointId.create();
        PlayerEndpoint endpoint = new PlayerEndpoint(
                bookId, ResourceLocation.tryParse("minecraft:stone"),
                endpointId,
                new BindingStorage.BindingEntry(
                        ResourceLocation.tryParse("minecraft:overworld"),
                        new BlockPos(4, 5, 6), "block.minecraft.stone",
                        "minecraft:stone", ItemStack.EMPTY),
                17L);

        BindingInfo info = MachineBindingInfoFactory.fromEndpoint(
                endpoint, null);

        assertEquals("minecraft:stone", info.itemKey());
        assertEquals(bookId, info.bindingBookId());
        assertEquals(endpointId, info.endpointId());
        assertEquals("block.minecraft.stone", info.displayName());
    }

    @Test
    void backendSpecificItemKeyOverridesSourceItem() {
        PlayerEndpoint endpoint = new PlayerEndpoint(
                UUID.randomUUID(), ResourceLocation.tryParse("refinedstorage:grid"),
                MachineEndpointId.create(),
                new BindingStorage.BindingEntry(
                        ResourceLocation.tryParse("minecraft:overworld"),
                        BlockPos.ZERO, "block.minecraft.stone",
                        "minecraft:stone", ItemStack.EMPTY),
                1L);

        BindingInfo info = MachineBindingInfoFactory.fromEndpoint(
                endpoint, "beyonddimensions:net_terminal_item");

        assertEquals("beyonddimensions:net_terminal_item", info.itemKey());
    }

    @Test
    void bindingLookupEntryIsProjectedFromTheSameSyncedIdentity() {
        BindingInfo info = new BindingInfo(
                "example:terminal",
                ResourceLocation.tryParse("minecraft:overworld"),
                new BlockPos(7, 8, 9),
                "example||block.example.machine",
                "Machine", "example:machine", ItemStack.EMPTY);

        BindingStorage.BindingEntry entry =
                MachineBindingInfoFactory.toEntry(info);

        assertEquals(info.dim(), entry.dim());
        assertEquals(info.pos(), entry.pos());
        assertEquals(info.blockKey(), entry.blockKey());
        assertEquals(info.blockRegKey(), entry.blockRegKey());
        assertEquals(info.displayStack(), entry.displayStack());
    }
}
