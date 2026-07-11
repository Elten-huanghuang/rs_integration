package com.huanghuang.rsintegration.autoeat.client;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class ClientState {

    private ClientState() {}

    public static AutoEatMode currentMode = AutoEatMode.DIVERSITY;
    public static ResourceLocation selectedItem;
    public static final Set<ResourceLocation> blacklistedItems = new HashSet<>();

    public static void cycleMode() {
        currentMode = currentMode.next();
    }

    /** Reset all client state when disconnecting from a server / leaving a world. */
    public static void reset() {
        currentMode = AutoEatMode.DIVERSITY;
        selectedItem = null;
        blacklistedItems.clear();
    }
}
