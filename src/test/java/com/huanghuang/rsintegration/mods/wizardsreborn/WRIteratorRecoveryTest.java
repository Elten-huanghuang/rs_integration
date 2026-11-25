package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.batch.OperationInputRecovery;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WRIteratorRecoveryTest extends BootstrapTest {
    @Test
    void recoversOnlyHeldAndSurvivingSideMaterials() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        Pedestal center = pedestal(level, BlockPos.ZERO);
        Pedestal consumed = pedestal(level, BlockPos.ZERO.east());
        Pedestal surviving = pedestal(level, BlockPos.ZERO.west());
        center.inventory.setItem(0, new ItemStack(Items.ENCHANTED_BOOK));

        OperationInputRecovery recovery = new OperationInputRecovery();
        ItemStack first = new ItemStack(Items.IRON_INGOT);
        ItemStack second = new ItemStack(Items.LAPIS_LAZULI);
        second.getOrCreateTag().putString("test", "原始材料");
        ItemStack held = new ItemStack(Items.GOLD_INGOT);
        assertTrue(WRBatchDelegate.placeIteratorInput(consumed, player, first, recovery.hold(first)));
        assertTrue(WRBatchDelegate.placeIteratorInput(surviving, player, second, recovery.hold(second)));
        recovery.hold(held);
        consumed.inventory.removeItem(0, 1);

        var recovered = recovery.recover(level);
        assertEquals(2, recovered.size());
        assertTrue(ItemStack.matches(second, recovered.get(0)));
        assertTrue(ItemStack.matches(held, recovered.get(1)));
        assertTrue(surviving.inventory.getItem(0).isEmpty());
        assertTrue(center.inventory.getItem(0).is(Items.ENCHANTED_BOOK));
    }

    @Test
    void missingInventoryLeavesTheUnplacedMaterialHeld() {
        BlockEntity inaccessible = mock(BlockEntity.class);
        OperationInputRecovery recovery = new OperationInputRecovery();
        ItemStack held = new ItemStack(Items.IRON_INGOT);
        assertFalse(WRBatchDelegate.placeIteratorInput(inaccessible, mock(ServerPlayer.class),
                held, recovery.hold(held)));
        assertTrue(ItemStack.matches(held, recovery.recover(null).get(0)));
    }

    private static Pedestal pedestal(ServerLevel level, BlockPos pos) {
        Pedestal pedestal = new Pedestal(pos);
        when(level.hasChunkAt(pos)).thenReturn(true);
        when(level.getBlockEntity(pos)).thenReturn(pedestal);
        return pedestal;
    }

    private static final class Pedestal extends BlockEntity {
        private final SimpleContainer inventory = new SimpleContainer(1);

        private Pedestal(BlockPos pos) {
            super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState());
        }
    }
}
