package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageBackendResolution;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Explicit RS-only smoke tests; never selected by normal gameplay code. */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID)
public final class StorageTestCommand {
    private static final StorageBackendId RS = new StorageBackendId("refinedstorage");

    private StorageTestCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("rsi_storage_test")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("snapshot").executes(StorageTestCommand::snapshot))
                .then(Commands.literal("simulate_insert").executes(StorageTestCommand::simulateInsert))
                .then(Commands.literal("roundtrip")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(StorageTestCommand::roundTrip))));
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
