package com.huanghuang.rsintegration.command;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.CuriosAccess;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import net.minecraft.world.item.crafting.Ingredient;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.*;
import com.huanghuang.rsintegration.crafting.tree.RecipeBrowserSlowCallDiagnostics;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyProgressSavedData;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyLock;
import com.huanghuang.rsintegration.mods.goety.magic.GoetySoulCraftingTask;
import com.huanghuang.rsintegration.magic.MagicResourceDiagnostics;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceRouteCounters;
import com.huanghuang.rsintegration.magic.MagicResourceTaskLedger;
import com.huanghuang.rsintegration.mods.goety.magic.SoulPlanPreview;
import com.huanghuang.rsintegration.magic.route.BackendResolution;
import com.huanghuang.rsintegration.magic.route.Channel;
import com.huanghuang.rsintegration.magic.route.MagicScope;
import com.huanghuang.rsintegration.magic.route.MagicStorageBackendDescriptor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
                        .executes(DebugCommand::perfSnapshot)
                        .then(Commands.literal("reset")
                                .executes(DebugCommand::perfReset)))
                .then(Commands.literal("magic")
                        .then(Commands.literal("route")
                                .executes(DebugCommand::magicRoute)))
                .then(Commands.literal("pending")
                        .executes(DebugCommand::dumpPendingAudits)
                        .then(Commands.literal("resolve")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(DebugCommand::resolvePendingAudit)))
                        .then(Commands.literal("clear")
                                .executes(DebugCommand::clearPendingAudits)))
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

    // ── magic pending ─────────────────────────────────────────────

    /** 列出无法自动回收、需要人工核对的魔法资源记录。 */
    private static int dumpPendingAudits(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<MagicResourceTaskLedger.Pending> pending =
                MagicResourceTaskLedger.get(server).pendingAudits();
        long now = server.overworld().getGameTime();
        if (pending.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("魔法资源待核对记录：无"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "魔法资源待核对记录 " + pending.size() + " 条："), false);
        for (MagicResourceTaskLedger.Pending entry : pending) {
            String task = entry.taskId() == null ? "-" : entry.taskId().toString();
            String network = entry.bdNetworkId() >= 0 ? "bd=" + entry.bdNetworkId() : "bd=-";
            long ageTicks = entry.recordedAt() <= 0 ? -1L
                    : Math.max(0L, now - entry.recordedAt());
            String age = ageTicks < 0L ? "时间未知" : formatAge(ageTicks);
            String line = "  " + entry.id() + " task=" + task
                    + " kind=" + entry.kind().id()
                    + " amount=" + entry.amount()
                    + " " + network
                    + " reason=" + entry.reason()
                    + " " + age;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "核对完成后执行 /rsi_debug pending resolve <id> 移除记录。"), false);
        return pending.size();
    }

    /** 核对完成，移除一条待核对记录。 */
    private static int resolvePendingAudit(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "id");
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("无效的记录 ID：" + raw));
            return 0;
        }
        boolean removed = MagicResourceTaskLedger.get(ctx.getSource().getServer())
                .resolvePending(id);
        ctx.getSource().sendSuccess(() -> Component.literal(removed
                ? "已移除待核对记录 " + id
                : "没有找到待核对记录 " + id), false);
        return removed ? 1 : 0;
    }

    /** 清空所有待核对记录，用于确认过账后收尾。 */
    private static int clearPendingAudits(CommandContext<CommandSourceStack> ctx) {
        MagicResourceTaskLedger ledger = MagicResourceTaskLedger.get(ctx.getSource().getServer());
        List<MagicResourceTaskLedger.Pending> pending = ledger.pendingAudits();
        for (MagicResourceTaskLedger.Pending entry : pending) {
            ledger.resolvePending(entry.id());
        }
        int cleared = pending.size();
        ctx.getSource().sendSuccess(() ->
                Component.literal("已清空 " + cleared + " 条待核对记录"), false);
        return cleared;
    }

    /** 人类可读的记录时长。 */
    private static String formatAge(long ticks) {
        long seconds = ticks / 20L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + "h" + (minutes % 60L) + "m";
        return (hours / 24L) + "d" + (hours % 24L) + "h";
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
        var ingredient = Ingredient.of(target);

        String modeLabel = noInventory ? " (no-inventory)" : "";

        // Candidate decision tree — use actual inventory when available
        var network = noInventory ? null : RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        CraftStorageEndpoint endpoint = network == null
                ? null : CraftStorageEndpoints.fromLegacyNetwork(network);
        Map<CraftingResolver.StackKey, Long> available = noInventory
                ? Map.of()
                : MaterialSources.listAllAvailable(player, endpoint);
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
            if (entry.getValue() > 0 && IngredientMatcher.test(ingredient, entry.getKey())) {
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
                List.of(ingredient), available, player.serverLevel(), player,
                endpoint, missingOut,
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
        var progress = EreAlchemyProgressSavedData.get(level);
        int codeCount = data.size();
        int progressCount = progress.size();
        data.clearAll();
        progress.clearAll();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + codeCount + " Embers alchemy code entries and "
                        + progressCount + " inference progress entries."), true);
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
        String browserSlow = RecipeBrowserSlowCallDiagnostics.summary();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  " + browserSlow), false);
        for (String line : magicRouteCounterLines()) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int perfReset(CommandContext<CommandSourceStack> ctx) {
        RecipeBrowserSlowCallDiagnostics.reset();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "已清理有界配方浏览器慢调用去重状态。"), false);
        return 1;
    }

    /** perf 的快照里没有魔法路由段，这里单独补上一段。 */
    private static List<String> magicRouteCounterLines() {
        List<String> lines = new ArrayList<>();
        lines.add("  backend_route_total=" + MagicResourceRouteCounters.routeTotal()
                + " backend_unsupported_total=" + MagicResourceRouteCounters.unsupportedTotal()
                + " backend_unreachable_total=" + MagicResourceRouteCounters.unreachableTotal()
                + " backend_ambiguous_total=" + MagicResourceRouteCounters.ambiguousTotal());
        String last = MagicResourceRouteCounters.lastRoute();
        lines.add("  last_route=" + (last.isEmpty() ? "无" : last));
        return lines;
    }

    // ── magic route ───────────────────────────────────────────────

    /**
     * 展示魔法资源的实际路由：每个资源挂了哪些后端、当前链路落在哪个权威后端。
     *
     * <p>解析是纯函数且不读库存（{@code record=false}），所以这条命令可以随时执行，
     * 不会干扰正在跑的任务。</p>
     */
    private static int magicRoute(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        MagicScope scope = MagicScope.of(
                ctx.getSource().getPlayerOrException(), null);
        ctx.getSource().sendSuccess(() -> Component.literal("=== 魔法资源路由 ==="), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  当前玩家=" + scope.player().getName().getString()
                        + " 链路=" + MagicResourceDiagnostics.linkName(scope)), false);
        boolean soulUnreachable = false;
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            BackendResolution resolution = RSIntegrationMod.MAGIC_ROUTER
                    .resolveCounted(kind, scope, false);
            List<MagicStorageBackendDescriptor> candidates =
                    RSIntegrationMod.MAGIC_ROUTER.candidates(kind);
            StringBuilder declared = new StringBuilder();
            for (MagicStorageBackendDescriptor descriptor : candidates) {
                if (declared.length() > 0) declared.append(", ");
                declared.append(descriptor.id()).append("(channel=")
                        .append(descriptor.channel(kind)).append(')');
            }
            final String line = "  " + kind.id()
                    + " 状态=" + resolution.kind()
                    + " 权威后端=" + (resolution.backend() == null
                            ? "无" : resolution.backend().id())
                    + " 通道=" + resolution.channel()
                    + " 候选=[" + declared + "]";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            if (kind == MagicResourceKind.SOUL
                    && resolution.kind() == BackendResolution.Kind.UNREACHABLE) {
                soulUnreachable = true;
            }
        }
        return soulUnreachable ? 1 : 0;
    }

    // ── dump bindings ─────────────────────────────────────────────

    private static int dumpBindings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ctx.getSource().sendSuccess(() -> Component.literal("=== Player: " + player.getName().getString() + " ==="), false);

        List<ItemStack> allStacks = new ArrayList<>();
        allStacks.addAll(player.getInventory().items);
        allStacks.addAll(player.getInventory().offhand);
        allStacks.addAll(player.getInventory().armor);
        allStacks.addAll(CuriosAccess.stacks(player));

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
