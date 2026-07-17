package com.huanghuang.rsintegration.mods.apotheosis;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/** Pure identity rules for Apotheosis library bindings. */
public final class ApotheosisLibraryBinding {
    public static final String LIBRARY = "apotheosis:library";
    public static final String ENDER_LIBRARY = "apotheosis:ender_library";

    private ApotheosisLibraryBinding() {
    }

    public static boolean isLibrary(@Nullable String blockRegistryKey) {
        return LIBRARY.equals(blockRegistryKey) || ENDER_LIBRARY.equals(blockRegistryKey);
    }

    public static boolean matchesSavedBlock(@Nullable String savedKey,
                                            @Nullable ResourceLocation currentKey) {
        return isLibrary(savedKey) && currentKey != null && savedKey.equals(currentKey.toString());
    }
}
