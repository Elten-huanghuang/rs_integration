package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerType;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeCountLimitConfig;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RSMagnetUpgradeTest extends BootstrapTest {
    private static synchronized RSMagnetUpgradeItem item() throws Exception {
        ResourceLocation id = new ResourceLocation("rs_integration_test", "rs_magnet");
        if (ForgeRegistries.ITEMS.getValue(id) instanceof RSMagnetUpgradeItem item) return item;
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            var item = new RSMagnetUpgradeItem(() -> 5, () -> 16, mock(IUpgradeCountLimitConfig.class));
            registry.register(id, item);
            return item;
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }

    @Test
    void fluidSwitchDefaultsOffPersistsAndIsIndependentOfItemsAndXp() throws Exception {
        var item = item();
        ItemStack stack = new ItemStack(item);
        IStorageWrapper storage = storage();
        Consumer<ItemStack> save = mock(Consumer.class);
        var wrapper = new RSMagnetUpgradeWrapper(storage, stack, save);
        assertFalse(wrapper.shouldPickupFluids());
        wrapper.setPickupItems(false);
        wrapper.setPickupXp(false);
        wrapper.setPickupFluids(true);
        verify(save, atLeastOnce()).accept(stack);
        ItemStack reloaded = ItemStack.of(stack.save(new CompoundTag()));
        var loaded = new RSMagnetUpgradeWrapper(storage, reloaded, save);
        assertTrue(loaded.shouldPickupFluids());
        assertFalse(loaded.shouldPickupItems());
        assertFalse(loaded.shouldPickupXp());
        loaded.setPickupFluids(false);
        assertFalse(loaded.shouldPickupFluids());
    }

    @Test
    void clientSendsFluidSwitchAndServerContainerAppliesIt() throws Exception {
        IStorageWrapper storage = storage();
        var clientWrapper = new RSMagnetUpgradeWrapper(storage, new ItemStack(item()), stack -> {});
        ServerPlayer client = mock(ServerPlayer.class);
        Level clientLevel = mock(Level.class);
        Field clientSide = Level.class.getDeclaredField("isClientSide");
        clientSide.setAccessible(true);
        clientSide.setBoolean(clientLevel, true);
        when(client.level()).thenReturn(clientLevel);
        UpgradeContainerType<MagnetUpgradeWrapper, MagnetUpgradeContainer> type =
                new UpgradeContainerType<>((player, id, wrapper, containerType) -> null);
        var container = spy(new RSMagnetUpgradeContainer(client, 3, clientWrapper, type));
        doNothing().when(container).sendDataToServer(any());
        container.setPickupFluids(true);
        ArgumentCaptor<Supplier<CompoundTag>> packet = ArgumentCaptor.forClass(Supplier.class);
        verify(container).sendDataToServer(packet.capture());
        CompoundTag message = packet.getValue().get();
        assertTrue(message.getBoolean(RSMagnetUpgradeWrapper.PICKUP_FLUIDS));
        ServerPlayer server = mock(ServerPlayer.class);
        when(server.level()).thenReturn(mock(Level.class));
        var serverWrapper = new RSMagnetUpgradeWrapper(storage, new ItemStack(item()), stack -> {});
        var serverContainer = new RSMagnetUpgradeContainer(server, 3, serverWrapper, type);
        serverContainer.handleMessage(message);
        assertTrue(serverContainer.shouldPickupFluids());
        message.putString(RSMagnetUpgradeWrapper.PICKUP_FLUIDS, "invalid");
        serverContainer.handleMessage(message);
        assertTrue(serverContainer.shouldPickupFluids());
    }

    private IStorageWrapper storage() {
        IStorageWrapper storage = mock(IStorageWrapper.class, RETURNS_DEEP_STUBS);
        var settings = storage.getSettingsHandler();
        var memory = mock(MemorySettingsCategory.class);
        doReturn(memory).when(settings)
                .getTypeCategory(MemorySettingsCategory.class);
        return storage;
    }
}
