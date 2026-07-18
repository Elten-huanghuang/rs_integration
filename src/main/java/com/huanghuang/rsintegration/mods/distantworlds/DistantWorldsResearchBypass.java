package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class DistantWorldsResearchBypass {
    private static final ResourceLocation FIR0N_RESEARCH = ResourceLocation.fromNamespaceAndPath(
            "distant_worlds", "incandescent_forever");

    private DistantWorldsResearchBypass() {}

    public static Grant unavailable() {
        return Grant.unavailable();
    }

    public static Grant noChange() {
        return new Grant(null, null, List.of(), true);
    }

    public static Grant temporarilyGrant(ServerPlayer player) {
        Advancement advancement = player.server.getAdvancements().getAdvancement(FIR0N_RESEARCH);
        if (advancement == null) return Grant.unavailable();
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        List<String> granted = new ArrayList<>();
        for (String criterion : progress.getRemainingCriteria()) {
            if (player.getAdvancements().award(advancement, criterion)) granted.add(criterion);
        }
        if (!player.getAdvancements().getOrStartProgress(advancement).isDone()) {
            revoke(player, advancement, granted);
            return Grant.unavailable();
        }
        return new Grant(player, advancement, List.copyOf(granted), true);
    }

    private static void revoke(ServerPlayer player, Advancement advancement, List<String> criteria) {
        for (String criterion : criteria) {
            if (!player.getAdvancements().revoke(advancement, criterion)) {
                RSIntegrationMod.LOGGER.error("[RSI-DW] Failed to restore temporary research criterion {}", criterion);
            }
        }
    }

    public static final class Grant implements AutoCloseable {
        private final ServerPlayer player;
        private final Advancement advancement;
        private final List<String> criteria;
        private final boolean available;
        private boolean closed;

        private Grant(ServerPlayer player, Advancement advancement, List<String> criteria, boolean available) {
            this.player = player;
            this.advancement = advancement;
            this.criteria = criteria;
            this.available = available;
        }

        private static Grant unavailable() {
            return new Grant(null, null, List.of(), false);
        }

        public boolean available() { return available; }

        @Override
        public void close() {
            if (closed || !available || player == null || advancement == null) return;
            closed = true;
            revoke(player, advancement, criteria);
        }
    }
}
