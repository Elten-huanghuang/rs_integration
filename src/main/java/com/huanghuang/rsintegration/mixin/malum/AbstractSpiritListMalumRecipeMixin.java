package com.huanghuang.rsintegration.mixin.malum;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Relaxes Malum's spirit matching so that extra spirit types on the altar
 * no longer prevent crafting. The vanilla check requires exact type-count
 * equality ({@code recipe.spirits.size() != altarSpirits.size() → fail}),
 * which blocks recipes when unrelated spirits are nearby.
 * <p>
 * After this change, only the required spirits must be present with
 * sufficient counts — extra types are silently tolerated.
 */
@Mixin(value = com.sammy.malum.common.recipe.AbstractSpiritListMalumRecipe.class, remap = false)
public class AbstractSpiritListMalumRecipeMixin {

    @Unique
    private static Field rsi$spiritsField;

    @Inject(method = "doSpiritsMatch", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void rsi$relaxedDoSpiritsMatch(List<ItemStack> altarSpirits, CallbackInfoReturnable<Boolean> cir) {
        List<?> requiredSpirits;
        try {
            if (rsi$spiritsField == null) {
                rsi$spiritsField = this.getClass().getField("spirits");
                rsi$spiritsField.setAccessible(true);
            }
            requiredSpirits = (List<?>) rsi$spiritsField.get(this);
        } catch (Exception e) {
            return; // fall through to original logic
        }

        if (requiredSpirits == null || requiredSpirits.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }

        for (Object required : requiredSpirits) {
            boolean found = false;
            for (ItemStack altarStack : altarSpirits) {
                try {
                    Object requiredItem = required.getClass().getMethod("getItem").invoke(required);
                    int requiredCount = (int) required.getClass().getMethod("getCount").invoke(required);
                    if (altarStack.getItem().equals(requiredItem)
                            && altarStack.getCount() >= requiredCount) {
                        found = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (!found) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}
