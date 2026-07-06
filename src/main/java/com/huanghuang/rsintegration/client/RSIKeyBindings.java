package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;

public final class RSIKeyBindings {

    private RSIKeyBindings() {}

    public static KeyMapping KEY_CLEAR_SEARCH;
    public static KeyMapping KEY_MOD_FILTER;
    public static KeyMapping KEY_TRANSFER_RECIPE;

    private static volatile boolean registered;

    public static void registerKeyMappings() {
        if (registered) return;
        registered = true;

        KEY_CLEAR_SEARCH = new KeyMapping(
                "key.rsi.clear_search",
                KeyConflictContext.GUI,
                InputConstants.Type.MOUSE,
                2,
                "key.categories.rsi"
        );
        KEY_MOD_FILTER = new KeyMapping(
                "key.rsi.mod_filter",
                KeyConflictContext.GUI,
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_LALT,
                "key.categories.rsi"
        );
        KEY_TRANSFER_RECIPE = new KeyMapping(
                "key.rsi.transfer_recipe",
                KeyConflictContext.GUI,
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_T,
                "key.categories.rsi"
        );

        RSIntegrationMod.MOD_BUS.addListener(
                (RegisterKeyMappingsEvent e) -> {
                    e.register(KEY_CLEAR_SEARCH);
                    e.register(KEY_MOD_FILTER);
                    e.register(KEY_TRANSFER_RECIPE);
                });
    }
}
