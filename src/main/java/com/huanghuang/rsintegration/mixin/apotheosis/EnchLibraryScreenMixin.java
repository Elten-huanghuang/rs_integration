package com.huanghuang.rsintegration.mixin.apotheosis;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/** Adds pinyin and registry-name matching to Apotheosis' native library filter. */
@Pseudo
@Mixin(targets = "dev.shadowsoffire.apotheosis.ench.library.EnchLibraryScreen", remap = false)
public abstract class EnchLibraryScreenMixin {

    @Shadow private EditBox filter;

    @Inject(method = "isAllowedBySearch", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$matchPinyin(Object2IntMap.Entry<Enchantment> entry,
                                CallbackInfoReturnable<Boolean> cir) {
        String query = filter == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }

        Enchantment enchantment = entry.getKey();
        String localized = ChatFormatting.stripFormatting(
                I18n.get(enchantment.getDescriptionId())).toLowerCase(Locale.ROOT);
        ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
        boolean matches = localized.contains(query)
                || PinyinUtil.toPinyin(localized).contains(query)
                || PinyinUtil.toPinyinInitials(localized).contains(query)
                || id != null && (id.toString().contains(query) || id.getPath().contains(query));
        cir.setReturnValue(matches);
    }
}
