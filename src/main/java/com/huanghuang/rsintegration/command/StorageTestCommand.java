package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageBackendResolution;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

/** Explicit RS-only smoke tests; never selected by normal gameplay code. */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID)
public final class StorageTestCommand {
    private static final StorageBackendId RS = new StorageBackendId("refinedstorage");

    private StorageTestCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(buildCommand());
    }

    static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("rsi_storage_test")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("snapshot").executes(StorageTestCommand::snapshot))
                .then(Commands.literal("simulate_insert").executes(StorageTestCommand::simulateInsert))
                .then(Commands.literal("insert")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L, Long.MAX_VALUE))
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests(StorageTestCommand::suggestItems)
                                        .executes(StorageTestCommand::insert))))
                .then(Commands.literal("roundtrip")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(StorageTestCommand::roundTrip)));
    }

    private static int snapshot(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        StorageSession session = resolve(player, context);
        if (session == null) return 0;
        StorageSnapshotResult result = session.snapshotItems(player);
        if (!result.successful()) {
            return fail(context, "snapshot failed: " + result.status()
                    + " (" + result.diagnosticCode() + ")");
        }
        StorageSnapshot snapshot = result.snapshot().orElseThrow();
        context.getSource().sendSuccess(() -> Component.literal(
                "RS snapshot OK: " + snapshot.items().size() + " item keys, revision="
                        + snapshot.revision()), false);
        return 1;
    }

    private static int simulateInsert(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return fail(context, "hold a non-empty item in your main hand");
        StorageSession session = resolve(player, context);
        if (session == null) return 0;
        StorageOperationResult result = session.insert(player, held.copy(), true);
        context.getSource().sendSuccess(() -> Component.literal(
                "RS simulate insert: status=" + result.status() + ", accepted="
                        + result.transferredAmount().orElse(0L) + ", remainderKnown="
                        + result.remainder().isPresent()), false);
        return result.status() == StorageOperationStatus.SUCCESS
                || result.status() == StorageOperationStatus.PARTIAL ? 1 : 0;
    }

    private static int insert(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        long amount = LongArgumentType.getLong(context, "amount");
        ResourceLocation itemId = ResourceLocationArgument.getId(context, "item");
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return fail(context, "unknown item: " + itemId);
        }
        StorageSession session = resolve(player, context);
        if (session == null) return 0;
        ItemStack template = new ItemStack(item);
        long inserted = insertLong(session, player, template, amount);
        if (inserted != amount) {
            return fail(context, "insert incomplete: requested=" + amount
                    + ", accepted=" + inserted);
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "RS insert OK: " + itemId + " x" + CompactQuantityFormat.format(inserted)
                        + " (" + inserted + ")"), false);
        return 1;
    }

    static long insertLong(StorageSession session, ServerPlayer player,
                           ItemStack template, long requested) {
        if (session == null || player == null || template == null
                || template.isEmpty() || requested <= 0L) return 0L;
        long accepted = 0L;
        while (accepted < requested) {
            int chunk = SaturatingLongMath.clampToInt(requested - accepted);
            if (chunk <= 0) break;
            StorageOperationResult result = session.insert(
                    player, template.copyWithCount(chunk), false);
            long moved = result.transferredAmount().orElse(0L);
            if (moved <= 0L) break;
            accepted = SaturatingLongMath.add(accepted, Math.min(moved, chunk));
            if (moved < chunk) break;
        }
        return accepted;
    }

    private static CompletableFuture<Suggestions> suggestItems(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(
                ForgeRegistries.ITEMS.getKeys(), builder);
    }

    private static int roundTrip(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        int amount = IntegerArgumentType.getInteger(context, "amount");
        if (held.isEmpty()) return fail(context, "hold a non-empty item in your main hand");
        if (amount > held.getCount()) return fail(context, "amount exceeds held stack count");
        StorageSession session = resolve(player, context);
        if (session == null) return 0;
        ItemStack input = held.copyWithCount(amount);
        StorageOperationResult inserted = session.insert(player, input, false);
        long accepted = inserted.transferredAmount().orElse(0L);
        if (inserted.status() != StorageOperationStatus.SUCCESS || accepted != amount) {
            return fail(context, "roundtrip insert did not accept exactly " + amount
                    + ": status=" + inserted.status() + ", accepted=" + accepted);
        }
        StorageOperationResult extracted = session.extractExact(
                player, session.itemKey(input), amount, false);
        long returned = extracted.transferredAmount().orElse(0L);
        context.getSource().sendSuccess(() -> Component.literal(
                "RS roundtrip: inserted=" + accepted + ", extracted=" + returned
                        + ", extractStatus=" + extracted.status()), false);
        return extracted.status() == StorageOperationStatus.SUCCESS && returned == amount ? 1 : 0;
    }

    private static StorageSession resolve(ServerPlayer player, CommandContext<CommandSourceStack> context) {
        for (StorageBackendResolution resolution
                : RSIntegrationMod.STORAGE_BACKENDS.registry().resolveDefaultResultsForPlayer(player)) {
            if (!RS.equals(resolution.backendId())) continue;
            StorageResolutionResult result = resolution.result();
            if (result.resolved()) return result.session().orElseThrow();
            fail(context, "RS network unavailable: " + result.status());
            return null;
        }
        fail(context, "RS backend is not registered");
        return null;
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
