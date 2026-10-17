package com.huanghuang.rsintegration.magic;

import com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import com.hollingsworth.arsnouveau.api.source.ISpecialSourceProvider;
import com.hollingsworth.arsnouveau.api.source.SourceManager;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ArsResonantSourceNativeInteropTest extends BootstrapTest {
    private static final String SOURCE_UTIL = "com.hollingsworth.arsnouveau.api.util.SourceUtil";
    private static final String SOURCE_UTIL_MIXIN =
            "com.huanghuang.rsintegration.mixin.arsnouveau.SourceUtilMixin";

    @Test
    void arsNativeSourceDiscoveryFindsTheResonantTankProvider() {
        BlockPos tankPos = new BlockPos(10, 64, 10);
        BlockPos machinePos = tankPos.east();
        Level level = mock(Level.class);
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation("rsintegration_test", "ars_source_" + System.nanoTime()));
        doReturn(dimension).when(level).dimension();
        doReturn(true).when(level).hasChunkAt(tankPos);
        doReturn(true).when(level).isLoaded(tankPos);

        ArsResonantSourceTankBlockEntity tank = new ArsResonantSourceTankBlockEntity(
                BlockEntityType.CHEST, tankPos, Blocks.CHEST.defaultBlockState());
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("DisplayAmount", 500);
        snapshot.putInt("Capacity", Integer.MAX_VALUE);
        tank.load(snapshot);
        tank.setLevel(level);
        doReturn(tank).when(level).getBlockEntity(tankPos);
        assertNotNull(tank.getLevel(), "测试需要把 level 注入共振魔源罐");
        assertFalse(level.isClientSide, "测试 level 必须是服务端");
        tank.registerSourceProvider();
        Set<ISpecialSourceProvider> registered = SourceManager.INSTANCE.getCopySetForLevel(level);
        ISpecialSourceProvider registeredProvider = registered.stream()
                .filter(provider -> provider.getSource() == tank)
                .findFirst().orElseThrow(() -> new AssertionError("共振魔源罐应已注册到 SourceManager"));
        assertNotNull(tank.getLevel());
        assertFalse(level.isClientSide);
        assertFalse(tank.isRemoved());
        assertTrue(level.hasChunkAt(tankPos));
        assertSame(tank, level.getBlockEntity(tankPos));
        assertSame(tank, registeredProvider.getSource(), "SourceManager 应注册当前共振罐的 provider");
        assertTrue(registeredProvider.isValid(), "已注册的 Ars provider 应有效");
        assertEquals(tankPos, registeredProvider.getCurrentPos());
        assertEquals(500, registeredProvider.getSource().getSource());
        List<ISpecialSourceProvider> providers = SourceManager.INSTANCE
                .canTakeSourceNearby(machinePos, level, 2);

        assertTrue(providers.stream().anyMatch(provider -> provider.getSource() == tank),
                "Ars 原生 SourceManager 应发现共振魔源罐");
    }

    @Test
    void arsNativeTransferUsesIntMaxRateAndWritesIntoTheTank() {
        BlockPos pos = BlockPos.ZERO;
        ArsResonantSourceTankBlockEntity tank = spy(new ArsResonantSourceTankBlockEntity(
                BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState()));
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
    void arsNativeProducerDiscoversTankAsReceivingTarget() {
        BlockPos tankPos = new BlockPos(4, 64, 4);
        BlockPos producerPos = tankPos.east();
        Level level = mock(Level.class);
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation("rsintegration_test", "ars_source_output_" + System.nanoTime()));
        doReturn(dimension).when(level).dimension();

        ArsResonantSourceTankBlockEntity tank = spy(new ArsResonantSourceTankBlockEntity(
                BlockEntityType.CHEST, tankPos, Blocks.CHEST.defaultBlockState()));
        tank.setLevel(level);
        doReturn(true).when(tank).canAcceptSource();
        doReturn(0).when(tank).getSource();
        doReturn(Integer.MAX_VALUE).when(tank).getMaxSource();
        doReturn(0).when(tank).addSource(anyInt());

        // SourceManager 是 Ars 产出机器的公共目标发现入口；用同样的 provider
        // 形状接入 spy，避免测试框架复制实体后 provider 捕获旧实例。
        ISpecialSourceProvider provider = new ISpecialSourceProvider() {
            @Override public ISourceTile getSource() { return tank; }
            @Override public boolean isValid() { return true; }
            @Override public BlockPos getCurrentPos() { return tankPos; }
        };
        SourceManager.INSTANCE.addInterface(level, provider);

        List<ISpecialSourceProvider> targets = SourceManager.INSTANCE
                .canGiveSourceNearby(producerPos, level, 5);
        ISpecialSourceProvider target = targets.stream()
                .filter(candidate -> candidate.getSource() == tank)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ars 产出机器应发现共振魔源罐作为接收目标"));

        ISourceTile producer = mock(ISourceTile.class);
        doReturn(Integer.MAX_VALUE).when(producer).getTransferRate();
        doReturn(Integer.MAX_VALUE).when(producer).getSource();
        doReturn(0).when(producer).removeSource(Integer.MAX_VALUE);

        int moved = nativeTransferMachine().transferSource(producer, target.getSource());

        assertEquals(Integer.MAX_VALUE, moved);
        verify(producer).removeSource(Integer.MAX_VALUE);
        verify(tank).addSource(Integer.MAX_VALUE);
    }

    @Test
    void arsMixinOnlyAppliesWhenAllPatchedLambdaTargetsExist() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();

        assertTrue(plugin.shouldApplyMixin(SOURCE_UTIL, SOURCE_UTIL_MIXIN),
                "本地 Ars 版本应包含三个已补丁的 SourceUtil 扫描入口");
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
