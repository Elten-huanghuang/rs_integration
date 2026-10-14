package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StoragePermissionResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageThreadGuard;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.UnorderedStackHandlerRemoveZero;
import com.wintercogs.beyonddimensions.api.storage.key.impl.FluidStackKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BeyondDimensionsFluidSessionTest extends BootstrapTest {
    private static final StorageBackendId BD = new StorageBackendId("beyonddimensions");
    private MockedStatic<StorageThreadGuard> threads;
    private Item tokenItem;
    private Object originalToken;
    private Field registryValue;
    private UnorderedStackHandlerRemoveZero storage;
    private BeyondDimensionsSession session;
    private ServerPlayer player;

    @BeforeEach void setup() throws Exception {
        tokenItem = InkFluidTestFixtures.tokenItem();
        registryValue = RegistryObject.class.getDeclaredField("value");
        registryValue.setAccessible(true);
        originalToken = registryValue.get(ModItems.ALCHEMIST_INK_FLUID);
        registryValue.set(ModItems.ALCHEMIST_INK_FLUID, tokenItem);
        threads = mockStatic(StorageThreadGuard.class);
        storage = new UnorderedStackHandlerRemoveZero(AbstractUnorderedStackHandler.UiTimestampPolicy.NONE);
        session = spy(new BeyondDimensionsSession(new Network(storage), new StorageReference(BD, "1")));
        player = mock(ServerPlayer.class);
        doReturn(StoragePermissionResult.allowed()).when(session).checkPermission(eq(player), any());
    }

    @AfterEach void release() throws Exception {
        threads.close();
        registryValue.set(ModItems.ALCHEMIST_INK_FLUID, originalToken);
    }

    @Test void fullAndNarrowSnapshotsExposeExactFluidNbtAndLongMilliBucketCounts() {
        FluidStack water = water("first", 1);
        long amount = (long) Integer.MAX_VALUE + 5000;
        storage.insert(new FluidStackKey(water), amount, false);
        storage.insert(new FluidStackKey(water("second", 1)), 750, false);
        for (Set<Item> filter : new Set[] {null, Set.of(tokenItem)}) {
            var snapshot = session.snapshotItems(player, filter).snapshot().orElseThrow();
            assertEquals(amount, snapshot.countExact(session.itemKey(InkFluidSupport.token(water))));
            assertEquals(750, snapshot.countExact(session.itemKey(InkFluidSupport.token(water("second", 1)))));
        }
        assertEquals(0, session.snapshotItems(player, Set.of(Items.BUCKET)).snapshot().orElseThrow().items().size());
        assertEquals(0, session.countDerivedContainer(player, new ItemStack(Items.WATER_BUCKET)),
                "装桶由配方计划消耗空桶和流体，不能同时虚增水桶库存");
        assertEquals(1, water.getAmount());
    }

    @Test void simulateThenExtractMatchingAndRefundUseNativeFluidRatherThanTokenItems() {
        FluidStack water = water("chosen", 1);
        FluidStack other = water("other", 1);
        storage.insert(new FluidStackKey(water), 2000, false);
        storage.insert(new FluidStackKey(other), 3000, false);
        ItemStack token = InkFluidSupport.token(water);
        var ingredient = StrictNBTIngredient.of(token);
        var probe = session.extractMatching(player, ingredient, 750, true);
        assertTrue(probe.complete());
        assertEquals(2000, count(water));
        var extraction = session.extractMatching(player, ingredient, 750, false);
        assertTrue(extraction.complete());
        ItemStack extracted = extraction.extractedStacks().get(0);
        assertEquals(750, extracted.getCount());
        assertTrue(InkFluidSupport.fluid(extracted).isFluidEqual(water));
        assertEquals(1250, count(water));
        assertEquals(3000, count(other));
        assertTrue(session.insert(player, extracted, true).complete());
        assertEquals(1250, count(water));
        assertTrue(session.insert(player, extracted, false).complete());
        assertEquals(2000, count(water));
        assertTrue(storage.getStorage().stream().allMatch(entry -> entry.key() instanceof FluidStackKey));
    }

    @Test void partialExtractionAndPartialCapacityPreserveKnownFluidRemainders() {
        storage = new UnorderedStackHandlerRemoveZero(AbstractUnorderedStackHandler.UiTimestampPolicy.NONE, 1000, 8);
        session = spy(new BeyondDimensionsSession(new Network(storage), new StorageReference(BD, "1")));
        doReturn(StoragePermissionResult.allowed()).when(session).checkPermission(eq(player), any());
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        storage.insert(new FluidStackKey(water), 600, false);
        var extracted = session.extractExact(player, session.itemKey(InkFluidSupport.token(water)), 1000, false);
        assertEquals(600, extracted.transferredAmount().orElseThrow());
        assertEquals(0, count(water));
        var inserted = session.insert(player, InkFluidSupport.token(new FluidStack(Fluids.WATER, 1500)), false);
        assertEquals(1000, inserted.transferredAmount().orElseThrow());
        ItemStack remainder = inserted.remainder().orElseThrow();
        assertEquals(500, remainder.getCount());
        assertTrue(InkFluidSupport.fluid(remainder).isFluidEqual(water));
    }

    @Test void deniedAccessAndOversizedRequestsDoNotMutateStorage() {
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        storage.insert(new FluidStackKey(water), 2000, false);
        doReturn(StoragePermissionResult.denied()).when(session).checkPermission(player, StoragePermission.EXTRACT);
        assertFalse(session.extractExact(player, session.itemKey(InkFluidSupport.token(water)), 1000, false).complete());
        assertFalse(session.extractExact(player, session.itemKey(InkFluidSupport.token(water)),
                (long) Integer.MAX_VALUE + 1, false).complete());
        assertEquals(2000, count(water));
    }

    private long count(FluidStack fluid) { return storage.getStackByKey(new FluidStackKey(fluid)).amount(); }
    private static FluidStack water(String variant, int amount) {
        FluidStack fluid = new FluidStack(Fluids.WATER, amount);
        fluid.getOrCreateTag().putString("variant", variant);
        return fluid;
    }

    public record Network(UnorderedStackHandlerRemoveZero storage) {
        public Object getUnifiedStorage() { return storage; }
    }
}
