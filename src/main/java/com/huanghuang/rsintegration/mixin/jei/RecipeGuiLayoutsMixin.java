package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
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
import net.minecraft.world.item.crafting.Ingredient;
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
    private static final ResourceLocation FA_HEPHAESTUS_SMITHING_UID =
            new ResourceLocation("forbidden_arcanus", "hephaestus_smithing");
    private static final ResourceLocation FA_HEPHAESTUS_UPGRADING_UID =
            new ResourceLocation("forbidden_arcanus", "hephaestus_forge_upgrading");
    private static final ResourceLocation EIDOLON_CRUCIBLE_UID =
            new ResourceLocation("eidolon", "crucible");
    private static final ResourceLocation EIDOLON_WORKTABLE_UID =
            new ResourceLocation("eidolon", "worktable");
    private static final ResourceLocation EMBER_ALCHEMY_UID =
            new ResourceLocation("embers", "alchemy");

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

        int totalRecipes = 0;
        int buttonsAdded = 0;
        int skippedNoRecipe = 0;
        int skippedNoFilter = 0;
        int skippedNoRecipeId = 0;
        int skippedNoBinding = 0;
        int faSeen = 0;
        int faNoRecipe = 0;
        int faNoFilter = 0;
        int faNoRecipeId = 0;
        int faNoBinding = 0;

        for (int i = 0; i < recipeLayoutsWithButtons.size(); i++) {
            RecipeLayoutWithButtons<?> layout = recipeLayoutsWithButtons.get(i);
            IRecipeLayoutDrawable<?> recipeLayout = layout.recipeLayout();
            Object recipe = getRecipeFromLayout(recipeLayout);
            if (recipe == null) {
                try {
                    String catUid = recipeLayout.getRecipeCategory().getRecipeType().getUid().toString();
                    if (catUid.startsWith("forbidden_arcanus")) {
                        faNoRecipe++;
                        RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA category {} returned null recipe from getRecipe()", catUid);
                    }
                } catch (Exception ignored) {}
                skippedNoRecipe++; continue;
            }

            String recipeClassName = recipe.getClass().getName();
            boolean isFa = recipeClassName.startsWith("com.stal111.forbidden_arcanus");
            boolean isFaOrTlm = isFa
                    || recipeClassName.startsWith("com.github.tartaricacid.touhoulittlemaid.");
            if (isFa) {
                faSeen++;
                RSIntegrationMod.LOGGER.info("[RSI-JEI-Mixin] FA recipe detected: class={}, uid={}",
                        recipeClassName, rsi$safeCategoryUid(recipeLayout));
            }

            String filter = getBindingFilter(recipe, recipeLayout);
            boolean isGeneric = false;
            if (filter == null) {
                if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe
                        && rsAvailable) {
                    filter = "generic";
                    isGeneric = true;
                } else {
                    if (isFa) faNoFilter++;
                    if (isFaOrTlm) {
                        RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA/TLM recipe '{}' (class={}) got null filter — skipped",
                                getRecipeIdSafe(recipe), recipeClassName);
                    }
                    skippedNoFilter++;
                    continue;
                }
            }

            totalRecipes++;

            ResourceLocation recipeId = getRecipeId(recipe);
            if (recipeId == null) {
                if (isFa) faNoRecipeId++;
                if (isFaOrTlm) {
                    RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA/TLM recipe class={} getRecipeId returned null — skipped",
                            recipeClassName);
                }
                skippedNoRecipeId++;
                continue;
            }

            // Skip Goety rituals that don't produce items:
            // - requiresSacrifice() (entity sacrifice rituals)
            // - SummonRitual / ConvertRitual / TeleportRitual (no item output)
            if (rsi$isGoetyRitual(recipe)
                    && (rsi$isGoetySacrificial(recipe) || rsi$isGoetyNonItemRitual(recipe))) {
                continue;
            }

            ResourceLocation bindingDim;
            BlockPos machinePos;
            if (isGeneric) {
                bindingDim = player.level().dimension().location();
                machinePos = player.blockPosition();
            } else {
                BindingStorage.BindingEntry binding = findBinding(container, filter);
                if (binding == null) {
                    if (isFa) faNoBinding++;
                if (isFaOrTlm) {
                    RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA/TLM recipe {} (filter={}) findBinding returned null — no bound item in inventory?",
                            recipeId, filter);
                }
                skippedNoBinding++;
                    continue;
                }
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
            } else if (filter.equals("worktable")) {
                tooltipKey = "gui.rs_integration.jei.eidolon_worktable_craft";
            } else if (filter.equals("touhou_little_maid")) {
                tooltipKey = "gui.rs_integration.jei.tlm_maid_altar_craft";
            } else if (filter.equals("embers")) {
                tooltipKey = "gui.rs_integration.jei.embers_alchemy_craft";
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
            buttonsAdded++;

            if (rsi$isGoetyRitual(recipe)) {
                GoetyRSNetworkHandler.sendCheckRS(recipeId, bindingDim, machinePos);
            }
        }

        RSIntegrationMod.LOGGER.info("[RSI-JEI-Mixin] Layouts processed: totalRecipes={} buttonsAdded={} "
                        + "skipped(filter={} recipeId={} binding={} noRecipe={}) "
                        + "| FA: seen={} noRecipe={} noFilter={} noRecipeId={} noBinding={}",
                totalRecipes, buttonsAdded,
                skippedNoFilter, skippedNoRecipeId, skippedNoBinding, skippedNoRecipe,
                faSeen, faNoRecipe, faNoFilter, faNoRecipeId, faNoBinding);
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
            if (FA_HEPHAESTUS_SMITHING_UID.equals(uid)) return "hephaestus_forge";
            if (FA_HEPHAESTUS_UPGRADING_UID.equals(uid)) return "hephaestus_forge";
            if (EIDOLON_CRUCIBLE_UID.equals(uid)) return "crucible";
            if (EIDOLON_WORKTABLE_UID.equals(uid)) return "worktable";
            if (EMBER_ALCHEMY_UID.equals(uid)) return "embers";
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
        if (recipeClassName.startsWith("com.github.tartaricacid.touhoulittlemaid."))
            return "touhou_little_maid";
        if (recipeClassName.startsWith("com.rekindled.embers."))
            return "embers";
        if (recipeClassName.equals("elucent.eidolon.recipe.WorktableRecipe"))
            return "worktable";
        if (recipeClassName.startsWith("elucent.eidolon"))
            return "crucible";

        return null;
    }

    @Unique
    private static ResourceLocation getRecipeId(Object recipe) {
        if (rsi$isGoetyRitual(recipe)) return ((com.Polarice3.Goety.common.crafting.RitualRecipe) recipe).getId();
        String className = recipe.getClass().getName();

        // Standard Recipe.getId() / m_6423_()
        try {
            Method getId = null;
            try { getId = recipe.getClass().getMethod("getId"); }
            catch (NoSuchMethodException e) { getId = recipe.getClass().getMethod("m_6423_"); }
            Object result = getId.invoke(recipe);
            if (result instanceof ResourceLocation id) return id;
        } catch (Exception e) { /* falls through to mod-specific handlers */ }

        // getResourceLocation() fallback (common in non-Recipe objects)
        try {
            Method getRL = recipe.getClass().getMethod("getResourceLocation");
            Object result = getRL.invoke(recipe);
            if (result instanceof ResourceLocation id) return id;
        } catch (Exception ignored) {}

        // FA-specific: Ritual is a Java Record stored in FARegistries.RITUAL custom registry
        if (className.startsWith("com.stal111.forbidden_arcanus")) {
            ResourceLocation id = rsi$getFARitualId(recipe);
            if (id != null) return id;
        }

        // TLM-specific: AltarRecipeWrapper wraps AltarRecipe but loses the ID
        if (className.equals("com.github.tartaricacid.touhoulittlemaid.compat.jei.altar.AltarRecipeWrapper")) {
            ResourceLocation id = rsi$getTlmWrapperRecipeId(recipe);
            if (id != null) return id;
        }

        RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] getRecipeId failed for {} — no strategy succeeded", className);
        return null;
    }

    // ── FA fingerprint cache (built once, like betterjei) ──────────────
    @Unique
    private static final java.util.Map<String, ResourceLocation> rsi$faFingerprintCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    @Unique
    private static volatile boolean rsi$faCacheBuilt;

    @Unique
    private static ResourceLocation rsi$getFARitualId(Object recipe) {
        try {
            Object ritual = rsi$unpackFaRitual(recipe);
            var level = Minecraft.getInstance().level;
            if (level == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA ritual ID: Minecraft.level is null");
                return null;
            }

            java.util.Optional<?> optKey;
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                var registry = (net.minecraft.core.Registry) rsi$getFaRegistry(level);
                if (registry == null) return null;

                // 1. Identity match via registry.getKey() (like betterjei)
                ResourceLocation key = registry.getKey(ritual);
                if (key != null) return key;

                // 2. getResourceKey() fallback
                optKey = registry.getResourceKey(ritual);
                if (optKey.isPresent()) {
                    return ((net.minecraft.resources.ResourceKey<?>) optKey.get()).location();
                }

                // 3. Build fingerprint cache on first use, then lookup
                rsi$ensureFaFingerprintCache(registry);
                String fp = rsi$buildFaFingerprint(ritual);
                ResourceLocation cached = rsi$faFingerprintCache.get(fp);
                if (cached != null) return cached;

                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA ritual not found ({} entries, fp={})",
                        registry.keySet().size(), fp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA registry access failed: {}", e.toString());
                // Fallback: try per-call fingerprint match with whatever registry we have
                return rsi$faFingerprintMatch(null, ritual);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA ritual ID lookup failed: {}", e.toString());
        }
        return null;
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void rsi$ensureFaFingerprintCache(net.minecraft.core.Registry registry) {
        if (rsi$faCacheBuilt) return;

        try {
            Class<?> ritualClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.Ritual");
            int skipped = 0;
            int failed = 0;
            for (Object rawEntry : registry.entrySet()) {
                    java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) rawEntry;
                try {
                    var key = (net.minecraft.resources.ResourceKey<?>) entry.getKey();
                    Object value = entry.getValue();
                    if (value == null || !ritualClass.isInstance(value)) {
                        skipped++;
                        continue;
                    }
                    String fp = rsi$buildFaFingerprint(value);
                    if (fp != null && !fp.isEmpty()) {
                        rsi$faFingerprintCache.put(fp, key.location());
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA cache entry failed: {}", e.toString());
                }
            }
            rsi$faCacheBuilt = true;
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA fingerprint cache built: {} entries (skipped={} failed={})",
                    rsi$faFingerprintCache.size(), skipped, failed);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA fingerprint cache build failed: {}", e.toString());
        }
    }

    /** Build a fingerprint string matching betterjei's format for uniqueness. */
    @Unique
    private static String rsi$buildFaFingerprint(Object ritual) {
        try {
            StringBuilder sb = new StringBuilder();
            Class<?> c = ritual.getClass();

            // Essences: all 4 ints
            Object essences = c.getMethod("essences").invoke(ritual);
            if (essences != null) {
                Class<?> ec = essences.getClass();
                sb.append("E=")
                  .append(ec.getMethod("aureal").invoke(essences)).append(',')
                  .append(ec.getMethod("souls").invoke(essences)).append(',')
                  .append(ec.getMethod("blood").invoke(essences)).append(',')
                  .append(ec.getMethod("experience").invoke(essences))
                  .append('|');
            }

            // Main ingredient: first matching item's registry name
            Object mainIng = c.getMethod("mainIngredient").invoke(ritual);
            if (mainIng instanceof Ingredient ing) {
                ItemStack[] items = ing.getItems();
                if (items.length > 0) {
                    ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                            items[0].getItem());
                    sb.append("M=").append(itemId != null ? itemId : "?").append('|');
                }
            }

            // All inputs: ingredient first item + amount
            @SuppressWarnings("unchecked")
            List<?> inputs = (List<?>) c.getMethod("inputs").invoke(ritual);
            if (inputs != null) {
                for (int i = 0; i < inputs.size(); i++) {
                    Object ri = inputs.get(i);
                    Class<?> ric = ri.getClass();
                    Ingredient ing = (Ingredient) ric.getMethod("ingredient").invoke(ri);
                    int amt = (int) ric.getMethod("amount").invoke(ri);
                    ItemStack[] items = ing.getItems();
                    String itemName = items.length > 0
                            ? String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(items[0].getItem()))
                            : "?";
                    sb.append('I').append(i).append('=').append(itemName).append(':').append(amt).append('|');
                }
            }

            return sb.toString();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA fingerprint build failed: {}", e.toString());
            return "";
        }
    }

    /** Get FA ritual registry, with Forge RegistryManager fallback (like betterjei). */
    @Unique
    private static net.minecraft.core.Registry<?> rsi$getFaRegistry(net.minecraft.client.multiplayer.ClientLevel level) {
        try {
            Class<?> faReg = Class.forName("com.stal111.forbidden_arcanus.core.registry.FARegistries");
            java.lang.reflect.Field f = faReg.getField("RITUAL");
            Object regKey = f.get(null);
            @SuppressWarnings({"unchecked", "rawtypes"})
            var key = (net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>) regKey;
            return level.registryAccess().registryOrThrow(key);
        } catch (Exception e1) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA registryOrThrow failed, trying RegistryManager: {}", e1.toString());
            try {
                Class<?> rmClass = Class.forName("net.minecraftforge.registries.RegistryManager");
                java.lang.reflect.Field activeField = rmClass.getField("ACTIVE");
                Object active = activeField.get(null);
                for (java.lang.reflect.Method m : active.getClass().getMethods()) {
                    if (m.getName().equals("getRegistry") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        Class<?> faReg = Class.forName("com.stal111.forbidden_arcanus.core.registry.FARegistries");
                        java.lang.reflect.Field f = faReg.getField("RITUAL");
                        Object key = f.get(null);
                        Object reg = m.invoke(active, key);
                        if (reg instanceof net.minecraft.core.Registry<?> r) return r;
                    }
                }
            } catch (Exception e2) {
                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA RegistryManager fallback also failed: {}", e2.toString());
            }
        }
        return null;
    }

    /**
     * Per-call fingerprint match as last-resort fallback.
     * Compares essences + result data directly, bypassing broken RitualResult.equals().
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Unique
    private static ResourceLocation rsi$faFingerprintMatch(
            net.minecraft.core.Registry registry, Object target) {
        try {
            Class<?> ritualClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.Ritual");
            Class<?> essencesDefClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesDefinition");
            Class<?> createItemResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.CreateItemResult");
            Class<?> upgradeTierResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.UpgradeTierResult");

            java.lang.reflect.Method essencesM = ritualClass.getMethod("essences");
            java.lang.reflect.Method resultM = ritualClass.getMethod("result");
            java.lang.reflect.Method inputsM = ritualClass.getMethod("inputs");
            java.lang.reflect.Method aurealM = essencesDefClass.getMethod("aureal");
            java.lang.reflect.Method soulsM  = essencesDefClass.getMethod("souls");
            java.lang.reflect.Method bloodM  = essencesDefClass.getMethod("blood");
            java.lang.reflect.Method expM    = essencesDefClass.getMethod("experience");

            Object targetResult = resultM.invoke(target);
            Object targetEssences = essencesM.invoke(target);
            List<?> targetInputs = (List<?>) inputsM.invoke(target);
            int targetInputCount = targetInputs != null ? targetInputs.size() : 0;
            int ta = (int) aurealM.invoke(targetEssences);
            int ts = (int) soulsM.invoke(targetEssences);
            int tb = (int) bloodM.invoke(targetEssences);
            int te = (int) expM.invoke(targetEssences);

            // If registry is null, try to get it now
            if (registry == null) {
                var level = Minecraft.getInstance().level;
                if (level == null) return null;
                registry = rsi$getFaRegistry(level);
                if (registry == null) return null;
            }

            for (Object key : registry.keySet()) {
                Object candidate = registry.get((net.minecraft.resources.ResourceKey<?>) key);
                if (candidate == null || !ritualClass.isInstance(candidate)) continue;

                Object candResult = resultM.invoke(candidate);
                Object candEssences = essencesM.invoke(candidate);

                if ((int) aurealM.invoke(candEssences) != ta) continue;
                if ((int) soulsM.invoke(candEssences)  != ts) continue;
                if ((int) bloodM.invoke(candEssences)  != tb) continue;
                if ((int) expM.invoke(candEssences)    != te) continue;

                List<?> candInputs = (List<?>) inputsM.invoke(candidate);
                if ((candInputs != null ? candInputs.size() : 0) != targetInputCount) continue;

                if (createItemResultClass.isInstance(targetResult)
                        && createItemResultClass.isInstance(candResult)) {
                    java.lang.reflect.Method getResultM =
                            createItemResultClass.getMethod("getResult");
                    ItemStack targetOut = (ItemStack) getResultM.invoke(targetResult);
                    ItemStack candOut = (ItemStack) getResultM.invoke(candResult);
                    if (ItemStack.isSameItemSameTags(targetOut, candOut)) {
                        return ((net.minecraft.resources.ResourceKey<?>) key).location();
                    }
                } else if (upgradeTierResultClass.isInstance(targetResult)
                        && upgradeTierResultClass.isInstance(candResult)) {
                    java.lang.reflect.Method getReqM =
                            upgradeTierResultClass.getMethod("getRequiredTier");
                    java.lang.reflect.Method getUpM =
                            upgradeTierResultClass.getMethod("getUpgradedTier");
                    if ((int) getReqM.invoke(targetResult) == (int) getReqM.invoke(candResult)
                            && (int) getUpM.invoke(targetResult) == (int) getUpM.invoke(candResult)) {
                        return ((net.minecraft.resources.ResourceKey<?>) key).location();
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA fingerprint match failed: {}", e.toString());
        }
        return null;
    }

    /** Extract the underlying {@code Ritual} from a JEI display wrapper if present. */
    @Unique
    private static Object rsi$unpackFaRitual(Object obj) {
        String name = obj.getClass().getName();
        if (!name.startsWith("com.stal111.forbidden_arcanus")) return obj;
        // Try getRitual() first, then ritual() for Java Records
        for (String methodName : new String[]{"getRitual", "ritual"}) {
            try {
                java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
                Object inner = m.invoke(obj);
                if (inner != null
                        && inner.getClass().getName().startsWith("com.stal111.forbidden_arcanus")) {
                    return inner;
                }
            } catch (Exception ignored) {}
        }
        return obj;
    }

    @Unique
    private static ResourceLocation rsi$getTlmWrapperRecipeId(Object wrapper) {
        try {
            // Get output from wrapper
            Method getOutput = wrapper.getClass().getMethod("getOutput");
            ItemStack output = (ItemStack) getOutput.invoke(wrapper);
            if (output.isEmpty()) return null;

            var level = Minecraft.getInstance().level;
            if (level == null) return null;

            // Access InitRecipes.ALTAR_CRAFTING recipe type
            Class<?> initRecipes = Class.forName("com.github.tartaricacid.touhoulittlemaid.init.InitRecipes");
            java.lang.reflect.Field f = initRecipes.getField("ALTAR_CRAFTING");
            var recipeType = (net.minecraft.world.item.crafting.RecipeType<?>) f.get(null);

            var access = level.registryAccess();
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<Recipe<?>> recipes = (List) level.getRecipeManager().getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) recipeType);
            for (Recipe<?> r : recipes) {
                if (ItemStack.isSameItemSameTags(r.getResultItem(access), output)) {
                    return r.getId();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] TLM altar recipe ID lookup failed: {}", e.toString());
        }
        return null;
    }

    @Unique
    private static String getRecipeIdSafe(Object recipe) {
        ResourceLocation id = getRecipeId(recipe);
        return id != null ? id.toString() : "<null>";
    }

    @Unique
    private static Runnable createHandler(Object recipe, ResourceLocation recipeId,
                                           ResourceLocation dim, BlockPos machinePos, String filter) {
        if (filter.equals("generic")) {
            return () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket(recipeId, true));
        }
        // All mod recipes (including FA) route through the plan-preview flow.
        // GenericCraftPacket now falls back to FARegistries.RITUAL when a recipe
        // is not found in RecipeManager, so FA rituals show the plan tree too.
        return () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                new com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket(recipeId, true, dim, machinePos));
    }

    @javax.annotation.Nullable
    @Unique
    private static BindingStorage.BindingEntry findBinding(@javax.annotation.Nullable AbstractContainerMenu container, String filter) {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;

        boolean debugFaTlm = filter.equals("hephaestus_forge") || filter.equals("touhou_little_maid");
        List<String> allBlockKeys = debugFaTlm ? new ArrayList<>() : null;

        if (container != null) {
            for (net.minecraft.world.inventory.Slot slot : container.slots) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                        if (debugFaTlm) allBlockKeys.add(entry.blockKey());
                        if (entry.blockKey().contains(filter)) return entry;
                    }
                }
            }
        }

        var inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (debugFaTlm) allBlockKeys.add(entry.blockKey());
                if (entry.blockKey().contains(filter)) return entry;
            }
        }
        for (ItemStack stack : inv.offhand) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (debugFaTlm) allBlockKeys.add(entry.blockKey());
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
                            if (debugFaTlm) allBlockKeys.add(entry.blockKey());
                            if (entry.blockKey().contains(filter)) return entry;
                        }
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Reflection probe failed", e); }

        if (debugFaTlm) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] findBinding(filter={}) found no match. All blockKeys in inv: {}",
                    filter, allBlockKeys.isEmpty() ? "<none>" : String.join(", ", allBlockKeys));
        }

        return null;
    }

    @Unique
    private static String rsi$safeCategoryUid(IRecipeLayoutDrawable<?> layout) {
        try {
            return layout.getRecipeCategory().getRecipeType().getUid().toString();
        } catch (Exception e) { return "?"; }
    }

    @Unique
    private static boolean rsi$isGoetyRitual(Object recipe) {
        return ModList.get().isLoaded("goety")
                && recipe.getClass().getName().equals("com.Polarice3.Goety.common.crafting.RitualRecipe");
    }

    @Unique
    private static boolean rsi$isGoetySacrificial(Object recipe) {
        try {
            return (boolean) recipe.getClass().getMethod("requiresSacrifice").invoke(recipe);
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true for Goety rituals that don't produce items and can't be automated (Convert/Teleport). */
    @Unique
    private static boolean rsi$isGoetyNonItemRitual(Object recipe) {
        try {
            java.lang.reflect.Method getRitual = recipe.getClass().getMethod("getRitual");
            Object ritual = getRitual.invoke(recipe);
            if (ritual == null) return false;
            String name = ritual.getClass().getName();
            if (name.equals("com.Polarice3.Goety.common.ritual.CraftItemRitual")) return false;
            if (name.equals("com.Polarice3.Goety.common.ritual.EnchantItemRitual")) return false;
            if (name.equals("com.Polarice3.Goety.common.ritual.SummonRitual")) return false;
            // ConvertRitual, TeleportRitual — no item output, potentially destructive
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Goety ritual type check failed", e);
            return false;
        }
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
