package com.huanghuang.rsintegration.mixin.itemcollectors;

import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a nearby Item Collectors block from stealing delayed machine output. */
@Pseudo
@Mixin(targets = "com.supermartijn642.itemcollectors.CollectorBlockEntity", remap = false)
public abstract class CollectorBlockEntityMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$skipActiveCraftOutputZone(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        try {
            Object area = self.getClass().getMethod("getAffectedArea").invoke(self);
            if (area instanceof AABB bounds
                    && CraftOutputInterceptor.intersectsActiveZone(level, bounds)) {
                ci.cancel();
            }
        } catch (ReflectiveOperationException ignored) {
            // A future Item Collectors version with no affected-area accessor
            // simply keeps its normal behavior instead of blocking its tick.
        }
    }
}
