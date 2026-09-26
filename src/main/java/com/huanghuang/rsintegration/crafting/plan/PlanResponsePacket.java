package com.huanghuang.rsintegration.crafting.plan;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.storage.StorageCapability;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageReferenceCodec;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.storage.StorageBackendId;

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
    private static final int MAX_BACKEND_ID_LENGTH = 64;
    private static final int MAX_NETWORK_ID_LENGTH = StorageReference.MAX_NETWORK_ID_LENGTH;

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

    /**
     * {@link FriendlyByteBuf#readComponent()} returns null for a JSON {@code null}
     * payload; normalize it so downstream rendering never sees a null element.
     */
    private static Component readComponentOrEmpty(FriendlyByteBuf buf) {
        Component decoded = buf.readComponent();
        return decoded != null ? decoded : Component.empty();
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
        int startIndex = buf.writerIndex();
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
            for (DemandRole role : step.inputRoles()) {
                buf.writeVarInt(role.ordinal());
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
        }
        // Materials
        buf.writeVarInt(plan.materials().size());
        for (Map.Entry<IngredientKey, PlanResponse.Availability> e : plan.materials().entrySet()) {
            e.getKey().write(buf);
            buf.writeVarInt(e.getValue().needed());
            buf.writeVarInt(e.getValue().available());
            buf.writeVarInt(e.getValue().missingCount());
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
        // Mod warnings (Goety research/structure, FA essences).
        // Sent as Components so the client resolves the translation — a dedicated
        // server has no rs_integration lang table.
        buf.writeVarInt(plan.modWarnings().size());
        for (Component w : plan.modWarnings()) buf.writeComponent(w);
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
            for (Component s : plan.embersAspectNames()) buf.writeComponent(s);
        }
        buf.writeBoolean(plan.embersInputNames() != null);
        if (plan.embersInputNames() != null) {
            buf.writeVarInt(plan.embersInputNames().length);
            for (Component s : plan.embersInputNames()) buf.writeComponent(s);
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
        // Keep hard prerequisite gating independent from material feasibility.
        buf.writeBoolean(plan.executionBlocked());
        buf.writeVarInt(plan.machineCandidates().size());
        for (MachineCandidateView candidate : plan.machineCandidates()) {
            buf.writeUtf(candidate.dimension(), MAX_DIMENSION_LENGTH);
            buf.writeVarInt(candidate.x());
            buf.writeVarInt(candidate.y());
            buf.writeVarInt(candidate.z());
            buf.writeItem(candidate.icon());
            buf.writeVarInt(candidate.state().ordinal());
            buf.writeComponent(candidate.status());
        }
        // Per-recipe prerequisite diagnostics for intermediate tree nodes.
        buf.writeVarInt(plan.stepIssues().size());
        for (Map.Entry<ResourceLocation, PlanResponse.StepIssue> entry : plan.stepIssues().entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeBoolean(entry.getValue().blocked());
            buf.writeVarInt(entry.getValue().warnings().size());
            for (Component warning : entry.getValue().warnings()) buf.writeComponent(warning);
        }
        buf.writeBoolean(plan.storageReference() != null);
        if (plan.storageReference() != null) {
            buf.writeNbt(StorageReferenceCodec.encode(plan.storageReference()));
        }
        buf.writeVarInt(plan.storageNetworks().size());
        for (StorageNetworkDescriptor descriptor : plan.storageNetworks()) {
            buf.writeUtf(descriptor.reference().backendId().value(), MAX_BACKEND_ID_LENGTH);
            buf.writeUtf(descriptor.reference().networkId(), MAX_NETWORK_ID_LENGTH);
            buf.writeUtf(descriptor.displayName(), StorageNetworkDescriptor.MAX_DISPLAY_NAME_LENGTH);
            buf.writeBoolean(descriptor.defaultNetwork());
            buf.writeVarInt(descriptor.capabilities().size());
            for (StorageCapability capability : descriptor.capabilities()) {
                buf.writeVarInt(capability.ordinal());
            }
        }
        buf.writeBoolean(requestId != 0L);
        if (requestId != 0L) buf.writeVarLong(requestId);
        PerformanceMonitor.recordPlanPacketBytes(buf.writerIndex() - startIndex);
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
            List<DemandRole> inputRoles = new ArrayList<>(inCount);
            DemandRole[] demandRoles = DemandRole.values();
            for (int j = 0; j < inCount; j++) {
                int ordinal = buf.readVarInt();
                if (ordinal < 0 || ordinal >= demandRoles.length) {
                    throw new DecoderException("Invalid input demand role: " + ordinal);
                }
                inputRoles.add(demandRoles[ordinal]);
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
            steps.add(new PlanStep(rid, output, batches, inputs, alternatives, modType,
                    depth, hasOrSiblings, recipeWidth, recipeHeight, alternativeModTypes,
                    inputRoles));
        }
        // Materials
        int matCount = readBoundedCount(buf);
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        for (int i = 0; i < matCount; i++) {
            IngredientKey key = IngredientKey.read(buf);
            materials.put(key, new PlanResponse.Availability(buf.readVarInt(), buf.readVarInt(),
                    readNonNegativeVarInt(buf, "material shortage")));
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
        List<Component> modWarnings = new ArrayList<>(modWarnCount);
        for (int i = 0; i < modWarnCount; i++) modWarnings.add(readComponentOrEmpty(buf));
        int repeatCount = buf.readVarInt();
        // Embers alchemy pedestal data
        int[] embersCode = null;
        if (buf.readBoolean()) {
            int len = readBoundedCount(buf);
            embersCode = new int[len];
            for (int i = 0; i < len; i++) embersCode[i] = buf.readVarInt();
        }
        Component[] embersAspectNames = null;
        if (buf.readBoolean()) {
            int len = readBoundedCount(buf);
            embersAspectNames = new Component[len];
            for (int i = 0; i < len; i++) embersAspectNames[i] = readComponentOrEmpty(buf);
        }
        Component[] embersInputNames = null;
        if (buf.readBoolean()) {
            int len = readBoundedCount(buf);
            embersInputNames = new Component[len];
            for (int i = 0; i < len; i++) embersInputNames[i] = readComponentOrEmpty(buf);
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
        boolean hasGraph = buf.readBoolean();
        PlanGraphView graph = hasGraph ? readGraph(buf) : null;
        boolean executionBlocked = buf.readBoolean();
        int candidateCount = readBoundedCount(buf);
        List<MachineCandidateView> machineCandidates = new ArrayList<>(candidateCount);
        MachineCandidateView.State[] machineStates = MachineCandidateView.State.values();
        for (int i = 0; i < candidateCount; i++) {
            String dimension = buf.readUtf(MAX_DIMENSION_LENGTH);
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            ItemStack icon = buf.readItem();
            int stateOrdinal = buf.readVarInt();
            if (stateOrdinal < 0 || stateOrdinal >= machineStates.length) {
                throw new DecoderException("Invalid machine candidate state: " + stateOrdinal);
            }
            Component status = readComponentOrEmpty(buf);
            machineCandidates.add(new MachineCandidateView(
                    dimension, x, y, z, icon, machineStates[stateOrdinal], status));
        }
        int stepIssueCount = readBoundedCount(buf);
        Map<ResourceLocation, PlanResponse.StepIssue> stepIssues = new LinkedHashMap<>();
        for (int i = 0; i < stepIssueCount; i++) {
            ResourceLocation stepRecipeId = buf.readResourceLocation();
            boolean blocked = buf.readBoolean();
            int warningCount = readBoundedCount(buf);
            List<Component> warnings = new ArrayList<>(warningCount);
            for (int j = 0; j < warningCount; j++) warnings.add(readComponentOrEmpty(buf));
            stepIssues.put(stepRecipeId, new PlanResponse.StepIssue(warnings, blocked));
        }
        StorageReference storageReference = null;
        if (buf.readBoolean()) {
            storageReference = StorageReferenceCodec.decode(buf.readNbt())
                    .orElseThrow(() -> new DecoderException("invalid plan storage reference"));
        }
        int storageNetworkCount = readBoundedCount(buf);
        List<StorageNetworkDescriptor> storageNetworks = new ArrayList<>(storageNetworkCount);
        StorageCapability[] capabilities = StorageCapability.values();
        for (int i = 0; i < storageNetworkCount; i++) {
            String backendId = buf.readUtf(MAX_BACKEND_ID_LENGTH);
            String networkId = buf.readUtf(MAX_NETWORK_ID_LENGTH);
            String displayName = buf.readUtf(StorageNetworkDescriptor.MAX_DISPLAY_NAME_LENGTH);
            boolean defaultNetwork = buf.readBoolean();
            int capabilityCount = readBoundedCount(buf);
            Set<StorageCapability> capabilitySet = EnumSet.noneOf(StorageCapability.class);
            for (int j = 0; j < capabilityCount; j++) {
                int ordinal = buf.readVarInt();
                if (ordinal < 0 || ordinal >= capabilities.length) {
                    throw new DecoderException("invalid storage capability: " + ordinal);
                }
                capabilitySet.add(capabilities[ordinal]);
            }
            try {
                storageNetworks.add(new StorageNetworkDescriptor(
                        new StorageReference(new StorageBackendId(backendId), networkId),
                        displayName, defaultNetwork, capabilitySet));
            } catch (IllegalArgumentException e) {
                throw new DecoderException("invalid storage network descriptor", e);
            }
        }
        // requestId follows the per-step diagnostics and is a required protocol field.
        long requestId = 0L;
        if (buf.readBoolean()) {
            requestId = buf.readVarLong();
            if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
                throw new DecoderException("PlanResponsePacket requestId out of range");
            }
        }
        // Fail closed on trailing bytes after full packet consumed
        if (buf.readableBytes() != 0) {
            throw new DecoderException("Trailing bytes in PlanResponsePacket");
        }
        return new PlanResponsePacket(new PlanResponse(success, targetName, targetResult,
                steps, materials, missing, recipeId,
                execModType, execDim, execX, execY, execZ, modWarnings, repeatCount,
                embersCode, embersAspectNames, embersInputNames, embersSeed, embersCanInfer,
                embersCodeFromCache, executionMachineSupportsGui, baseItem, boundMachineTypes,
                leftovers, clickedOutput, graph, executionBlocked, machineCandidates,
                stepIssues, storageReference, storageNetworks), requestId);
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
        if (version != CraftPlanGraph.CURRENT_VERSION) {
            throw new DecoderException("Unsupported graph protocol version: " + version
                    + ", expected: " + CraftPlanGraph.CURRENT_VERSION);
        }
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
                inputs.add(new PlanGraphView.InputView(readNonNegativeVarInt(buf, "input port"),
                        buf.readItem(), readNonNegativeVarInt(buf, "input quantity"),
                        readNonNegativeVarInt(buf, "input role ordinal")));
            }
            List<PlanGraphView.OutputView> outputs = new ArrayList<>();
            for (int j = 0, m = readBoundedCount(buf); j < m; j++) {
                outputs.add(new PlanGraphView.OutputView(readNonNegativeVarInt(buf, "output port"),
                        buf.readItem(), readNonNegativeVarInt(buf, "output quantity"),
                        readNonNegativeVarInt(buf, "output kind ordinal")));
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
                "[RSI-PlanPkt] Client received PlanResponsePacket: recipeId={} success={} steps={} graphNodes={}",
                packet.plan.recipeId(), packet.plan.success(), packet.plan.steps().size(),
                packet.plan.graph() != null ? packet.plan.graph().nodes().size() : 0);
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanResponseClientPacketHandler.handle(
                        packet.plan, packet.requestId)));
        ctx.setPacketHandled(true);
    }
}
