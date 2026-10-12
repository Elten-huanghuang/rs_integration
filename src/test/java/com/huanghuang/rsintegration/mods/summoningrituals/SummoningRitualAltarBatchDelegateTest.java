package com.huanghuang.rsintegration.mods.summoningrituals;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummoningRitualAltarBatchDelegateTest extends BootstrapTest {
    @BeforeAll
    static void initializeModContext() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @BeforeAll
    static void initializeCapabilityBoundary() throws ClassNotFoundException {
        // 单测没有 Forge 转换器，仅替代能力令牌注册；投料仍使用真实库存实现。
        try (MockedStatic<CapabilityManager> manager = mockStatic(CapabilityManager.class)) {
            manager.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(Capability.class));
            Class.forName(ForgeCapabilities.class.getName());
        }
    }

    @Test
    void successfulPreparationLeavesMaterialsOnIdleAltar() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());

        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.isCraftComplete(fixture.level));
        assertTrue(fixture.delegate.collectResult(fixture.player).isEmpty());
        fixture.delegate.onBatchFinished(fixture.player);

        assertEquals(Items.DIAMOND, fixture.inventory.getStackInSlot(0).getItem());
        assertEquals(Items.STICK, fixture.inventory.getStackInSlot(2).getItem());
        verify(fixture.altar, times(1)).getCapability(ForgeCapabilities.ITEM_HANDLER);
    }

    @Test
    void ritualLockingItemCapabilityAfterPlacementDoesNotFailPreparation() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        fixture.hideItemCapability();

        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.isCraftComplete(fixture.level));
        fixture.delegate.onBatchFinished(fixture.player);

        assertEquals(1, fixture.inventory.getStackInSlot(0).getCount());
        assertEquals(1, fixture.inventory.getStackInSlot(2).getCount());
        verify(fixture.altar, times(1)).getCapability(ForgeCapabilities.ITEM_HANDLER);
    }

    @Test
    void ritualMayConsumePreparedMaterialsBeforeCompletionObservation() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        fixture.hideItemCapability();
        fixture.inventory.setStackInSlot(0, ItemStack.EMPTY);
        fixture.inventory.setStackInSlot(2, ItemStack.EMPTY);

        assertEquals(CraftPhase.DONE, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.delegate.collectResult(fixture.player).isEmpty());
        assertFalse(fixture.delegate.publishesDeclaredGraphOutputs());
        verify(fixture.altar, times(1)).getCapability(ForgeCapabilities.ITEM_HANDLER);
    }

    @Test
    void unavailableCapabilityBeforePlacementStillRejectsPreparation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.hideItemCapability();

        assertFalse(fixture.start());
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.inventory.getStackInSlot(2).isEmpty());
    }

    @Test
    void rejectedMaterialReservationCannotCompletePreparation() throws Exception {
        Fixture fixture = new Fixture();

        assertFalse(fixture.delegate.tryStartWithMaterials(fixture.player,
                List.of(new ItemStack(Items.STICK)), new ExtractionLedger()));
        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
    }

    @Test
    void replacedAltarCannotCompletePreparationOrLoseItsOwnMaterials() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        BlockEntity replacement = mock(BlockEntity.class);
        when(replacement.getCapability(ForgeCapabilities.ITEM_HANDLER))
                .thenReturn(fixture.itemCapability);
        when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(replacement);

        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
        fixture.delegate.onBatchFailed(fixture.player, "altar replaced");

        assertEquals(1, fixture.inventory.getStackInSlot(0).getCount());
        assertEquals(1, fixture.inventory.getStackInSlot(2).getCount());
    }

    @Test
    void failureOnOriginalIdleAltarStillRecoversPlacedMaterials() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());

        fixture.delegate.onBatchFailed(fixture.player, "cancelled before completion");

        assertTrue(fixture.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.inventory.getStackInSlot(2).isEmpty());
        assertEquals(2, fixture.delegate.failureRecoveredInputs().size());
        assertTrue(fixture.delegate.physicalFailureCleanupCompleted());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
    }

    @Test
    void missingOrRemovedAltarStillFailsAfterPlacement() throws Exception {
        Fixture missing = new Fixture();
        assertTrue(missing.start());
        when(missing.level.getBlockEntity(BlockPos.ZERO)).thenReturn(null);
        assertEquals(CraftPhase.FAILED, missing.delegate.observeCraft(missing.level).phase());

        Fixture removed = new Fixture();
        assertTrue(removed.start());
        when(removed.altar.isRemoved()).thenReturn(true);
        assertEquals(CraftPhase.FAILED, removed.delegate.observeCraft(removed.level).phase());
    }

    @Test
    void unloadedAltarStillFailsAfterPlacement() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.start());
        when(fixture.level.hasChunkAt(BlockPos.ZERO)).thenReturn(false);

        assertEquals(CraftPhase.FAILED, fixture.delegate.observeCraft(fixture.level).phase());
        assertFalse(fixture.delegate.isCraftComplete(fixture.level));
    }

    private static final class Fixture {
        final SummoningRitualAltarBatchDelegate delegate = new SummoningRitualAltarBatchDelegate();
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final BlockEntity altar = mock(BlockEntity.class);
        final ItemStackHandler inventory = new ItemStackHandler(3);
        final LazyOptional<IItemHandler> itemCapability = LazyOptional.of(() -> inventory);

        Fixture() throws Exception {
            when(player.serverLevel()).thenReturn(level);
            when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);
            when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(altar);
            when(altar.getCapability(ForgeCapabilities.ITEM_HANDLER)).thenReturn(itemCapability);
            Recipe<?> recipe = mock(Recipe.class);
            when(recipe.getId()).thenReturn(new ResourceLocation("summoningrituals", "test_ritual"));

            // 预设校验完成后的配方与库存快照，直接验证实际投料和完成观察入口。
            set(delegate, "altarPos", BlockPos.ZERO);
            set(delegate, "recipe", recipe);
            set(delegate, "catalyst", Ingredient.of(Items.STICK));
            set(delegate, "recipeInputs", List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)));
            set(delegate, "preparedSnapshot", List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
            delegate.setStorageEndpoint(mock(CraftStorageEndpoint.class));
        }

        boolean start() {
            return delegate.tryStartWithMaterials(player,
                    List.of(new ItemStack(Items.STICK), new ItemStack(Items.DIAMOND)),
                    new ExtractionLedger());
        }

        void hideItemCapability() {
            // 模拟原祭坛在仪式运行时不再暴露 ITEM_HANDLER，保留其真实库存。
            when(altar.getCapability(ForgeCapabilities.ITEM_HANDLER)).thenReturn(LazyOptional.empty());
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
