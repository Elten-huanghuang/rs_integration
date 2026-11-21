package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MalumAltarOutputTest extends BootstrapTest {
    private static final BlockPos ALTAR_POS = new BlockPos(1, 64, 2);
    private final ServerPlayer player = mock(ServerPlayer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final MalumBatchDelegate altar = new MalumBatchDelegate();
    private final ItemStack output = new ItemStack(Items.INK_SAC, 2);

    @BeforeAll
    static void initializeMod() {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @BeforeEach
    void prepareAltar() {
        when(player.serverLevel()).thenReturn(level);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.isLoaded(ALTAR_POS)).thenReturn(true);
        Reflect.setField(altar, "recipe", new ShapelessRecipe(
                ResourceLocation.fromNamespaceAndPath("test", "altar_ink"),
                "", CraftingBookCategory.MISC, output, NonNullList.create()));
        Reflect.setField(altar, "myPos", ALTAR_POS);
    }

    @ParameterizedTest
    @CsvSource({"false, 0", "false, 2", "true, 0", "true, 2"})
    void capturedOrMissingOutputLeavesExistingInkUntouched(boolean offhand, int capturedCount) {
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        var slots = offhand ? inventory.offhand : inventory.items;
        slots.set(0, output.copyWithCount(5));
        when(level.getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class), any())).thenReturn(List.of());

        var result = new OperationLifecycle(altar).collect(player, 1,
                () -> capturedCount == 0 ? List.of() : List.of(output.copyWithCount(capturedCount)));

        assertEquals(5, slots.get(0).getCount());
        assertEquals(capturedCount, result.stacks().stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test
    void directCollectionStillTakesOnlyTheExpectedAmountFromTheGround() {
        ItemEntity entity = mock(ItemEntity.class);
        ItemStack groundStack = output.copyWithCount(3);
        when(entity.getItem()).thenReturn(groundStack);
        when(level.getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class), any())).thenReturn(List.of(entity));

        var result = new OperationLifecycle(altar).collect(player, 1, List::of);

        assertEquals(1, result.stacks().size());
        assertEquals(2, result.stacks().get(0).getCount());
        assertEquals(1, groundStack.getCount());
        verify(entity, never()).discard();
    }
}
