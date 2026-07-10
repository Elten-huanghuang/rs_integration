package com.huanghuang.rsintegration.mixin.moonstone;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.SlotContext;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Mixin(value = com.moonstone.moonstonemod.item.maxitem.book.nine_sword_books.class, remap = false)
public abstract class NineSwordBookMixin {

    private static final UUID BONUS_UUID = UUID.fromString("492dc575-b72e-3d83-b2fd-33ab63727151");

    @Unique
    private static final ThreadLocal<Player> rsi$currentPlayer = new ThreadLocal<>();

    @Inject(method = "curioTick", at = @At("HEAD"))
    private void rsi$capturePlayer(SlotContext ctx, ItemStack stack, CallbackInfo ci) {
        if (ctx.entity() instanceof Player player) {
            rsi$currentPlayer.set(player);
        }
    }

    @ModifyVariable(method = "curioTick", at = @At("STORE"), ordinal = 1, index = 10)
    private int rsi$addDiskSwordCount(int hotbarCount) {
        int count = hotbarCount;
        Player player = rsi$currentPlayer.get();
        if (player instanceof ServerPlayer sp) {
            ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
            if (disk != null) {
                for (ItemStack s : disk.getStacks()) {
                    if (!s.isEmpty() && rsi$isSword(s)) count++;
                }
            }
        }
        return Math.min(count, RSIntegrationConfig.NINE_SWORD_MAX_COUNT.get());
    }

    @Inject(method = "curioTick", at = @At("RETURN"))
    private void rsi$clearPlayer(SlotContext ctx, ItemStack stack, CallbackInfo ci) {
        rsi$currentPlayer.remove();
    }

    @Inject(method = "Head", at = @At("RETURN"), cancellable = true)
    private void rsi$addDiskSwordsToHead(Player player, ItemStack stack,
                                         CallbackInfoReturnable<Multimap<Attribute, AttributeModifier>> cir) {
        if (!(player instanceof ServerPlayer sp)) return;

        Multimap<Attribute, AttributeModifier> originalMap = cir.getReturnValue();
        if (originalMap == null || originalMap.isEmpty()) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) return;

        int diskSwords = 0;
        for (ItemStack s : disk.getStacks()) {
            if (!s.isEmpty() && rsi$isSword(s)) diskSwords++;
        }
        if (diskSwords == 0) return;

        int hotbarSwords = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().items.get(i);
            if (!s.isEmpty() && rsi$isSword(s)) hotbarSwords++;
        }
        if (hotbarSwords == 0) return;

        int max = RSIntegrationConfig.NINE_SWORD_MAX_COUNT.get();
        int effectiveDisk = Math.min(diskSwords, Math.max(0, max - hotbarSwords));
        if (effectiveDisk <= 0) return;

        double scale = (double) effectiveDisk / hotbarSwords;

        Multimap<Attribute, AttributeModifier> newMap = HashMultimap.create(originalMap);

        for (Map.Entry<Attribute, Collection<AttributeModifier>> entry : originalMap.asMap().entrySet()) {
            Attribute attr = entry.getKey();
            for (AttributeModifier mod : entry.getValue()) {
                newMap.put(attr, new AttributeModifier(
                        BONUS_UUID,
                        mod.getName(),
                        mod.getAmount() * scale,
                        mod.getOperation()));
            }
        }

        cir.setReturnValue(newMap);
    }

    @Unique
    private static boolean rsi$isSword(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SwordItem) return true;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        if (key != null) {
            String path = key.getPath().toLowerCase();
            String namespace = key.getNamespace().toLowerCase();
            if (namespace.contains("slashblade") || path.contains("sword") ||
                    path.contains("blade") || path.contains("katana")) {
                return true;
            }
        }
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }
}
