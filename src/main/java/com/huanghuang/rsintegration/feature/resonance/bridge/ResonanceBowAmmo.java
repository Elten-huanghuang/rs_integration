package com.huanghuang.rsintegration.feature.resonance.bridge;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.living.LivingGetProjectileEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID)
public final class ResonanceBowAmmo {
    private ResonanceBowAmmo() {}

    public static boolean isNormalArrow(ItemStack stack) {
        return stack.is(Items.ARROW);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGetProjectile(LivingGetProjectileEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getProjectileWeaponItemStack();
        if (!event.getProjectileItemStack().isEmpty() || !bow.is(Items.BOW) || player.getAbilities().instabuild
                || EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, bow) <= 0) {
            return;
        }
        boolean hasArrow = player instanceof ServerPlayer serverPlayer
                ? ResonanceInventoryBridge.hasItem(serverPlayer, ResonanceBowAmmo::isNormalArrow)
                : player.isLocalPlayer() && ClientDiskData.hasNormalArrow();
        if (!hasArrow) return;

        // 只向原版逻辑提供不会被扣除的普通箭副本。
        ItemStack arrow = new ItemStack(Items.ARROW);
        if (arrow.getItem() instanceof ArrowItem ammo && ammo.isInfinite(arrow, bow, player)) {
            event.setProjectileItemStack(arrow);
        }
    }
}
