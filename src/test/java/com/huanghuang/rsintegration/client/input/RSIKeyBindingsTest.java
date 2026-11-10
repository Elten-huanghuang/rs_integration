package com.huanghuang.rsintegration.client.input;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBinding;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.ExplicitMachineBindingPacket;
import com.huanghuang.rsintegration.network.binding.IBindingHook;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RSIKeyBindingsTest extends BootstrapTest {
    private static final BlockPos MACHINE = new BlockPos(3, 64, 5);
    private final List<ExplicitMachineBindingPacket> requests = new ArrayList<>();
    private Minecraft minecraft;
    private LocalPlayer player;
    private KeyMapping previousKey;
    private MockedStatic<AltarBindingRegistry> registry;
    private BindingEventHandler.MachineBindingTarget target;
    private List<BindingEventHandler.MachineBindingTarget> targets;

    @BeforeAll
    static void initializeModContext() {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
        previousKey = RSIKeyBindings.KEY_BIND_MACHINE;
        RSIKeyBindings.KEY_BIND_MACHINE = mock(KeyMapping.class);
        minecraft = mock(Minecraft.class);
        player = mock(LocalPlayer.class);
        minecraft.player = player;
        minecraft.hitResult = new BlockHitResult(Vec3.atCenterOf(MACHINE), Direction.UP, MACHINE, false);
        ItemStack connector = new ItemStack(Items.APPLE);
        when(player.getMainHandItem()).thenReturn(connector);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(connector);
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(ItemStack.EMPTY);
        registry = mockStatic(AltarBindingRegistry.class);
        IBindingHook hook = mock(IBindingHook.class);
        when(hook.createBinding(connector)).thenReturn(Optional.of(new AltarBinding(
                AltarBinding.RS_NETWORK, Component.literal("test"), new CompoundTag())));
        registry.when(() -> AltarBindingRegistry.findHook(connector)).thenReturn(Optional.of(hook));
        Field targetField = BindingEventHandler.class.getDeclaredField("TARGETS");
        targetField.setAccessible(true);
        targets = (List<BindingEventHandler.MachineBindingTarget>) targetField.get(null);
        target = new BindingEventHandler.MachineBindingTarget("minecraft", ModType.CUSTOM_GUI,
                RSIntegrationConfig.ENABLE_BINDING, List.of(), List.of("minecraft:stone"),
                "test", true);
        targets.add(target);
        Level level = mock(Level.class);
        when(player.level()).thenReturn(level);
        when(level.getBlockState(MACHINE)).thenReturn(Blocks.STONE.defaultBlockState());
    }

    @AfterEach
    void tearDown() {
        if (targets != null) targets.remove(target);
        if (registry != null) registry.close();
        RSIKeyBindings.KEY_BIND_MACHINE = previousKey;
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void altClickConsumesThePressAndReleaseDoesNotSendAnotherRequest() {
        when(RSIKeyBindings.KEY_BIND_MACHINE.isActiveAndMatches(any())).thenReturn(true);
        InputEvent.MouseButton.Pre press = input(GLFW.GLFW_PRESS);
        RSIKeyBindings.handleBindingMouseInput(press, minecraft, requests::add);
        RSIKeyBindings.handleBindingMouseInput(input(GLFW.GLFW_RELEASE), minecraft, requests::add);

        assertEquals(List.of(new ExplicitMachineBindingPacket(MACHINE, InteractionHand.MAIN_HAND)), requests);
        verify(press).setCanceled(true);
    }

    @Test
    void shiftClickUsesOneConsumedRequestEvenWithTwoTerminalsInHand() {
        when(player.isShiftKeyDown()).thenReturn(true);
        ItemStack connector = player.getMainHandItem();
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(connector);
        InputEvent.MouseButton.Pre press = input(GLFW.GLFW_PRESS);
        RSIKeyBindings.handleBindingMouseInput(press, minecraft, requests::add);

        assertEquals(1, requests.size());
        assertEquals(InteractionHand.MAIN_HAND, requests.get(0).hand());
        verify(press).setCanceled(true);
    }

    @Test
    void offhandTerminalUsesTheOffhandInItsSingleRequest() {
        when(RSIKeyBindings.KEY_BIND_MACHINE.isActiveAndMatches(any())).thenReturn(true);
        ItemStack connector = player.getMainHandItem();
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(connector);

        InputEvent.MouseButton.Pre press = input(GLFW.GLFW_PRESS);
        RSIKeyBindings.handleBindingMouseInput(press, minecraft, requests::add);

        assertEquals(List.of(new ExplicitMachineBindingPacket(MACHINE, InteractionHand.OFF_HAND)), requests);
        verify(press).setCanceled(true);
    }

    @Test
    void bdTerminalKeepsItsNativeShiftAction() {
        when(player.isShiftKeyDown()).thenReturn(true);
        ItemStack connector = player.getMainHandItem();
        IBindingHook hook = mock(IBindingHook.class);
        when(hook.createBinding(connector)).thenReturn(Optional.of(new AltarBinding(
                AltarBinding.BD_NETWORK, Component.literal("test"), new CompoundTag())));
        registry.when(() -> AltarBindingRegistry.findHook(connector)).thenReturn(Optional.of(hook));
        InputEvent.MouseButton.Pre press = input(GLFW.GLFW_PRESS);

        RSIKeyBindings.handleBindingMouseInput(press, minecraft, requests::add);

        assertTrue(requests.isEmpty());
        verify(press, never()).setCanceled(true);
    }

    @Test
    void separateIntentionalClicksStillSendSeparateToggleRequests() {
        when(RSIKeyBindings.KEY_BIND_MACHINE.isActiveAndMatches(any())).thenReturn(true);
        RSIKeyBindings.handleBindingMouseInput(input(GLFW.GLFW_PRESS), minecraft, requests::add);
        RSIKeyBindings.handleBindingMouseInput(input(GLFW.GLFW_RELEASE), minecraft, requests::add);
        RSIKeyBindings.handleBindingMouseInput(input(GLFW.GLFW_PRESS), minecraft, requests::add);
        assertEquals(2, requests.size());
    }

    @Test
    void ordinaryRightClickAndShiftClickOnOtherBlocksRemainNative() {
        InputEvent.MouseButton.Pre ordinary = input(GLFW.GLFW_PRESS);
        RSIKeyBindings.handleBindingMouseInput(ordinary, minecraft, requests::add);
        when(player.isShiftKeyDown()).thenReturn(true);
        when(player.level().getBlockState(MACHINE)).thenReturn(Blocks.DIRT.defaultBlockState());
        InputEvent.MouseButton.Pre otherBlock = input(GLFW.GLFW_PRESS);
        RSIKeyBindings.handleBindingMouseInput(otherBlock, minecraft, requests::add);

        assertTrue(requests.isEmpty());
        verify(ordinary, never()).setCanceled(true);
        verify(otherBlock, never()).setCanceled(true);
    }

    private static InputEvent.MouseButton.Pre input(int action) {
        InputEvent.MouseButton.Pre event = mock(InputEvent.MouseButton.Pre.class);
        when(event.getButton()).thenReturn(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        when(event.getAction()).thenReturn(action);
        return event;
    }
}
