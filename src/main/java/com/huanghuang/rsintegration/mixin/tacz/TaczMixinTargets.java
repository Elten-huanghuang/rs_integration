package com.huanghuang.rsintegration.mixin.tacz;

/** TaCZ Mixin 共用的目标签名，避免注入器与门控条件发生漂移。 */
public final class TaczMixinTargets {
    public static final String GUN_HUD_HANDLE_CACHE_COUNT = "handleCacheCount";
    public static final String GUN_HUD_HANDLE_CACHE_COUNT_DESCRIPTOR =
            "(Lnet/minecraft/client/player/LocalPlayer;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;"
                    + "Lcom/tacz/guns/api/item/IGun;)V";
    public static final String GUN_HUD_HANDLE_CACHE_COUNT_METHOD =
            GUN_HUD_HANDLE_CACHE_COUNT + GUN_HUD_HANDLE_CACHE_COUNT_DESCRIPTOR;
    public static final String GUN_HUD_CACHE_INVENTORY_AMMO_FIELD =
            "cacheInventoryAmmoCount";
    public static final String GUN_HUD_CACHE_INVENTORY_AMMO_DESCRIPTOR = "I";
    public static final String GUN_HUD_CHECK_AMMO_TIMESTAMP_FIELD =
            "checkAmmoTimestamp";
    public static final String GUN_HUD_CHECK_AMMO_TIMESTAMP_DESCRIPTOR = "J";

    private TaczMixinTargets() {}
}
