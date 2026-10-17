package com.huanghuang.rsintegration.magic;

import com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import com.hollingsworth.arsnouveau.common.block.tile.SourceJarTile;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ArsResonantSourceNativeInteropTest extends BootstrapTest {
    @Test
    void resonantTankEntersArsNativeSourceMachineChain() {
        assertTrue(SourceJarTile.class.isAssignableFrom(
                ArsResonantSourceTankBlockEntity.class));
        assertTrue(AbstractSourceMachine.class.isAssignableFrom(
                ArsResonantSourceTankBlockEntity.class));
    }

    @Test
    void arsNativeTransferWritesIntoRsBackedTank() {
        ArsResonantSourceTankBlockEntity tank = spy(tank());
        doReturn(Integer.MAX_VALUE).when(tank).getMaxSource();
        doReturn(0).when(tank).getSource();
        doReturn(0).when(tank).insert(anyInt());

        ISourceTile producer = mock(ISourceTile.class);
        doReturn(Integer.MAX_VALUE).when(producer).getTransferRate();
        doReturn(Integer.MAX_VALUE).when(producer).getSource();
        doReturn(0).when(producer).removeSource(Integer.MAX_VALUE);

        int moved = nativeTransferMachine().transferSource(producer, tank);

        assertEquals(Integer.MAX_VALUE, moved);
        verify(producer).removeSource(Integer.MAX_VALUE);
        verify(tank).addSource(Integer.MAX_VALUE);
        verify(tank).insert(Integer.MAX_VALUE);
    }

    @Test
    void unboundTankDoesNotAdvertiseAnUnavailableInventory() {
        ArsResonantSourceTankBlockEntity tank = tank();

        assertFalse(tank.isBound(), "测试罐体必须处于未绑定状态");
        assertFalse(tank.canAcceptSource(),
                "未绑定时没有可写入的 RS 库存，不能向 Ars 宣称可接收");
    }

    @Test
    void nativeInheritanceReplacesFeatureSpecificArsMixins() throws IOException {
        String mixins = Files.readString(
                Path.of("src/main/resources/rs_integration.mixins.json"));
        assertFalse(mixins.contains("arsnouveau.SourceUtilMixin"));
        assertFalse(mixins.contains("arsnouveau.RelaySourceInteropMixin"));
        assertFalse(mixins.contains("arsnouveau.RelaySplitterSourceInteropMixin"));
    }

    private static ArsResonantSourceTankBlockEntity tank() {
        return new ArsResonantSourceTankBlockEntity(
                BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
    }

    private static AbstractSourceMachine nativeTransferMachine() {
        return new AbstractSourceMachine(BlockEntityType.CHEST, BlockPos.ZERO,
                Blocks.CHEST.defaultBlockState()) {
            @Override
            public int getTransferRate() {
                return Integer.MAX_VALUE;
            }

        };
    }
}
