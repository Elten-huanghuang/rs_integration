package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.BindingStorage;
import com.huanghuang.rsintegration.mods.goety.AltarCraftButtons;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.goety.GoetyRSNetworkHandler;
import com.huanghuang.rsintegration.mods.goety.RSClientAvailabilityCache;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(value = RecipeGuiLayouts.class, remap = false)
public class RecipeGuiLayoutsMixin {

    private static final ResourceLocation WR_CRYSTALLIZER_UID =
            new ResourceLocation("wizards_reborn", "wissen_crystallizer");
    private static final ResourceLocation WR_ITERATOR_UID =
            new ResourceLocation("wizards_reborn", "arcane_iterator");
    private static final ResourceLocation MALUM_SPIRIT_INFUSION_UID =
            new ResourceLocation("malum", "spirit_infusion");
    private static final ResourceLocation WR_WORKBENCH_UID =
            new ResourceLocation("wizards_reborn", "arcane_workbench");
    private static final ResourceLocation WR_CRYSTAL_RITUAL_UID =
            new ResourceLocation("wizards_reborn", "crystal_ritual");
    private static final ResourceLocation WR_CRYSTAL_INFUSION_UID =
            new ResourceLocation("wizards_reborn", "crystal_infusion");

    @Shadow
    private List<RecipeLayoutWithButtons<?>> recipeLayoutsWithButtons;

    @Unique
    private final List<Integer> rsi$layoutIndices = new ArrayList<>();
    @Unique
    private final List<int[]> rsi$positions = new ArrayList<>();

    @Unique
    private final List<ResourceLocation> rsi$recipeIds = new ArrayList<>();

    @Inject(method = "setRecipeLayoutsWithButtons", at = @At("RETURN"))
    private void rsi$onLayoutsSet(CallbackInfo ci) {
        AltarCraftButtons.clear();
        rsi$layoutIndices.clear();
        rsi$positions.clear();

        rsi$recipeIds.clear();

        if (!RSIntegrationConfig.ENABLE_JEI.get()) return;

        AbstractContainerMenu container = getParentContainer();
        var player = Minecraft.getInstance().player;

        // Compute once: RS available = mod loaded (server handler fails gracefully if no network)
        boolean rsAvailable = ModList.get().isLoaded("refinedstorage");

        for (int i = 0; i < recipeLayoutsWithButtons.size(); i++) {
            RecipeLayoutWithButtons<?> layout = recipeLayoutsWithButtons.get(i);
            IRecipeLayoutDrawable<?> recipeLayout = layout.recipeLayout();
            Object recipe = getRecipeFromLayout(recipeLayout);
            if (recipe == null) continue;

            String filter = getBindingFilter(recipe, recipeLayout);
            boolean isGeneric = false;
            if (filter == null) {
                // For vanilla CraftingRecipe / unrecognized recipes, check RS availability
                if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe
                        && rsAvailable) {
                    filter = "generic";
                    isGeneric = true;
                } else {
                    continue;
                }
            }

            ResourceLocation recipeId = getRecipeId(recipe);
            if (recipeId == null) continue;

            ResourceLocation bindingDim;
            BlockPos machinePos;
            if (isGeneric) {
                bindingDim = player.level().dimension().location();
                machinePos = player.blockPosition();
            } else {
                BindingStorage.BindingEntry binding = findBinding(container, filter);
                if (binding == null) continue;
                bindingDim = binding.dim();
                machinePos = binding.pos();
            }

            Runnable handler = createHandler(recipe, recipeId, bindingDim, machinePos, filter);
            if (handler == null) continue;

            String tooltipKey;
            if (rsi$isGoetyRitual(recipe)) {
                tooltipKey = "gui.rs_integration.jei.altar_craft";
            } else if (filter.equals("spirit_altar")) {
                tooltipKey = "gui.rs_integration.jei.malum_spirit_craft";
            } else if (filter.equals("crystal_ritual")) {
                tooltipKey = "gui.rs_integration.jei.wr_crystal_craft";
            } else if (filter.equals("hephaestus_forge")) {
                tooltipKey = "gui.rs_integration.jei.fa_ritual_craft";
            } else if (filter.equals("crucible")) {
                tooltipKey = "gui.rs_integration.jei.eidolon_crucible_craft";
            } else if (filter.equals("generic")) {
                tooltipKey = "gui.rs_integration.jei.rs_auto_craft";
            } else {
                tooltipKey = "gui.rs_integration.jei.wr_remote_craft";
            }

            rsi$layoutIndices.add(i);
            rsi$positions.add(new int[]{0, 0, 10, 10});

            rsi$recipeIds.add(recipeId);

            ModType modType = computeModType(recipe);
            AltarCraftButtons.add(0, 0, 10, 10, handler, tooltipKey, recipeId,
                    bindingDim, machinePos, modType);

            if (rsi$isGoetyRitual(recipe)) {
                GoetyRSNetworkHandler.sendCheckRS(recipeId, bindingDim, machinePos);
            }
        }
    }

    @Inject(method = "updateRecipeButtonPositions", at = @At("RETURN"))
    private void rsi$updatePositions(CallbackInfo ci) {
        AltarCraftButtons.clearTransferPositions();
        for (int i = 0; i < rsi$layoutIndices.size(); i++) {
            int layoutIdx = rsi$layoutIndices.get(i);
            if (layoutIdx >= recipeLayoutsWithButtons.size()) continue;

            RecipeLayoutWithButtons<?> layout = recipeLayoutsWithButtons.get(layoutIdx);
            RecipeTransferButton transferBtn = layout.transferButton();
            ImmutableRect2i area = ((GuiIconToggleButtonAccessor) transferBtn).getButton().getArea();
            int bx = area.getX();
            int by = area.getY() + area.getHeight();

            int[] pos = rsi$positions.get(i);
            pos[0] = bx;
            pos[1] = by;
            pos[2] = area.getWidth();
            pos[3] = area.getHeight();

            List<int[]> globalPos = AltarCraftButtons.getPositions();
            if (i < globalPos.size()) {
                int[] gp = globalPos.get(i);
                gp[0] = bx;
                gp[1] = by;
                gp[2] = area.getWidth();
                gp[3] = area.getHeight();
            }

            AltarCraftButtons.addTransferPos(area.getX(), area.getY(), area.getWidth(), area.getHeight());
        }
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void rsi$drawButtons(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                  CallbackInfoReturnable<Optional<IRecipeLayoutDrawable<?>>> cir) {
        if (rsi$positions.isEmpty()) return;

        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < rsi$positions.size(); i++) {
            int[] pos = rsi$positions.get(i);
            int bx = pos[0], by = pos[1], bw = pos[2], bh = pos[3];
            boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;

            ResourceLocation recipeId = rsi$recipeIds.get(i);
            boolean[] rsResults = recipeId != null ? RSClientAvailabilityCache.get(recipeId) : null;
            boolean hasData = rsResults != null && rsResults.length > 0;
            boolean rsAvailable = hasData;
            if (rsAvailable) {
                for (boolean b : rsResults) {
                    if (!b) { rsAvailable = false; break; }
                }
            }

            int bgColor, borderColor, textColor;
            if (hasData) {
                if (rsAvailable) {
                    bgColor = hovered ? 0xFF33AA33 : 0xFF226622;
                    borderColor = hovered ? 0xFF66FF66 : 0xFF33AA33;
                    textColor = hovered ? 0xFFFFFF : 0xCCFFCC;
                } else {
                    bgColor = hovered ? 0xFFAA3333 : 0xFF662222;
                    borderColor = hovered ? 0xFFFF6666 : 0xFFAA3333;
                    textColor = hovered ? 0xFFFFFF : 0xFFCCCC;
                }
            } else {
                bgColor = hovered ? 0xFF555555 : 0xFF333333;
                borderColor = hovered ? 0xFFFFFFFF : 0xFF888888;
                textColor = hovered ? 0xFFFFFF : 0xAAAAAA;
            }

            guiGraphics.fill(bx, by, bx + bw, by + bh, borderColor);
            guiGraphics.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, bgColor);

            String symbol = hasData && rsAvailable ? "✓" : "+";
            int textW = font.width(symbol);
            guiGraphics.drawString(font, symbol,
                    bx + (bw - textW) / 2,
                    by + (bh - font.lineHeight) / 2,
                    textColor);
        }
    }

    @Unique
    private static Object getRecipeFromLayout(IRecipeLayoutDrawable<?> layout) {
        try {
            return layout.getRecipe();
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static String getBindingFilter(Object recipe, IRecipeLayoutDrawable<?> recipeLayout) {
        if (rsi$isGoetyRitual(recipe)) return "goety";

        try {
            ResourceLocation uid = recipeLayout.getRecipeCategory().getRecipeType().getUid();
            if (MALUM_SPIRIT_INFUSION_UID.equals(uid)) return "spirit_altar";
            if (WR_CRYSTALLIZER_UID.equals(uid)) return "wissen_crystallizer";
            if (WR_ITERATOR_UID.equals(uid)) return "arcane_iterator";
            if (WR_WORKBENCH_UID.equals(uid)) return "arcane_workbench";
            if (WR_CRYSTAL_RITUAL_UID.equals(uid)) return "crystal_ritual";
            if (WR_CRYSTAL_INFUSION_UID.equals(uid)) return "crystal_ritual";
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Reflection probe failed", e); }

        String recipeClassName = recipe.getClass().getName();
        if (recipeClassName.equals("com.sammy.malum.common.recipe.SpiritInfusionRecipe"))
            return "spirit_altar";
        if (recipeClassName.startsWith("mod.maxbogomol.wizards_reborn.common.recipe.WissenCrystallizer"))
            return "wissen_crystallizer";
        if (recipeClassName.startsWith("mod.maxbogomol.wizards_reborn.common.recipe.ArcaneIterator"))
            return "arcane_iterator";
        if (recipeClassName.startsWith("mod.maxbogomol.wizards_reborn.common.recipe.ArcaneWorkbench"))
            return "arcane_workbench";
        if (recipeClassName.startsWith("mod.maxbogomol.wizards_reborn.common.recipe.CrystalRitual"))
            return "crystal_ritual";
        if (recipeClassName.startsWith("mod.maxbogomol.wizards_reborn.common.recipe.CrystalInfusion"))
            return "crystal_ritual";
        if (recipeClassName.startsWith("com.stal111.forbidden_arcanus"))
            return "hephaestus_forge";
        if (recipeClassName.startsWith("elucent.eidolon"))
            return "crucible";

        return null;
    }

    @Unique
    private static ResourceLocation getRecipeId(Object recipe) {
        if (rsi$isGoetyRitual(recipe)) return ((com.Polarice3.Goety.common.crafting.RitualRecipe) recipe).getId();
        try {
            Method getId = null;
            try { getId = recipe.getClass().getMethod("getId"); }
            catch (NoSuchMethodException e) { getId = recipe.getClass().getMethod("m_6423_"); }
            Object result = getId.invoke(recipe);
            if (result instanceof ResourceLocation id) return id;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Reflection probe failed", e); }
        return null;
    }

    @Unique
    private static Runnable createHandler(Object recipe, ResourceLocation recipeId,
                                           ResourceLocation dim, BlockPos machinePos, String filter) {
        if (filter.equals("generic")) {
            return () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket(recipeId, true));
        }
        // All mod recipes (Goety, Malum, FA, Eidolon, WR) route through the
        // plan-preview flow so the result is properly collected and inserted back into RS.
        return () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                new com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket(recipeId, true, dim, machinePos));
    }

    @javax.annotation.Nullable
    @Unique
    private static BindingStorage.BindingEntry findBinding(@javax.annotation.Nullable AbstractContainerMenu container, String filter) {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;

        if (container != null) {
            for (net.minecraft.world.inventory.Slot slot : container.slots) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                        if (entry.blockKey().contains(filter)) return entry;
                    }
                }
            }
        }

        var inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (entry.blockKey().contains(filter)) return entry;
            }
        }
        for (ItemStack stack : inv.offhand) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (entry.blockKey().contains(filter)) return entry;
            }
        }

        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                var handler = opt.get();
                for (var stacksHandler : handler.getCurios().values()) {
                    var stacks = stacksHandler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        ItemStack stack = stacks.getStackInSlot(s);
                        for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                            if (entry.blockKey().contains(filter)) return entry;
                        }
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Reflection probe failed", e); }

        return null;
    }

    @Unique
    private static boolean rsi$isGoetyRitual(Object recipe) {
        return ModList.get().isLoaded("goety")
                && recipe.getClass().getName().equals("com.Polarice3.Goety.common.crafting.RitualRecipe");
    }

    @Unique
    private static ModType computeModType(Object recipe) {
        if (recipe instanceof Recipe<?> r) {
            ModType mt = ModType.classifyRecipe(r);
            if (mt != null) return mt;
        }
        return ModType.GENERIC;
    }

    @Unique
    private static AbstractContainerMenu getParentContainer() {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu != null) {
            return player.containerMenu;
        }
        return null;
    }
}
