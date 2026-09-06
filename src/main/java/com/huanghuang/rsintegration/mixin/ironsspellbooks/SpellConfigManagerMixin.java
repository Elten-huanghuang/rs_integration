package com.huanghuang.rsintegration.mixin.ironsspellbooks;

import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.api.config.SpellConfigManager", remap = false)
public abstract class SpellConfigManagerMixin {
    @Inject(method = "buildConfigManager(Ljava/util/Map;Z)Z", at = @At("RETURN"),
            remap = false, require = 1)
    private void rsi$refreshAppliedSpellConfig(CallbackInfoReturnable<Boolean> callback) {
        // False can still publish valid entries plus defaults for malformed entries.
        IronSpellBooksRecipeCatalog.onSpellConfigApplied();
    }
}
