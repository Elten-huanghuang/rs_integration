package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@OnlyIn(Dist.CLIENT)
final class PlanResponseClientPacketHandler {
    private PlanResponseClientPacketHandler() {}

    static void handle(PlanResponse plan, long requestId) {
        RSIntegrationMod.LOGGER.debug(
                "[RSI-PlanPkt] enqueueWork running on client thread: recipeId={}",
                plan.recipeId());
        // Error responses intentionally carry an empty target stack. Do not
        // open a normal plan screen for those packets: ItemStack.EMPTY's
        // hover name is "Air", which makes a failed request look like an
        // actual recipe targeting air. Valid infeasible plans still have a
        // recipe id/target and continue to open with their missing materials.
        if (!plan.success()
                && (plan.recipeId() == null || plan.recipeId().isEmpty())
                && plan.targetResult().isEmpty()) {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                Component message = plan.modWarnings().isEmpty()
                        ? Component.translatable("rsi.generic.error.craft_failed")
                        : plan.modWarnings().get(0);
                mc.player.displayClientMessage(message, false);
            }
            return;
        }
        List<String> missing = localizeItemNames(plan.missing());
        String targetName = plan.targetResult().isEmpty()
                ? plan.targetName()
                : plan.targetResult().getHoverName().getString();
        PlanResponse localized = new PlanResponse(
                plan.success(), targetName, plan.targetResult(), plan.steps(), plan.materials(), missing,
                plan.recipeId(), plan.executionModTypeId(), plan.executionDim(), plan.executionPosX(),
                plan.executionPosY(), plan.executionPosZ(), plan.modWarnings(), plan.repeatCount(),
                plan.embersCode(), plan.embersAspectNames(), plan.embersInputNames(), plan.embersSeed(),
                plan.embersCanInfer(), plan.embersCodeFromCache(), plan.executionMachineSupportsGui(),
                plan.baseItem(), plan.boundMachineTypes(), plan.leftovers(), plan.clickedOutput(), plan.graph(),
                plan.executionBlocked(), plan.machineCandidates(), plan.stepIssues(),
                plan.storageReference(), plan.storageNetworks());
        openScreen(localized, requestId);
    }

    static List<String> localizeItemNames(List<String> names) {
        if (names.isEmpty()) return names;
        LinkedHashSet<String> localized = new LinkedHashSet<>(names.size());
        for (String name : names) {
            String translated = name;
            int hintStart = name.indexOf(" \u00a7");
            String key = hintStart >= 0 ? name.substring(0, hintStart) : name;
            String suffix = hintStart >= 0 ? name.substring(hintStart) : "";
            ResourceLocation descriptionItemId = itemIdForDescriptionId(key);
            ResourceLocation itemId = ResourceLocation.tryParse(key);
            var registeredItem = itemId != null ? ForgeRegistries.ITEMS.getValue(itemId) : null;
            if (descriptionItemId != null) {
                // Keep a machine-readable identity until the bookmark list turns it
                // into a localized ItemStack label. Localizing here loses the item id.
                translated = descriptionItemId + suffix;
            } else if (registeredItem != null && !new ItemStack(registeredItem).isEmpty()) {
                translated = itemId + suffix;
            } else if (I18n.exists(key)) {
                translated = I18n.get(key) + suffix;
            }
            localized.add(translated);
        }
        return new ArrayList<>(localized);
    }

    @Nullable
    private static ResourceLocation itemIdForDescriptionId(String descriptionId) {
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (descriptionId.equals(stack.getDescriptionId())) {
                return ForgeRegistries.ITEMS.getKey(item);
            }
        }
        return null;
    }

    private static void openScreen(PlanResponse plan, long requestId) {
        RSIntegrationMod.LOGGER.debug(
                "[RSI-PlanPkt] openScreen called: success={} steps={} graphNodes={}",
                plan.success(), plan.steps().size(),
                plan.graph() != null ? plan.graph().nodes().size() : 0);
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-PlanPkt] openScreen ABORT: mc.player is null");
            return;
        }
        if (mc.screen instanceof CraftingPlanScreen existing
                && requestId != 0L && requestId < existing.activeRequestId()) {
            RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] Dropping stale request response {} < {}",
                    requestId, existing.activeRequestId());
            return;
        }
        if (mc.screen instanceof CraftingPlanScreen existing
                && plan.recipeId() != null
                && !plan.recipeId().isEmpty()
                && !plan.recipeId().equals(existing.getRecipeId())) {
            RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] Dropping stale response: received={} active={}",
                    plan.recipeId(), existing.getRecipeId());
            return;
        }
        if (mc.screen instanceof CraftingPlanScreen existing
                && requestId != 0L
                && (plan.recipeId() == null || plan.recipeId().isEmpty()
                || plan.recipeId().equals(existing.getRecipeId()))) {
            RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] openScreen UPDATE: refreshing plan for {}",
                    plan.recipeId());
            existing.acceptResponse(requestId, plan);
            return;
        }
        try {
            mc.setScreen(new CraftingPlanScreen(plan));
            RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] CraftingPlanScreen opened successfully");
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-PlanPkt] Failed to open CraftingPlanScreen:", e);
        }
    }
}
