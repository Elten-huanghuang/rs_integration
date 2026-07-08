package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;

public final class RSIKeyBindings {

    private RSIKeyBindings() {}

    /** Alt + middle-click in JEI ingredient list → clear search. */
    public static KeyMapping KEY_CLEAR_SEARCH;
    /** Alt + left-click on a JEI ingredient → filter by that mod. */
    public static KeyMapping KEY_MOD_FILTER;
    /** Ctrl + T in JEI recipe view → transfer recipe to RS. */
    public static KeyMapping KEY_TRANSFER_RECIPE;
    /** Ctrl + left-drag on RS grid → extract one of each swiped item. */
    public static KeyMapping KEY_SWIPE_EXTRACT;

    private static volatile boolean registered;

    public static void registerKeyMappings() {
        if (registered) return;
        registered = true;

        KEY_CLEAR_SEARCH = new KeyMapping(
                "key.rsi.clear_search",
                KeyConflictContext.GUI,
                KeyModifier.ALT,
                InputConstants.Type.MOUSE,
                2,
                "key.categories.rsi"
        );
        KEY_MOD_FILTER = new KeyMapping(
                "key.rsi.mod_filter",
                KeyConflictContext.GUI,
                KeyModifier.ALT,
                InputConstants.Type.MOUSE,
                0,
                "key.categories.rsi"
        );
        KEY_TRANSFER_RECIPE = new KeyMapping(
                "key.rsi.transfer_recipe",
                KeyConflictContext.GUI,
                KeyModifier.CONTROL,
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_T,
                "key.categories.rsi"
        );
        KEY_SWIPE_EXTRACT = new KeyMapping(
                "key.rsi.swipe_extract",
                KeyConflictContext.GUI,
                KeyModifier.CONTROL,
                InputConstants.Type.MOUSE,
                0,
                "key.categories.rsi"
        );

        RSIntegrationMod.MOD_BUS.addListener(
                (RegisterKeyMappingsEvent e) -> {
                    e.register(KEY_CLEAR_SEARCH);
                    e.register(KEY_MOD_FILTER);
                    e.register(KEY_TRANSFER_RECIPE);
                    e.register(KEY_SWIPE_EXTRACT);
                });
    }
}
