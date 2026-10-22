package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerUtilsTest extends BootstrapTest {
    private ServerPlayer player;
    private Inventory inventory;
    private Inventory clientInventory;
    private InventoryMenu inventoryMenu;
    private InventoryMenu clientInventoryMenu;
    private AbstractContainerMenu clientMenu;
    private final List<ClientboundContainerSetSlotPacket> slotPackets = new ArrayList<>();
    private ItemStack clientCarried;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getAbilities()).thenReturn(new Abilities());
        inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        Player clientPlayer = mock(Player.class);
        Level clientLevel = mock(Level.class);
        Field clientSideField = Level.class.getField("isClientSide");
        clientSideField.setAccessible(true);
        clientSideField.set(clientLevel, true);
        when(clientPlayer.level()).thenReturn(clientLevel);
        clientInventory = new Inventory(clientPlayer);
        clientInventoryMenu = new InventoryMenu(clientInventory, false, clientPlayer);
        inventoryMenu = new InventoryMenu(inventory, true, player);
        Field inventoryMenuField = Player.class.getField("inventoryMenu");
        inventoryMenuField.setAccessible(true);
        inventoryMenuField.set(player, inventoryMenu);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        doAnswer(call -> {
            receive(call.getArgument(0));
            return null;
        }).when(player.connection).send(any(Packet.class));
        inventoryMenu.setSynchronizer(new TestSynchronizer());
        openMenuWithoutInventory();
        slotPackets.clear();
    }

    @Test
    void insertsIntoEmptyInventoryWithoutChangingTheSourceStack() {
        ItemStack source = new ItemStack(Items.IRON_INGOT, 20);

        ItemStack remainder = PlayerUtils.insertIntoPlayerInventory(player, source);

        assertTrue(remainder.isEmpty());
        assertEquals(20, source.getCount());
        assertEquals(20, inventory.getItem(0).getCount());
        assertInventoriesMatch();
    }

    @Test
    void mergesExistingStackAndSplitsAnOversizedOutput() {
        inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 60));

        ItemStack remainder = PlayerUtils.insertIntoPlayerInventory(
                player, new ItemStack(Items.IRON_INGOT, 130));

        assertTrue(remainder.isEmpty());
        assertEquals(64, inventory.getItem(0).getCount());
        assertEquals(64, inventory.getItem(1).getCount());
        assertEquals(62, inventory.getItem(2).getCount());
        assertInventoriesMatch();
    }

    @Test
    void returnsOnlyTheUninsertedRemainderWhenInventoryIsAlmostFull() {
        fillMainInventory();
        inventory.setItem(10, new ItemStack(Items.IRON_INGOT, 60));

        ItemStack remainder = PlayerUtils.insertIntoPlayerInventory(
                player, new ItemStack(Items.IRON_INGOT, 130));

        assertEquals(126, remainder.getCount());
        assertEquals(64, inventory.getItem(10).getCount());
        assertEquals(64, clientInventory.getItem(10).getCount());
    }

    @Test
    void fullInventoryDoesNotClaimAnOutputWasInserted() {
        fillMainInventory();

        ItemStack remainder = PlayerUtils.insertIntoPlayerInventory(
                player, new ItemStack(Items.IRON_INGOT, 130));

        assertEquals(130, remainder.getCount());
        assertTrue(remainder.is(Items.IRON_INGOT));
        assertFalse(slotPackets.stream().anyMatch(packet -> packet.getItem().is(Items.IRON_INGOT)));
    }

    @Test
    void respectsTheStackLimitOfUnstackableOutputs() {
        ItemStack remainder = PlayerUtils.insertIntoPlayerInventory(
                player, new ItemStack(Items.IRON_SWORD, 3));

        assertTrue(remainder.isEmpty());
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(1, inventory.getItem(slot).getCount());
        }
        assertInventoriesMatch();
    }

    @Test
    void consumingDeliveredItemsAlsoSyncsWhenTheOpenMenuHasNoPlayerSlots() {
        inventory.selected = 0;
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        for (int slot = 1; slot < 9; slot++) {
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT, 8));
        assertEquals(8, clientInventory.getItem(9).getCount());
        slotPackets.clear();

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(8, ledger.reserveFromInventory(
                    Ingredient.of(Items.IRON_INGOT), 8, player).getCount());
            assertTrue(ledger.commitToEndpoint(null, player));
        }

        assertTrue(inventory.getItem(9).isEmpty());
        assertTrue(clientInventory.getItem(9).isEmpty(),
                "不包含玩家槽的菜单也必须清除客户端上已被下一次合成消耗的成品");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inventorySyncMustNotBroadcastTheInactiveInventoryMenusCursor(boolean fullSync) {
        inventoryMenu.setRemoteCarried(new ItemStack(Items.DIAMOND));
        player.containerMenu.setCarried(new ItemStack(Items.EMERALD, 3));
        player.containerMenu.sendAllDataToRemote();
        assertEquals(3, clientCarried.getCount());
        slotPackets.clear();

        if (fullSync) {
            PlayerUtils.broadcastInventoryChanges(player);
        } else {
            PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT));
        }

        assertTrue(clientCarried.is(Items.EMERALD),
                "后台背包菜单的空光标不能覆盖当前菜单的真实光标");
        assertEquals(3, clientCarried.getCount());
        assertTrue(slotPackets.stream().noneMatch(packet -> packet.getContainerId() == -1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveInventoryMenuCannotCreateAGhostCursorStack(boolean fullSync) {
        inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
        assertTrue(player.containerMenu.getCarried().isEmpty());

        if (fullSync) {
            PlayerUtils.broadcastInventoryChanges(player);
        } else {
            PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT));
        }

        assertTrue(clientCarried.isEmpty());
    }

    @Test
    void broadcastsTheInventoryMenusCursorWhenItIsActuallyActive() {
        player.containerMenu = inventoryMenu;
        clientMenu = clientInventoryMenu;
        inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 5));

        PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT));

        assertTrue(clientCarried.is(Items.DIAMOND));
        assertEquals(5, clientCarried.getCount());
        assertInventoriesMatch();
    }

    @Test
    void stillSyncsChangesToTheActiveMenusCursor() {
        player.containerMenu.setCarried(new ItemStack(Items.EMERALD, 3));

        PlayerUtils.broadcastInventoryChanges(player);

        assertTrue(clientCarried.is(Items.EMERALD));
        assertEquals(3, clientCarried.getCount());
    }

    @Test
    void deferredSyncUsesTheLatestInventoryInsteadOfTheDeliveredSnapshot() {
        PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT, 8));
        inventory.removeItem(0, 8);
        slotPackets.clear();

        PlayerUtils.flushDeferredInventorySync(player);

        assertTrue(clientInventory.getItem(0).isEmpty());
        assertTrue(slotPackets.stream().anyMatch(packet ->
                packet.getSlot() == 0 && packet.getItem().isEmpty()));
        slotPackets.clear();
        PlayerUtils.flushDeferredInventorySync(player);
        assertTrue(slotPackets.isEmpty());
    }

    @Test
    void deferredSyncSurvivesSwitchingToAChestMenu() {
        PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT, 8));
        player.containerMenu = ChestMenu.threeRows(8, inventory);
        clientMenu = ChestMenu.threeRows(8, clientInventory);
        player.containerMenu.setSynchronizer(new TestSynchronizer());
        inventory.getItem(0).shrink(3);
        slotPackets.clear();

        PlayerUtils.flushDeferredInventorySync(player);

        assertEquals(5, clientInventory.getItem(0).getCount());
        assertEquals(5, clientMenu.getSlot(54).getItem().getCount());
    }

    @Test
    void deferredSyncDoesNotSendPacketsAfterDisconnect() {
        PlayerUtils.insertIntoPlayerInventory(player, new ItemStack(Items.IRON_INGOT, 8));
        when(player.hasDisconnected()).thenReturn(true);
        slotPackets.clear();

        PlayerUtils.flushDeferredInventorySync(player);

        assertTrue(slotPackets.isEmpty());
        when(player.hasDisconnected()).thenReturn(false);
        PlayerUtils.flushDeferredInventorySync(player);
        assertTrue(slotPackets.isEmpty());
    }

    private void fillMainInventory() {
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
    }

    private void assertInventoriesMatch() {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            assertTrue(ItemStack.matches(inventory.getItem(slot), clientInventory.getItem(slot)),
                    "服务端和客户端背包槽不一致: " + slot);
        }
    }

    private void openMenuWithoutInventory() {
        player.containerMenu = new MenuWithoutInventory(7);
        clientMenu = new MenuWithoutInventory(7);
        player.containerMenu.setSynchronizer(new TestSynchronizer());
    }

    // 按原版客户端规则应用数据包：非当前菜单的普通槽更新会被忽略。
    private void receive(Packet<?> packet) {
        if (packet instanceof ClientboundContainerSetSlotPacket slotPacket) {
            slotPackets.add(slotPacket);
            int id = slotPacket.getContainerId();
            if (id == ClientboundContainerSetSlotPacket.PLAYER_INVENTORY) {
                clientInventory.setItem(slotPacket.getSlot(), slotPacket.getItem().copy());
            } else if (id == -1) {
                clientCarried = slotPacket.getItem().copy();
            } else if (id == clientMenu.containerId) {
                clientMenu.setItem(slotPacket.getSlot(), slotPacket.getStateId(),
                        slotPacket.getItem().copy());
            } else if (id == 0 && InventoryMenu.isHotbarSlot(slotPacket.getSlot())) {
                clientInventory.setItem(slotPacket.getSlot() - 36, slotPacket.getItem().copy());
            }
        } else if (packet instanceof ClientboundContainerSetContentPacket content) {
            if (content.getContainerId() == 0) {
                clientInventoryMenu.initializeContents(
                        content.getStateId(), content.getItems(), content.getCarriedItem());
                if (clientMenu == clientInventoryMenu) clientCarried = content.getCarriedItem().copy();
            } else if (clientMenu != null && content.getContainerId() == clientMenu.containerId) {
                clientMenu.initializeContents(
                        content.getStateId(), content.getItems(), content.getCarriedItem());
                clientCarried = content.getCarriedItem().copy();
            }
        }
    }

    private final class TestSynchronizer implements ContainerSynchronizer {
        @Override
        public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> items,
                                    ItemStack carried, int[] data) {
            receive(new ClientboundContainerSetContentPacket(
                    menu.containerId, menu.incrementStateId(), items, carried));
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {
            receive(new ClientboundContainerSetSlotPacket(
                    menu.containerId, menu.incrementStateId(), slot, stack));
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) {
            receive(new ClientboundContainerSetSlotPacket(-1, menu.incrementStateId(), -1, stack));
        }

        @Override
        public void sendDataChange(AbstractContainerMenu menu, int id, int value) {}
    }

    private static final class MenuWithoutInventory extends AbstractContainerMenu {
        private MenuWithoutInventory(int id) {
            super(null, id);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
