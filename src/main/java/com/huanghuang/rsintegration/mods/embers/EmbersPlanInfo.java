package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

public record EmbersPlanInfo(
    @Nullable int[] code,
    @Nullable String[] aspectNames,
    @Nullable String[] inputNames,
    long seed,
    boolean canInfer,
    boolean codeFromCache
) {
    /**
     * Build plan-time Embers alchemy info: cached codes, display names, and
     * whether the bound tablet is available for inference.
     * Returns a dummy (all-null) info if {@code recipeModTypeId} is not
     * {@code "embers_alchemy"}.
     */
    public static EmbersPlanInfo build(@Nullable ServerPlayer player, Recipe<?> recipe,
                                       @Nullable INetwork network, ResourceLocation recipeId,
                                       @Nullable String recipeModTypeId,
                                       @Nullable ResourceLocation dim,
                                       @Nullable net.minecraft.core.BlockPos pos) {
        if (!ModIds.ID_EMBERS_ALCHEMY.equals(recipeModTypeId)) {
            return new EmbersPlanInfo(null, null, null, 0, false, false);
        }

        int[] code = null;
        String[] aspectNames = null;
        String[] inputNames = null;
        long seed = 0;
        boolean codeFromCache = false;

        if (network == null) {
            return new EmbersPlanInfo(null, null, null, 0,
                    dim != null && pos != null, false);
        }

        seed = player.serverLevel().getSeed();
        var savedData = KnownCodeSavedData.get(player.serverLevel());
        savedData.setWorldSeed(seed);
        code = savedData.getCode(recipeId.toString());
        if (code != null) {
            codeFromCache = true;
        } else if (RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get()) {
            try {
                var aspectsField = Reflect.findField(recipe.getClass(), "aspects");
                var inputsField = Reflect.findField(recipe.getClass(), "inputs");
                if (aspectsField.isPresent() && inputsField.isPresent()) {
                    aspectsField.get().setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var aspects = (List<Ingredient>) aspectsField.get().get(recipe);
                    inputsField.get().setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var inputs = (List<Ingredient>) inputsField.get().get(recipe);
                    if (aspects != null && inputs != null && !aspects.isEmpty()) {
                        code = EreAlchemyCalcDelegate.computeCode(seed, recipeId, aspects.size(), inputs.size());
                    }
                }
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Cannot compute code for preview: {}", ex.toString());
            }
        }

        if (code != null) {
            try {
                var aspectsField = Reflect.findField(recipe.getClass(), "aspects");
                var inputsField = Reflect.findField(recipe.getClass(), "inputs");
                if (aspectsField.isPresent() && inputsField.isPresent()) {
                    aspectsField.get().setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var aspects = (List<Ingredient>) aspectsField.get().get(recipe);
                    inputsField.get().setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var inputs = (List<Ingredient>) inputsField.get().get(recipe);
                    if (aspects != null && inputs != null) {
                        aspectNames = new String[code.length];
                        for (int i = 0; i < code.length; i++) {
                            int idx = code[i];
                            if (idx < aspects.size()) {
                                Ingredient aspectIng = aspects.get(idx);
                                ItemStack first = firstDisplayItem(aspectIng);
                                aspectNames[i] = first.isEmpty() ? "?" : first.getHoverName().getString();
                            } else {
                                aspectNames[i] = "?";
                            }
                        }
                        inputNames = new String[code.length];
                        for (int i = 0; i < code.length; i++) {
                            if (i < inputs.size()) {
                                Ingredient inputIng = inputs.get(i);
                                ItemStack first = firstDisplayItem(inputIng);
                                inputNames[i] = first.isEmpty() ? "?" : first.getHoverName().getString();
                            } else {
                                inputNames[i] = "?";
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Cannot resolve display names: {}", ex.toString());
            }
        }

        boolean canInfer = dim != null && pos != null;
        return new EmbersPlanInfo(code, aspectNames, inputNames, seed, canInfer, codeFromCache);
    }

    private static ItemStack firstDisplayItem(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        return items.length > 0 ? items[0] : ItemStack.EMPTY;
    }
}
