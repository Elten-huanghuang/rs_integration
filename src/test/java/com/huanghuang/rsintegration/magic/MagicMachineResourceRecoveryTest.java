package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MagicMachineResourceRecoveryTest extends BootstrapTest {
    @Test
    void extractsManaThroughNativeReceiverApi() {
        ResourceLocation dimensionId = new ResourceLocation("minecraft", "overworld");
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        RegistryAccess registries = mock(RegistryAccess.class);
        when(server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId))).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(level.registryAccess()).thenReturn(registries);
        when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);

        FakeManaMachine machine = new FakeManaMachine(100);
        machine.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(machine);

        MagicMachineResourceRecovery.Result result = MagicMachineResourceRecovery.recover(
                server, MagicResourceKind.MANA, dimensionId, BlockPos.ZERO, 40);

        assertSame(MagicMachineResourceRecovery.Status.EXTRACTED, result.status());
        assertEquals(40, result.amount());
        assertEquals(60, machine.getCurrentMana());
    }

    @Test
    void extractsSourceThroughNativeSourceTileApi() {
        ResourceLocation dimensionId = new ResourceLocation("minecraft", "overworld");
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId))).thenReturn(level);
        when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);

        FakeSourceMachine machine = new FakeSourceMachine(80);
        machine.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(machine);

        MagicMachineResourceRecovery.Result result = MagicMachineResourceRecovery.recover(
                server, MagicResourceKind.SOURCE, dimensionId, BlockPos.ZERO, 30);

        assertSame(MagicMachineResourceRecovery.Status.EXTRACTED, result.status());
        assertEquals(30, result.amount());
        assertEquals(50, machine.getSource());
    }

    private static final class FakeManaMachine extends BlockEntity {
        private int mana;

        private FakeManaMachine(int mana) {
            super(mock(BlockEntityType.class), BlockPos.ZERO, Blocks.AIR.defaultBlockState());
            this.mana = mana;
        }

        public int getCurrentMana() {
            return mana;
        }

        public void receiveMana(int amount) {
            mana = Math.max(0, mana + amount);
        }
    }

    private static final class FakeSourceMachine extends BlockEntity {
        private int source;

        private FakeSourceMachine(int source) {
            super(mock(BlockEntityType.class), BlockPos.ZERO, Blocks.AIR.defaultBlockState());
            this.source = source;
        }

        public int getSource() {
            return source;
        }

        public int removeSource(int amount) {
            int removed = Math.min(source, Math.max(0, amount));
            source -= removed;
            return source;
        }
    }
}
