package com.huanghuang.rsintegration.mods.farmersdelight;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CookingPotInputRecoveryTest extends BootstrapTest {
    private static final BlockPos POS = new BlockPos(1, 64, 2);
    private static final ResourceLocation RECIPE = ResourceLocation.parse("test:enchanted_serving_container");

    @BeforeAll
    static void initializeRuntime() throws ClassNotFoundException {
        var config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @Test
    void nativePotUsesTheEnchantedContainerToServeTheMeal() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(64, Items.STICK);
        ItemStack enchanted = enchantedContainer();
        try (var tickets = mockStatic(ForcedChunkTicketManager.class);
             var ledger = ExtractionLedger.external()) {
            fixture.prepare();
            assertTrue(fixture.delegate.startPreparedOperation(fixture.player,
                    List.of(new ItemStack(Items.CARROT, 2), enchanted), ledger));
            assertTrue(ItemStack.matches(enchanted, fixture.slots.getStackInSlot(7)));

            // 模拟烹饪计时结束，再让原生锅实际使用容器并移交成品。
            CookingPotBlockEntity nativePot = mock(CookingPotBlockEntity.class, CALLS_REAL_METHODS);
            Field inventory = CookingPotBlockEntity.class.getDeclaredField("inventory");
            inventory.setAccessible(true);
            inventory.set(nativePot, fixture.slots);
            Field container = CookingPotBlockEntity.class.getDeclaredField("mealContainerStack");
            container.setAccessible(true);
            container.set(nativePot, fixture.recipe.getOutputContainer());
            fixture.slots.setStackInSlot(0, ItemStack.EMPTY);
            ItemStack meal = new ItemStack(Items.APPLE, 2);
            fixture.slots.setStackInSlot(6, meal.copy());
            Method serve = CookingPotBlockEntity.class.getDeclaredMethod("useStoredContainersOnMeal");
            serve.setAccessible(true);
            serve.invoke(nativePot);

            assertTrue(fixture.slots.getStackInSlot(7).isEmpty());
            assertTrue(fixture.delegate.isMachineCraftFinished(fixture.level, fixture.machine));
            assertStacks(List.of(meal), fixture.delegate.outputSource().collectFinal(fixture.player).stacks());
        }
    }

    @Test
    void enchantedContainerStartsAndKeepsItsOriginalNbt() {
        Fixture fixture = new Fixture(64, Items.STICK);
        ItemStack enchanted = enchantedContainer();
        try (var tickets = mockStatic(ForcedChunkTicketManager.class);
             var ledger = ExtractionLedger.external()) {
            fixture.prepare();
            assertTrue(fixture.delegate.startPreparedOperation(fixture.player,
                    List.of(new ItemStack(Items.CARROT, 2), enchanted), ledger));
            assertTrue(ItemStack.matches(enchanted, fixture.slots.getStackInSlot(7)));
            assertTrue(ItemStack.matches(new ItemStack(Items.CARROT, 2), fixture.slots.getStackInSlot(0)));

            // 已消费的材料不能按模板重建；只退机器中仍实际存在的容器。
            fixture.slots.setStackInSlot(0, ItemStack.EMPTY);
            fixture.delegate.recover(fixture.player, "cancelled");
            assertStacks(List.of(enchanted), fixture.delegate.failureRecoveredInputs());
            assertTrue(fixture.slots.getStackInSlot(7).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedGraphStartRefundsUntouchedAndPartiallyInsertedStacksExactlyOnce(boolean partialInsert)
            throws ReflectiveOperationException {
        ItemStack container = partialInsert ? enchantedContainer() : new ItemStack(Items.BOWL, 2);
        Fixture fixture = new Fixture(partialInsert ? 1 : 64, container.getItem());
        Field server = ServerPlayer.class.getField("server");
        server.setAccessible(true);
        server.set(fixture.player, mock(MinecraftServer.class));
        List<ItemStack> supplied = List.of(new ItemStack(Items.CARROT, 2), container);
        List<ItemStack> storage = new ArrayList<>(supplied.stream().map(ItemStack::copy).toList());
        StorageBackendId backend = new StorageBackendId("test");
        StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        CraftStorageEndpoint endpoint = () -> session;
        when(session.reference()).thenReturn(new StorageReference(backend, "pot-recovery"));
        when(session.snapshotItems(fixture.player)).thenAnswer(call -> StorageSnapshotResult.success(
                new StorageSnapshot(backend, storage.stream().map(stack -> new StoredItem(
                        StorageItemKey.fromItemStack(backend, stack), stack.getCount())).toList())));
        when(session.extractMatching(eq(fixture.player), any(Ingredient.class), anyLong(), eq(false)))
                .thenAnswer(call -> {
                    Ingredient ingredient = call.getArgument(1);
                    long count = call.getArgument(2);
                    List<ItemStack> extracted = storage.stream().filter(ingredient)
                            .filter(stack -> stack.getCount() == count).limit(1).toList();
                    storage.removeAll(extracted);
                    return StorageOperationResult.extracted(StorageOperationMode.PERFORM, count, extracted);
                });
        when(session.insert(eq(fixture.player), any(ItemStack.class), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            storage.add(stack.copy());
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        });
        fixture.delegate.setStorageEndpoint(endpoint);

        try (var tickets = mockStatic(ForcedChunkTicketManager.class);
             var ledger = ExtractionLedger.external()) {
            fixture.prepare();
            for (ItemStack stack : supplied) {
                assertFalse(ledger.reserveFromEndpoint(Ingredient.of(stack), stack.getCount(),
                        endpoint, fixture.player).isEmpty());
            }
            assertTrue(ledger.commitToEndpoint(endpoint, fixture.player));
            assertTrue(storage.isEmpty());

            ModType type = ModType.CUSTOM_GUI;
            var machine = new BoundMachine(Level.OVERWORLD.location(), POS, type, null);
            var group = new ParallelCraftGroup(List.of(machine), type, RECIPE, fixture.player,
                    1, false, fixture.delegate.concurrencyCapabilities(), endpoint,
                    1, ignored -> fixture.delegate, fixture.delegate);
            var materials = new CountedOperationMaterials.Builder(1, 1);
            var pool = new CountedOperationMaterials.Pool().add(CountedOperationMaterials.Origin.INITIAL, supplied);
            group.materialPlan().entries().forEach(entry -> materials.add(entry, pool));
            group.setCountedOperationMaterials(materials.build());

            assertTrue(group.start(OperationStartContext.chainReserved(fixture.player, ledger,
                    MaterialPlan.none(), List.of(), InputBufferPlan.none())));
            assertTrue(group.isDraining());
            assertTrue(fixture.slots.getStackInSlot(0).isEmpty(), "启动失败不能留下半组投料继续烹饪");
            assertTrue(fixture.slots.getStackInSlot(7).isEmpty());
            group.refundQueuedBulkMaterials(fixture.player);
            assertTrue(storage.isEmpty(), "已调用锅具启动，必须等其回收凭据再退款");
            group.recover(fixture.player, "failed cooking pot start");
            assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, group.failureTerminalClass());
            ledger.retainCommittedRefunds(group.failureRecoveredInputs());
            ledger.refundCommittedToEndpoint(endpoint, fixture.player);
            assertEquals(2, storage.stream().filter(stack -> stack.is(Items.CARROT)).mapToInt(ItemStack::getCount).sum());
            assertStacks(List.of(container), storage.stream().filter(stack -> !stack.is(Items.CARROT)).toList());

            group.recover(fixture.player, "repeated failure cleanup");
            ledger.refundCommittedToEndpoint(endpoint, fixture.player);
            assertEquals(4, storage.stream().mapToInt(ItemStack::getCount).sum());
        }
    }

    private static void assertStacks(List<ItemStack> expected, List<ItemStack> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(ItemStack.matches(expected.get(i), actual.get(i)));
        }
    }

    private static ItemStack enchantedContainer() {
        ItemStack stack = new ItemStack(Items.STICK, 2);
        stack.enchant(Enchantments.UNBREAKING, 1);
        return stack;
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final BlockEntity machine = mock(BlockEntity.class);
        final ItemStackHandler slots;
        final Item plannedContainer;
        final CookingPotRecipe recipe = new CookingPotRecipe(RECIPE, "", null,
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.CARROT)),
                new ItemStack(Items.APPLE, 2), new ItemStack(Items.STICK, 2), 0, 20);
        final CookingPotBatchDelegate delegate = new CookingPotBatchDelegate() {
            @Override protected boolean isSupportedRecipe(Recipe<?> candidate) { return candidate == Fixture.this.recipe; }
            @Override protected boolean isSupportedBlockEntity(BlockEntity actual) { return actual == machine; }
            @Override protected ItemStackHandler getInventory(BlockEntity actual) { return slots; }
            @Override protected boolean isHeated(BlockEntity actual) { return true; }
            @Override protected boolean inputBufferEnabled() { return false; }
            @Override protected ItemStack getRequiredContainer(Recipe<?> recipe, RegistryAccess access) {
                return new ItemStack(Items.STICK, 2);
            }
            @Override protected List<IngredientSpec> recipeSlots() {
                return List.of(new IngredientSpec(Ingredient.of(Items.CARROT), 2),
                        new IngredientSpec(Ingredient.of(plannedContainer), 2));
            }
        };

        Fixture(int ingredientSlotLimit, Item plannedContainer) {
            this.plannedContainer = plannedContainer;
            slots = new ItemStackHandler(9) {
                @Override public int getSlotLimit(int slot) { return slot == 0 ? ingredientSlotLimit : 64; }
            };
            when(machine.getBlockPos()).thenReturn(POS);
            when(player.level()).thenReturn(level);
            when(player.serverLevel()).thenReturn(level);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(level.hasChunkAt(POS)).thenReturn(true);
            when(level.getBlockEntity(POS)).thenReturn(machine);
            var manager = mock(RecipeManager.class);
            doReturn(Optional.of(recipe)).when(manager).byKey(RECIPE);
            when(level.getRecipeManager()).thenReturn(manager);
            delegate.setStorageEndpoint(mock(CraftStorageEndpoint.class));
        }

        void prepare() {
            assertTrue(delegate.validateAndInit(player, RECIPE, null, POS));
        }
    }
}
