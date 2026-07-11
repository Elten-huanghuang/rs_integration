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
            RSIntegrationMod.LOGGER.info("[RSI-NineSwords] size() Hack! HotbarSize: {}, DiskSize(Bug-Scaled): {}, Total: {}", originalSize, extra, finalSize);
        }

        return finalSize;
    }

    // ========== 核心逻辑：完美复刻原版的多倍统计 Bug ==========
    @Unique
    private int rsi$computeExtraSize(Player player) {
        if (!(player instanceof ServerPlayer sp)) return 0;
        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) return 0;

        // 获取原模组的 Config List，判断它当前会将一把剑翻多少倍
        @SuppressWarnings("unchecked")
        List<? extends String> configList = (List<? extends String>) com.moonstone.moonstonemod.Config.SERVER.nineSwordList.get();
        int bugMultiplier = configList.size();
        if (bugMultiplier == 0) bugMultiplier = 1;

        // 先数清楚快捷栏里已经有多少把有效剑
        int hotbarSwords = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack sword = player.getInventory().items.get(i);
            if (!sword.isEmpty() && rsi$getSwordWeight(sword, configList, bugMultiplier) > 0) {
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

            int weight = rsi$getSwordWeight(sword, configList, bugMultiplier);
            if (weight > 0) {
                // 如果是一把合格的剑，我们同样把它乘以 bugMultiplier 塞进去
                extraSize += weight;
                needed--;
            }
        }
        return extraSize;
    }

    @Unique
    private static int rsi$getSwordWeight(ItemStack sword, List<? extends String> configList, int bugMultiplier) {
        Item item = sword.getItem();
        boolean isSwordItem = item instanceof SwordItem;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        String regName = key != null ? key.toString() : "";
        boolean containsSword = regName.contains("sword");

        // 兼容拔刀剑等各种武器
        boolean isRsiCompat = regName.contains("slashblade") || regName.contains("blade") ||
                regName.contains("katana") || sword.is(ItemTags.SWORDS) || sword.is(ItemTags.AXES);

        // 如果是标准的剑/拔刀剑，原版代码会给它乘上 config 的长度 (默认是 2)
        if (isSwordItem || containsSword || isRsiCompat) {
            return bugMultiplier;
        }

        // 如果都不是，但在 config 配置列表里，原版只会给它 1 的权重
        for (String s : configList) {
            if (regName.equals(s)) return 1;
        }

        return 0;
    }
}