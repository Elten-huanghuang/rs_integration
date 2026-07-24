package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionSnapshot;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionTargetIds;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.AltarCraftButtons;
import com.huanghuang.rsintegration.sidepanel.client.GuiNavStack;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.goety.GoetyRSNetworkHandler;
import com.huanghuang.rsintegration.mods.goety.RSClientAvailabilityCache;
import com.huanghuang.rsintegration.reflection.probes.FAReflection;
import com.huanghuang.rsintegration.reflection.probes.TLMReflection;
import com.huanghuang.rsintegration.util.ModIds;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
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

    // UID constants: only those NOT covered by ModType.filterForJeiUid() remain.
    // FA_HEPHAESTUS_SMITHING_UID is also used by extractFaSmithingBaseItem.
    private static final ResourceLocation FA_HEPHAESTUS_SMITHING_UID =
            new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_smithing");
    private static final ResourceLocation SMELTING_UID =
            new ResourceLocation("minecraft", "smelting");
    private static final ResourceLocation BLASTING_UID =
            new ResourceLocation("minecraft", "blasting");
    private static final ResourceLocation SMOKING_UID =
            new ResourceLocation("minecraft", "smoking");
    private static final ResourceLocation CAMPFIRE_UID =
            new ResourceLocation("minecraft", "campfire");
    private static final ResourceLocation STONECUTTING_UID =
            new ResourceLocation("minecraft", "stonecutting");
    private static final ResourceLocation SMITHING_UID =
            new ResourceLocation("minecraft", "smithing");
    private static final ResourceLocation CRABBERSDELIGHT_CRAB_TRAP_UID =
            new ResourceLocation("crabbersdelight", "crab_trap_loot");
    private static final ResourceLocation ANVIL_UID =
            new ResourceLocation("minecraft", "anvil");

    @Shadow
    private List<RecipeLayoutWithButtons<?>> recipeLayoutsWithButtons;

    @Unique
    private final List<Integer> rsi$layoutIndices = new ArrayList<>();
    @Unique
    private final List<int[]> rsi$positions = new ArrayList<>();

    @Unique
    private final List<ResourceLocation> rsi$recipeIds = new ArrayList<>();
    @Unique
    private final List<Boolean> rsi$hasMachineGui = new ArrayList<>();

    @Inject(method = "setRecipeLayoutsWithButtons", at = @At("HEAD"))
    private void rsi$onLayoutsSetHead(List<RecipeLayoutWithButtons<?>> layouts, CallbackInfo ci) {
        if (layouts == null || layouts.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] setRecipeLayoutsWithButtons HEAD: EMPTY list");
        } else {
            try {
                String catUid = layouts.get(0).recipeLayout().getRecipeCategory()
                        .getRecipeType().getUid().toString();
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] setRecipeLayoutsWithButtons HEAD: {} recipes, uid={}",
                        layouts.size(), catUid);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] setRecipeLayoutsWithButtons HEAD: {} recipes, uid=?",
                        layouts.size(), e);
            }
        }
    }

    @Inject(method = "setRecipeLayoutsWithButtons", at = @At("RETURN"))
    private void rsi$onLayoutsSet(CallbackInfo ci) {
        AltarCraftButtons.clear();
        rsi$layoutIndices.clear();
        rsi$positions.clear();
        rsi$recipeIds.clear();
        rsi$hasMachineGui.clear();

        if (!RSIntegrationConfig.ENABLE_JEI.get()) return;

        AbstractContainerMenu container = getParentContainer();
        var player = Minecraft.getInstance().player;
        if (player == null) return;  // Unlikely but possible during screen transitions

        // Compute once: RS available = mod loaded (server handler fails gracefully if no network)
        boolean rsAvailable = ModList.get().isLoaded(ModIds.REFINED_STORAGE);

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
                    if (catUid.startsWith(ModIds.FORBIDDEN_ARCANUS)) {
                        faNoRecipe++;
                        RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA category {} returned null recipe from getRecipe()", catUid);
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] category uid check failed", e); }
                skippedNoRecipe++; continue;
            }

            if (recipe instanceof QuestSubmissionSnapshot quest) {
                ResourceLocation questRecipeId =
                        QuestSubmissionTargetIds
                                .of(quest.questId());
                Runnable handler = () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                        new QuestSubmissionRequestPacket(
                                quest.questId()));
                rsi$layoutIndices.add(i);
                rsi$positions.add(new int[]{0, 0, 10, 10});
                rsi$recipeIds.add(questRecipeId);
                AltarCraftButtons.add(0, 0, 10, 10, handler,
                        "gui.rs_integration.jei.ftb_quest_submit", questRecipeId,
                        player.level().dimension().location(), player.blockPosition(), null);
                rsi$hasMachineGui.add(false);
                buttonsAdded++;
                totalRecipes++;
                continue;
            }

            String recipeClassName = recipe.getClass().getName();
            boolean isFa = recipeClassName.startsWith("com.stal111.forbidden_arcanus");
            boolean isFaOrTlm = isFa
                    || recipeClassName.startsWith("com.github.tartaricacid.touhoulittlemaid.");
            if (isFa) {
                faSeen++;
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA recipe detected: class={}, uid={}",
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
            // - ConvertRitual / TeleportRitual (no item output)
            // Note: SummonRitual is kept (returns false) to allow remote triggering
            if (rsi$isGoetyRitual(recipe)
                    && (rsi$isGoetySacrificial(recipe) || rsi$isGoetyNonItemRitual(recipe))) {
                continue;
            }

            // Skip SmithingTrimRecipe — output depends on input armor NBT, not predictable
            if (recipe instanceof net.minecraft.world.item.crafting.SmithingTrimRecipe) {
                continue;
            }

            ResourceLocation bindingDim;
            BlockPos machinePos;
            String boundBlockKey = null;
            String boundBlockRegKey = null;
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
                boundBlockKey = binding.blockKey();
                boundBlockRegKey = binding.blockRegKey();
            }

            // FA smithing: ApplyModifierRecipe has no fixed base ingredient,
            // so extract the displayed base from JEI's visual slots instead.
            // Two paths reach here:
            //   1. FA's own hephaestus_smithing category → isFa=true (Ritual class)
            //   2. Vanilla minecraft:smithing category → isFa=false for
            //      SmithingTransformRecipe, but recipeId namespace is
            //      forbidden_arcanus.  Check that so we don't miss it.
            ItemStack faSmithingBase = null;
            boolean isSmithingFilter = filter.equals("block.minecraft.smithing_table");
            boolean isForgeFilter = filter.equals("hephaestus_forge");
            boolean faSmithing = (isSmithingFilter || isForgeFilter) && (isFa
                    || recipeId.getNamespace().equals(ModIds.FORBIDDEN_ARCANUS));
            if (faSmithing) {
                faSmithingBase = extractFaSmithingBaseItem(recipeLayout);
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA smithing base extraction: result={} isFa={} class={}",
                        faSmithingBase != null ? faSmithingBase.getHoverName().getString() : "null",
                        isFa, recipeClassName);
            } else if (isSmithingFilter && !faSmithing) {
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Non-FA smithing filter matched, class={}", recipeClassName);
            }
            // WR Arcane Iterator enchant recipes: levels I/II/III share one recipe
            // id and declare no static output, so the server can't tell which level
            // the player clicked from the id alone. Capture the OUTPUT ghost slot
            // (the leveled enchanted book JEI renders) so the server can require the
            // matching (N-1)-level center book and produce level N.
            ItemStack wrTargetOutput = null;
            if (ModIds.WIZARDS_REBORN.equals(recipeId.getNamespace())
                    && recipeId.getPath().startsWith("arcane_iterator/")) {
                wrTargetOutput = extractOutputStack(recipeLayout);
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] WR arcane iterator output capture: recipeId={} output={}",
                        recipeId, wrTargetOutput != null ? wrTargetOutput.getHoverName().getString() : "null");
            }
            Runnable handler = createHandler(recipe, recipeId, bindingDim, machinePos, filter, faSmithingBase, wrTargetOutput);
            if (handler == null) continue;

            ModType modType = "vanilla_brewing_stand".equals(filter)
                    ? ModType.byId("vanilla_brewing_stand")
                    : computeModType(recipe);

            String tooltipKey;
            if (rsi$isGoetyRitual(recipe)) {
                tooltipKey = "gui.rs_integration.jei.altar_craft";
            } else if (rsi$isGoetyBrazierRecipe(recipe)) {
                tooltipKey = "gui.rs_integration.jei.goety_brazier_craft";
            } else if (modType != null && modType.jeiTooltipKey() != null) {
                tooltipKey = modType.jeiTooltipKey();
            } else if (filter.startsWith("block.minecraft.")) {
                tooltipKey = "gui.rs_integration.jei.vanilla_machine_craft";
            } else if (filter.equals("generic")) {
                tooltipKey = "gui.rs_integration.jei.rs_auto_craft";
            } else {
                // Filter-based lookup for mods without jeiTooltipKey configured
                tooltipKey = switch (filter) {
                    case "spirit_altar" -> "gui.rs_integration.jei.malum_spirit_craft";
                    case "spirit_crucible" -> "gui.rs_integration.jei.malum_crucible_craft";
                    case "crystal_ritual" -> "gui.rs_integration.jei.wr_crystal_craft";
                    case "hephaestus_forge" -> "gui.rs_integration.jei.fa_ritual_craft";
                    case "crucible" -> "gui.rs_integration.jei.eidolon_crucible_craft";
                    case "worktable" -> "gui.rs_integration.jei.eidolon_worktable_craft";
                    case "ritual" -> "gui.rs_integration.jei.eidolon_ritual_craft";
                    case ModIds.TOUHOU_LITTLE_MAID -> "gui.rs_integration.jei.tlm_maid_altar_craft";
                    case ModIds.EMBERS -> "gui.rs_integration.jei.embers_alchemy_craft";
                    case "aether_freezer" -> "gui.rs_integration.jei.aether_freezer_craft";
                    case "aether_incubator" -> "gui.rs_integration.jei.aether_incubator_craft";
                    case "aether_altar" -> "gui.rs_integration.jei.aether_altar_craft";
                    case ModIds.ID_AVARITIA_CRAFTING -> "gui.rs_integration.jei.avaritia_crafting";
                    case ModIds.ID_AVARITIA_COMPRESSOR -> "gui.rs_integration.jei.avaritia_compressor";
                    case ModIds.ID_AVARITIA_SMITHING -> "gui.rs_integration.jei.avaritia_smithing";
                    case "crabbersdelight" -> "gui.rs_integration.jei.crabbersdelight_trap";
                    default -> "gui.rs_integration.jei.wr_remote_craft";
                };
            }

            rsi$layoutIndices.add(i);
            rsi$positions.add(new int[]{0, 0, 10, 10});

            rsi$recipeIds.add(recipeId);
            AltarCraftButtons.add(0, 0, 10, 10, handler, tooltipKey, recipeId,
                    bindingDim, machinePos, modType);

            // Register machine GUI button for non-generic (bound) recipes
            // when the bound machine supports remote GUI.
            if (!isGeneric && bindingDim != null && machinePos != null
                    && modType != null
                    && supportsGuiWithRegCheck(boundBlockKey, boundBlockRegKey)) {
                ResourceLocation guiDim = bindingDim;
                BlockPos guiPos = machinePos;
                ResourceLocation jeiOpenRecipeId = recipeId;
                ItemStack capturedBaseForMachine = faSmithingBase;
                AltarCraftButtons.addMachineGui(0, 0, 10, 10, () -> {
                    GuiNavStack.pushCurrent();
                    RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                            new OpenBoundMachineGuiPacket(
                                    guiDim, guiPos, jeiOpenRecipeId.toString(), jeiOpenRecipeId, capturedBaseForMachine));
                });
                rsi$hasMachineGui.add(true);
            } else {
                rsi$hasMachineGui.add(false);
            }

            buttonsAdded++;

            if (rsi$isGoetyRitual(recipe) || rsi$isGoetyBrazierRecipe(recipe)) {
                GoetyRSNetworkHandler.sendCheckRS(recipeId, bindingDim, machinePos);
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Layouts processed: totalRecipes={} buttonsAdded={} "
                        + "skipped(filter={} recipeId={} binding={} noRecipe={}) "
                        + "| FA: seen={} noRecipe={} noFilter={} noRecipeId={} noBinding={}",
                totalRecipes, buttonsAdded,
                skippedNoFilter, skippedNoRecipeId, skippedNoBinding, skippedNoRecipe,
                faSeen, faNoRecipe, faNoFilter, faNoRecipeId, faNoBinding);
    }

    @Unique
    private static final int MIN_BUTTON_W = 10;
    @Unique
    private static final int MIN_BUTTON_H = 10;
    @Unique
    private static final int BUTTON_GAP = 2;
    @Unique
    private static final int SCREEN_MARGIN = 2;

    @Inject(method = "updateRecipeButtonPositions", at = @At("RETURN"))
    private void rsi$updatePositions(CallbackInfo ci) {
        AltarCraftButtons.clearTransferPositions();
        int mgIdx = 0;
        List<int[]> mgPos = AltarCraftButtons.getMachineGuiPositions();
        List<int[]> occupied = rsi$collectJeiOccupiedAreas();
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        for (int i = 0; i < rsi$layoutIndices.size(); i++) {
            int layoutIdx = rsi$layoutIndices.get(i);
            if (layoutIdx >= recipeLayoutsWithButtons.size()) continue;

            RecipeLayoutWithButtons<?> layout = recipeLayoutsWithButtons.get(layoutIdx);
            IRecipeLayoutDrawable<?> recipeLayout = layout.recipeLayout();
            RecipeTransferButton transferBtn = layout.transferButton();
            ImmutableRect2i area = ((GuiIconToggleButtonAccessor) transferBtn).getButton().getArea();
            int bw = Math.max(area.getWidth(), MIN_BUTTON_W);
            int bh = Math.max(area.getHeight(), MIN_BUTTON_H);
            boolean hasMachineGui = rsi$hasMachineGui.size() > i && rsi$hasMachineGui.get(i);
            int groupWidth = bw + (hasMachineGui ? BUTTON_GAP + bw : 0);
            int[] placement = rsi$findButtonPlacement(recipeLayout.getRectWithBorder(), area,
                    groupWidth, bh, screenWidth, screenHeight, occupied);
            int bx = placement[0];
            int by = placement[1];

            int[] pos = rsi$positions.get(i);
            pos[0] = bx;
            pos[1] = by;
            pos[2] = bw;
            pos[3] = bh;

            List<int[]> globalPos = AltarCraftButtons.getPositions();
            if (i < globalPos.size()) {
                int[] gp = globalPos.get(i);
                gp[0] = bx;
                gp[1] = by;
                gp[2] = bw;
                gp[3] = bh;
            }

            AltarCraftButtons.addTransferPos(area.getX(), area.getY(), bw, bh);
            occupied.add(new int[]{bx, by, groupWidth, bh});

            if (hasMachineGui && mgIdx < mgPos.size()) {
                int[] mgp = mgPos.get(mgIdx);
                mgp[0] = bx + bw + BUTTON_GAP;
                mgp[1] = by;
                mgp[2] = bw;
                mgp[3] = bh;
                mgIdx++;
            }
        }
    }

    @Unique
    private List<int[]> rsi$collectJeiOccupiedAreas() {
        List<int[]> occupied = new ArrayList<>();
        for (RecipeLayoutWithButtons<?> layout : recipeLayoutsWithButtons) {
            IRecipeLayoutDrawable<?> drawable = layout.recipeLayout();
            rsi$addOccupiedArea(occupied, drawable.getRectWithBorder());
            rsi$addOccupiedArea(occupied, drawable.getRecipeTransferButtonArea());
            rsi$addOccupiedArea(occupied, drawable.getRecipeBookmarkButtonArea());
        }
        return occupied;
    }

    @Unique
    private static void rsi$addOccupiedArea(List<int[]> occupied, Rect2i area) {
        if (area != null && area.getWidth() > 0 && area.getHeight() > 0) {
            occupied.add(new int[]{area.getX(), area.getY(), area.getWidth(), area.getHeight()});
        }
    }

    @Unique
    private static int[] rsi$findButtonPlacement(Rect2i recipeArea, ImmutableRect2i transferArea,
                                                  int width, int height, int screenWidth, int screenHeight,
                                                  List<int[]> occupied) {
        int recipeLeft = recipeArea.getX();
        int recipeTop = recipeArea.getY();
        int recipeRight = recipeLeft + recipeArea.getWidth();
        int recipeBottom = recipeTop + recipeArea.getHeight();
        int anchorX = transferArea.getX();
        int anchorY = transferArea.getY();
        int[][] candidates = {
                {anchorX, recipeBottom + BUTTON_GAP},
                {recipeRight + BUTTON_GAP, anchorY},
                {recipeLeft - width - BUTTON_GAP, anchorY},
                {anchorX, recipeTop - height - BUTTON_GAP}
        };

        for (int[] candidate : candidates) {
            if (rsi$isInsideScreen(candidate[0], candidate[1], width, height, screenWidth, screenHeight)
                    && !rsi$overlapsAny(candidate[0], candidate[1], width, height, occupied)) {
                return candidate;
            }
        }

        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < candidates.length; i++) {
            int x = rsi$clamp(candidates[i][0], SCREEN_MARGIN,
                    Math.max(SCREEN_MARGIN, screenWidth - SCREEN_MARGIN - width));
            int y = rsi$clamp(candidates[i][1], SCREEN_MARGIN,
                    Math.max(SCREEN_MARGIN, screenHeight - SCREEN_MARGIN - height));
            long score = (long) rsi$overlapArea(x, y, width, height, occupied) * 1_000_000L
                    + (long) Math.abs(x - candidates[0][0]) + Math.abs(y - candidates[0][1]) + i;
            if (score < bestScore) {
                bestScore = score;
                best = new int[]{x, y};
            }
        }
        return best;
    }

    @Unique
    private static boolean rsi$isInsideScreen(int x, int y, int width, int height,
                                               int screenWidth, int screenHeight) {
        return x >= SCREEN_MARGIN && y >= SCREEN_MARGIN
                && x + width <= screenWidth - SCREEN_MARGIN
                && y + height <= screenHeight - SCREEN_MARGIN;
    }

    @Unique
    private static boolean rsi$overlapsAny(int x, int y, int width, int height, List<int[]> areas) {
        for (int[] area : areas) {
            if (rsi$overlapArea(x, y, width, height, area) > 0) return true;
        }
        return false;
    }

    @Unique
    private static int rsi$overlapArea(int x, int y, int width, int height, List<int[]> areas) {
        int total = 0;
        for (int[] area : areas) {
            total += rsi$overlapArea(x, y, width, height, area);
        }
        return total;
    }

    @Unique
    private static int rsi$overlapArea(int x, int y, int width, int height, int[] area) {
        int overlapWidth = Math.max(0, Math.min(x + width, area[0] + area[2]) - Math.max(x, area[0]));
        int overlapHeight = Math.max(0, Math.min(y + height, area[1] + area[3]) - Math.max(y, area[1]));
        return overlapWidth * overlapHeight;
    }

    @Unique
    private static int rsi$clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
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

        // Draw machine GUI buttons (gear icon to the right of "+" buttons)
        List<int[]> mgPositions = AltarCraftButtons.getMachineGuiPositions();
        for (int i = 0; i < mgPositions.size(); i++) {
            int[] pos = mgPositions.get(i);
            int mx = pos[0], my = pos[1], mw = pos[2], mh = pos[3];
            boolean hovered = mouseX >= mx && mouseX < mx + mw && mouseY >= my && mouseY < my + mh;

            int bgColor = hovered ? 0xFF556688 : 0xFF334455;
            int borderColor = hovered ? 0xFF88AACC : 0xFF556677;
            guiGraphics.fill(mx, my, mx + mw, my + mh, borderColor);
            guiGraphics.fill(mx + 1, my + 1, mx + mw - 1, my + mh - 1, bgColor);

            // Monitor/display icon for "open machine GUI"
            int iconColor = hovered ? 0xFFCCDDEE : 0xFF8899AA;
            int screenColor = hovered ? 0xFFEEF4FF : 0xFFAABBCC;
            int standColor = hovered ? 0xFF99AACC : 0xFF667788;
            // Bezel
            guiGraphics.fill(mx + 1, my + 1, mx + mw - 1, my + mh - 3, iconColor);
            // Screen
            guiGraphics.fill(mx + 2, my + 2, mx + mw - 2, my + mh - 4, screenColor);
            // Stand
            guiGraphics.fill(mx + mw / 2 - 1, my + mh - 2, mx + mw / 2 + 1, my + mh - 1, standColor);
            guiGraphics.fill(mx + mw / 2 - 2, my + mh - 1, mx + mw / 2 + 2, my + mh, standColor);
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
        if (recipe instanceof com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingRecipe) {
            return "apotheosis_gem_cutting";
        }
        if (rsi$isGoetyRitual(recipe)) return ModIds.GOETY;

        // YHK cooking pot recipes: 3 ModTypes share 1 JEI UID, so we use
        // result.getCraftingRemainingItem() to pick the right pot type.
        String yhkCooking = rsi$classifyYhkCooking(recipe);
        if (yhkCooking != null) return yhkCooking;

        try {
            ResourceLocation uid = recipeLayout.getRecipeCategory().getRecipeType().getUid();

            // Botania ManaInfusion recipes carry their catalyst in the recipe itself.
            // Use that catalyst to keep pool, alchemy catalyst, and conjuration catalyst distinct.
            if ("botania:mana_pool".equals(uid.toString())) {
                String catalyst = rsi$botaniaManaCatalystFilter(recipe);
                if (catalyst != null) return catalyst;
            }

            // 1. ModType UID → filter lookup (replaces per-mod if/else chain)
            String uidStr = uid.toString();
            String filter = ModType.filterForJeiUid(uidStr);
            if (uidStr.startsWith("youkaishomecoming")) {
                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] getBindingFilter YHK: uid={} filter={} class={}",
                        uidStr, filter, recipe.getClass().getName());
            }
            if (filter != null) return filter;

            // 2. Vanilla / unregistered UIDs not covered by ModType registry
            if (SMITHING_UID.equals(uid)) {
                String cn = recipe.getClass().getName();
                if (cn.startsWith("com.stal111.forbidden_arcanus.")) return "hephaestus_forge";
                if (cn.startsWith("committee.nova.mods.avaritia.")) return ModIds.ID_AVARITIA_SMITHING;
                return "block.minecraft.smithing_table";
            }
            if (SMELTING_UID.equals(uid)) return "block.minecraft.furnace";
            if (BLASTING_UID.equals(uid)) return "block.minecraft.blast_furnace";
            if (SMOKING_UID.equals(uid)) return "block.minecraft.smoker";
            if (CAMPFIRE_UID.equals(uid)) return "block.minecraft.campfire";
            if (STONECUTTING_UID.equals(uid)) return "block.minecraft.stonecutter";
            if (ANVIL_UID.equals(uid)) return "block.minecraft.anvil";
            if (CRABBERSDELIGHT_CRAB_TRAP_UID.equals(uid)) return "crabbersdelight";
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Reflection probe failed", e); }

        // 3. Class name fallback (recipes without standard JEI UIDs, or edge cases)
        String recipeClassName = recipe.getClass().getName();

        if (recipeClassName.equals("com.Polarice3.Goety.common.crafting.BrazierRecipe"))
            return ModIds.GOETY;

        // Avaritia — multiple sub-types with different filters; not resolved by single-ModType lookup
        if (recipeClassName.startsWith("committee.nova.mods.avaritia.common.crafting.recipe.")) {
            if (recipeClassName.endsWith("ExtremeSmithingRecipe")) return ModIds.ID_AVARITIA_SMITHING;
            if (recipeClassName.endsWith("CompressorRecipe")) return ModIds.ID_AVARITIA_COMPRESSOR;
            return ModIds.ID_AVARITIA_CRAFTING;
        }

        // ModType class-name → filter lookup
        String classFilter = ModType.filterForRecipeClass(recipeClassName);
        if (classFilter != null) return classFilter;

        if (recipeClassName.startsWith("alabaster.crabbersdelight."))
            return "crabbersdelight";

        // Returning null is the normal case for any recipe RS Integration doesn't bind
        // (vanilla crafting/anvil, unsupported mods) — keep at debug to avoid log spam.
        RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] getBindingFilter returned NULL: uid={} class={}",
                    rsi$safeCategoryUid(recipeLayout), recipe.getClass().getName());
        return null;
    }

    /**
     * Strip JEI pagination wrapper from recipe IDs.
     * JEI pagination creates pseudo-IDs like {@code mod:jei.real_path/page}
     * whose {@code getId()} doesn't match any real recipe.  Recover the
     * original ID by removing the {@code jei.} prefix and {@code /N} suffix.
     */
    @Unique
    private static ResourceLocation unwrapJeiId(ResourceLocation id) {
        if (id == null) return null;
        String path = id.getPath();
        if (!path.startsWith("jei.")) return id;
        // Strip "jei." prefix
        String inner = path.substring(4);
        // Strip trailing "/N" page number
        int slash = inner.lastIndexOf('/');
        if (slash > 0 && slash < inner.length() - 1) {
            inner = inner.substring(0, slash);
        }
        RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] unwrapJeiId: {} -> {}", id, inner);
        return new ResourceLocation(id.getNamespace(), inner);
    }

    @Unique
    private static ResourceLocation getRecipeId(Object recipe) {
        String className = recipe.getClass().getName();

        if (className.equals("dev.shadowsoffire.apotheosis.adventure.compat.GemCuttingCategory$GemCuttingRecipe")) {
            ItemStack output = rsi$readGemCuttingOutput(recipe);
            var virtualRecipe = output.isEmpty() ? null
                    : com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingCatalog
                    .recipeForTarget(output);
            return virtualRecipe == null ? null : virtualRecipe.getId();
        }

        // Standard Recipe.getId() / m_6423_()
        try {
            Method getId = Reflect.findMethod(recipe.getClass(), "getId", new Class<?>[0]);
            if (getId == null) getId = Reflect.findMethod(recipe.getClass(), "m_6423_", new Class<?>[0]);
            if (getId != null) {
                Object result = getId.invoke(recipe);
                if (result instanceof ResourceLocation id) return unwrapJeiId(id);
            }
        } catch (Exception e) { /* falls through to mod-specific handlers */ }

        // getResourceLocation() fallback (common in non-Recipe objects)
        try {
            Method getRL = Reflect.findMethod(recipe.getClass(), "getResourceLocation", new Class<?>[0]);
            if (getRL != null) {
                Object result = getRL.invoke(recipe);
                if (result instanceof ResourceLocation id) return unwrapJeiId(id);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] getResourceLocation failed", e); }

        // FA-specific: Ritual is a Java Record stored in FARegistries.RITUAL custom registry
        if (className.startsWith("com.stal111.forbidden_arcanus")) {
            ResourceLocation id = rsi$getFARitualId(recipe);
            if (id != null) return unwrapJeiId(id);
        }

        // TLM-specific: AltarRecipeWrapper wraps AltarRecipe but loses the ID
        if (className.equals("com.github.tartaricacid.touhoulittlemaid.compat.jei.altar.AltarRecipeWrapper")) {
            ResourceLocation id = rsi$getTlmWrapperRecipeId(recipe);
            if (id != null) return id;
        }

        // FarmingForBlockheads Market: IMarketEntry → UUID → ResourceLocation
        if (className.startsWith("net.blay09.mods.farmingforblockheads.")) {
            ResourceLocation id = rsi$getMarketEntryId(recipe);
            if (id != null) return id;
        }

        // CrabbersDelight: CrabTrapRecipeWrapper has no getId(), use input item as synthetic ID
        if (className.equals("alabaster.crabbersdelight.integration.jei.CrabTrapRecipeWrapper")) {
            try {
                Method getInput = recipe.getClass().getMethod("getInput");
                ItemStack input = (ItemStack) getInput.invoke(recipe);
                if (!input.isEmpty()) {
                    ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(input.getItem());
                    if (itemId != null) return new ResourceLocation("crabbersdelight", "crab_trap_loot/" + itemId.getNamespace() + "/" + itemId.getPath());
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] CrabTrapRecipeWrapper getId failed", e);
            }
            return null;
        }

        // JEI AnvilRecipe (repair, enchantment combine, rename)
        if (recipe instanceof mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe anvilRecipe) {
            ResourceLocation uid = anvilRecipe.getUid();
            if (uid != null) return uid;
        }

        // JEI's vanilla brewing display is not a Recipe and has no stable ID.
        // Resolve it against the server-side Forge brewing catalog by exact
        // input, reagent and output stacks (including potion NBT).
        if (className.toLowerCase(java.util.Locale.ROOT).contains("brewing")) {
            ResourceLocation brewingId = rsi$findBrewingCatalogId(recipe);
            if (brewingId != null) return brewingId;
        }

        RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] getRecipeId failed for {} — no strategy succeeded", className);
        return null;
    }

    @Unique
    private static ItemStack rsi$readGemCuttingOutput(Object recipe) {
        try {
            java.lang.reflect.Field output = recipe.getClass().getDeclaredField("out");
            output.setAccessible(true);
            Object value = output.get(recipe);
            if (value instanceof ItemStack stack && !stack.isEmpty()) return stack.copy();
        } catch (ReflectiveOperationException ignored) {
        }
        for (java.lang.reflect.Field field : recipe.getClass().getDeclaredFields()) {
            if (!ItemStack.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                ItemStack stack = (ItemStack) field.get(recipe);
                if (stack != null && !stack.isEmpty()) {
                    var instance = dev.shadowsoffire.apotheosis.adventure.socket.gem.GemInstance
                            .unsocketed(stack);
                    if (instance.isValidUnsocketed()) return stack.copy();
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return ItemStack.EMPTY;
    }

    @Unique
    private static ResourceLocation rsi$findBrewingCatalogId(Object display) {
        try {
            if (display instanceof mezz.jei.api.recipe.vanilla.IJeiBrewingRecipe brewing) {
                ItemStack output = brewing.getPotionOutput();
                for (ItemStack input : brewing.getPotionInputs()) {
                    for (ItemStack reagent : brewing.getIngredients()) {
                        ResourceLocation id = rsi$findBrewingCatalogId(input, reagent, output);
                        if (id != null) return id;
                    }
                }
                for (ItemStack input : brewing.getPotionInputs()) {
                    for (ItemStack reagent : brewing.getIngredients()) {
                        ResourceLocation id = com.huanghuang.rsintegration.mods.vanilla.brewing
                                .VanillaBrewingCatalog.registerExact(input, reagent, output);
                        if (id != null) return id;
                    }
                }
                return null;
            }
            ItemStack input = rsi$firstStack(display, "getInput", "getInputs");
            ItemStack reagent = rsi$firstStack(display, "getIngredient", "getReagent");
            ItemStack output = rsi$firstStack(display, "getOutput", "getResult");
            return rsi$findBrewingCatalogId(input, reagent, output);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] brewing catalog ID lookup failed", e);
            return null;
        }
    }

    @Unique
    private static ResourceLocation rsi$findBrewingCatalogId(ItemStack input, ItemStack reagent,
                                                               ItemStack output) {
        if (input.isEmpty() || reagent.isEmpty() || output.isEmpty()) return null;
        if (Minecraft.getInstance().level != null) {
            com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog
                    .ensureBuilt(Minecraft.getInstance().level);
        }
        return com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog
                .findId(input, reagent, output);
    }

    @Unique
    private static ItemStack rsi$firstStack(Object object, String... methods) throws Exception {
        for (String name : methods) {
            try {
                Object value = object.getClass().getMethod(name).invoke(object);
                if (value instanceof ItemStack stack) return stack;
                if (value instanceof Iterable<?> iterable) {
                    for (Object element : iterable) if (element instanceof ItemStack stack) return stack;
                }
            } catch (NoSuchMethodException ignored) {}
        }
        return ItemStack.EMPTY;
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

                // 4. Per-call direct field comparison (handles wrapper/clone objects
                //    whose Ingredient.getItems() order differs from the cache entry)
                ResourceLocation fallback = rsi$faFingerprintMatch(registry, ritual);
                if (fallback != null) return fallback;

                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA ritual not found ({} entries, fp={})",
                        registry.keySet().size(), fp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA registry access failed", e);
                // Fallback: try per-call fingerprint match with whatever registry we have
                return rsi$faFingerprintMatch(null, ritual);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA ritual ID lookup failed", e);
        }
        return null;
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void rsi$ensureFaFingerprintCache(net.minecraft.core.Registry registry) {
        if (rsi$faCacheBuilt) return;

        try {
            int skipped = 0;
            int failed = 0;
            for (Object rawEntry : registry.entrySet()) {
                    java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) rawEntry;
                try {
                    var key = (net.minecraft.resources.ResourceKey<?>) entry.getKey();
                    Object value = entry.getValue();
                    if (value == null || !FAReflection.ritualClass.isInstance(value)) {
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
                    RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA cache entry failed", e);
                }
            }
            rsi$faCacheBuilt = true;
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA fingerprint cache built: {} entries (skipped={} failed={})",
                    rsi$faFingerprintCache.size(), skipped, failed);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA fingerprint cache build failed", e);
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
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA fingerprint build failed", e);
            return "";
        }
    }

    /** Get FA ritual registry, with Forge RegistryManager fallback (like betterjei). */
    @Unique
    private static net.minecraft.core.Registry<?> rsi$getFaRegistry(net.minecraft.client.multiplayer.ClientLevel level) {
        try {
            java.lang.reflect.Field f = FAReflection.faRegistriesClass.getField("RITUAL");
            Object regKey = f.get(null);
            @SuppressWarnings({"unchecked", "rawtypes"})
            var key = (net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>) regKey;
            return level.registryAccess().registryOrThrow(key);
        } catch (Exception e1) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] FA registryOrThrow failed, trying RegistryManager", e1);
            try {
                Class<?> rmClass = Class.forName("net.minecraftforge.registries.RegistryManager");
                java.lang.reflect.Field activeField = rmClass.getField("ACTIVE");
                Object active = activeField.get(null);
                for (java.lang.reflect.Method m : active.getClass().getMethods()) {
                    if (m.getName().equals("getRegistry") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        java.lang.reflect.Field f = FAReflection.faRegistriesClass.getField("RITUAL");
                        Object key = f.get(null);
                        Object reg = m.invoke(active, key);
                        if (reg instanceof net.minecraft.core.Registry<?> r) return r;
                    }
                }
            } catch (Exception e2) {
                RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA RegistryManager fallback also failed", e2);
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
            java.lang.reflect.Method essencesM = FAReflection.ritualClass.getMethod("essences");
            java.lang.reflect.Method resultM = FAReflection.ritualClass.getMethod("result");
            java.lang.reflect.Method inputsM = FAReflection.ritualClass.getMethod("inputs");
            java.lang.reflect.Method aurealM = FAReflection.essencesDefinitionClass.getMethod("aureal");
            java.lang.reflect.Method soulsM  = FAReflection.essencesDefinitionClass.getMethod("souls");
            java.lang.reflect.Method bloodM  = FAReflection.essencesDefinitionClass.getMethod("blood");
            java.lang.reflect.Method expM    = FAReflection.essencesDefinitionClass.getMethod("experience");

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
                if (candidate == null || !FAReflection.ritualClass.isInstance(candidate)) continue;

                Object candResult = resultM.invoke(candidate);
                Object candEssences = essencesM.invoke(candidate);

                if ((int) aurealM.invoke(candEssences) != ta) continue;
                if ((int) soulsM.invoke(candEssences)  != ts) continue;
                if ((int) bloodM.invoke(candEssences)  != tb) continue;
                if ((int) expM.invoke(candEssences)    != te) continue;

                List<?> candInputs = (List<?>) inputsM.invoke(candidate);
                if ((candInputs != null ? candInputs.size() : 0) != targetInputCount) continue;

                if (FAReflection.createItemResultClass.isInstance(targetResult)
                        && FAReflection.createItemResultClass.isInstance(candResult)) {
                    java.lang.reflect.Method getResultM =
                            FAReflection.createItemResultClass.getMethod("getResult");
                    ItemStack targetOut = (ItemStack) getResultM.invoke(targetResult);
                    ItemStack candOut = (ItemStack) getResultM.invoke(candResult);
                    if (ItemStack.isSameItemSameTags(targetOut, candOut)) {
                        return ((net.minecraft.resources.ResourceKey<?>) key).location();
                    }
                } else if (FAReflection.upgradeTierResultClass.isInstance(targetResult)
                        && FAReflection.upgradeTierResultClass.isInstance(candResult)) {
                    java.lang.reflect.Method getReqM =
                            FAReflection.upgradeTierResultClass.getMethod("getRequiredTier");
                    java.lang.reflect.Method getUpM =
                            FAReflection.upgradeTierResultClass.getMethod("getUpgradedTier");
                    if ((int) getReqM.invoke(targetResult) == (int) getReqM.invoke(candResult)
                            && (int) getUpM.invoke(targetResult) == (int) getUpM.invoke(candResult)) {
                        return ((net.minecraft.resources.ResourceKey<?>) key).location();
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] FA fingerprint match failed", e);
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
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] ritual method probe failed", e); }
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
            java.lang.reflect.Field f = TLMReflection.initRecipesClass.getField("ALTAR_CRAFTING");
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
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] TLM altar recipe ID lookup failed", e);
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
                                           ResourceLocation dim, BlockPos machinePos, String filter,
                                           @javax.annotation.Nullable ItemStack faSmithingBase,
                                           @javax.annotation.Nullable ItemStack targetOutput) {
        if (filter.equals("generic")) {
            return () -> {
                GenericCraftPacket pkt;
                try {
                    pkt = new GenericCraftPacket(recipeId, true);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.error("[RSI-JEI] Failed to create GenericCraftPacket (generic): recipeId={}", recipeId, e);
                    return;
                }
                RSIntegrationMod.LOGGER.debug("[RSI-JEI] Sending GenericCraftPacket (generic): recipeId={}", recipeId);
                BatchCraftNetworkHandler.CHANNEL.sendToServer(pkt);
            };
        }
        // Anvil recipes can't be auto-crafted (no persistent inventory, XP cost).
        // Open the remote anvil GUI directly instead of building a plan.
        if (filter.equals("block.minecraft.anvil")) {
            ResourceLocation anvilDim = dim;
            BlockPos anvilPos = machinePos;
            ResourceLocation anvilRecipeId = recipeId;
            return () -> {
                GuiNavStack.pushCurrent();
                RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                        new OpenBoundMachineGuiPacket(anvilDim, anvilPos,
                                anvilRecipeId.toString(), anvilRecipeId));
            };
        }
        // All mod recipes (including Aether) route through the plan-preview flow.
        // GenericCraftPacket now falls back to FARegistries.RITUAL when a recipe
        // is not found in RecipeManager, so FA rituals show the plan tree too.
        // FA smithing base item: prefer JEI visual-slot extraction (handles both
        // hephaestus_smithing and vanilla smithing layouts); fall back to
        // getIngredients() for synthetic SmithingTransformRecipe objects.
        ItemStack capturedBase = faSmithingBase;
        if (capturedBase == null
                && recipe instanceof Recipe<?> r
                && recipeId.getNamespace().equals(ModIds.FORBIDDEN_ARCANUS)
                && r.getIngredients().size() >= 3) {
            Ingredient baseIng = r.getIngredients().get(1);
            if (!baseIng.isEmpty()) {
                ItemStack[] stacks = baseIng.getItems();
                if (stacks.length > 0 && !stacks[0].isEmpty()) capturedBase = stacks[0].copy();
            }
        }
        final ItemStack finalCapturedBase = capturedBase;
        final ItemStack finalTargetOutput = targetOutput;
        return () -> {
            GenericCraftPacket pkt;
            try {
                pkt = new GenericCraftPacket(recipeId, true, dim, machinePos, 1, false, finalCapturedBase, finalTargetOutput);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-JEI] Failed to create GenericCraftPacket: recipeId={} dim={} pos={}", recipeId, dim, machinePos, e);
                return;
            }
            RSIntegrationMod.LOGGER.debug("[RSI-JEI] Sending GenericCraftPacket: recipeId={} dim={} pos={} baseItem={}",
                    recipeId, dim, machinePos, finalCapturedBase != null ? finalCapturedBase.getHoverName().getString() : "null");
            BatchCraftNetworkHandler.CHANNEL.sendToServer(pkt);
        };
    }

    /**
     * Extracts the specific base item displayed in JEI for an FA
     * smithing recipe.  Two layouts exist:
     * <ul>
     *   <li>{@code hephaestus_smithing} — FA's own category; the
     *       {@code mainIngredient} is the <b>first</b> INPUT slot (index 0).</li>
     *   <li>{@code minecraft:smithing} — synthetic {@code SmithingTransformRecipe};
     *       standard vanilla layout: template(0), <b>base(1)</b>, addition(2).</li>
     * </ul>
     */
    /**
     * Extracts the first OUTPUT slot's displayed stack from a JEI recipe layout.
     * Used to capture NBT-variant outputs that share one recipe id — e.g. WR
     * arcane iterator enchant recipes render the leveled book ("Curse II") in the
     * output slot even though the recipe declares no static output.
     */
    @Unique
    private static ItemStack extractOutputStack(IRecipeLayoutDrawable<?> recipeLayout) {
        try {
            IRecipeSlotsView slotsView = recipeLayout.getRecipeSlotsView();
            var outputSlots = slotsView.getSlotViews(RecipeIngredientRole.OUTPUT);
            for (IRecipeSlotView slot : outputSlots) {
                ItemStack out = slot.getDisplayedItemStack().orElse(null);
                if (out != null && !out.isEmpty()) return out.copy();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] Failed to extract output stack", e);
        }
        return null;
    }

    @Unique
    private static ItemStack extractFaSmithingBaseItem(IRecipeLayoutDrawable<?> recipeLayout) {
        try {
            IRecipeSlotsView slotsView = recipeLayout.getRecipeSlotsView();
            ResourceLocation uid = recipeLayout.getRecipeCategory().getRecipeType().getUid();

            // FA hephaestus_smithing: mainIngredient is INPUT slot 0
            if (FA_HEPHAESTUS_SMITHING_UID.equals(uid)) {
                var inputSlots = slotsView.getSlotViews(RecipeIngredientRole.INPUT);
                if (!inputSlots.isEmpty()) {
                    return inputSlots.get(0).getDisplayedItemStack().orElse(null);
                }
            }

            // Vanilla smithing: standard layout template/base/addition
            // Named "base" slot first, then index 1
            java.util.Optional<IRecipeSlotView> named = slotsView.findSlotByName("base");
            if (named.isPresent()) {
                return named.get().getDisplayedItemStack().orElse(null);
            }
            var inputSlots = slotsView.getSlotViews(RecipeIngredientRole.INPUT);
            if (inputSlots.size() >= 2) {
                return inputSlots.get(1).getDisplayedItemStack().orElse(null);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] Failed to extract FA smithing base item", e);
        }
        return null;
    }

    @Unique
    private static BindingStorage.BindingEntry findBinding(@javax.annotation.Nullable AbstractContainerMenu container, String filter) {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;

		boolean debugFaTlm = filter.equals("hephaestus_forge") || filter.equals("touhou_little_maid");
		boolean debugVanilla = filter != null && filter.startsWith("block.minecraft.");
		boolean debugYhk = filter != null && filter.startsWith("youkaishomecoming");
		boolean debug = debugFaTlm || debugVanilla || debugYhk;
        List<String> allBlockKeys = debug ? new ArrayList<>() : null;

        if (container != null) {
            for (net.minecraft.world.inventory.Slot slot : container.slots) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                        if (debug) allBlockKeys.add(entry.blockKey());
                        if (rsi$bindingMatchesFilter(entry.blockKey(), filter)) return entry;
                    }
                }
            }
        }

        var inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (debug) allBlockKeys.add(entry.blockKey());
                if (rsi$bindingMatchesFilter(entry.blockKey(), filter)) return entry;
            }
        }
        for (ItemStack stack : inv.offhand) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (debug) allBlockKeys.add(entry.blockKey());
                if (rsi$bindingMatchesFilter(entry.blockKey(), filter)) return entry;
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
                            if (debug) allBlockKeys.add(entry.blockKey());
                            if (rsi$bindingMatchesFilter(entry.blockKey(), filter)) return entry;
                        }
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] Reflection probe failed", e); }

        if (debug) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] findBinding(filter={}) found no match. All blockKeys in inv: {}",
                    filter, allBlockKeys.isEmpty() ? "<none>" : String.join(", ", allBlockKeys));
        }

        return null;
    }

    @Unique
    private static boolean rsi$bindingMatchesFilter(String blockKey, String filter) {
        if (blockKey == null || filter == null) return false;
        if (blockKey.contains(filter)) return true;
        int sep = blockKey.indexOf("||");
        if (sep < 0) return false;
        String prefix = blockKey.substring(0, sep);
        if ("vanilla_furnace".equals(filter)) {
            return "ironfurnaces_furnace".equals(prefix);
        }
        if ("vanilla_blast_furnace".equals(filter)) {
            return "ironfurnaces_blast_furnace".equals(prefix);
        }
        if ("vanilla_smoker".equals(filter)) {
            return "ironfurnaces_smoker".equals(prefix);
        }
        return false;
    }

    @Unique
    private static String rsi$safeCategoryUid(IRecipeLayoutDrawable<?> layout) {
        try {
            return layout.getRecipeCategory().getRecipeType().getUid().toString();
        } catch (Exception e) { return "?"; }
    }

    @Unique
    private static ResourceLocation rsi$getMarketEntryId(Object recipe) {
        try {
            java.lang.reflect.Method getEntryId = Reflect.findMethod(recipe.getClass(),
                    "getEntryId", new Class<?>[0]);
            if (getEntryId != null) {
                Object result = getEntryId.invoke(recipe);
                if (result instanceof java.util.UUID uuid) {
                    return new ResourceLocation("farmingforblockheads", "market/" + uuid);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] getMarketEntryId failed", e);
        }
        return null;
    }

    @Unique
    private static boolean rsi$isGoetyRitual(Object recipe) {
        return ModList.get().isLoaded(ModIds.GOETY)
                && recipe.getClass().getName().equals("com.Polarice3.Goety.common.crafting.RitualRecipe");
    }

    @Unique
    private static boolean rsi$isGoetyBrazierRecipe(Object recipe) {
        return ModList.get().isLoaded(ModIds.GOETY)
                && recipe.getClass().getName().equals("com.Polarice3.Goety.common.crafting.BrazierRecipe");
    }

    @Unique
    private static boolean rsi$isGoetySacrificial(Object recipe) {
        try {
            return (boolean) recipe.getClass().getMethod("requiresSacrifice").invoke(recipe);
        } catch (Exception e) {
            return false;
        }
    }

    /** Content-based JEI filter for YHK cooking pot recipes.
     *  All three pot sizes share the {@code pot_cooking} JEI UID, so we read
     *  {@code getResult().getCraftingRemainingItem()} to tell which pot the
     *  recipe needs.  Returns the ModType filter string, or null if this
     *  isn't a YHK cooking pot recipe. */
    @Unique
    private static String rsi$classifyYhkCooking(Object recipe) {
        String cn = recipe.getClass().getName();
        if (!cn.startsWith("dev.xkmc.youkaishomecoming.content.pot.cooking."))
            return null;
        try {
            java.lang.reflect.Method getResult = recipe.getClass().getMethod("getResult");
            net.minecraft.world.item.ItemStack result =
                    (net.minecraft.world.item.ItemStack) getResult.invoke(recipe);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                net.minecraft.world.item.ItemStack container = result.getCraftingRemainingItem();
                net.minecraft.resources.ResourceLocation key =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                                container.getItem());
                String k = key.toString();
                if ("youkaishomecoming:short_iron_pot".equals(k))
                    return "youkaishomecoming_cooking_short";
                if ("youkaishomecoming:stockpot".equals(k))
                    return "youkaishomecoming_cooking_large";
                if ("youkaishomecoming:small_iron_pot".equals(k))
                    return "youkaishomecoming_cooking_small";
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-JEI-Mixin] YHK pot type probe failed", e); }
        return "youkaishomecoming_cooking_small";
    }

    /** Returns true for Goety rituals that don't produce items and can't be automated (Convert/Teleport). */
    @Unique
    private static boolean rsi$isGoetyNonItemRitual(Object recipe) {
        try {
            java.lang.reflect.Method getRitual = recipe.getClass().getMethod("getRitual");
            Object ritual = getRitual.invoke(recipe);
            if (ritual == null) return false;
            String name = ritual.getClass().getName();
            // Item-producing rituals: keep these (return false = not a non-item ritual)
            if (name.equals("com.Polarice3.Goety.common.ritual.CraftItemRitual")) return false;
            if (name.equals("com.Polarice3.Goety.common.ritual.EnchantItemRitual")) return false;
            // SummonRitual: kept to allow remote triggering even though no item output
            if (name.equals("com.Polarice3.Goety.common.ritual.SummonRitual")) return false;
            // ConvertRitual, TeleportRitual — no item output, potentially destructive
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] Goety ritual type check failed", e);
            return false;
        }
    }

    @Unique
    private static ModType computeModType(Object recipe) {
        String className = recipe.getClass().getName();
        if (className.startsWith("net.blay09.mods.farmingforblockheads.")) {
            return ModType.byId("farmingforblockheads");
        }
        if (recipe instanceof Recipe<?> r) {
            ModType mt = ModType.classifyRecipe(r);
            if (mt != null) return mt;
        }
        ModType jeiType = ModType.findByRecipeClass(className);
        if (jeiType != null) return jeiType;
        return ModType.GENERIC;
    }

    @Unique
    private static boolean supportsGuiWithRegCheck(String blockKey, @javax.annotation.Nullable String blockRegKey) {
        if (!BindingEventHandler.supportsGuiByBlockKey(blockKey)) return false;
        if (blockRegKey != null) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(blockRegKey);
            if (rl != null) {
                var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(rl);
                if (block != null) {
                    var target = BindingEventHandler.CLASS_TARGET_MAP
                            .get(block.getClass().getName());
                    if (target != null && !target.supportsGui) return false;
                }
            }
        }
        return true;
    }


    @Unique
    private static String rsi$botaniaManaCatalystFilter(Object recipe) {
        if (!rsi$implementsNamedType(recipe, "vazkii.botania.api.recipe.ManaInfusionRecipe")) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] Botania mana recipe has unexpected class {}; refusing fallback binding",
                    recipe == null ? "null" : recipe.getClass().getName());
            return null;
        }
        try {
            Object catalyst = recipe.getClass().getMethod("getRecipeCatalyst").invoke(recipe);
            if (catalyst == null) return "mana_pool";
            Object displayed = catalyst.getClass().getMethod("getDisplayed").invoke(catalyst);
            if (displayed instanceof Iterable<?> states) {
                for (Object value : states) {
                    if (!(value instanceof net.minecraft.world.level.block.state.BlockState state)) continue;
                    ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (id == null) continue;
                    if ("botania".equals(id.getNamespace()) && "conjuration_catalyst".equals(id.getPath())) {
                        return "conjuration_catalyst";
                    }
                    if ("botania".equals(id.getNamespace()) && "alchemy_catalyst".equals(id.getPath())) {
                        return "alchemy_catalyst";
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] Failed to inspect Botania mana catalyst", exception);
        }
        RSIntegrationMod.LOGGER.warn("[RSI-JEI-Mixin] Unsupported Botania mana catalyst for recipe {}; hiding RSI button",
                getRecipeIdSafe(recipe));
        return null;
    }

    @Unique
    private static boolean rsi$implementsNamedType(Object value, String typeName) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(typeName)) return true;
            for (Class<?> contract : type.getInterfaces()) {
                if (rsi$namedInterface(contract, typeName)) return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean rsi$namedInterface(Class<?> type, String typeName) {
        if (type.getName().equals(typeName)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (rsi$namedInterface(parent, typeName)) return true;
        }
        return false;
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
