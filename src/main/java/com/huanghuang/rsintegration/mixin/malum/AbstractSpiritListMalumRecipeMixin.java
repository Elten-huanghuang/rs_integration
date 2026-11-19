package com.huanghuang.rsintegration.mixin.malum;

import com.sammy.malum.common.recipe.AbstractSpiritListMalumRecipe;
import com.sammy.malum.common.recipe.SpiritInfusionRecipe;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = AbstractSpiritListMalumRecipe.class, remap = false)
public class AbstractSpiritListMalumRecipeMixin {

    @Inject(method = "doSpiritsMatch", at = @At("RETURN"), cancellable = true)
    private void rsi$relaxedDoSpiritsMatch(List<ItemStack> offered, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && rsi$acceptsExtraAltarSpirits((AbstractSpiritListMalumRecipe) (Object) this, offered)) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    static boolean rsi$acceptsExtraAltarSpirits(AbstractSpiritListMalumRecipe recipe, List<ItemStack> offered) {
        // 只放宽祭坛的匹配；坩埚必须用精魂种类区分共用促符的配方。
        return recipe instanceof SpiritInfusionRecipe && offered.size() > recipe.spirits.size()
                && recipe.getSortedSpirits(offered).size() == recipe.spirits.size();
    }
}
