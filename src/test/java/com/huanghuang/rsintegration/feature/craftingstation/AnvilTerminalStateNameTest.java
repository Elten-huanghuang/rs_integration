package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnvilTerminalStateNameTest extends BootstrapTest {
    @Test
    void displaysInputNameUntilTheUserEditsIt() {
        ItemStack input = new ItemStack(Items.DIAMOND_SWORD);
        input.setHoverName(Component.literal("Knight Blade"));

        assertEquals("Knight Blade", AnvilTerminalState.visibleItemName(input, "", false));
        assertEquals("Custom Name", AnvilTerminalState.visibleItemName(input, "Custom Name", true));
    }

    @Test
    void keepsAnExplicitlyClearedNameEmpty() {
        ItemStack input = new ItemStack(Items.DIAMOND_SWORD);

        assertEquals("", AnvilTerminalState.visibleItemName(input, "", true));
        assertEquals("", AnvilTerminalState.visibleItemName(ItemStack.EMPTY, "Old Name", false));
    }

    @Test
    void preservesExistingCustomNameInAnvilResult() {
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(new Inventory(player));
        GridContainerMenu menu = mock(GridContainerMenu.class);
        when(menu.getPlayer()).thenReturn(player);
        AnvilTerminalState state = new AnvilTerminalState(menu);

        ItemStack namedSword = new ItemStack(Items.DIAMOND_SWORD);
        namedSword.setDamageValue(100);
        namedSword.setHoverName(Component.literal("Knight Blade"));
        ItemStack repairSword = new ItemStack(Items.DIAMOND_SWORD);
        repairSword.setDamageValue(100);
        state.setInput(0, namedSword);
        state.setInput(1, repairSword);

        assertEquals("Knight Blade", state.itemName());
        assertEquals("Knight Blade", state.result().getHoverName().getString());
    }
}
