package com.huanghuang.rsintegration.compat.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;

@EmiEntrypoint
public final class RSEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        // Recipe decorators are hidden by EMI's default show-recipe-decorators=false
        // setting. RecipeDisplayMixin attaches the player-facing buttons instead.
    }
}
