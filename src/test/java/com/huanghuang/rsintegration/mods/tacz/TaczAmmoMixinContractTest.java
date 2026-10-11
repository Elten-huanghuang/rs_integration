package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TaczAmmoMixinContractTest {
    @Test
    void actualTaczJarHasEveryRequiredCapabilityCallSite() throws IOException {
        assertCapabilityCall(AbstractGunItem.class, "canReload");
        assertCapabilityCall(ModernKineticGunScriptAPI.class, "consumeAmmoFromPlayer");
        assertCapabilityCall(ModernKineticGunScriptAPI.class, "hasAmmoToConsume");
    }

    @Test
    void ammoMixinsRequireBothTaczAndAResonanceBackend() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();
        String mixin = "com.huanghuang.rsintegration.mixin.tacz.AbstractGunItemAmmoMixin";
        assertFalse(plugin.shouldApplyMixin("missing.tacz.AbstractGunItem", mixin));
        assertTrue(plugin.shouldApplyMixin(AbstractGunItem.class.getName(), mixin));
    }

    @Test
    void hudCountingHookMatchesTheActualTaczJar() throws IOException {
        ClassNode hud = readClass(GunHudOverlay.class);
        assertTrue(hud.fields.stream().anyMatch(field -> "cacheInventoryAmmoCount".equals(field.name)
                && "I".equals(field.desc)));
        assertTrue(hud.methods.stream().anyMatch(method -> "handleInventoryAmmo".equals(method.name)
                && "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Inventory;)V".equals(method.desc)));
        MethodNode cache = hud.methods.stream().filter(method -> "handleCacheCount".equals(method.name))
                .findFirst().orElseThrow();
        assertEquals(1, Arrays.stream(cache.instructions.toArray()).filter(instruction ->
                instruction instanceof MethodInsnNode call && "handleInventoryAmmo".equals(call.name)).count());
        assertTrue(readClass(ModernKineticGunScriptAPI.class).methods.stream().anyMatch(method ->
                "getShooter".equals(method.name) && "()Lnet/minecraft/world/entity/LivingEntity;".equals(method.desc)));
    }

    private static ClassNode readClass(Class<?> type) throws IOException {
        try (InputStream stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void assertCapabilityCall(Class<?> type, String name) throws IOException {
        try (InputStream stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            MethodNode method = node.methods.stream().filter(candidate -> name.equals(candidate.name))
                    .findFirst().orElseThrow();
            long callSites = Arrays.stream(method.instructions.toArray())
                    .filter(instruction -> instruction instanceof MethodInsnNode call
                            && "net/minecraft/world/entity/LivingEntity".equals(call.owner)
                            && "getCapability".equals(call.name)
                            && "(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;".equals(call.desc))
                    .count();
            assertEquals(1, callSites, type.getSimpleName() + "." + name);
        }
    }
}
