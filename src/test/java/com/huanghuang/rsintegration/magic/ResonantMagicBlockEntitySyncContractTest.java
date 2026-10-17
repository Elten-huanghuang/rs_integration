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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(1_000_000, packet.getTag().getInt("Capacity"));
    }

    @Test
    void resonantContainersUseNetworkScaleCapacity() throws IOException {
        BlockPos pos = new BlockPos(4, 5, 6);
        var manaPool = new BotaniaResonantManaPoolBlockEntity(BlockEntityType.CHEST, pos,
                Blocks.CHEST.defaultBlockState());

        assertEquals(1_000_000_000, manaPool.resourceCapacity());
        assertEquals(1_000_000_000, MagicResourceKind.SOURCE.capacity());
        String sourceAdapter = Files.readString(Path.of("src/main/java/com/huanghuang/"
                + "rsintegration/magic/ArsResonantSourceTankBlockEntity.java"));
        assertTrue(sourceAdapter.contains("MagicResourceKind.SOURCE.capacity()"));
        assertTrue(sourceAdapter.contains("getTransferRate() { return Integer.MAX_VALUE; }"));
        assertTrue(sourceAdapter.contains("return getSource();"));
        assertTrue(sourceAdapter.contains("ISpecialSourceProvider"));
        assertTrue(sourceAdapter.contains("SourceManager.INSTANCE.addInterface(level, specialSourceProvider)"));
    }

    @Test
    void capacitySnapshotIsPersistedForClientHud() {
        var entity = new ResonantMagicBlockEntity(BlockEntityType.CHEST, BlockPos.ZERO,
                Blocks.CHEST.defaultBlockState(), MagicResourceKind.MANA, 42);

        CompoundTag saved = new CompoundTag();
        entity.saveAdditional(saved);

        assertEquals(42, saved.getInt("Capacity"));
    }
}
