package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Read-only machine readiness snapshot rendered by the crafting plan screen. */
public record MachineCandidateView(
        String dimension,
        int x,
        int y,
        int z,
        ItemStack icon,
        State state,
        Component status
) {
    public enum State {
        READY,
        TEMPORARY,
        INCOMPATIBLE
    }

    public MachineCandidateView {
        dimension = dimension == null ? "" : dimension;
        icon = icon == null ? ItemStack.EMPTY : icon.copy();
        state = state == null ? State.INCOMPATIBLE : state;
        status = status == null ? Component.empty() : status.copy();
    }

    @Override
    public ItemStack icon() {
        return icon.copy();
    }
}
