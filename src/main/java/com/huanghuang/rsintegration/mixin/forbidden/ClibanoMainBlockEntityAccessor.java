package com.huanghuang.rsintegration.mixin.forbidden;

import com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClibanoMainBlockEntity.class)
public interface ClibanoMainBlockEntityAccessor {

    @Invoker(value = "getBurnDuration", remap = false)
    int rsi$callGetBurnDuration(ItemStack stack);
}
