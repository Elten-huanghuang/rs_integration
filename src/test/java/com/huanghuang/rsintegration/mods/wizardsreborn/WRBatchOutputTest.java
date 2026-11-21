package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.mixin.wizardsreborn.CrystalBlockOutputMixin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WRBatchOutputTest extends BootstrapTest {
    @BeforeAll
    static void initializeMod() {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            RSIntegrationMod.LOGGER.info("初始化 WR 产物回归测试");
        }
    }

    @Test
    void missingCrystalOutputDoesNotConsumeExistingPlayerItem() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        Inventory inventory = new Inventory(player);
        ItemStack expected = new ItemStack(Items.INK_SAC, 2);
        inventory.items.set(0, expected.copyWithCount(5));
        when(player.getInventory()).thenReturn(inventory);
        when(player.serverLevel()).thenReturn(level);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class))).thenReturn(List.of());

        WRBatchDelegate delegate = new WRBatchDelegate();
        Reflect.setField(delegate, "player", player);
        Reflect.setField(delegate, "myPos", BlockPos.ZERO);
        Reflect.setField(delegate, "machineType", WRBatchDelegate.MachineType.CRYSTAL_RITUAL);
        Reflect.setField(delegate, "recipe", new ShapelessRecipe(
                ResourceLocation.fromNamespaceAndPath("test", "crystal_output"),
                "", CraftingBookCategory.MISC, expected, NonNullList.create()));

        OperationOutputSource.Handoff handoff = delegate.outputSource().collectFinal(player);

        assertTrue(handoff.stacks().isEmpty());
        assertEquals(5, inventory.items.get(0).getCount());
    }

    @Test
    void nativeInfusionAcceptsOnlyItsOwnOutputOnceAndKeepsTheActualTag() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.serverLevel()).thenReturn(level);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        WRBatchDelegate delegate = new WRBatchDelegate();
        var machine = new CrystalBlockOutputMixin() {};
        Reflect.setField(delegate, "be", machine);
        Reflect.setField(delegate, "player", player);
        Reflect.setField(delegate, "myPos", BlockPos.ZERO);
        Reflect.setField(delegate, "machineType", WRBatchDelegate.MachineType.CRYSTAL_RITUAL);
        Reflect.setField(delegate, "nativeCrystalOutput", true);
        Reflect.setField(delegate, "recipe", new ShapelessRecipe(
                ResourceLocation.fromNamespaceAndPath("test", "native_infusion"),
                "", CraftingBookCategory.MISC, new ItemStack(Items.INK_SAC, 2), NonNullList.create()));

        var reception = TaskOutputReception.open();
        delegate.bindProduction(reception.writer());
        delegate.armCrystalOutput();
        assertNull(delegate.getOutputCaptureRegion());
        assertFalse(machine.rsi$receiveCrystalOutput(new ItemStack(Items.DIAMOND)));
        var actual = new ItemStack(Items.INK_SAC, 2);
        actual.getOrCreateTag().putString("variant", "infused");
        assertTrue(machine.rsi$receiveCrystalOutput(actual));
        assertFalse(machine.rsi$receiveCrystalOutput(actual));

        var result = new OperationLifecycle(delegate, reception).collect(player, 1, List::of);

        assertTrue(result.settlement().complete());
        assertEquals(1, result.stacks().size());
        assertEquals(2, result.stacks().get(0).getCount());
        assertTrue(ItemStack.isSameItemSameTags(actual, result.stacks().get(0)));
        verify(level, never()).getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class));
    }
}
