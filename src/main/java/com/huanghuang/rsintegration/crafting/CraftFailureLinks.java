package com.huanghuang.rsintegration.crafting;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

final class CraftFailureLinks {
    static final String COMMAND = "rsi_failure_report";
    private UUID pending;
    private long generation;
    private boolean defer;

    static String localCommand(Style style) {
        ClickEvent click = style == null ? null : style.getClickEvent();
        if (click == null || click.getAction() != ClickEvent.Action.RUN_COMMAND) return null;
        String prefix = "/" + COMMAND;
        String command = click.getValue();
        return command.equals(prefix) || command.startsWith(prefix + " ") ? command.substring(1) : null;
    }

    static Component message(UUID id) {
        Component link = Component.translatable("rsi.diagnostic.chat_link").withStyle(style -> style
                .withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " " + id))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("rsi.diagnostic.chat_hover", id.toString()))));
        return Component.translatable("rsi.diagnostic.chat_failure").withStyle(ChatFormatting.RED)
                .append("\n").append(link);
    }

    static <Source> void register(CommandDispatcher<Source> dispatcher, Consumer<String> request) {
        dispatcher.register(LiteralArgumentBuilder.<Source>literal(COMMAND)
                .executes(context -> { request.accept(""); return 0; })
                .then(RequiredArgumentBuilder.<Source, String>argument("task", StringArgumentType.word())
                        .executes(context -> {
                            request.accept(StringArgumentType.getString(context, "task"));
                            return 1;
                        })));
    }

    boolean request(String text, long currentGeneration, Function<UUID, CraftFailureHistory.Entry> lookup) {
        pending = null;
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text) || lookup.apply(id) == null) return false;
            pending = id;
            generation = currentGeneration;
            defer = true;
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    void tick(long currentGeneration, Function<UUID, CraftFailureHistory.Entry> lookup,
              Consumer<CraftFailureHistory.Entry> open, Runnable expired) {
        if (pending == null) return;
        if (currentGeneration != generation) { clear(); return; }
        if (defer) { defer = false; return; }
        CraftFailureHistory.Entry entry = lookup.apply(pending);
        clear();
        if (entry == null) expired.run();
        else open.accept(entry);
    }

    void clear() { pending = null; }
}
