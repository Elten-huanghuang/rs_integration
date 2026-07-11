package com.huanghuang.rsintegration.mixin.moonstone;

import com.google.common.collect.Multimap;
import com.huanghuang.rsintegration.RSIntegrationMod;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.SlotContext;

import java.util.List;

@Mixin(value = com.moonstone.moonstonemod.item.maxitem.book.nine_sword_books.class, remap = false)
public abstract class NineSwordBooksMixin {

    @Unique
    private static final ThreadLocal<Player> rsi$currentPlayer = new ThreadLocal<>();

    // 缓存 RS 盘折算后的额外剑数
    @Unique
    private static final ThreadLocal<Integer> rsi$cachedExtraSize = ThreadLocal.withInitial(() -> 0);

    @Inject(method = "curioTick", at = @At("HEAD"))
    private void rsi$onCurioTickHead(SlotContext ctx, ItemStack stack, CallbackInfo ci) {
        if (ctx.entity() instanceof Player player) {
            rsi$currentPlayer.set(player);
            rsi$cachedExtraSize.set(rsi$computeExtraSize(player));
        }
    }

    @Inject(method = "curioTick", at = @At("RETURN"))
    private void rsi$onCurioTickReturn(SlotContext ctx, ItemStack stack, CallbackInfo ci) {
        rsi$currentPlayer.remove();
        rsi$cachedExtraSize.remove();
    }

    @Inject(method = "Head", at = @At("HEAD"))
    private void rsi$onHeadHead(Player player, ItemStack stack, CallbackInfoReturnable<Multimap<Attribute, AttributeModifier>> cir) {
        rsi$currentPlayer.set(player);
        rsi$cachedExtraSize.set(rsi$computeExtraSize(player));
    }

    @Inject(method = "Head", at = @At("RETURN"))
    private void rsi$onHeadReturn(Player player, ItemStack stack, CallbackInfoReturnable<Multimap<Attribute, AttributeModifier>> cir) {
        rsi$currentPlayer.remove();
        rsi$cachedExtraSize.remove();
    }

    // 拦截 integers.size()，注入等效后的剑数
    @Redirect(method = {"Head", "curioTick"}, at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"), remap = false)
    private int rsi$redirectListSize(List<?> list) {
        int originalSize = list.size();
        if (!list.isEmpty() && !(list.get(0) instanceof Integer)) {
            return originalSize;
        }

        Integer extra = rsi$cachedExtraSize.get();
        if (extra == null) extra = 0;

        int finalSize = originalSize + extra;

        Player player = rsi$currentPlayer.get();
        if (player != null && player.tickCount % 40 == 0 && extra > 0) {
            // 已移除 "Bug-Scaled" 字眼，因为原模组已修复该 Bug，现在是 1:1 绝对公平转换
            RSIntegrationMod.LOGGER.info("[RSI-NineSwords] size() Hack! HotbarSize: {}, DiskSize(Extra): {}, Total: {}", originalSize, extra, finalSize);
        }

        return finalSize;
    }

    // ========== 核心逻辑：1:1 统计快捷栏与 RS 盘的剑 ==========
    @Unique
    private int rsi$computeExtraSize(Player player) {
        if (!(player instanceof ServerPlayer sp)) return 0;
        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) return 0;

        // 获取原模组的 Config List，用于备用判定
        @SuppressWarnings("unchecked")
        List<? extends String> configList = (List<? extends String>) com.moonstone.moonstonemod.Config.SERVER.nineSwordList.get();

        // 先数清楚快捷栏里已经有多少把有效剑
        int hotbarSwords = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack sword = player.getInventory().items.get(i);
            if (!sword.isEmpty() && rsi$isValidSword(sword, configList)) {
                hotbarSwords++;
            }
        }

        // 我们最多只能再从 RS 盘里拿几把？(默认 9 把上限)
        int maxSwords = RSIntegrationConfig.NINE_SWORD_MAX_COUNT.get();
        int needed = Math.max(0, maxSwords - hotbarSwords);
        if (needed <= 0) return 0;

        int extraSize = 0;
        for (ItemStack sword : disk.getStacks()) {
            if (needed <= 0) break;
            if (sword.isEmpty()) continue;

            if (rsi$isValidSword(sword, configList)) {
                // 原模组 Bug 已修复，现在每一把符合条件的剑都严格只算作 1 把
                extraSize++;
                needed--;
            }
        }
        return extraSize;
    }

    @Unique
    private static boolean rsi$isValidSword(ItemStack sword, List<? extends String> configList) {
        Item item = sword.getItem();
        boolean isSwordItem = item instanceof SwordItem;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        String regName = key != null ? key.toString() : "";
        boolean containsSword = regName.contains("sword");

        // 兼容拔刀剑等各种武器，弥补原作者“只认原版剑”的歧视判定
        boolean isRsiCompat = regName.contains("slashblade") || regName.contains("blade") ||
                regName.contains("katana") || sword.is(ItemTags.SWORDS) || sword.is(ItemTags.AXES);

        if (isSwordItem || containsSword || isRsiCompat) {
            return true;
        }

        // 如果都不是，去查配置列表里有没有强制指定的特殊武器
        for (String s : configList) {
            if (regName.equals(s)) return true;
        }

        return false;
    }
}