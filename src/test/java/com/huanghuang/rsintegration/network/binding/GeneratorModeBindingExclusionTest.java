package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.machine.FurnaceMachineAccess;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 更多熔炉的发电机模式必须在递归合成与机器枚举中被明确排除。 */
class GeneratorModeBindingExclusionTest extends BootstrapTest {
    private static final BlockPos POS = new BlockPos(3, 64, -7);
    private static final String IRON_FURNACE_KEY =
            "ironfurnaces_furnace||block.ironfurnaces.iron_furnace";
    private static final String VANILLA_FURNACE_KEY =
            "vanilla_furnace||block.minecraft.furnace";

    private ServerLevel levelWith(BlockEntity entity) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.isLoaded(POS)).thenReturn(true);
        when(level.getBlockEntity(POS)).thenReturn(entity);
        return level;
    }

    @Test
    void generatorModeIronFurnaceIsExcluded() {
        BlockIronFurnaceTileBase furnace = mock(BlockIronFurnaceTileBase.class);
        when(furnace.isGenerator()).thenReturn(true);
        ServerLevel level = levelWith(furnace);

        assertFalse(AltarBindingRegistry.isCraftModeUsable(level, POS, IRON_FURNACE_KEY));
        assertTrue(FurnaceMachineAccess.isGeneratorMode(furnace));
    }

    @Test
    void ordinaryModeIronFurnaceStaysUsable() {
        BlockIronFurnaceTileBase furnace = mock(BlockIronFurnaceTileBase.class);
        when(furnace.isGenerator()).thenReturn(false);
        ServerLevel level = levelWith(furnace);

        assertTrue(AltarBindingRegistry.isCraftModeUsable(level, POS, IRON_FURNACE_KEY));
        assertFalse(FurnaceMachineAccess.isGeneratorMode(furnace));
    }

    @Test
    void nonIronFurnaceBindingIsNotAffected() {
        ServerLevel level = levelWith(mock(AbstractFurnaceBlockEntity.class));

        assertTrue(AltarBindingRegistry.isCraftModeUsable(level, POS, VANILLA_FURNACE_KEY));
    }

    @Test
    void unloadedChunkKeepsBindingUsable() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.isLoaded(POS)).thenReturn(false);

        assertTrue(AltarBindingRegistry.isCraftModeUsable(level, POS, IRON_FURNACE_KEY));
    }
}
