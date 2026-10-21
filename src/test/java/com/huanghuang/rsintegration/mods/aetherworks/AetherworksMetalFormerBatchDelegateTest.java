package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.PreparationState;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageCapability;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import com.rekindled.embers.api.power.IEmberCapability;
import com.rekindled.embers.recipe.FluidIngredient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.sirplop.aetherworks.blockentity.IForge;
import net.sirplop.aetherworks.blockentity.MetalFormerBlockEntity;
import net.sirplop.aetherworks.recipe.MetalFormerContext;
import net.sirplop.aetherworks.recipe.MetalFormerRecipe;
import net.sirplop.aetherworks.recipe.IMetalFormerRecipe;
import net.sirplop.aetherworks.power.DefaultHeatCapability;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AetherworksMetalFormerBatchDelegateTest extends BootstrapTest {
    @Test
    void nativeForgeFlagIsFalseAndMetalFormerTickDoesNotCheckIt() throws Exception {
        // 不初始化模组注册表，直接验证实际测试依赖 JAR 的接口契约。
        ClassNode forge = nativeClass("AetherForgeBlockEntity");
        var flag = forge.methods.stream().filter(method -> method.name.equals("canFunction"))
                .findFirst().orElseThrow();
        assertEquals(List.of(Opcodes.ICONST_0, Opcodes.IRETURN),
                Arrays.stream(flag.instructions.toArray()).map(node -> node.getOpcode())
                        .filter(opcode -> opcode >= 0).toList());
        var tick = nativeClass("MetalFormerBlockEntity").methods.stream()
                .filter(method -> method.name.equals("onForgeTick")).findFirst().orElseThrow();
        assertFalse(Arrays.stream(tick.instructions.toArray())
                .anyMatch(node -> node instanceof MethodInsnNode call && call.name.equals("canFunction")));
    }

    @Test
    void nativeRecipeConsumesReservedFluidAndReturnsOnlyThePhysicalResult() throws Exception {
        Fixture fixture = new Fixture();
        fixture.attachPoweredForge();
        // 使用与原生 JAR 一致的接口返回值和冷炉温度，复现投料之前的启动阻塞。
        assertFalse(fixture.forge.canFunction());
        assertEquals(0.0, fixture.heat.getHeat());
        FluidIngredient fluid = mock(FluidIngredient.class);
        when(fluid.getFluids()).thenReturn(List.of(new FluidStack(Fluids.WATER, 360)));
        when(fluid.test(any(FluidStack.class))).thenAnswer(call -> {
            FluidStack stack = call.getArgument(0);
            return stack.getFluid() == Fluids.WATER && stack.getAmount() >= 360;
        });
        MetalFormerRecipe recipe = new MetalFormerRecipe(new ResourceLocation("aetherworks", "test_forming"),
                Ingredient.of(Items.DIAMOND), fluid, 2600, 300, new ItemStack(Items.EMERALD), false);
        set(fixture.delegate, "recipe", recipe);
        set(fixture.delegate, "required", List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1),
                new IngredientSpec(Ingredient.of(Items.POTION), 360)));
        set(fixture.delegate, "placedItem", ItemStack.EMPTY);
        set(fixture.delegate, "placedFluid", FluidStack.EMPTY);
        try (MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class);
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            tokens.when(() -> InkFluidSupport.fluid(any(ItemStack.class)))
                    .thenReturn(new FluidStack(Fluids.WATER, 360));
            assertTrue(fixture.delegate.startPreparedOperation(mock(ServerPlayer.class),
                    List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.POTION, 360)),
                    new ExtractionLedger()));
            assertEquals(360, fixture.tank.getFluidAmount());
            assertEquals(2600.0, fixture.heat.getHeat());
            assertEquals(Items.DIAMOND, fixture.machine.inventory.getStackInSlot(0).getItem());
            assertSame(recipe, fixture.machine.cachedRecipe);
            // 加工消耗和散热之后，仍应在下一次观察时恢复配方温度。
            fixture.heat.removeAmount(0.6, true);
            assertEquals(CraftPhase.WORKING, fixture.delegate.observeMachineCraft(
                    fixture.level, fixture.machine).phase());
            assertEquals(2600.0, fixture.heat.getHeat());
            assertTrue(fixture.delegate.collectResult(null).isEmpty());

            // 原生配方在同一刻消耗物品和流体，再把产物写回槽位 0。
            ItemStack produced = recipe.assemble(new MetalFormerContext(
                    fixture.machine.inventory, fixture.tank, 2600), RegistryAccess.EMPTY);
            fixture.machine.inventory.setStackInSlot(0, produced);
            assertEquals(0, fixture.tank.getFluidAmount());
            assertEquals(CraftPhase.DONE, fixture.delegate.observeMachineCraft(
                    fixture.level, fixture.machine).phase());
            assertEquals(Items.EMERALD, fixture.delegate.collectResult(null).getItem());
            assertTrue(fixture.machine.inventory.getStackInSlot(0).isEmpty());
            assertTrue(fixture.delegate.collectResult(null).isEmpty());
        }
    }

    @Test
    void coldNativeForgePreparesWithoutRequiringCanFunctionOrTouchingInputs() throws Exception {
        Fixture fixture = new Fixture();
        fixture.attachPoweredForge();
        assertFalse(fixture.forge.canFunction());
        assertEquals(PreparationState.READY, fixture.prepare().state());
        assertTrue(fixture.machine.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.tank.isEmpty());
        assertEquals(0.0, fixture.heat.getHeat());

        fixture.tank.setFluid(new FluidStack(Fluids.WATER, 90));
        assertEquals(PreparationState.RETRY, fixture.prepare().state());
        assertEquals(90, fixture.tank.getFluidAmount());
    }

    @Test
    void bdFluidEndpointCanPrepareMetalForming() throws Exception {
        Fixture fixture = new Fixture();
        fixture.attachPoweredForge();
        assertEquals(PreparationState.READY, fixture.prepare("beyonddimensions", true).state());
        assertEquals(PreparationState.FATAL, fixture.prepare("beyonddimensions", false).state());
        assertTrue(fixture.tank.isEmpty());
    }

    @Test
    void missingEmberStillWaitsBeforeTemperatureControlAndPlacement() throws Exception {
        Fixture fixture = new Fixture();
        fixture.attachPoweredForge();
        when(fixture.ember.getEmber()).thenReturn(0.0);
        assertEquals(PreparationState.RETRY, fixture.prepare().state());
        assertFalse(fixture.delegate.startPreparedOperation(mock(ServerPlayer.class),
                List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.POTION, 360)),
                new ExtractionLedger()));
        assertTrue(fixture.machine.inventory.getStackInSlot(0).isEmpty());
        assertTrue(fixture.tank.isEmpty());
        assertEquals(0.0, fixture.heat.getHeat());
    }

    @Test
    void sharedSlotDoesNotCompleteBeforeNbtTransformation() {
        ItemStack input = new ItemStack(Items.POTION);
        ItemStack output = input.copy();
        output.getOrCreateTag().putInt("gem_color", -8210433);
        assertFalse(AetherworksMetalFormerBatchDelegate.outputReady(input, output, input, 0, false, false));
        assertTrue(AetherworksMetalFormerBatchDelegate.outputReady(output, output, input, 0, false, true));
        assertFalse(AetherworksMetalFormerBatchDelegate.outputReady(output, output, input, 12, true, true));
    }

    @Test
    void unchangedStackRequiresEvidenceAndResultCountMustBeEnough() {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        assertFalse(AetherworksMetalFormerBatchDelegate.outputReady(stack, stack, stack, 0, false, false));
        assertTrue(AetherworksMetalFormerBatchDelegate.outputReady(stack, stack, stack, 0, true, false));
        assertTrue(AetherworksMetalFormerBatchDelegate.outputReady(stack, stack, stack, 0, false, true));
        assertFalse(AetherworksMetalFormerBatchDelegate.outputReady(stack, stack.copyWithCount(2),
                new ItemStack(Items.DIAMOND), 0, true, true));
    }

    @Test
    void cleanupRecoversOnlyOwnRemainingInputsEvenAfterForgeDetached() throws Exception {
        Fixture fixture = new Fixture();
        fixture.machine.inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        fixture.tank.setFluid(new FluidStack(Fluids.WATER, 500));
        try (MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> new ItemStack(Items.POTION, ((FluidStack) call.getArgument(0)).getAmount()));
            fixture.delegate.clearMachineState(fixture.machine, null);
            List<ItemStack> recovered = fixture.delegate.failureRecoveredInputs();
            assertEquals(2, recovered.size());
            assertEquals(1, recovered.get(0).getCount());
            assertEquals(360, recovered.get(1).getCount());
            assertEquals(2, fixture.machine.inventory.getStackInSlot(0).getCount());
            assertEquals(140, fixture.tank.getFluidAmount());
        }
    }

    @Test
    void cleanupDoesNotRefundCompletedOutputOrExternallyReplacedInput() throws Exception {
        Fixture fixture = new Fixture();
        fixture.machine.inventory.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT));
        fixture.tank.setFluid(new FluidStack(Fluids.LAVA, 360));
        fixture.delegate.clearMachineState(fixture.machine, null);
        assertTrue(fixture.delegate.failureRecoveredInputs().isEmpty());
        assertEquals(Items.GOLD_INGOT, fixture.machine.inventory.getStackInSlot(0).getItem());
        assertEquals(360, fixture.tank.getFluidAmount());

        Fixture completed = new Fixture();
        completed.machine.inventory.setStackInSlot(0, new ItemStack(Items.EMERALD));
        set(completed.delegate, "started", true);
        completed.delegate.clearMachineState(completed.machine, null);
        assertTrue(completed.delegate.failureRecoveredInputs().isEmpty());
        assertEquals(Items.EMERALD, completed.machine.inventory.getStackInSlot(0).getItem());
    }

    @Test
    void failureBeforePlacementLeavesExistingMachineProgressUntouched() throws Exception {
        Fixture fixture = new Fixture();
        set(fixture.delegate, "placedItem", ItemStack.EMPTY);
        set(fixture.delegate, "placedFluid", FluidStack.EMPTY);
        fixture.machine.progress = 42;
        fixture.machine.inventory.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT));
        fixture.delegate.clearMachineState(fixture.machine, null);
        assertEquals(42, fixture.machine.progress);
        assertEquals(Items.GOLD_INGOT, fixture.machine.inventory.getStackInSlot(0).getItem());
    }

    @Test
    void lostItemStillAllowsTheChainToRefundPhysicallyRecoveredFluid() throws Exception {
        Fixture fixture = new Fixture();
        fixture.attachPoweredForge();
        set(fixture.delegate, "recipe", mock(IMetalFormerRecipe.class));
        set(fixture.delegate, "started", true);
        fixture.tank.setFluid(new FluidStack(Fluids.WATER, 360));
        var observation = fixture.delegate.observeMachineCraft(fixture.level, fixture.machine);
        assertEquals(CraftPhase.FAILED, observation.phase());
        // 不走“全部材料已消耗”的免退款分支，保留实际回收到的流体。
        assertFalse(fixture.delegate.failureConsumesInputs(observation));
        try (MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> new ItemStack(Items.POTION, ((FluidStack) call.getArgument(0)).getAmount()));
            fixture.delegate.clearMachineState(fixture.machine, null);
            assertEquals(1, fixture.delegate.failureRecoveredInputs().size());
            assertEquals(360, fixture.delegate.failureRecoveredInputs().get(0).getCount());
            assertTrue(fixture.tank.isEmpty());
        }
    }

    private static final class Fixture {
        final AetherworksMetalFormerBatchDelegate delegate = new AetherworksMetalFormerBatchDelegate();
        final MetalFormerBlockEntity machine = mock(MetalFormerBlockEntity.class);
        final FluidTank tank = new FluidTank(1000);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = new BlockPos(0, 64, 0);
        IForge forge;
        BlockEntity owner;
        IEmberCapability ember;
        final DefaultHeatCapability heat = new DefaultHeatCapability();

        Fixture() throws Exception {
            when(level.hasChunkAt(pos)).thenReturn(true);
            when(level.getBlockEntity(pos)).thenReturn(machine);
            when(machine.getTank()).thenReturn(tank);
            machine.inventory = new ItemStackHandler(1);
            set(delegate, "level", level);
            set(delegate, "pos", pos);
            set(delegate, "machine", machine);
            set(delegate, "expected", new ItemStack(Items.EMERALD));
            set(delegate, "placedItem", new ItemStack(Items.DIAMOND));
            set(delegate, "placedFluid", new FluidStack(Fluids.WATER, 360));
        }

        void attachPoweredForge() throws Exception {
            owner = mock(BlockEntity.class, withSettings().extraInterfaces(IForge.class));
            forge = (IForge) owner;
            BlockPos forgePos = pos.below();
            when(owner.getBlockPos()).thenReturn(forgePos);
            when(level.hasChunkAt(forgePos)).thenReturn(true);
            when(level.getBlockEntity(forgePos)).thenReturn(owner);
            when(forge.getOwner()).thenReturn(owner);
            when(forge.getParts()).thenReturn(List.of(machine));
            when(forge.canFunction()).thenReturn(false);
            ember = mock(IEmberCapability.class);
            when(ember.getEmber()).thenReturn(1000.0);
            when(forge.getEmberCapability()).thenReturn(ember);
            heat.setHeatCapacity(3000.0);
            when(forge.getHeatCapability()).thenReturn(heat);
            set(delegate, "forge", forge);
        }

        IBatchDelegate.PreparationResult prepare() {
            return prepare("refinedstorage", false);
        }

        IBatchDelegate.PreparationResult prepare(String backend, boolean fluidStorage) {
            ServerPlayer player = mock(ServerPlayer.class);
            FluidIngredient fluid = mock(FluidIngredient.class);
            when(fluid.getFluids()).thenReturn(List.of(new FluidStack(Fluids.WATER, 360)));
            MetalFormerRecipe recipe = new MetalFormerRecipe(new ResourceLocation("aetherworks", "test_forming"),
                    Ingredient.of(Items.DIAMOND), fluid, 2600, 300, new ItemStack(Items.EMERALD), false);
            RecipeManager recipes = mock(RecipeManager.class);
            doReturn(Optional.of(recipe)).when(recipes).byKey(recipe.getId());
            when(level.getRecipeManager()).thenReturn(recipes);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class, RETURNS_DEEP_STUBS);
            when(endpoint.session().reference()).thenReturn(new StorageReference(
                    new StorageBackendId(backend), "test"));
            when(endpoint.session().supports(StorageCapability.FLUID_STORAGE)).thenReturn(fluidStorage);
            delegate.setStorageEndpoint(endpoint);
            try (MockedStatic<CraftPacketUtils> context = mockStatic(CraftPacketUtils.class);
                 MockedStatic<AetherworksMachineSafety> structure = mockStatic(AetherworksMachineSafety.class);
                 MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class)) {
                context.when(() -> CraftPacketUtils.resolveLevel(player.server, null, player)).thenReturn(level);
                structure.when(() -> AetherworksMachineSafety.findAttachedForge(level, pos, machine))
                        .thenReturn(owner);
                tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                        .thenReturn(new ItemStack(Items.POTION, 360));
                return delegate.prepare(player, recipe.getId(), null, pos);
            }
        }
    }

    private static ClassNode nativeClass(String name) throws Exception {
        String resource = "net/sirplop/aetherworks/blockentity/" + name + ".class";
        try (InputStream input = AetherworksMetalFormerBatchDelegateTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
