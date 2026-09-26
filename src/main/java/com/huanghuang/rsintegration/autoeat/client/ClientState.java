package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.AutoEatPreferences;
import com.huanghuang.rsintegration.autoeat.network.UpdateAutoEatPreferencesPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class ClientState {

    private ClientState() {}

    public static AutoEatMode currentMode = AutoEatMode.DIVERSITY;
    public static final Set<ResourceLocation> selectedItems = new LinkedHashSet<>();
    public static final Set<ResourceLocation> blacklistedItems = new HashSet<>();
    public static final Set<ResourceLocation> blacklistedEffects = new HashSet<>();

    public static void cycleMode() {
        currentMode = currentMode.next();
        syncPreferences();
    }

    public static void toggleSelectedItem(ResourceLocation item) {
        if (!selectedItems.remove(item)
                && selectedItems.size() < AutoEatPreferences.MAX_SELECTED_ITEMS) {
            selectedItems.add(item);
        }
        syncPreferences();
    }

    public static void selectItems(Collection<ResourceLocation> items) {
        for (ResourceLocation item : items) {
            if (selectedItems.size() >= AutoEatPreferences.MAX_SELECTED_ITEMS) break;
            if (item != null) selectedItems.add(item);
        }
        syncPreferences();
    }

    public static void deselectItems(Collection<ResourceLocation> items) {
        selectedItems.removeAll(items);
        syncPreferences();
    }

    public static void applyPreferences(AutoEatMode mode, Collection<ResourceLocation> items) {
        currentMode = mode == null ? AutoEatMode.DIVERSITY : mode;
        selectedItems.clear();
        if (items != null) selectWithoutSync(items);
    }

    private static void syncPreferences() {
        NetworkHandler.CHANNEL.sendToServer(
                new UpdateAutoEatPreferencesPacket(currentMode, selectedItems));
    }

    /** Reset all client state when disconnecting from a server / leaving a world. */
    public static void reset() {
        currentMode = AutoEatMode.DIVERSITY;
        selectedItems.clear();
        blacklistedItems.clear();
        blacklistedEffects.clear();
    }

    private static void selectWithoutSync(Collection<ResourceLocation> items) {
        for (ResourceLocation item : items) {
            if (selectedItems.size() >= AutoEatPreferences.MAX_SELECTED_ITEMS) break;
            if (item != null) selectedItems.add(item);
        }
    }
}
