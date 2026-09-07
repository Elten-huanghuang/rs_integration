package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import com.huanghuang.rsintegration.crafting.availability.RecipeAvailabilityCache;
import com.huanghuang.rsintegration.crafting.availability.RecipeAvailabilityKey;
import com.huanghuang.rsintegration.crafting.batch.RecipeAvailabilityRequestPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

public final class RecipeAvailabilityClient {
    private static final RecipeAvailabilityCache CACHE = new RecipeAvailabilityCache();
    private static Object level;
    private static Object screen;
    private static Object menu;
    private static int inventoryRevision;
    private static ItemStack mainHand = ItemStack.EMPTY;
    private static ItemStack offHand = ItemStack.EMPTY;

    private RecipeAvailabilityClient() {}

    public static MaterialAvailability get(@Nullable RecipeAvailabilityKey key) {
        Minecraft mc = Minecraft.getInstance();
        if (key == null || mc.player == null || mc.getConnection() == null) return MaterialAvailability.UNKNOWN;
        if (level != mc.level || screen != mc.screen || menu != mc.player.containerMenu
                || inventoryRevision != mc.player.getInventory().getTimesChanged()
                || !ItemStack.matches(mainHand, mc.player.getMainHandItem())
                || !ItemStack.matches(offHand, mc.player.getOffhandItem())) {
            CACHE.clear();
            level = mc.level;
            screen = mc.screen;
            menu = mc.player.containerMenu;
            inventoryRevision = mc.player.getInventory().getTimesChanged();
            mainHand = mc.player.getMainHandItem().copy();
            offHand = mc.player.getOffhandItem().copy();
        }
        return CACHE.get(key, Util.getMillis(), (request, ticket) ->
                NetworkHandler.CHANNEL.sendToServer(new RecipeAvailabilityRequestPacket(request, ticket)));
    }

    public static void accept(RecipeAvailabilityKey key, long ticket, MaterialAvailability state) {
        CACHE.accept(key, ticket, state, Util.getMillis());
    }

    public static void clear() {
        CACHE.clear();
        level = screen = menu = null;
        mainHand = offHand = ItemStack.EMPTY;
    }
}
