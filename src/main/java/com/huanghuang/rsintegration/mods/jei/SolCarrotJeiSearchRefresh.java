package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.mods.rs.SolCarrotSearchStatus;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;

/** Refreshes JEI's visible results when the local SolCarrot food list changes. */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class SolCarrotJeiSearchRefresh {
    private static WeakReference<JeiIngredientFilterRefresh> filter =
            new WeakReference<>(null);
    private static long observedRevision = Long.MIN_VALUE;

    private SolCarrotJeiSearchRefresh() {}

    public static void register(JeiIngredientFilterRefresh ingredientFilter) {
        filter = new WeakReference<>(ingredientFilter);
        observedRevision = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        SolCarrotSearchStatus.refresh(minecraft.player);
        if (!SolCarrotSearchStatus.isAvailable()) return;

        long revision = SolCarrotSearchStatus.revision();
        if (revision == observedRevision) return;
        observedRevision = revision;
        JeiIngredientFilterRefresh current = filter.get();
        if (current != null) current.rsi$refreshSolCarrotResults();
    }
}
