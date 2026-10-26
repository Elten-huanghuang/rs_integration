package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.consumer.ConsumerCraftability;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnostic;
import com.huanghuang.rsintegration.api.consumer.ConsumerExternalRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionOptions;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPage;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicket;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicketId;
import com.huanghuang.rsintegration.api.consumer.ConsumerMachineSelector;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderStatus;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanId;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanningPreferences;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCandidate;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCatalogPage;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCatalogRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeQuery;
import com.huanghuang.rsintegration.api.consumer.ConsumerRequestAmount;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerServiceState;
import com.huanghuang.rsintegration.api.consumer.ConsumerStorageSelector;
import com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ConsumerSmokeCommand {
    private static final ResourceLocation CONSUMER_ID =
            smokeId("consumer");
    private static final Map<PlanKey, ConsumerPlanId> PLANS =
            new ConcurrentHashMap<>();
    private static final Map<UUID, ConsumerOrderId> LAST_ORDERS =
            new ConcurrentHashMap<>();

    private ConsumerSmokeCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rsi_api_smoke")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("catalog")
                        .then(Commands.argument(
                                        "item", StringArgumentType.string())
                                .executes(ConsumerSmokeCommand::catalog)))
                .then(Commands.literal("inspect")
                        .then(Commands.argument(
                                        "recipe", StringArgumentType.string())
                                .executes(ConsumerSmokeCommand::inspect)))
                .then(Commands.literal("page")
                        .then(Commands.argument(
                                        "ticket", StringArgumentType.string())
                                .executes(ConsumerSmokeCommand::page)))
                .then(Commands.literal("submit")
                        .then(Commands.argument(
                                        "ticket", StringArgumentType.string())
                                .executes(ConsumerSmokeCommand::submit)))
                .then(Commands.literal("status")
                        .executes(ConsumerSmokeCommand::status))
                .then(Commands.literal("cancel")
                        .executes(ConsumerSmokeCommand::cancel)));
    }

    private static int catalog(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        RSICraftConsumerApi api = readyApi(player, context);
        if (api == null) return 0;
        ResourceLocation itemId = ResourceLocation.tryParse(
                StringArgumentType.getString(context, "item"));
        Item item = itemId == null ? Items.AIR
                : BuiltInRegistries.ITEM.get(itemId);
        if (item == Items.AIR) {
            context.getSource().sendFailure(
                    Component.literal("Unknown item: " + itemId));
            return 0;
        }
        ConsumerResult<ConsumerRecipeCatalogPage> result =
                api.findRecipes(player, new ConsumerRecipeCatalogRequest(
                        new ConsumerRecipeQuery.ByOutput(
                                IntegrationResourceKey.item(
                                        new ItemStack(item)),
                                ResourceMatchMode.ANY_IDENTITY
                        ),
                        64,
                        0L
                ));
        if (!result.successful()) {
            return failure(context, result.diagnostics());
        }
        ConsumerRecipeCatalogPage page = result.value();
        context.getSource().sendSuccess(() -> Component.literal(
                "Catalog candidates=" + page.candidates().size()
                        + " revision=" + page.recipeRevision()
                        + " next=" + page.nextCursor()), false);
        for (ConsumerRecipeCandidate candidate : page.candidates()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    candidate.recipeId() + " process="
                            + candidate.processId() + " mode="
                            + candidate.executionKind()), false);
        }
        return page.candidates().size();
    }

    private static int inspect(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        RSICraftConsumerApi api = readyApi(player, context);
        if (api == null) return 0;
        ResourceLocation recipeId = ResourceLocation.tryParse(
                StringArgumentType.getString(context, "recipe"));
        if (recipeId == null) {
            context.getSource().sendFailure(
                    Component.literal("Invalid recipe id"));
            return 0;
        }
        ConsumerInspectionRequest request = new ConsumerInspectionRequest(
                CONSUMER_ID,
                new ConsumerInspectionRequestId(UUID.randomUUID()),
                List.of(new ConsumerRecipeKey(
                        recipeId,
                        null,
                        new ConsumerRequestAmount.OutputUnits(1L),
                        ConsumerPlanningPreferences.empty()
                )),
                new ConsumerMachineSelector.AutomaticBound(),
                new ConsumerStorageSelector.Default(),
                ConsumerOutputDestination.PLAYER_INVENTORY,
                new ConsumerInspectionOptions(64, true)
        );
        ConsumerResult<ConsumerInspectionTicket> result =
                api.openInspection(player, request);
        if (!result.successful()) {
            return failure(context, result.diagnostics());
        }
        ConsumerInspectionTicket ticket = result.value();
        context.getSource().sendSuccess(() -> Component.literal(
                "Inspection opened: " + ticket.ticketId().value()
                        + " recipes=" + ticket.totalRecipes()), false);
        return 1;
    }

    private static int page(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        RSICraftConsumerApi api = readyApi(player, context);
        if (api == null) return 0;
        ConsumerInspectionTicketId ticketId = ticket(context);
        if (ticketId == null) return 0;
        ConsumerResult<ConsumerInspectionPage> result =
                api.nextInspectionPage(player, ticketId);
        if (!result.successful()) {
            return failure(context, result.diagnostics());
        }
        ConsumerInspectionPage page = result.value();
        context.getSource().sendSuccess(() -> Component.literal(
                "Inspection page=" + page.pageIndex()
                        + " state=" + page.state()
                        + " next=" + page.nextPageIndex()), false);
        for (ConsumerCraftability craftability : page.results()) {
            if (craftability.planId() != null) {
                PLANS.put(
                        new PlanKey(player.getUUID(), ticketId),
                        craftability.planId());
            }
            context.getSource().sendSuccess(() -> Component.literal(
                    craftability.recipe().recipeId()
                            + " status=" + craftability.status()
                            + " executable="
                            + craftability.executableQuantity()
                            + " plan=" + craftability.planId()), false);
        }
        return 1;
    }

    private static int submit(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        RSICraftConsumerApi api = readyApi(player, context);
        if (api == null) return 0;
        ConsumerInspectionTicketId ticketId = ticket(context);
        if (ticketId == null) return 0;
        ConsumerPlanId planId = PLANS.get(
                new PlanKey(player.getUUID(), ticketId));
        if (planId == null) {
            context.getSource().sendFailure(Component.literal(
                    "No READY plan recorded for ticket "
                            + ticketId.value()));
            return 0;
        }
        ConsumerResult<ConsumerOrderReceipt> result = api.submit(
                player,
                new ConsumerOrderRequest(
                        CONSUMER_ID,
                        new ConsumerExternalRequestId(UUID.randomUUID()),
                        planId
                )
        );
        if (!result.successful()) {
            return failure(context, result.diagnostics());
        }
        ConsumerOrderReceipt receipt = result.value();
        LAST_ORDERS.put(player.getUUID(), receipt.orderId());
        context.getSource().sendSuccess(() -> Component.literal(
                "Order submitted: " + receipt.orderId().value()
                        + " state=" + receipt.state()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        RSICraftConsumerApi api = readyApi(player, context);
        if (api == null) return 0;
        ConsumerOrderId orderId = LAST_ORDERS.get(player.getUUID());
        if (orderId == null) {
            context.getSource().sendFailure(
                    Component.literal("No smoke order recorded"));
            return 0;
        }
        ConsumerResult<ConsumerOrderStatus> result =
                api.status(player, CONSUMER_ID, orderId);
        if (!result.successful()) {
            return failure(context, result.diagnostics());
        }
        ConsumerOrderStatus status = result.value();
        context.getSource().sendSuccess(() -> Component.literal(
                "Order " + status.orderId().value()
                        + " state=" + status.state()
                        + " outputs=" + status.outputs().size()), false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        RSICraftConsumerApi api = readyApi(player, context);
        if (api == null) return 0;
        ConsumerOrderId orderId = LAST_ORDERS.get(player.getUUID());
        if (orderId == null) {
            context.getSource().sendFailure(
                    Component.literal("No smoke order recorded"));
            return 0;
        }
        var result = api.cancel(
                player,
                CONSUMER_ID,
                orderId,
                new ConsumerExternalRequestId(UUID.randomUUID())
        );
        if (!result.successful()) {
            return failure(context, result.diagnostics());
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "Cancel request accepted for " + orderId.value()), false);
        return 1;
    }

    private static ServerPlayer player(
            CommandContext<CommandSourceStack> context
    ) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(
                    Component.literal("Player command only"));
        }
        return player;
    }

    private static RSICraftConsumerApi readyApi(
            ServerPlayer player,
            CommandContext<CommandSourceStack> context
    ) {
        if (player == null) return null;
        RSICraftConsumerApi api = RSICraftConsumerApi.get().orElse(null);
        if (api == null || api.state() != ConsumerServiceState.READY) {
            context.getSource().sendFailure(Component.literal(
                    "Consumer API is not ready: "
                            + (api == null ? "not installed" : api.state())));
            return null;
        }
        return api;
    }

    private static ConsumerInspectionTicketId ticket(
            CommandContext<CommandSourceStack> context
    ) {
        try {
            return new ConsumerInspectionTicketId(UUID.fromString(
                    StringArgumentType.getString(context, "ticket")));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(
                    Component.literal("Invalid ticket UUID"));
            return null;
        }
    }

    private static int failure(
            CommandContext<CommandSourceStack> context,
            List<ConsumerDiagnostic> diagnostics
    ) {
        for (ConsumerDiagnostic diagnostic : diagnostics) {
            context.getSource().sendFailure(Component.literal(
                    diagnostic.code() + ": " + diagnostic.detail()));
        }
        return 0;
    }

    private static ResourceLocation smokeId(String path) {
        return new ResourceLocation(RsiApiSmokeMod.MOD_ID, path);
    }

    private record PlanKey(UUID playerId, ConsumerInspectionTicketId ticketId) {}
}
