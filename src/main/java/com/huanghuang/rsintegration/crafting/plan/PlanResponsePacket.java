package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import io.netty.handler.codec.DecoderException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Server → Client: sends the resolved crafting plan for display.
 */
public final class PlanResponsePacket {

    private final PlanResponse plan;
    private final long requestId;

    /**
     * Upper bound on any decoded collection/array length. Far above any real
     * plan (deepest recipe trees are dozens of steps), but caps a malformed or
     * malicious packet's preallocation so a bogus count cannot OOM the client.
     */
    private static final int MAX_DECODE_COUNT = 4096;
    private static final int MAX_RECIPE_ID_LENGTH = 256;
    private static final int MAX_TARGET_NAME_LENGTH = 256;
    private static final int MAX_MOD_TYPE_LENGTH = 128;
    private static final int MAX_DIMENSION_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 2048;
    private static final int MAX_DISPLAY_NAME_LENGTH = 256;

    /** Reject corrupt counts before allocation or field decoding. */
    private static int readBoundedCount(FriendlyByteBuf buf) {
        int raw = buf.readVarInt();
        if (raw < 0 || raw > MAX_DECODE_COUNT) {
            throw new DecoderException("PlanResponsePacket count out of bounds: " + raw);
        }
        return raw;
    }

    private static int readNonNegativeVarInt(FriendlyByteBuf buf, String field) {
        int value = buf.readVarInt();
        if (value < 0) throw new DecoderException(field + " must be non-negative: " + value);
        return value;
    }

    public PlanResponsePacket(PlanResponse plan) {
        this(plan, 0L);
    }

    public PlanResponsePacket(PlanResponse plan, long requestId) {
        if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("requestId out of range");
        }
        this.plan = plan;
        this.requestId = requestId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(plan.success());
        buf.writeUtf(plan.recipeId() != null ? plan.recipeId() : "", MAX_RECIPE_ID_LENGTH);
        // Always send full plan data — even infeasible plans need the recipe
        // chain structure so the player can see what intermediate steps are
        // required and which leaf materials are missing.
        buf.writeUtf(plan.targetName(), MAX_TARGET_NAME_LENGTH);
        buf.writeItem(plan.targetResult());
        // Steps
        buf.writeVarInt(plan.steps().size());
        for (PlanStep step : plan.steps()) {
            buf.writeResourceLocation(step.recipeId());
            buf.writeItem(step.output());
            buf.writeVarInt(step.batches());
            buf.writeVarInt(step.inputs().size());
            for (ItemStack in : step.inputs()) {
                buf.writeItem(in);
            }
            buf.writeVarInt(step.alternatives().size());
            for (ResourceLocation alt : step.alternatives()) {
                buf.writeResourceLocation(alt);
            }
            buf.writeBoolean(step.modType() != null);
            if (step.modType() != null) buf.writeUtf(step.modType().id(), MAX_MOD_TYPE_LENGTH);
            buf.writeVarInt(step.depth());
            buf.writeBoolean(step.hasOrSiblings());
            buf.writeVarInt(step.recipeWidth());
            buf.writeVarInt(step.recipeHeight());
            buf.writeVarInt(step.alternativeModTypes().size());
            for (String mt : step.alternativeModTypes()) {
                buf.writeUtf(mt, MAX_MOD_TYPE_LENGTH);
            }
            buf.writeVarInt(step.warnings().size());
            for (String w : step.warnings()) {
                buf.writeUtf(w, MAX_MESSAGE_LENGTH);
            }
        }
        // Materials
        buf.writeVarInt(plan.materials().size());
        for (Map.Entry<IngredientKey, PlanResponse.Availability> e : plan.materials().entrySet()) {
            e.getKey().write(buf);
            buf.writeVarInt(e.getValue().needed());
            buf.writeVarInt(e.getValue().available());
        }
        // Missing
        buf.writeVarInt(plan.missing().size());
        for (String m : plan.missing()) {
            buf.writeUtf(m, MAX_MESSAGE_LENGTH);
        }
        // Execution routing
        buf.writeBoolean(plan.executionModTypeId() != null);
        if (plan.executionModTypeId() != null) buf.writeUtf(plan.executionModTypeId(), MAX_MOD_TYPE_LENGTH);
        buf.writeBoolean(plan.executionDim() != null);
        if (plan.executionDim() != null) buf.writeUtf(plan.executionDim(), MAX_DIMENSION_LENGTH);
        buf.writeVarInt(plan.executionPosX());
        buf.writeVarInt(plan.executionPosY());
        buf.writeVarInt(plan.executionPosZ());
        // Mod warnings (Goety research/structure, FA essences)
        buf.writeVarInt(plan.modWarnings().size());
        for (String w : plan.modWarnings()) buf.writeUtf(w, MAX_MESSAGE_LENGTH);
        buf.writeVarInt(plan.repeatCount());
        // Embers alchemy pedestal data
        buf.writeBoolean(plan.embersCode() != null);
        if (plan.embersCode() != null) {
            buf.writeVarInt(plan.embersCode().length);
            for (int c : plan.embersCode()) buf.writeVarInt(c);
        }
        buf.writeBoolean(plan.embersAspectNames() != null);
        if (plan.embersAspectNames() != null) {
            buf.writeVarInt(plan.embersAspectNames().length);
            for (String s : plan.embersAspectNames()) buf.writeUtf(s, MAX_DISPLAY_NAME_LENGTH);
        }
        buf.writeBoolean(plan.embersInputNames() != null);
        if (plan.embersInputNames() != null) {
            buf.writeVarInt(plan.embersInputNames().length);
            for (String s : plan.embersInputNames()) buf.writeUtf(s, MAX_DISPLAY_NAME_LENGTH);
        }
        buf.writeVarLong(plan.embersSeed());
        buf.writeBoolean(plan.embersCanInfer());
        buf.writeBoolean(plan.embersCodeFromCache());
        buf.writeBoolean(plan.executionMachineSupportsGui());
        buf.writeBoolean(plan.baseItem() != null);
        if (plan.baseItem() != null) buf.writeItem(plan.baseItem());
        // boundMachineTypes availability passport
        buf.writeVarInt(plan.boundMachineTypes().size());
        for (String mt : plan.boundMachineTypes()) buf.writeUtf(mt, MAX_MOD_TYPE_LENGTH);
        // Leftovers (overproduction from integer batch rounding)
        buf.writeVarInt(plan.leftovers().size());
        for (Map.Entry<IngredientKey, Integer> e : plan.leftovers().entrySet()) {
            e.getKey().write(buf);
            buf.writeVarInt(e.getValue());
        }
        // Clicked ghost-output (NBT-variant target, e.g. WR leveled book) — tail-appended
        buf.writeBoolean(plan.clickedOutput() != null);
        if (plan.clickedOutput() != null) buf.writeItem(plan.clickedOutput());
        // Server-authored DAG view — protocol v8 tail
        buf.writeBoolean(plan.graph() != null);
        if (plan.graph() != null) writeGraph(buf, plan.graph());
        buf.writeBoolean(requestId != 0L);
        if (requestId != 0L) buf.writeVarLong(requestId);
    }

    public static PlanResponsePacket decode(FriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        String recipeId = buf.readUtf(MAX_RECIPE_ID_LENGTH);
        // Always read full plan data — the server sends the recipe chain
        // structure even for infeasible plans so the client can render
        // intermediate steps and material shortages.
        String targetName = buf.readUtf(MAX_TARGET_NAME_LENGTH);
        ItemStack targetResult = buf.readItem();
        // Steps
        int stepCount = readBoundedCount(buf);
        List<PlanStep> steps = new ArrayList<>(stepCount);
        for (int i = 0; i < stepCount; i++) {
            var rid = buf.readResourceLocation();
            ItemStack output = buf.readItem();
            int batches = buf.readVarInt();
            int inCount = readBoundedCount(buf);
            List<ItemStack> inputs = new ArrayList<>(inCount);
            for (int j = 0; j < inCount; j++) {
                inputs.add(buf.readItem());
            }
            int altCount = readBoundedCount(buf);
            List<ResourceLocation> alternatives = new ArrayList<>(altCount);
            for (int j = 0; j < altCount; j++) {
                alternatives.add(buf.readResourceLocation());
            }
            ModType modType = null;
            if (buf.readBoolean()) {
                modType = ModType.byId(buf.readUtf(MAX_MOD_TYPE_LENGTH));
            }
            int depth = buf.readVarInt();
            boolean hasOrSiblings = buf.readBoolean();
            int recipeWidth = buf.readVarInt();
            int recipeHeight = buf.readVarInt();
            int altModCount = readBoundedCount(buf);
            List<String> alternativeModTypes = new ArrayList<>(altModCount);
            for (int j = 0; j < altModCount; j++) {
                alternativeModTypes.add(buf.readUtf(MAX_MOD_TYPE_LENGTH));
            }
            int warnCount = readBoundedCount(buf);
            List<String> warnings = new ArrayList<>(warnCount);
            for (int j = 0; j < warnCount; j++) {
                warnings.add(buf.readUtf(MAX_MESSAGE_LENGTH));
            }
            steps.add(new PlanStep(rid, output, batches, inputs, alternatives, modType,
                    depth, hasOrSiblings, recipeWidth, recipeHeight, alternativeModTypes,
                    warnings));
        }
        // Materials
        int matCount = readBoundedCount(buf);
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        for (int i = 0; i < matCount; i++) {
            IngredientKey key = IngredientKey.read(buf);
            materials.put(key, new PlanResponse.Availability(buf.readVarInt(), buf.readVarInt()));
        }
        // Missing
        int missCount = readBoundedCount(buf);
        List<String> missing = new ArrayList<>(missCount);
        for (int i = 0; i < missCount; i++) {
            missing.add(buf.readUtf(MAX_MESSAGE_LENGTH));
        }
        // Execution routing
        String execModType = buf.readBoolean() ? buf.readUtf(MAX_MOD_TYPE_LENGTH) : null;
        String execDim = buf.readBoolean() ? buf.readUtf(MAX_DIMENSION_LENGTH) : null;
        int execX = buf.readVarInt();
        int execY = buf.readVarInt();
        int execZ = buf.readVarInt();
        // Mod warnings
        int modWarnCount = readBoundedCount(buf);
        List<String> modWarnings = new ArrayList<>(modWarnCount);
        for (int i = 0; i < modWarnCount; i++) modWarnings.add(buf.readUtf(MAX_MESSAGE_LENGTH));
        int repeatCount = buf.readVarInt();
        // Embers alchemy pedestal data
        int[] embersCode = null;
        if (buf.readBoolean()) {
            int len = readBoundedCount(buf);
            embersCode = new int[len];
            for (int i = 0; i < len; i++) embersCode[i] = buf.readVarInt();
        }
        String[] embersAspectNames = null;
        if (buf.readBoolean()) {
            int len = readBoundedCount(buf);
            embersAspectNames = new String[len];
            for (int i = 0; i < len; i++) embersAspectNames[i] = buf.readUtf(MAX_DISPLAY_NAME_LENGTH);
        }
        String[] embersInputNames = null;
        if (buf.readBoolean()) {
            int len = readBoundedCount(buf);
            embersInputNames = new String[len];
            for (int i = 0; i < len; i++) embersInputNames[i] = buf.readUtf(MAX_DISPLAY_NAME_LENGTH);
        }
        long embersSeed = buf.readVarLong();
        boolean embersCanInfer = buf.readBoolean();
        boolean embersCodeFromCache = buf.readBoolean();
        boolean executionMachineSupportsGui = buf.readBoolean();
        ItemStack baseItem = buf.readBoolean() ? buf.readItem() : null;
        // boundMachineTypes availability passport
        int boundMtCount = readBoundedCount(buf);
        Set<String> boundMachineTypes = new LinkedHashSet<>();
        for (int i = 0; i < boundMtCount; i++) boundMachineTypes.add(buf.readUtf(MAX_MOD_TYPE_LENGTH));
        // Leftovers (overproduction)
        int leftoverCount = readBoundedCount(buf);
        Map<IngredientKey, Integer> leftovers = new LinkedHashMap<>();
        for (int i = 0; i < leftoverCount; i++) {
            IngredientKey key = IngredientKey.read(buf);
            leftovers.put(key, buf.readVarInt());
        }
        // Clicked ghost-output — required protocol field.
        ItemStack clickedOutput = buf.readBoolean() ? buf.readItem() : null;
        // Server-authored DAG view — required protocol field.
        PlanGraphView graph = buf.readBoolean() ? readGraph(buf) : null;
        if (buf.readableBytes() != 0) {
            throw new DecoderException("Trailing bytes in PlanResponsePacket");
        }
        long requestId = 0L;
        if (buf.isReadable() && buf.readBoolean()) {
            requestId = buf.readVarLong();
            if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
                throw new DecoderException("PlanResponsePacket requestId out of range");
            }
        }
        return new PlanResponsePacket(new PlanResponse(success, targetName, targetResult,
                steps, materials, missing, recipeId,
                execModType, execDim, execX, execY, execZ, modWarnings, repeatCount,
                embersCode, embersAspectNames, embersInputNames, embersSeed, embersCanInfer,
                embersCodeFromCache, executionMachineSupportsGui, baseItem, boundMachineTypes,
                leftovers, clickedOutput, graph), requestId);
    }

    private static void writeGraph(FriendlyByteBuf buf, PlanGraphView graph) {
        buf.writeVarInt(graph.version());
        buf.writeVarInt(graph.nodes().size());
        for (PlanGraphView.NodeView node : graph.nodes()) {
            buf.writeVarInt(node.nodeId());
            buf.writeResourceLocation(node.recipeId());
            buf.writeUtf(node.modTypeId(), MAX_MOD_TYPE_LENGTH);
            buf.writeVarInt(node.executions());
            buf.writeItem(node.primaryOutput());
            buf.writeVarInt(node.alternativeIds().size());
            for (ResourceLocation alternative : node.alternativeIds()) {
                buf.writeResourceLocation(alternative);
            }
            buf.writeVarInt(node.alternativeModTypeIds().size());
            for (String alternativeModType : node.alternativeModTypeIds()) {
                buf.writeUtf(alternativeModType, MAX_MOD_TYPE_LENGTH);
            }
            buf.writeVarInt(node.inputs().size());
            for (PlanGraphView.InputView input : node.inputs()) {
                buf.writeVarInt(input.portIndex());
                buf.writeItem(input.display());
                buf.writeVarInt(input.quantity());
                buf.writeVarInt(input.roleOrdinal());
            }
            buf.writeVarInt(node.outputs().size());
            for (PlanGraphView.OutputView output : node.outputs()) {
                buf.writeVarInt(output.portIndex());
                buf.writeItem(output.display());
                buf.writeVarInt(output.quantity());
                buf.writeVarInt(output.kindOrdinal());
            }
        }
        buf.writeVarInt(graph.edges().size());
        for (PlanGraphView.EdgeView edge : graph.edges()) {
            buf.writeVarInt(edge.consumerNodeId());
            buf.writeVarInt(edge.consumerPortIndex());
            writeSource(buf, edge.source());
            buf.writeItem(edge.material());
            buf.writeVarInt(edge.quantity());
        }
        buf.writeVarInt(graph.roots().size());
        for (PlanGraphView.RootView root : graph.roots()) {
            buf.writeItem(root.display());
            buf.writeVarInt(root.quantity());
            buf.writeVarInt(root.unresolvedQuantity());
            buf.writeVarInt(root.allocations().size());
            for (PlanGraphView.RootEdgeView allocation : root.allocations()) {
                writeSource(buf, allocation.source());
                buf.writeItem(allocation.material());
                buf.writeVarInt(allocation.quantity());
            }
        }
        buf.writeVarInt(graph.unresolved().size());
        for (PlanGraphView.UnresolvedView unresolved : graph.unresolved()) {
            buf.writeVarInt(unresolved.consumerNodeId());
            buf.writeVarInt(unresolved.consumerPortIndex());
            buf.writeItem(unresolved.display());
            buf.writeVarInt(unresolved.quantity());
        }
        buf.writeVarInt(graph.topologicalOrder().size());
        for (int nodeId : graph.topologicalOrder()) buf.writeVarInt(nodeId);
    }

    static PlanGraphView readGraph(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        List<PlanGraphView.NodeView> nodes = new ArrayList<>();
        for (int i = 0, n = readBoundedCount(buf); i < n; i++) {
            int nodeId = readNonNegativeVarInt(buf, "graph node id");
            ResourceLocation recipe = buf.readResourceLocation();
            String modType = buf.readUtf(MAX_MOD_TYPE_LENGTH);
            int executions = readNonNegativeVarInt(buf, "graph executions");
            ItemStack primary = buf.readItem();
            List<ResourceLocation> alternativeIds = new ArrayList<>();
            for (int j = 0, m = readBoundedCount(buf); j < m; j++) {
                alternativeIds.add(buf.readResourceLocation());
            }
            List<String> alternativeModTypes = new ArrayList<>();
            for (int j = 0, m = readBoundedCount(buf); j < m; j++) {
                alternativeModTypes.add(buf.readUtf(MAX_MOD_TYPE_LENGTH));
            }
            List<PlanGraphView.InputView> inputs = new ArrayList<>();
            for (int j = 0, m = readBoundedCount(buf); j < m; j++) {
                inputs.add(new PlanGraphView.InputView(readNonNegativeVarInt(buf, "input port"), buf.readItem(),
                        readNonNegativeVarInt(buf, "input quantity"), buf.readVarInt()));
            }
            List<PlanGraphView.OutputView> outputs = new ArrayList<>();
            for (int j = 0, m = readBoundedCount(buf); j < m; j++) {
                outputs.add(new PlanGraphView.OutputView(readNonNegativeVarInt(buf, "output port"), buf.readItem(),
                        readNonNegativeVarInt(buf, "output quantity"), buf.readVarInt()));
            }
            nodes.add(new PlanGraphView.NodeView(nodeId, recipe, modType, executions,
                    primary, alternativeIds, alternativeModTypes, inputs, outputs));
        }
        List<PlanGraphView.EdgeView> edges = new ArrayList<>();
        for (int i = 0, n = readBoundedCount(buf); i < n; i++) {
            edges.add(new PlanGraphView.EdgeView(readNonNegativeVarInt(buf, "edge consumer node"),
                    readNonNegativeVarInt(buf, "edge consumer port"), readSource(buf), buf.readItem(),
                    readNonNegativeVarInt(buf, "edge quantity")));
        }
        List<PlanGraphView.RootView> roots = new ArrayList<>();
        for (int i = 0, n = readBoundedCount(buf); i < n; i++) {
            ItemStack display = buf.readItem();
            int quantity = readNonNegativeVarInt(buf, "root quantity");
            int unresolvedQuantity = readNonNegativeVarInt(buf, "root unresolved quantity");
            if (unresolvedQuantity > quantity) {
                throw new DecoderException("root unresolved quantity exceeds root quantity");
            }
            List<PlanGraphView.RootEdgeView> allocations = new ArrayList<>();
            for (int j = 0, m = readBoundedCount(buf); j < m; j++) {
                allocations.add(new PlanGraphView.RootEdgeView(readSource(buf),
                        buf.readItem(), readNonNegativeVarInt(buf, "root allocation quantity")));
            }
            roots.add(new PlanGraphView.RootView(display, quantity, unresolvedQuantity, allocations));
        }
        List<PlanGraphView.UnresolvedView> unresolved = new ArrayList<>();
        for (int i = 0, n = readBoundedCount(buf); i < n; i++) {
            unresolved.add(new PlanGraphView.UnresolvedView(
                    readNonNegativeVarInt(buf, "unresolved consumer node"),
                    readNonNegativeVarInt(buf, "unresolved consumer port"), buf.readItem(),
                    readNonNegativeVarInt(buf, "unresolved quantity")));
        }
        List<Integer> topological = new ArrayList<>();
        for (int i = 0, n = readBoundedCount(buf); i < n; i++) {
            topological.add(readNonNegativeVarInt(buf, "topological node id"));
        }
        return new PlanGraphView(version, nodes, edges, roots, unresolved, topological);
    }

    private static void writeSource(FriendlyByteBuf buf, PlanGraphView.SourceView source) {
        buf.writeBoolean(source.initial());
        if (!source.initial()) {
            buf.writeVarInt(source.producerNodeId());
            buf.writeVarInt(source.producerPortIndex());
        }
    }

    private static PlanGraphView.SourceView readSource(FriendlyByteBuf buf) {
        return buf.readBoolean()
                ? new PlanGraphView.SourceView(true, -1, -1)
                : new PlanGraphView.SourceView(false,
                readNonNegativeVarInt(buf, "source producer node"),
                readNonNegativeVarInt(buf, "source producer port"));
    }

    public PlanResponse plan() {
        return plan;
    }

    public long requestId() {
        return requestId;
    }

    @SuppressWarnings("resource")
    public static void handle(PlanResponsePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        RSIntegrationMod.LOGGER.debug(
                "[RSI-PlanPkt] Client received PlanResponsePacket: recipeId={} success={} steps={}",
                packet.plan.recipeId(), packet.plan.success(), packet.plan.steps().size());
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-PlanPkt] enqueueWork running on client thread: recipeId={}",
                    packet.plan.recipeId());
            localizePlanForClient(packet.plan, packet.requestId);
        });
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void localizePlanForClient(PlanResponse plan, long requestId) {
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
                plan.baseItem(), plan.boundMachineTypes(), plan.leftovers(), plan.clickedOutput(), plan.graph());
        openScreen(localized, requestId);
    }

    @OnlyIn(Dist.CLIENT)
    private static List<String> localizeItemNames(List<String> names) {
        if (names.isEmpty()) return names;
        List<String> localized = new ArrayList<>(names.size());
        for (String name : names) {
            String translated = name;
            int hintStart = name.indexOf(" §");
            String key = hintStart >= 0 ? name.substring(0, hintStart) : name;
            String suffix = hintStart >= 0 ? name.substring(hintStart) : "";
            if (net.minecraft.client.resources.language.I18n.exists(key)) {
                translated = net.minecraft.client.resources.language.I18n.get(key) + suffix;
            } else for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                if (item.getDescription().getString().equals(name)
                        || item.getDescriptionId().equals(name)) {
                    translated = item.getDescription().getString();
                    break;
                }
            }
            localized.add(translated);
        }
        return localized;
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(PlanResponse plan, long requestId) {
        RSIntegrationMod.LOGGER.debug(
                "[RSI-PlanPkt] openScreen called: success={} steps={}",
                plan.success(), plan.steps().size());
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-PlanPkt] openScreen ABORT: mc.player is null");
            return;
        }
        // A response can arrive after the user has already requested another
        // recipe. Never let that stale response replace the active plan screen.
        if (mc.screen instanceof CraftingPlanScreen existing
                && requestId != 0L && requestId < existing.activeRequestId()) {
            RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] Dropping stale request response {} < {}",
                    requestId, existing.activeRequestId());
            return;
        }
        if (mc.screen instanceof CraftingPlanScreen existing
                && plan.recipeId() != null
                && !plan.recipeId().equals(existing.getRecipeId())) {
            RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] Dropping stale response: received={} active={}",
                    plan.recipeId(), existing.getRecipeId());
            return;
        }
        // If a CraftingPlanScreen is already open for the same recipe,
        // update it in-place rather than replacing it — this preserves
        // scroll position and avoids flicker during OR-path switching.
        if (mc.screen instanceof CraftingPlanScreen existing
                && plan.recipeId() != null
                && plan.recipeId().equals(existing.getRecipeId())) {
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
