package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmRSModule;
import com.huanghuang.rsintegration.mods.cthulhucreatures.CthulhuCreaturesRSModule;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

class TlmAltarBindingTest extends BootstrapTest {
    private static final BlockPos ALTAR = new BlockPos(-8, 5, 18);
    private static final BlockPos DISK_DRIVE = new BlockPos(6, 3, 21);
    private static final ResourceLocation RECIPE_ID = new ResourceLocation(
            ModIds.TOUHOU_LITTLE_MAID, "altar/craft_hakurei_gohei");
    private static final String BLOCK_KEY = "touhou_little_maid||block.touhou_little_maid.altar";

    private ServerPlayer player;
    private MinecraftServer server;
    private ServerLevel level;
    private INetwork network;
    private INetworkNode node;
    private BlockEntity proxyEntity;
    private Recipe<?> recipe;
    private ModType type;
    private MockedStatic<ModList> loading;

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
    void setUp() throws Exception {
        TlmRSModule.INSTANCE.registerModType();
        type = ModType.byId(ModIds.TOUHOU_LITTLE_MAID);
        AltarBindingRegistry.invalidateScanCache();
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);
        ModList mods = mock(ModList.class);
        when(mods.isLoaded(ModIds.REFINED_STORAGE)).thenReturn(true);
        loading = mockStatic(ModList.class);
        loading.when(ModList::get).thenReturn(mods);

        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isLoaded(ALTAR)).thenReturn(true);
        when(level.isLoaded(DISK_DRIVE)).thenReturn(true);
        player = mock(ServerPlayer.class);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);

        ItemStack connector = new ItemStack(Items.APPLE);
        connector.getOrCreateTag().putString("Dimension", "minecraft:overworld");
        connector.getOrCreateTag().putInt("NodeX", DISK_DRIVE.getX());
        connector.getOrCreateTag().putInt("NodeY", DISK_DRIVE.getY());
        connector.getOrCreateTag().putInt("NodeZ", DISK_DRIVE.getZ());
        BindingStorage.addBinding(connector, Level.OVERWORLD.location(), ALTAR,
                BLOCK_KEY, "touhou_little_maid:altar", null);
        inventory.items.set(2, connector);

        Block altarBlock = mock(Block.class);
        when(altarBlock.getDescriptionId()).thenReturn("block.touhou_little_maid.altar");
        BlockState state = mock(BlockState.class);
        when(state.getBlock()).thenReturn(altarBlock);
        BlockEntity altarEntity = mock(BlockEntity.class);
        when(altarEntity.getBlockState()).thenReturn(state);
        when(level.getBlockEntity(ALTAR)).thenReturn(altarEntity);

        // 模拟真实 DiskDriveBlockEntity 的节点代理接口，网络不直接挂在方块实体上。
        proxyEntity = mock(BlockEntity.class, withSettings().extraInterfaces(INetworkNodeProxy.class));
        node = mock(INetworkNode.class);
        network = mock(INetwork.class);
        doReturn(node).when((INetworkNodeProxy<?>) proxyEntity).getNode();
        when(node.getNetwork()).thenReturn(network);
        when(level.getBlockEntity(DISK_DRIVE)).thenReturn(proxyEntity);

        recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(RECIPE_ID);
        RecipeManager manager = mock(RecipeManager.class);
        doReturn(Optional.of(recipe)).when(manager).byKey(RECIPE_ID);
        when(level.getRecipeManager()).thenReturn(manager);
    }

    @AfterEach
    void tearDown() {
        AltarBindingRegistry.unbindAll(Level.OVERWORLD, ALTAR);
        AltarBindingRegistry.invalidateScanCache();
        loading.close();
    }

    @Test
    void savedAltarBindingToDiskDriveWorksInPreviewAndMachineSelection() {
        assertSame(network, RSIntegrationNetwork.resolveNetworkStrict(server, Level.OVERWORLD, DISK_DRIVE));
        assertTrue(AltarBindingRegistry.hasBindingForRecipe(player, recipe, type));
        assertEquals(List.of(new AltarBindingRegistry.BoundMachine(
                        Level.OVERWORLD.location(), ALTAR, type, BLOCK_KEY)),
                AltarBindingRegistry.getBoundMachinesForRecipe(player, type, RECIPE_ID));
        assertSame(network, RSAltarBindingResolver.resolveNetworkForAltar(player, Level.OVERWORLD, ALTAR));
    }

    @Test
    void fleshAltarOnTheSameDiskDriveWorksInPreviewAndMachineSelection() {
        CthulhuCreaturesRSModule.INSTANCE.registerModType();
        ModType fleshType = ModType.byId(ModIds.ID_CTHULHU_FLESH_ALTAR);
        BlockPos fleshPos = new BlockPos(-6, 2, 30);
        ResourceLocation fleshRecipe = new ResourceLocation(ModIds.CTHULHU_CREATURES, "altar_netherite_upgrade");
        String fleshKey = ModIds.ID_CTHULHU_FLESH_ALTAR + "||block.cthulhu_creatures.flesh_altar";
        ItemStack connector = player.getInventory().items.get(2);
        BindingStorage.addBinding(connector, Level.OVERWORLD.location(), fleshPos,
                fleshKey, CthulhuCreaturesRSModule.BLOCK_ID, new ItemStack(Items.STONE));
        Block block = mock(Block.class);
        when(block.getDescriptionId()).thenReturn("block.cthulhu_creatures.flesh_altar");
        BlockState state = mock(BlockState.class);
        when(state.getBlock()).thenReturn(block);
        when(level.isLoaded(fleshPos)).thenReturn(true);
        when(level.getBlockState(fleshPos)).thenReturn(state);
        Recipe<?> nativeRecipe = mock(Recipe.class);
        when(nativeRecipe.getId()).thenReturn(fleshRecipe);
        RecipeManager manager = level.getRecipeManager();
        doReturn(Optional.of(nativeRecipe)).when(manager).byKey(fleshRecipe);
        try {
            assertTrue(AltarBindingRegistry.hasBindingForRecipe(player, nativeRecipe, fleshType));
            assertEquals(List.of(new AltarBindingRegistry.BoundMachine(
                            Level.OVERWORLD.location(), fleshPos, fleshType, fleshKey)),
                    AltarBindingRegistry.getBoundMachinesForRecipe(player, fleshType, fleshRecipe));
        } finally {
            AltarBindingRegistry.unbindAll(Level.OVERWORLD, fleshPos);
        }
    }

    @Test
    void disconnectedDiskDriveIsNotAnAvailableAltarBinding() {
        when(node.getNetwork()).thenReturn(null);
        assertNull(RSIntegrationNetwork.resolveNetworkStrict(server, Level.OVERWORLD, DISK_DRIVE));
        assertFalse(AltarBindingRegistry.hasBindingForRecipe(player, recipe, type));
        assertTrue(AltarBindingRegistry.getBoundMachinesForRecipe(player, type, RECIPE_ID).isEmpty());
    }

    @Test
    void unloadedNetworkChunkIsNotLoadedByBindingProbe() {
        when(level.isLoaded(DISK_DRIVE)).thenReturn(false);
        assertFalse(AltarBindingRegistry.hasBindingForRecipe(player, recipe, type));
        verify(level, never()).getBlockEntity(DISK_DRIVE);
        verifyNoInteractions(node);
    }

    @Test
    void proxyWithoutNodeDoesNotResolveNetwork() {
        doReturn(null).when((INetworkNodeProxy<?>) proxyEntity).getNode();
        assertNull(RSIntegrationNetwork.resolveNetworkStrict(server, Level.OVERWORLD, DISK_DRIVE));
    }

    @Test
    void directlyExposedNetworkNodeStillResolves() {
        BlockEntity directEntity = mock(BlockEntity.class, withSettings().extraInterfaces(INetworkNode.class));
        when(((INetworkNode) directEntity).getNetwork()).thenReturn(network);
        when(level.getBlockEntity(DISK_DRIVE)).thenReturn(directEntity);
        assertSame(network, RSIntegrationNetwork.resolveNetworkStrict(server, Level.OVERWORLD, DISK_DRIVE));
    }

    @Test
    void replacedAltarIsNotAvailableEvenWithConnectedDiskDrive() {
        BlockState state = mock(BlockState.class);
        when(state.getBlock()).thenReturn(mock(Block.class));
        when(state.getBlock().getDescriptionId()).thenReturn("block.minecraft.stone");
        when(level.getBlockEntity(ALTAR).getBlockState()).thenReturn(state);
        when(level.getBlockState(ALTAR)).thenReturn(state);
        assertFalse(AltarBindingRegistry.hasBindingForRecipe(player, recipe, type));
    }
}
