package com.huanghuang.rsintegration.network.binding;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.gui.MachineInteractionCheckEvent;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BindingInteractionTest extends BootstrapTest {
    private static final BlockPos MACHINE = new BlockPos(3, 64, 5);
    private ServerPlayer player;
    private ServerLevel level;
    private ItemStack connector;
    private MachineBindingSavedData data;
    private BindingEventHandler.MachineBindingTarget target;
    private List<BindingEventHandler.MachineBindingTarget> targets;
    private MockedStatic<ModList> mods;
    private MockedStatic<ServerLifecycleHooks> lifecycle;
    private MockedStatic<MachineBindingSavedData> saved;
    private MockedStatic<MachineFavoritesSavedData> favorites;
    private MockedStatic<AltarBindingRegistry> registry;
    private MockedStatic<RSIntegrationNetwork> networks;
    private MockedStatic<MachineBindingSyncBridge> sync;

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
        ModList modList = mock(ModList.class);
        when(modList.isLoaded(ModIds.REFINED_STORAGE)).thenReturn(true);
        mods = mockStatic(ModList.class);
        mods.when(ModList::get).thenReturn(modList);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(MACHINE)).thenReturn(Blocks.STONE.defaultBlockState());
        player = mock(ServerPlayer.class);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.level()).thenReturn(level);
        when(player.isShiftKeyDown()).thenReturn(true);
        when(player.getInventory()).thenReturn(new Inventory(player));
        connector = new ItemStack(Items.APPLE);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(connector);
        lifecycle = mockStatic(ServerLifecycleHooks.class);
        lifecycle.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
        data = new MachineBindingSavedData();
        saved = mockStatic(MachineBindingSavedData.class);
        saved.when(() -> MachineBindingSavedData.get(server)).thenReturn(data);
        saved.when(() -> MachineBindingSavedData.peek(server)).thenReturn(data);
        favorites = mockStatic(MachineFavoritesSavedData.class);
        favorites.when(() -> MachineFavoritesSavedData.get(server))
                .thenReturn(mock(MachineFavoritesSavedData.class));
        registry = mockStatic(AltarBindingRegistry.class);
        IBindingHook hook = mock(IBindingHook.class);
        when(hook.createBinding(connector)).thenReturn(Optional.of(new AltarBinding(
                AltarBinding.RS_NETWORK, Component.literal("test"), new CompoundTag())));
        registry.when(() -> AltarBindingRegistry.findHook(connector)).thenReturn(Optional.of(hook));
        networks = mockStatic(RSIntegrationNetwork.class);
        sync = mockStatic(MachineBindingSyncBridge.class);
        Field targetField = BindingEventHandler.class.getDeclaredField("TARGETS");
        targetField.setAccessible(true);
        targets = (List<BindingEventHandler.MachineBindingTarget>) targetField.get(null);
        target = new BindingEventHandler.MachineBindingTarget("minecraft", ModType.CUSTOM_GUI,
                RSIntegrationConfig.ENABLE_BINDING, List.of(Blocks.STONE.getClass().getName()),
                "test", true);
        targets.add(target);
    }

    @AfterEach
    void tearDown() {
        if (targets != null) targets.remove(target);
        if (sync != null) sync.close();
        if (networks != null) networks.close();
        if (registry != null) registry.close();
        if (favorites != null) favorites.close();
        if (saved != null) saved.close();
        if (lifecycle != null) lifecycle.close();
        if (mods != null) mods.close();
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void bindingConsumesTheNativeInteractionInsteadOfFallingThroughToOtherHand() {
        PlayerInteractEvent.RightClickBlock event = mock(PlayerInteractEvent.RightClickBlock.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getLevel()).thenReturn(level);
        when(event.getPos()).thenReturn(MACHINE);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);

        BindingEventHandler.onRightClickBlock(event);

        assertBound();
        verify(event).setCancellationResult(InteractionResult.CONSUME);
        verify(event).setCanceled(true);
    }

    @Test
    void remotePermissionProbeWhileSneakingCannotUnbindTheMachine() {
        BindingEventHandler.handleExplicitBind(player, MACHINE, InteractionHand.MAIN_HAND);
        assertBound();
        MachineEndpointId endpoint = MachineBindingService.endpointId(
                connector, Level.OVERWORLD.location(), MACHINE);
        clearInvocations(player);

        BindingEventHandler.onRightClickBlock(new MachineInteractionCheckEvent(player, MACHINE));

        assertBound();
        assertEquals(endpoint, MachineBindingService.endpointId(
                connector, Level.OVERWORLD.location(), MACHINE));
        verify(player, never()).displayClientMessage(any(), anyBoolean());
    }

    @Test
    void permissionProbeCanStillBeCanceledByProtectionHandlers() {
        MachineInteractionCheckEvent event = new MachineInteractionCheckEvent(player, MACHINE);
        assertInstanceOf(PlayerInteractEvent.RightClickBlock.class, event);
        assertTrue(event.isCancelable());
        event.setCanceled(true);
        assertTrue(event.isCanceled());
    }

    @Test
    void intentionalUnbindThenRebindWorksWithTheSameTerminalBook() {
        BindingEventHandler.handleExplicitBind(player, MACHINE, InteractionHand.MAIN_HAND);
        assertBound();
        UUID book = MachineBindingService.bookId(connector);
        BindingEventHandler.handleExplicitBind(player, MACHINE, InteractionHand.MAIN_HAND);
        assertFalse(BindingStorage.hasBinding(connector, Level.OVERWORLD.location(), MACHINE));
        assertEquals(0, data.size(book));

        BindingEventHandler.handleExplicitBind(player, MACHINE, InteractionHand.MAIN_HAND);

        assertBound();
        assertEquals(book, MachineBindingService.bookId(connector));
        assertEquals(1, data.size(book));
    }

    private void assertBound() {
        assertTrue(BindingStorage.hasBinding(connector, Level.OVERWORLD.location(), MACHINE));
        assertNotNull(MachineBindingService.bookId(connector));
        assertEquals(1, data.size(MachineBindingService.bookId(connector)));
    }
}
