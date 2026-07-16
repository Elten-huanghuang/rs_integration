package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.plan.PlanRenderEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared selection and labels for compact craft-progress views. */
final class CraftProgressPresentation {

    private CraftProgressPresentation() {}

    static Selection currentNodes(CraftProgressSnapshot snapshot, int limit) {
        int bestRank = Integer.MAX_VALUE;
        for (CraftProgressSnapshot.NodeProgress node : snapshot.nodes()) {
            bestRank = Math.min(bestRank, rank(node));
        }
        if (bestRank == Integer.MAX_VALUE) return new Selection(List.of(), 0);

        List<CraftProgressSnapshot.NodeProgress> matching = new ArrayList<>();
        for (CraftProgressSnapshot.NodeProgress node : snapshot.nodes()) {
            if (rank(node) == bestRank) matching.add(node);
        }
        int shown = Math.min(Math.max(0, limit), matching.size());
        return new Selection(List.copyOf(matching.subList(0, shown)), matching.size() - shown);
    }

    private static int rank(CraftProgressSnapshot.NodeProgress node) {
        if (node.state() == CraftProgressSnapshot.NodeState.RUNNING) {
            return node.draining() ? 1 : 0;
        }
        if (node.state() == CraftProgressSnapshot.NodeState.FAILED) return 2;
        if (node.state() == CraftProgressSnapshot.NodeState.READY) return 3;
        if (node.state() == CraftProgressSnapshot.NodeState.BLOCKED) {
            return node.reason() == CraftProgressSnapshot.Reason.NONE ? 5 : 4;
        }
        return Integer.MAX_VALUE;
    }

    static Component outputName(CraftProgressSnapshot.NodeProgress node) {
        ItemStack output = node.displayOutput();
        if (!output.isEmpty()) return output.getHoverName();
        ResourceLocation recipe = ResourceLocation.tryParse(node.recipeId());
        return Component.literal(recipe == null
                ? Component.translatable("rsi.progress.step.unknown").getString()
                : PlanRenderEngine.formatRecipeName(recipe));
    }

    static Component state(CraftProgressSnapshot.NodeProgress node) {
        String state = node.draining() ? "draining"
                : node.state().name().toLowerCase(Locale.ROOT);
        if (node.totalOperations() > 1) {
            return Component.translatable("rsi.progress.step.state_operations",
                    Component.translatable("rsi.progress.step.state." + state),
                    node.completedOperations(), node.totalOperations());
        }
        return Component.translatable("rsi.progress.step.state." + state);
    }

    static Component machine(CraftProgressSnapshot.NodeProgress node) {
        String type = node.modTypeId().isEmpty()
                ? Component.translatable("rsi.progress.step.machine_unknown").getString()
                : PlanRenderEngine.formatModTypeLabel(node.modTypeId());
        String location = formatMachineLabel(node.machineLabel());
        if (location.isEmpty()) return Component.literal(type);
        return Component.translatable("rsi.progress.step.machine", type, location);
    }

    private static String formatMachineLabel(String label) {
        if (label.isEmpty()) return "";
        int at = label.indexOf('@');
        String dimension = at >= 0 ? label.substring(0, at) : label;
        String position = at >= 0 ? label.substring(at + 1) : "";
        String translated = translateDimension(dimension);
        return position.isEmpty() ? translated : translated + " · " + position;
    }

    private static String translateDimension(String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return dimension;
        String key = "dimension." + id.getNamespace() + "." + id.getPath();
        if (I18n.exists(key)) return I18n.get(key);
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.dimension().location().equals(id)) {
            return Component.translatable("rsi.progress.step.dimension.current").getString();
        }
        return id.getPath().replace('_', ' ');
    }

    record Selection(List<CraftProgressSnapshot.NodeProgress> nodes, int remaining) {}
}
