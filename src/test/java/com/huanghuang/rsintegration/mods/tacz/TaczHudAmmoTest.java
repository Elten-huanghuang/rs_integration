package com.huanghuang.rsintegration.mods.tacz;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mixin.tacz.GunHudOverlayAmmoMixin;
import com.huanghuang.rsintegration.mixin.tacz.ModernKineticGunScriptAmmoMixin;
import com.huanghuang.rsintegration.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static com.huanghuang.rsintegration.testutil.TaczAmmoTestItems.ammo;
import static com.huanghuang.rsintegration.testutil.TaczAmmoTestItems.gun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TaczHudAmmoTest extends BootstrapTest {
    private Inventory inventory;

    @BeforeEach
    void setup() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
        inventory = new Inventory(mock(Player.class));
    }

    @AfterEach
    void clearState() {
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
        ClientDiskData.clear();
    }

    @Test
    void hudAddsMatchingDiskRoundsToTheNativeInventoryCount() throws ReflectiveOperationException {
        inventory.setItem(0, ammo("tacz:9mm", 5));
        inventory.setItem(1, ammo("tacz:556x45", 7));
        ClientDiskData.apply(0, 0, 0, 1, List.of(
                ammo("tacz:9mm", 64), ammo("tacz:9mm", 12), ammo("tacz:556x45", 20)));

        assertEquals(81, hudCount(gun("tacz:9mm")));
        assertEquals(27, hudCount(gun("tacz:556x45")));
        assertEquals(0, hudCount(gun("tacz:12g")));
    }

    @Test
    void repeatedHudRefreshDoesNotCountTheSameDiskRoundsTwice() throws ReflectiveOperationException {
        inventory.setItem(0, ammo("tacz:9mm", 5));
        ClientDiskData.apply(0, 0, 0, 1, List.of(ammo("tacz:9mm", 64)));

        assertEquals(69, hudCount(gun("tacz:9mm")));
        assertEquals(69, hudCount(gun("tacz:9mm")));
        assertEquals(64, ClientDiskData.getTaczAmmo().get(0).getCount());
    }

    @Test
    void updatedAndRemovedDiskSnapshotsImmediatelyChangeTheNextHudCount()
            throws ReflectiveOperationException {
        ClientDiskData.apply(0, 0, 0, 1, List.of(ammo("tacz:9mm", 64)));
        assertEquals(64, hudCount(gun("tacz:9mm")));
        ClientDiskData.apply(0, 0, 0, 2, List.of(ammo("tacz:9mm", 34)));
        assertEquals(34, hudCount(gun("tacz:9mm")));
        ClientDiskData.apply(0, 0, 0, 3, List.of());
        assertEquals(0, hudCount(gun("tacz:9mm")));
    }

    @Test
    void disabledCompatibilityKeepsTheNativeHudCount() throws ReflectiveOperationException {
        inventory.setItem(0, ammo("tacz:9mm", 5));
        ClientDiskData.apply(0, 0, 0, 1, List.of(ammo("tacz:9mm", 64)));
        RSIntegrationConfig.ENABLE_TACZ.set(false);
        assertEquals(5, hudCount(gun("tacz:9mm")));
        RSIntegrationConfig.ENABLE_TACZ.set(true);
        RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.set(false);
        assertEquals(5, hudCount(gun("tacz:9mm")));
    }

    @Test
    void nativeCreativeAmmoCountIsPreserved() {
        ClientDiskData.apply(0, 0, 0, 1, List.of(ammo("tacz:9mm", 64)));
        assertEquals(9999, TaczResonanceAmmoHandler.includeClientAmmoCount(gun("tacz:9mm"), 9999));
    }

    @Test
    void successfulServerReloadRequestsAnImmediateDiskSync() throws ReflectiveOperationException {
        ServerPlayer player = mock(ServerPlayer.class);
        try (MockedStatic<PassiveEffectEngine> sync = mockStatic(PassiveEffectEngine.class)) {
            consumeCallback(player, 30);
            sync.verify(() -> PassiveEffectEngine.refreshPlayer(player));
        }
    }

    @Test
    void failedClientAndDisabledReloadsDoNotRequestAServerSync() throws ReflectiveOperationException {
        ServerPlayer player = mock(ServerPlayer.class);
        try (MockedStatic<PassiveEffectEngine> sync = mockStatic(PassiveEffectEngine.class)) {
            consumeCallback(player, 0);
            consumeCallback(mock(Player.class), 30);
            RSIntegrationConfig.ENABLE_TACZ.set(false);
            consumeCallback(player, 30);
            sync.verifyNoInteractions();
        }
    }

    private int hudCount(ItemStack gun) throws ReflectiveOperationException {
        // 先运行 TaCZ jar 的真实背包计数，再通过我们实际注入的回调补入盘内数量。
        Method nativeScan = GunHudOverlay.class.getDeclaredMethod(
                "handleInventoryAmmo", ItemStack.class, Inventory.class);
        nativeScan.setAccessible(true);
        nativeScan.invoke(null, gun, inventory);
        Field nativeCount = GunHudOverlay.class.getDeclaredField("cacheInventoryAmmoCount");
        nativeCount.setAccessible(true);
        Field mixinCount = GunHudOverlayAmmoMixin.class.getDeclaredField("cacheInventoryAmmoCount");
        mixinCount.setAccessible(true);
        mixinCount.setInt(null, nativeCount.getInt(null));
        Method callback = GunHudOverlayAmmoMixin.class.getDeclaredMethod(
                "rsi$includeResonanceAmmo", ItemStack.class, Inventory.class, CallbackInfo.class);
        callback.setAccessible(true);
        callback.invoke(null, gun, inventory, new CallbackInfo("handleInventoryAmmo", false));
        return mixinCount.getInt(null);
    }

    private static void consumeCallback(LivingEntity shooter, int consumed) throws ReflectiveOperationException {
        ModernKineticGunScriptAmmoMixin mixin = new ModernKineticGunScriptAmmoMixin() {
            @Override public LivingEntity getShooter() { return shooter; }
        };
        Method callback = ModernKineticGunScriptAmmoMixin.class.getDeclaredMethod(
                "rsi$syncConsumedAmmo", int.class, CallbackInfoReturnable.class);
        callback.setAccessible(true);
        callback.invoke(mixin, 30, new CallbackInfoReturnable<>("consumeAmmoFromPlayer", false, consumed));
    }
}
