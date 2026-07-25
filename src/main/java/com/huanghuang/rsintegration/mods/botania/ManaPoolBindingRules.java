package com.huanghuang.rsintegration.mods.botania;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/** Pure binding-layout rules shared by Mana Pool preparation and its tests. */
final class ManaPoolBindingRules {

    enum State {
        READY,
        RETRY,
        FATAL
    }

    record Assessment(State state, @Nullable BlockPos poolPos, String detail) {
        Assessment {
            detail = detail == null ? "" : detail;
        }
    }

    private ManaPoolBindingRules() {}

    static Assessment assess(BlockPos bindingPos, boolean bindingIsPool, boolean poolAboveBinding,
                             boolean recipeHasCatalyst, boolean catalystMatchesBinding) {
        if (bindingPos == null) {
            return new Assessment(State.FATAL, null, "Mana Pool binding position is missing");
        }
        if (!bindingIsPool && !poolAboveBinding) {
            return new Assessment(State.RETRY, null,
                    "Mana Pool is missing directly above the catalyst binding");
        }

        BlockPos poolPos = bindingIsPool ? bindingPos.immutable() : bindingPos.above().immutable();
        if (!recipeHasCatalyst) {
            return bindingIsPool
                    ? new Assessment(State.READY, poolPos, "")
                    : new Assessment(State.FATAL, null,
                    "Plain Mana Pool recipes require a direct Mana Pool binding");
        }
        if (bindingIsPool) {
            return new Assessment(State.FATAL, null,
                    "Catalyst Mana Pool recipes require a binding on the catalyst below the pool");
        }
        if (!catalystMatchesBinding) {
            return new Assessment(State.FATAL, null,
                    "The bound Mana Pool catalyst does not match this recipe");
        }
        return new Assessment(State.READY, poolPos, "");
    }
}
