package com.huanghuang.rsintegration.mixin.tacz;

import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GunHudOverlayAmmoMixinTest {
    @Test
    void targetsTheCacheMethodSharedByOldAndNewTacz() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream source = GunHudOverlay.class.getResourceAsStream("GunHudOverlay.class")) {
            assertNotNull(source);
            new ClassReader(source).accept(
                    node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        assertTrue(node.methods.stream().anyMatch(method ->
                TaczMixinTargets.GUN_HUD_HANDLE_CACHE_COUNT.equals(method.name)
                        && TaczMixinTargets.GUN_HUD_HANDLE_CACHE_COUNT_DESCRIPTOR.equals(method.desc)));
        assertTrue(node.fields.stream().anyMatch(field ->
                TaczMixinTargets.GUN_HUD_CACHE_INVENTORY_AMMO_FIELD.equals(field.name)
                        && TaczMixinTargets.GUN_HUD_CACHE_INVENTORY_AMMO_DESCRIPTOR.equals(field.desc)));
        assertTrue(node.fields.stream().anyMatch(field ->
                TaczMixinTargets.GUN_HUD_CHECK_AMMO_TIMESTAMP_FIELD.equals(field.name)
                        && TaczMixinTargets.GUN_HUD_CHECK_AMMO_TIMESTAMP_DESCRIPTOR.equals(field.desc)));
    }

    @Test
    void injectionHandlerKeepsMixinLoadingVisibilityRules() throws Exception {
        Method handler = GunHudOverlayAmmoMixin.class.getDeclaredMethod(
                "rsi$includeResonanceAmmo", LocalPlayer.class, ItemStack.class,
                GunData.class, IGun.class, CallbackInfo.class);

        assertTrue(Modifier.isPrivate(handler.getModifiers()));
        assertTrue(Modifier.isStatic(handler.getModifiers()));
    }

    @Test
    void doesNotAppendResonanceAmmoWhenNativeCacheWasNotRefreshed() {
        assertTrue(GunHudOverlayAmmoMixin.rsi$shouldIncludeCache(20L, Long.MIN_VALUE));
        assertFalse(GunHudOverlayAmmoMixin.rsi$shouldIncludeCache(20L, 20L));
        assertTrue(GunHudOverlayAmmoMixin.rsi$shouldIncludeCache(21L, 20L));
    }

    @Test
    void pluginGatesOnTheExactMethodAndCacheFields() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();

        assertTrue(plugin.shouldApplyMixin(
                GunHudOverlay.class.getName(), GunHudOverlayAmmoMixin.class.getName()));
        assertFalse(plugin.shouldApplyMixin(
                "absent.tacz.GunHudOverlay", GunHudOverlayAmmoMixin.class.getName()));
    }
}
