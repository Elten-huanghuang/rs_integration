package com.huanghuang.rsintegration.crafting;
import java.lang.reflect.Field;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Bounded, player-owned progress diagnostics; never serializes inventory NBT. */
@OnlyIn(Dist.CLIENT)
public final class CraftFailureReport {
    public static final int MAX_REPORT_NODES = 32;
    public static final int MAX_REPORT_CHARS = 65_536;

    private CraftFailureReport() {}

    public static List<Component> lines(CraftProgressSnapshot snapshot, ItemStack target, String version) {
        return lines(snapshot, target, CraftFailureContext.unknown(version), false);
    }

    public static List<Component> lines(CraftProgressSnapshot snapshot, ItemStack target,
                                        CraftFailureContext context, boolean detailed) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("rsi.diagnostic.title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(field("version", context.clientVersions().getOrDefault("rs_integration", "unknown")));
        lines.add(Component.empty());
        lines.add(Component.translatable("rsi.diagnostic.attach_logs").withStyle(ChatFormatting.YELLOW));
        section(lines, "client_observations");
        lines.add(field("received", context.receivedAt() == null ? unknown()
                : context.receivedAt().format(DateTimeFormatter.ISO_ZONED_DATE_TIME)));
        for (var version : context.clientVersions().entrySet()) {
            lines.add(field("environment", version.getKey() + "=" + version.getValue()));
        }
        lines.add(Component.translatable("rsi.diagnostic.environment_scope").withStyle(ChatFormatting.GRAY));
        section(lines, "server_snapshot");
        lines.add(field("task", snapshot.craftId().toString()));
        lines.add(field("sequence", Integer.toString(snapshot.sequence())));
        lines.add(field("mode", context.mode()));
        lines.add(Component.translatable("rsi.diagnostic.times_unknown").withStyle(ChatFormatting.GRAY));
        lines.add(field("target", target.isEmpty() ? unknown() : target.getHoverName().getString()));
        lines.add(item("target_item", target));
        lines.add(Component.translatable("rsi.diagnostic.status", snapshot.result().name())
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        lines.add(Component.translatable("rsi.progress.summary", snapshot.completedNodes(),
                snapshot.totalNodes(), snapshot.runningNodes()));
        addReason(lines, snapshot.reason(), snapshot.technicalDetail());
        section(lines, "section.statistics");
        var states = new EnumMap<CraftProgressSnapshot.NodeState, Integer>(CraftProgressSnapshot.NodeState.class);
        var reasons = new EnumMap<CraftProgressSnapshot.Reason, Integer>(CraftProgressSnapshot.Reason.class);
        long runningOperations = 0;
        int draining = 0;
        for (var node : snapshot.nodes()) {
            states.merge(node.state(), 1, Integer::sum);
            if (node.reason() != CraftProgressSnapshot.Reason.NONE) reasons.merge(node.reason(), 1, Integer::sum);
            runningOperations += node.runningOperations();
            if (node.draining()) draining++;
        }
        lines.add(field("states", states.toString()));
        lines.add(field("reasons", reasons.toString()));
        lines.add(Component.translatable("rsi.diagnostic.activity", snapshot.nodes().size(),
                snapshot.totalNodes(), runningOperations, draining));
        lines.add(Component.empty());
        lines.add(Component.translatable("rsi.diagnostic.inputs_unknown").withStyle(ChatFormatting.GRAY));
        section(lines, "section.steps");

        List<CraftProgressSnapshot.NodeProgress> nodes = snapshot.nodes().stream()
                .filter(node -> node.state() == CraftProgressSnapshot.NodeState.FAILED
                        || node.reason() != CraftProgressSnapshot.Reason.NONE)
                .sorted(Comparator.comparingInt((CraftProgressSnapshot.NodeProgress node) ->
                        node.state() == CraftProgressSnapshot.NodeState.FAILED ? 0 : 1)
                        .thenComparingInt(CraftProgressSnapshot.NodeProgress::nodeId))
                .toList();
        if (nodes.isEmpty()) lines.add(Component.translatable("rsi.diagnostic.no_step").withStyle(ChatFormatting.GRAY));
        for (var node : nodes.stream().limit(MAX_REPORT_NODES).toList()) {
            lines.add(Component.empty());
            lines.add(Component.translatable("rsi.diagnostic.step", node.nodeId() + 1,
                    node.state().name(), node.completedOperations(), node.totalOperations())
                    .withStyle(node.state() == CraftProgressSnapshot.NodeState.FAILED ? ChatFormatting.RED : ChatFormatting.YELLOW,
                            ChatFormatting.BOLD));
            lines.add(field("recipe", node.recipeId()));
            lines.add(field("integration", node.modTypeId()));
            lines.add(item("output_item", node.displayOutput()));
            lines.add(Component.translatable("rsi.diagnostic.node_activity", node.runningOperations(), node.draining()));
            lines.add(node.machineLabel().isEmpty()
                    ? Component.translatable("rsi.diagnostic.machine_unknown").withStyle(ChatFormatting.GRAY)
                    : field("machine", node.machineLabel()));
            addReason(lines, node.reason(), node.technicalDetail());
        }
        if (nodes.size() > MAX_REPORT_NODES) {
            lines.add(Component.translatable("rsi.diagnostic.omitted", nodes.size() - MAX_REPORT_NODES)
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (detailed) addNbt(lines, target, nodes);
        return boundedLines(lines);
    }

    private static Component item(String key, ItemStack stack) {
        return Component.translatable("rsi.diagnostic." + key,
                stack.isEmpty() ? unknown() : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.isEmpty() ? unknown() : Integer.toString(stack.getCount()),
                Component.translatable(stack.isEmpty() ? "rsi.diagnostic.unknown"
                        : stack.hasTag() ? "rsi.diagnostic.nbt_present" : "rsi.diagnostic.nbt_absent"));
    }

    private static void addNbt(List<Component> lines, ItemStack target, List<CraftProgressSnapshot.NodeProgress> nodes) {
        section(lines, "section.nbt");
        lines.add(Component.translatable("rsi.diagnostic.nbt_scope").withStyle(ChatFormatting.GRAY));
        int entries = 0;
        int remaining = CraftFailureNbt.MAX_TOTAL_CHARS;
        for (int index = 0; index <= nodes.size(); index++) {
            ItemStack stack = index == 0 ? target : nodes.get(index - 1).displayOutput();
            if (!stack.hasTag()) continue;
            if (entries >= CraftFailureNbt.MAX_ENTRIES || remaining < CraftFailureNbt.TRUNCATED.length()) {
                lines.add(Component.translatable("rsi.diagnostic.nbt_omitted").withStyle(ChatFormatting.YELLOW));
                break;
            }
            String excerpt = CraftFailureNbt.excerpt(stack.getTag(), remaining);
            remaining -= excerpt.length();
            entries++;
            String label = index == 0 ? "target" : "node=" + (nodes.get(index - 1).nodeId() + 1) + " displayOutput";
            lines.add(Component.empty());
            lines.add(Component.translatable("rsi.diagnostic.nbt_entry", label,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), excerpt)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (entries == 0) lines.add(Component.translatable("rsi.diagnostic.nbt_none").withStyle(ChatFormatting.GRAY));
    }

    private static void section(List<Component> lines, String key) {
        lines.add(Component.empty());
        lines.add(Component.translatable("rsi.diagnostic." + key).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    }

    private static List<Component> boundedLines(List<Component> lines) {
        List<Component> result = new ArrayList<>();
        int remaining = MAX_REPORT_CHARS - Component.translatable("rsi.diagnostic.truncated").getString().length() - 1;
        for (Component line : lines) {
            int length = line.getString().length() + 1;
            if (length > remaining) {
                result.add(Component.translatable("rsi.diagnostic.truncated").withStyle(ChatFormatting.YELLOW));
                break;
            }
            result.add(line);
            remaining -= length;
        }
        return List.copyOf(result);
    }

    private static String unknown() { return Component.translatable("rsi.diagnostic.unknown").getString(); }

    public static String plainText(List<Component> lines) {
        String text = lines.stream().map(line -> ChatFormatting.stripFormatting(line.getString()))
                .collect(Collectors.joining("\n"));
        if (text.length() <= MAX_REPORT_CHARS) return text;
        String suffix = "\n" + Component.translatable("rsi.diagnostic.truncated").getString();
        int end = MAX_REPORT_CHARS - suffix.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + suffix;
    }

    private static void addReason(List<Component> lines, CraftProgressSnapshot.Reason reason, String detail) {
        lines.add(Component.translatable("rsi.diagnostic.reason",
                Component.translatable(reason == CraftProgressSnapshot.Reason.NONE
                        || reason == CraftProgressSnapshot.Reason.UNKNOWN
                        ? "rsi.progress.reason.failed_unspecified" : reason.translationKey()), reason.name())
                .withStyle(ChatFormatting.RED));
        lines.add(field("technical", detail));
        lines.add(Component.empty());
        lines.add(Component.translatable("rsi.diagnostic.check", Component.translatable(hintKey(reason)))
                .withStyle(ChatFormatting.YELLOW));
    }

    public static String hintKey(CraftProgressSnapshot.Reason reason) {
        String hint = switch (reason) {
            case WAITING_MATERIALS, MATERIAL_EXTRACTION_FAILED -> "materials";
            case MACHINE_BUSY, RESOURCE_CONFLICT, OPERATION_BUDGET -> "busy";
            case CHUNK_UNLOADED -> "chunk";
            case CONTRACT_INCOMPATIBLE -> "compatibility";
            case START_REJECTED, INPUT_CONFLICT -> "input";
            case OUTPUT_MISSING -> "output";
            case TIMEOUT -> "timeout";
            case NETWORK_UNAVAILABLE -> "network";
            case NO_BOUND_MACHINE -> "binding";
            case SPECIAL_RESOURCE_INSUFFICIENT -> "resource";
            case PLAYER_CANCELLED, PLAYER_OFFLINE, SERVER_STOP -> "stopped";
            default -> "unknown";
        };
        return "rsi.diagnostic.hint." + hint.toLowerCase(Locale.ROOT);
    }

    private static Component field(String name, String value) {
        return Component.translatable("rsi.diagnostic." + name,
                value == null || value.isBlank() ? unknown() : safeText(value))
                .withStyle(name.equals("target") ? ChatFormatting.WHITE : ChatFormatting.GRAY);
    }

    static String safeText(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        boolean truncated = value.codePointCount(0, value.length()) > 1024;
        value.codePoints().limit(truncated ? 1012 : 1024).forEach(code -> {
            // Keep raw details on one line and strip formatting/control characters.
            if (code == 0xA7 || Character.isISOControl(code)
                    || Character.getType(code) == Character.FORMAT) result.append(' ');
            else result.appendCodePoint(code);
        });
        if (truncated) result.append(" [truncated]");
        return result.toString();
    }
}
