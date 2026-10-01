package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.storage.rs.VerifiedDataWriter;
import com.refinedmods.refinedstorage.apiimpl.util.RSSavedData;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(RSSavedData.class)
public abstract class VerifiedStorageSaveMixin {
    @Inject(method = "save(Ljava/io/File;)V", at = @At("HEAD"), cancellable = true)
    private void rsi$writeVerifiedStorage(File file, CallbackInfo ci) {
        if (!RSStorageConfig.enabled(RSStorageConfig.VERIFY_SAVES)) return;
        ci.cancel();
        VerifiedDataWriter.save((SavedData) (Object) this, file);
    }
}
