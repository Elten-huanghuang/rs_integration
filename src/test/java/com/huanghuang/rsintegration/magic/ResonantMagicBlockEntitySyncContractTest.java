package com.huanghuang.rsintegration.magic;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ResonantMagicBlockEntitySyncContractTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void updatePacketCarriesBindingAndDisplaySnapshot() {
        BlockPos pos = new BlockPos(4, 5, 6);
        var entity = new ResonantMagicBlockEntity(BlockEntityType.CHEST, pos,
                Blocks.CHEST.defaultBlockState(), MagicResourceKind.MANA, 1_000_000);
        CompoundTag binding = new CompoundTag();
        binding.putString("dim", "minecraft:overworld");
        binding.putInt("x", 1);
        binding.putInt("y", 2);
        binding.putInt("z", 3);
        entity.setBinding(binding);

        ClientboundBlockEntityDataPacket packet = entity.getUpdatePacket();

        assertEquals(pos, packet.getPos());
        assertEquals(binding, packet.getTag().getCompound("RSBinding"));
        assertEquals("botania:mana", packet.getTag().getString("Kind"));
        assertEquals(1_000_000, packet.getTag().getLong("Capacity"));
    }

    @Test
    void resonantContainersUseNetworkScaleCapacity() throws IOException {
        BlockPos pos = new BlockPos(4, 5, 6);
        var manaPool = new ResonantMagicBlockEntity(BlockEntityType.CHEST, pos,
                Blocks.CHEST.defaultBlockState(), MagicResourceKind.MANA,
                MagicResourceKind.MANA.capacity());

        assertEquals(Long.MAX_VALUE, manaPool.resourceCapacity());
        assertEquals(Long.MAX_VALUE, MagicResourceKind.SOURCE.capacity());
        String sourceAdapter = Files.readString(Path.of("src/main/java/com/huanghuang/"
                + "rsintegration/magic/ArsResonantSourceTankBlockEntity.java"));
        assertTrue(sourceAdapter.contains("MagicResourceKind.SOURCE.capacity()"));
        assertTrue(sourceAdapter.contains("getTransferRate() { return Integer.MAX_VALUE; }"));
        assertTrue(sourceAdapter.contains("return getSource();"));
        assertTrue(sourceAdapter.contains("extends SourceJarTile"));
        assertTrue(sourceAdapter.contains("implements ResonantMagicStorage"));
        String mixinConfig = Files.readString(Path.of("src/main/resources/rs_integration.mixins.json"));
        assertFalse(mixinConfig.contains("arsnouveau.SourceUtilMixin"));
        String mixinPlugin = Files.readString(Path.of("src/main/java/com/huanghuang/"
                + "rsintegration/mixin/plugin/RSIntegrationMixinPlugin.java"));
        assertFalse(mixinPlugin.contains(".arsnouveau.SourceUtilMixin"));
    }

    @Test
    void capacitySnapshotIsPersistedForClientHud() {
        var entity = new ResonantMagicBlockEntity(BlockEntityType.CHEST, BlockPos.ZERO,
                Blocks.CHEST.defaultBlockState(), MagicResourceKind.MANA, 42);

        CompoundTag saved = new CompoundTag();
        entity.saveAdditional(saved);

        assertEquals(42, saved.getLong("Capacity"));
    }

    @Test
    void displaySnapshotKeepsLongAndReadsLegacyIntRecords() {
        var entity = new ResonantMagicBlockEntity(BlockEntityType.CHEST, BlockPos.ZERO,
                Blocks.CHEST.defaultBlockState(), MagicResourceKind.MANA, 42);
        long amount = Integer.MAX_VALUE + 123L;
        long capacity = Long.MAX_VALUE;
        CompoundTag modern = new CompoundTag();
        modern.putLong("DisplayAmount", amount);
        modern.putLong("Capacity", capacity);

        entity.load(modern);

        assertEquals(amount, entity.currentAmount());
        assertEquals(capacity, entity.resourceCapacity());
        CompoundTag saved = new CompoundTag();
        entity.saveAdditional(saved);
        assertEquals(amount, saved.getLong("DisplayAmount"));
        assertEquals(capacity, saved.getLong("Capacity"));

        CompoundTag legacy = new CompoundTag();
        legacy.putInt("DisplayAmount", Integer.MAX_VALUE);
        legacy.putInt("Capacity", Integer.MAX_VALUE);
        entity.load(legacy);
        assertEquals(Integer.MAX_VALUE, entity.currentAmount());
        assertEquals(Integer.MAX_VALUE, entity.resourceCapacity());
    }

    @Test
    void arsSourceTileUsesMaxTransferAndSignedSourceOperations() {
        ArsResonantSourceTankBlockEntity tank = spy(new ArsResonantSourceTankBlockEntity(
                BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState()));
        doReturn(1_000L).when(tank).resourceCapacity();
        doReturn(100L).when(tank).currentAmount();
        doReturn(0).when(tank).insert(anyInt());
        doReturn(0).when(tank).extract(anyInt());

        assertEquals(Integer.MAX_VALUE, tank.getTransferRate());
        tank.addSource(40);
        tank.removeSource(60);
        tank.addSource(-20);
        tank.removeSource(-30);

        verify(tank).insert(40);
        verify(tank).extract(60);
        verify(tank).extract(20);
        verify(tank).insert(30);
    }
}
