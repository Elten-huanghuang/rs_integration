package com.huanghuang.rsintegration.mods.aetherworks.client;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeverBinderTest extends BootstrapTest {
    @Test
    void missingOrReplacedLeverIsNotReadAsPowered() {
        assertFalse(LeverBinder.isPoweredLever(Blocks.AIR.defaultBlockState()));
        assertFalse(LeverBinder.isPoweredLever(Blocks.STONE.defaultBlockState()));
        assertFalse(LeverBinder.isPoweredLever(Blocks.LEVER.defaultBlockState()));
        assertTrue(LeverBinder.isPoweredLever(
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, true)));
    }
}
