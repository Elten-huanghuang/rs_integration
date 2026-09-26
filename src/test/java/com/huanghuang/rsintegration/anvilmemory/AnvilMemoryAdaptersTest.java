package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilMemoryAdaptersTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unsupportedMenuTypeIsIgnored() {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, 0) {
            @Override
            public ItemStack quickMoveStack(Player player, int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };

        assertNull(AnvilMemoryAdapters.find(menu));
    }

    @Test
    void preciseGoetyAdapterPrecedesVanillaAnvilFallback() throws ReflectiveOperationException {
        Field adaptersField = AnvilMemoryAdapters.class.getDeclaredField("ADAPTERS");
        adaptersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<AnvilMemoryAdapter> adapters = (List<AnvilMemoryAdapter>) adaptersField.get(null);
        List<String> ids = adapters.stream().map(AnvilMemoryAdapter::id).toList();

        assertTrue(ids.indexOf("goety_dark_anvil") < ids.indexOf("minecraft_anvil"));
    }
}
