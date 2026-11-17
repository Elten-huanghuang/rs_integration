package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SteamerMultiblockTest extends BootstrapTest {
    private static final BlockPos BASE = new BlockPos(10, 64, 10);
    private static final BooleanProperty CAPPED = BooleanProperty.create("capped");
    private static final BooleanProperty WATER = BooleanProperty.create("water");
    private static final ResourceLocation RECIPE_ID = new ResourceLocation("test", "steam_potato");

    @BeforeAll
    static void initializeRuntime() throws ClassNotFoundException {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class);
             var mods = mockStatic(ModList.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            Class.forName(RSIntegrationMod.class.getName());
            Class.forName(YHKReflection.class.getName());
        }
    }

    static Stream<Arguments> structures() {
        return Stream.of("youkaishomecoming", "youkaisfeasts")
                .flatMap(namespace -> Stream.of(1, 2, 3, 6, 7, 18)
                        .map(layers -> Arguments.of(namespace, layers)));
    }

    @ParameterizedTest
    @MethodSource("structures")
    void allRackBlocksShareOneBatchAndKeepTheirOwnInventoryOwner(String namespace, int layers)
            throws Exception {
        try (SteamerFixture fixture = new SteamerFixture(namespace, layers);
             ExtractionLedger ledger = new ExtractionLedger();
             var tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertTrue(fixture.delegate.supportsInputBuffer());
            int operations = Math.min(layers * 4, 64);
            InputBufferPlan plan = fixture.delegate.inputBufferPlan(64);
            assertTrue(plan.enabled());
            assertEquals(operations, plan.operations());
            fixture.delegate.useSharedLedger(ledger);
            assertTrue(fixture.delegate.startBufferedOperation(fixture.player, plan, ledger));
            assertEquals(operations, fixture.nonEmptyItems().size());
            fixture.assertCorrectOwners();

            List<TestItem> items = fixture.nonEmptyItems();
            for (int i = 0; i < items.size() - 1; i++) items.get(i).cook();
            assertFalse(fixture.delegate.isMachineCraftFinished(fixture.level, fixture.base()));
            items.get(items.size() - 1).cook();
            assertTrue(fixture.delegate.isMachineCraftFinished(fixture.level, fixture.base()));
            var output = fixture.delegate.collectStructuredResults(fixture.player);
            assertEquals(operations, output.stream().mapToInt(value -> value.stack().getCount()).sum());
            assertTrue(output.stream().allMatch(value -> value.stack().is(Items.BAKED_POTATO)));
            assertTrue(output.stream().allMatch(value -> value.stack().getTag().getInt("batch") == 7));
            assertTrue(fixture.delegate.collectAllResults(fixture.player).isEmpty());
            assertTrue(fixture.nonEmptyItems().isEmpty());
            fixture.assertCorrectOwners();
            fixture.delegate.onBatchFinished(fixture.player);
        }
    }

    @Test
    void bindingTheHighestSeparateLidUsesThePotBaseForItsOperationLease() throws Exception {
        try (SteamerFixture fixture = new SteamerFixture("youkaisfeasts", 18)) {
            BlockPos lid = BASE.above(SteamerBatchConfig.MAX_STRUCTURE_BLOCKS);
            assertTrue(fixture.delegate.validateAndInit(fixture.player, RECIPE_ID, null, lid));
            assertEquals(BASE, fixture.delegate.getMachinePos());
            assertEquals(BASE, fixture.delegate.getOperationMachinePos(lid));
            assertEquals(64, fixture.delegate.inputBufferPlan(64).operations());
        }
    }

    @Test
    void aPotWithoutAnyRackHasNoInputCapacity() throws Exception {
        try (SteamerFixture fixture = new SteamerFixture("youkaishomecoming", 0)) {
            assertFalse(fixture.delegate.supportsInputBuffer());
            assertFalse(fixture.delegate.inputBufferPlan(1).enabled());
        }
        assertEquals(0, SteamerBatchConfig.slotCapacity(0));
        assertEquals(12, SteamerBatchConfig.slotCapacity(3));
        assertEquals(72, SteamerBatchConfig.slotCapacity(18));
    }

    @Test
    void missingLidRejectsTheWholeStackBeforePlacingAnyInputs() throws Exception {
        try (SteamerFixture fixture = new SteamerFixture("youkaishomecoming", 3);
             ExtractionLedger ledger = new ExtractionLedger();
             var tickets = mockStatic(ForcedChunkTicketManager.class)) {
            fixture.setState(BASE.above(), false);
            assertFalse(fixture.delegate.supportsInputBuffer());
            assertFalse(fixture.delegate.startPreparedOperation(fixture.player,
                    List.of(new ItemStack(Items.POTATO)), ledger));
            assertTrue(fixture.nonEmptyItems().isEmpty());
        }
    }

    @Test
    void aGapStopsCollectionBeforeAnUnrelatedUpperRack() throws Exception {
        try (SteamerFixture fixture = new SteamerFixture("youkaishomecoming", 7)) {
            fixture.fillCookedItems();
            fixture.ids.put(BASE.above(), new ResourceLocation("minecraft", "air"));
            List<ItemStack> output = fixture.delegate.collectAllResults(fixture.player);
            assertEquals(8, output.size());
            assertEquals(20, fixture.nonEmptyItems().size());
        }
    }

    @Test
    void terminalCleanupClearsCollectibleItemsInUpperBlocks() throws Exception {
        try (SteamerFixture fixture = new SteamerFixture("youkaishomecoming", 7)) {
            fixture.fillCookedItems();
            fixture.delegate.onBatchFinished(fixture.player);
            assertTrue(fixture.nonEmptyItems().isEmpty());
            fixture.assertCorrectOwners();
        }
    }

    private static final class SteamerFixture implements AutoCloseable {
        private static final List<String> REFLECTION_FIELDS = List.of(
                "racksField", "tryAddItemMethod", "mayExtractMethod", "stackField",
                "rackSetStackMethod", "setChangedMethod", "isHeatedMethod",
                "waterPropertyField", "reflectionProbed");
        private final Class<?> previousMachineClass = YHKReflection.steamerBEClass;
        private final List<Object> previousValues = new ArrayList<>();
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final SteamerBatchDelegate delegate = spy(new SteamerBatchDelegate());
        private final Map<BlockPos, ResourceLocation> ids = new HashMap<>();
        private final Map<BlockPos, BlockState> states = new HashMap<>();
        private final Map<BlockPos, TestSteamer> machines = new HashMap<>();

        private SteamerFixture(String namespace, int layers) throws Exception {
            for (String name : REFLECTION_FIELDS) previousValues.add(field(name).get(null));
            YHKReflection.steamerBEClass = TestSteamer.class;
            field("reflectionProbed").set(null, true);
            field("racksField").set(null, TestSteamer.class.getField("racks"));
            field("tryAddItemMethod").set(null,
                    TestRack.class.getMethod("tryAddItem", TestSteamer.class, Level.class, ItemStack.class));
            field("mayExtractMethod").set(null, TestItem.class.getMethod("mayExtract"));
            field("stackField").set(null, TestItem.class.getField("stack"));
            field("rackSetStackMethod").set(null,
                    TestItem.class.getMethod("setStack", TestSteamer.class, ItemStack.class));
            field("setChangedMethod").set(null, TestItem.class.getMethod("setChanged"));
            field("isHeatedMethod").set(null, null);
            field("waterPropertyField").set(null, null);

            // 仅替代注册表与原生反射对象，结构遍历、批量规划和槽位读写都走真实委托。
            doAnswer(call -> ids.getOrDefault(call.getArgument(0),
                    new ResourceLocation("minecraft", "air"))).when(delegate).blockIdAt(any());
            when(level.hasChunkAt(any())).thenReturn(true);
            when(level.getBlockEntity(any())).thenAnswer(call -> machines.get(call.getArgument(0)));
            when(level.getBlockState(any())).thenAnswer(call -> states.getOrDefault(call.getArgument(0),
                    mock(BlockState.class)));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.level()).thenReturn(level);
            when(player.serverLevel()).thenReturn(level);

            int remaining = layers;
            int height = 0;
            do {
                BlockPos pos = BASE.above(height);
                int count = Math.min(remaining, height == 0 ? 2 : 4);
                TestSteamer machine = mock(TestSteamer.class);
                machine.racks = new ArrayList<>();
                for (int i = 0; i < count; i++) machine.racks.add(new TestRack(machine));
                machines.put(pos, machine);
                ids.put(pos, new ResourceLocation(namespace, height == 0 ? "steamer_pot" : "steamer_rack"));
                remaining -= count;
                setState(pos, remaining == 0 && count < (height == 0 ? 2 : 4));
                height++;
            } while (remaining > 0);
            BlockPos top = BASE.above(height - 1);
            if (!states.get(top).getValue(CAPPED)) {
                ids.put(top.above(), new ResourceLocation(namespace, "steamer_lid"));
                setState(top.above(), false);
            }
            SmokingRecipe recipe = new SmokingRecipe(RECIPE_ID, "", CookingBookCategory.FOOD,
                    Ingredient.of(Items.POTATO), new ItemStack(Items.BAKED_POTATO), 0, 100);
            RecipeManager manager = mock(RecipeManager.class);
            doReturn(Optional.of(recipe)).when(manager).byKey(RECIPE_ID);
            when(level.getRecipeManager()).thenReturn(manager);
            assertTrue(delegate.validateAndInit(player, RECIPE_ID, null, BASE));
        }

        private void setState(BlockPos pos, boolean capped) {
            BlockState state = mock(BlockState.class);
            when(state.getProperties()).thenReturn(Set.of(CAPPED, WATER));
            when(state.getValue(CAPPED)).thenReturn(capped);
            when(state.getValue(WATER)).thenReturn(true);
            states.put(pos, state);
        }

        private TestSteamer base() {
            return machines.get(BASE);
        }

        private List<TestItem> nonEmptyItems() {
            return machines.values().stream().flatMap(machine -> machine.racks.stream())
                    .flatMap(rack -> Stream.of(rack.list)).filter(item -> !item.stack.isEmpty()).toList();
        }

        private void fillCookedItems() {
            for (TestSteamer machine : machines.values()) {
                for (TestRack rack : machine.racks) {
                    for (TestItem item : rack.list) {
                        item.stack = new ItemStack(Items.POTATO);
                        item.cook();
                    }
                }
            }
        }

        private void assertCorrectOwners() {
            for (TestSteamer machine : machines.values()) {
                for (TestRack rack : machine.racks) {
                    for (TestItem item : rack.list) {
                        if (item.lastOwner != null) assertSame(machine, item.lastOwner);
                    }
                }
            }
        }

        private static Field field(String name) throws Exception {
            Field field = SteamerBatchDelegate.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @Override
        public void close() throws Exception {
            YHKReflection.steamerBEClass = previousMachineClass;
            for (int i = 0; i < REFLECTION_FIELDS.size(); i++) {
                field(REFLECTION_FIELDS.get(i)).set(null, previousValues.get(i));
            }
        }
    }

    public abstract static class TestSteamer extends BlockEntity {
        public List<TestRack> racks;

        protected TestSteamer() {
            super(null, BlockPos.ZERO, null);
        }
    }

    public static final class TestRack {
        public final TestItem[] list = {new TestItem(), new TestItem(), new TestItem(), new TestItem()};
        private final TestSteamer owner;

        private TestRack(TestSteamer owner) {
            this.owner = owner;
        }

        public boolean tryAddItem(TestSteamer machine, Level level, ItemStack stack) {
            assertSame(owner, machine);
            for (TestItem item : list) {
                if (!item.stack.isEmpty()) continue;
                item.setStack(machine, stack.split(1));
                return true;
            }
            return false;
        }
    }

    public static final class TestItem {
        public ItemStack stack = ItemStack.EMPTY;
        private boolean ready;
        private TestSteamer lastOwner;

        public boolean mayExtract() {
            return ready && !stack.isEmpty();
        }

        public void setStack(TestSteamer owner, ItemStack value) {
            lastOwner = owner;
            stack = value;
            ready = false;
        }

        public void setChanged() {}

        private void cook() {
            stack = new ItemStack(Items.BAKED_POTATO);
            stack.getOrCreateTag().putInt("batch", 7);
            ready = true;
        }
    }
}
