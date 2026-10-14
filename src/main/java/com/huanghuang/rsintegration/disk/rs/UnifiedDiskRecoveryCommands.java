package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.ModItems;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** 管理员找回凭据；所有副本仍通过已有的全服唯一挂载机制共享同一库存。 */
public final class UnifiedDiskRecoveryCommands {
    private UnifiedDiskRecoveryCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("list").executes(context -> list(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("recover").then(Commands.argument("id", UuidArgument.uuid())
                        .executes(context -> recover(context.getSource(), UuidArgument.getUuid(context, "id")))));
    }

    static int list(CommandSourceStack source, int page) {
        try {
            List<UUID> ids = UnifiedDiskManager.get(source.getLevel()).savedDiskIds();
            long start = (page - 1L) * 20;
            if (start >= ids.size()) {
                source.sendFailure(Component.translatable("rsi.unified_disk.recovery.empty_page")); return 0;
            }
            source.sendSuccess(() -> Component.translatable("rsi.unified_disk.recovery.list", ids.size(), page), false);
            int end = (int) Math.min(start + 20, ids.size());
            for (int index = (int) start; index < end; index++) {
                UUID id = ids.get(index);
                source.sendSuccess(() -> diskEntry(id), false);
            }
            return end - (int) start;
        } catch (IOException e) {
            source.sendFailure(Component.translatable("rsi.unified_disk.recovery.failed", e.getMessage())); return 0;
        }
    }

    private static Component diskEntry(UUID id) {
        String diskId = id.toString();
        Component copyId = Component.literal(diskId).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, diskId))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("rsi.unified_disk.recovery.copy_id_hover"))));
        Component recover = Component.translatable("rsi.unified_disk.recovery.recover_action").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/rsi_unified_disk recover " + diskId))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("rsi.unified_disk.recovery.recover_hover"))));
        return Component.empty().append(copyId).append(" ").append(recover);
    }

    static int recover(CommandSourceStack source, UUID id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack replacement = new ItemStack(ModItems.UNIFIED_STORAGE_DISK.get());
        try {
            UnifiedDiskManager.get(source.getLevel()).restoreIdentity(id, replacement);
            if (!player.getInventory().add(replacement)) {
                source.sendFailure(Component.translatable("rsi.unified_disk.recovery.inventory_full")); return 0;
            }
            source.sendSuccess(() -> Component.translatable("rsi.unified_disk.recovery.recovered", id.toString()), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.translatable("rsi.unified_disk.recovery.failed", e.getMessage())); return 0;
        }
    }
}
