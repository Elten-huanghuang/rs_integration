package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.*;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.BindingStorage;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyLock;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID)
public final class DebugCommand {

    private DebugCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("rsi_debug")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("dump")
                        .then(Commands.literal("chain")
                                .executes(DebugCommand::dumpChain)
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(DebugCommand::dumpChainForPlayer)))
                        .then(Commands.literal("index")
                                .executes(DebugCommand::dumpIndex))
                        .then(Commands.literal("handlers")
                                .executes(DebugCommand::dumpHandlers))
                        .then(Commands.literal("ledger")
                                .executes(DebugCommand::dumpLedger)
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(DebugCommand::dumpLedgerForPlayer))))
                .then(Commands.literal("trace")
                        .then(Commands.literal("--no-inventory")
                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                        .executes(DebugCommand::traceItemNoInventory)))
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(DebugCommand::traceItem)))
                .then(Commands.literal("audit")
                        .executes(DebugCommand::audit))
                .then(Commands.literal("embers_clearcache")
                        .executes(DebugCommand::embersClearCache))
                .then(Commands.literal("embers_clearlocks")
                        .executes(DebugCommand::embersClearLocks))
                .then(Commands.literal("perf")
                        .executes(DebugCommand::perfSnapshot))
                .then(Commands.literal("bindings")
                        .executes(DebugCommand::dumpBindings)));
    }

    // ── dump chain ───────────────────────────────────────────────

    private static int dumpChain(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return dumpChainFor(ctx.getSource().getPlayerOrException(), ctx);
    }

    private static int dumpChainForPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return dumpChainFor(EntityArgument.getPlayer(ctx, "player"), ctx);
    }

    private static int dumpChainFor(ServerPlayer player, CommandContext<CommandSourceStack> ctx) {
        AsyncCraftChain chain = AsyncCraftManager.getInstance().getChain(player);
        if (chain == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No active craft chain for " + player.getName().getString()), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("=== Chain State ==="), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Player: " + player.getName().getString()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("State: " + chain.state()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Step: " + chain.currentStep() + "/" + chain.stepsCount()), false);
        if (chain.isAborted()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Abort reason: " + chain.abortReason()), false);
        }

        ExtractionLedger ledger = chain.ledger();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Ledger: " + ledger.size() + " entries, state=" + ledger.state()
                + ", committed=" + ledger.isCommitted()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Pending: " + ledger.describePending()), false);

        ctx.getSource().sendSuccess(() -> Component.literal("Virtual Inventory:"), false);
        for (ItemStack vi : chain.virtualInventory()) {
            if (vi.isEmpty()) continue;
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(vi.getItem());
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + (rl != null ? rl.toString() : "?") + " x" + vi.getCount()
                    + (vi.hasTag() ? " +nbt" : "")), false);
        }
        return 1;
    }

    // ── dump ledger ───────────────────────────────────────────────

    private static int dumpLedger(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return dumpLedgerFor(ctx.getSource().getPlayerOrException(), ctx);
    }

    private static int dumpLedgerForPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return dumpLedgerFor(EntityArgument.getPlayer(ctx, "player"), ctx);
    }

    private static int dumpLedgerFor(ServerPlayer player, CommandContext<CommandSourceStack> ctx) {
        AsyncCraftChain chain = AsyncCraftManager.getInstance().getChain(player);
        if (chain == null || chain.ledger() == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No active ledger for " + player.getName().getString()), false);
            return 0;
        }

        ExtractionLedger ledger = chain.ledger();
        ctx.getSource().sendSuccess(() -> Component.literal("=== Ledger for " + player.getName().getString() + " ==="), false);
        ctx.getSource().sendSuccess(() -> Component.literal("State: " + ledger.state()
                + ", Committed: " + ledger.isCommitted()
                + ", Entries: " + ledger.size()), false);

        for (String desc : ledger.describeEntries()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + desc), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Pending reservations: " + ledger.describePending()), false);
        return 1;
    }

    // ── dump index ────────────────────────────────────────────────

    private static int dumpIndex(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Map<Item, List<RecipeIndex.Entry>> index = RecipeIndex.get(player.serverLevel());
        int totalEntries = 0;
        int genericEntries = 0;
        int modEntries = 0;
        for (var entry : index.values()) {
            totalEntries += entry.size();
            for (var e : entry) {
                if (e.modType() == ModType.GENERIC) genericEntries++;
                else modEntries++;
            }
        }
        final int fTotal = totalEntries;
        final int fGeneric = genericEntries;
        final int fMod = modEntries;
        final int fItemCount = index.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "RecipeIndex: " + fItemCount + " items, " + fTotal + " entries"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  Generic: " + fGeneric + ", Mod: " + fMod), false);
        return 1;
    }

    // ── dump handlers ─────────────────────────────────────────────

    private static int dumpHandlers(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ctx.getSource().sendSuccess(() -> Component.literal("Registered handlers:"), false);
        for (ModType mt : ModType.values()) {
            var handler = ModRecipeHandlers.handlerFor(mt);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + mt.id() + " → " + (handler != null ? handler.getClass().getSimpleName() : "NONE")), false);
        }
        return 1;
    }

    // ── trace item ────────────────────────────────────────────────

    private static int traceItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return doTrace(ctx, false);
    }

    private static int traceItemNoInventory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return doTrace(ctx, true);
    }

    private static int doTrace(CommandContext<CommandSourceStack> ctx, boolean noInventory) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String itemName = StringArgumentType.getString(ctx, "item");
        ResourceLocation rl = ResourceLocation.tryParse(itemName);
        if (rl == null) {
            ctx.getSource().sendFailure(Component.literal("Invalid item: " + itemName));
            return 0;
        }
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) {
            ctx.getSource().sendFailure(Component.literal("Item not found: " + itemName));
            return 0;
        }

        ItemStack target = new ItemStack(item);
        var ingredient = net.minecraft.world.item.crafting.Ingredient.of(target);

        String modeLabel = noInventory ? " (no-inventory)" : "";

        // Candidate decision tree — use actual inventory when available
        var network = noInventory ? null : RSIntegration.resolveNetworkFromPlayer(player);
        Map<CraftingResolver.StackKey, Integer> available = noInventory
                ? Map.of()
                : MaterialSources.listAllAvailable(player, network);
        List<CraftingResolver.TraceEntry> diag = CraftingResolver.traceCandidates(
                ingredient, player.serverLevel(), player, available);

        ctx.getSource().sendSuccess(() -> Component.literal("=== Trace: " + itemName + modeLabel + " ==="), false);

        int considered = 0, skipped = 0;
        for (var d : diag) {
            if (d.skipped()) skipped++;
            else considered++;
        }
        final int fConsidered = considered;
        final int fSkipped = skipped;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Candidates: " + fConsidered + " considered, " + fSkipped + " skipped"), false);

        for (var d : diag) {
            String prefix = d.skipped() ? "  [SKIP] " : "  [USE]  ";
            final String line = prefix + d.recipeId() + " (" + d.modType().id() + ") score=" + d.score()
                    + (d.skipped() ? " — " + d.reason() : "");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }

        // Show ingredient requirements for each USE candidate (grouped by item)
        var rm = player.serverLevel().getRecipeManager();
        var recipeIndex = RecipeIndex.get(player.serverLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("--- Recipe ingredients ---"), false);
        for (var d : diag) {
            if (d.skipped()) continue;
            var recipe = rm.byKey(d.recipeId()).orElse(null);
            if (recipe == null) continue;
            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            if (specs == null || specs.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  " + d.recipeId() + ": no ingredient specs"), false);
                continue;
            }
            Map<String, Integer> grouped = new LinkedHashMap<>();
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                String desc = describeIngredientSpec(spec);
                grouped.merge(desc, spec.count(), Integer::sum);
            }
            for (var g : grouped.entrySet()) {
                final String line = "  " + d.recipeId() + " needs " + g.getValue() + "x " + g.getKey();
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
        }

        // Pre-check: count how many of the target item are already available
        int alreadyHave = 0;
        for (var entry : available.entrySet()) {
            if (entry.getValue() > 0 && (ingredient.test(entry.getKey().toStack()) || SlashBladeRecipeHandler.matchesStackKey(ingredient, entry.getKey()))) {
                alreadyHave += entry.getValue();
            }
        }
        if (alreadyHave > 0) {
            final int fHave = alreadyHave;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "--- Inventory: " + fHave + "x already available ---"), false);
        }

        // Resolution — reuses network & available from candidate trace above
        List<String> missingOut = new ArrayList<>();
        List<CraftingResolver.ResolutionStep> steps = CraftingResolver.resolveStepsForIngredientsWithTypes(
                List.of(ingredient), available, player.serverLevel(), player, network, missingOut,
                null, true);

        String netStatus = noInventory ? "disabled" : (network != null ? "found" : "not found");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "--- Resolution result (" + steps.size() + " steps, network=" + netStatus + ") ---"), false);
        if (steps.isEmpty()) {
            if (alreadyHave > 0) {
                final int fHave = alreadyHave;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  Already available (" + fHave + "x in inventory/network). No crafting needed."), false);
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  Use --no-inventory to force full resolution chain."), false);
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal("  Unresolvable."), false);
            }
        } else {
            for (int i = 0; i < steps.size(); i++) {
                final int fi = i;
                final var step = steps.get(fi);
                String extra = step.recipeTypeId() != null
                        ? " (" + step.recipeTypeId() + ")" : "";
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  [" + fi + "] " + step.recipeId() + " via " + step.modType().id() + extra), false);

                // Show OR alternatives for this step's output
                var stepRecipe = rm.byKey(step.recipeId()).orElse(null);
                if (stepRecipe != null) {
                    ItemStack stepOut = ModRecipeHandlers.tryGetResultItem(stepRecipe, player.serverLevel().registryAccess());
                    if (!stepOut.isEmpty()) {
                        List<RecipeIndex.Entry> entries = recipeIndex.get(stepOut.getItem());
                        if (entries != null && entries.size() > 1) {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "      OR alternatives for " + ForgeRegistries.ITEMS.getKey(stepOut.getItem()) + ":"), false);
                            for (var alt : entries) {
                                if (alt.recipe().getId().equals(step.recipeId())) continue;
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "        - " + alt.recipe().getId() + " (" + alt.modType().id() + ")"), false);
                            }
                        }
                    }
                }
            }
        }

        if (!missingOut.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("--- Missing ingredients (" + missingOut.size() + ") ---"), false);
            for (String m : missingOut) {
                ctx.getSource().sendSuccess(() -> Component.literal("  MISS: " + m), false);
            }
        }
        return 1;
    }

    private static String describeIngredientSpec(IngredientSpec spec) {
        ItemStack[] items = spec.ingredient().getItems();
        if (items.length == 1 && !items[0].isEmpty()) {
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(items[0].getItem());
            if (rl != null) return rl.toString();
        }
        if (items.length > 0 && !items[0].isEmpty()) {
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(items[0].getItem());
            return (rl != null ? rl.toString() : "?") + " +" + (items.length - 1) + " variants";
        }
        return "?";
    }

    // ── audit ─────────────────────────────────────────────────────

    private static int audit(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var rm = player.serverLevel().getRecipeManager();
        int total = 0;
        int handled = 0;
        int unhandled = 0;

        for (var recipe : rm.getRecipes()) {
            ModType type = ModType.classifyRecipe(recipe);
            if (type == null) continue;
            total++;
            var handler = ModRecipeHandlers.handlerFor(recipe);
            if (handler != null && handler.getIngredients(recipe) != null) {
                handled++;
            } else {
                unhandled++;
                RSIntegrationMod.LOGGER.info("[RSI-Audit] Unhandled: {} (type={}, class={})",
                        recipe.getId(), type.id(), recipe.getClass().getName());
            }
        }
        final int fTotal = total;
        final int fHandled = handled;
        final int fUnhandled = unhandled;

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Audit: " + fTotal + " mod recipes, " + fHandled + " handled, " + fUnhandled + " unhandled"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Check logs for unhandled recipe details."), false);
        return 1;
    }

    // ── embers clearcache ───────────────────────────────────────────

    private static int embersClearCache(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var level = ctx.getSource().getServer().overworld();
        var data = KnownCodeSavedData.get(level);
        int count = data.size();
        data.clearAll();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + count + " Embers alchemy code cache entries."), true);
        return 1;
    }

    // ── embers clearlocks ──────────────────────────────────────────

    private static int embersClearLocks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int count = EreAlchemyLock.clearAll();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + count + " Embers tablet lock(s)."), true);
        return 1;
    }

    // ── perf snapshot ─────────────────────────────────────────────

    private static int perfSnapshot(CommandContext<CommandSourceStack> ctx) {
        String snap = PerformanceMonitor.snapshot();
        ctx.getSource().sendSuccess(() -> Component.literal(snap), false);
        return 1;
    }

    // ── dump bindings ─────────────────────────────────────────────

    private static int dumpBindings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ctx.getSource().sendSuccess(() -> Component.literal("=== Player: " + player.getName().getString() + " ==="), false);

        List<ItemStack> allStacks = new ArrayList<>();
        allStacks.addAll(player.getInventory().items);
        allStacks.addAll(player.getInventory().offhand);
        allStacks.addAll(player.getInventory().armor);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        allStacks.add(stacks.getStackInSlot(s));
                    }
                }
            }
        } catch (Exception e) { /* curios not present */ }

        int totalBindings = 0;
        for (ItemStack stack : allStacks) {
            if (stack.isEmpty()) continue;
            var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
            String itemId = rl != null ? rl.toString() : "unknown";
            List<BindingStorage.BindingEntry> entries = BindingStorage.getBindings(stack);
            if (entries.isEmpty()) continue;
            for (var entry : entries) {
                totalBindings++;
                ModType mt = ModType.fromBlockKey(entry.blockKey());
                String typeName = mt != null ? mt.id() : "UNKNOWN";
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  [" + typeName + "] " + itemId + " → " + entry.blockKey()
                        + " @ dim=" + entry.dim() + " pos=" + entry.pos().getX()
                        + "," + entry.pos().getY() + "," + entry.pos().getZ()), false);
            }
        }

        if (totalBindings == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("  (no bindings found)"), false);
        } else {
            final int fTotal = totalBindings;
            ctx.getSource().sendSuccess(() -> Component.literal("  Total: " + fTotal + " binding(s)"), false);
        }
        return 1;
    }
}
