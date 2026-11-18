package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.PreviewRateLimiter;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collections;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

public final class GenericCraftPacket {
    private ResourceLocation recipeId;
    private final boolean preview;
    /** itemRegKey → forced recipeId (only for preview mode, empty when unused) */
    private final Map<String, String> forcedRecipes;
    private Map<String, ItemStack> materialLocks = Map.of();
    private PlanningPreferences planningPreferences = PlanningPreferences.empty();
    private final ResourceLocation dim;
    private final BlockPos pos;
    private final int repeatCount;
    private final boolean inferMode;
    /** JEI-provided base item for FA ApplyModifierRecipe prefill */
    private final ItemStack baseItem;
    /**
     * JEI-provided concrete output (ghost slot) the player clicked. Lets the
     * server distinguish NBT-variant outputs that share one recipe id — e.g. WR
     * arcane iterator "Curse II" vs "Curse I" both resolve to the same recipe.
     * Null when the client didn't supply it (backward compatible).
     */
    private final ItemStack targetOutput;
    /** Client-generated correlation id for preview responses; zero is legacy. */
    private long requestId;
    private long confirmedPreviewRequestId;
    private OutputDestination outputDestination = OutputDestination.RS_NETWORK;
    /** Explicit backend-qualified target selected in the plan UI; null means server default. */
    @Nullable
    private StorageReference storageReference;
    private boolean maximize;
    /** Execute only independently craftable intermediate materials. */
    private boolean partialPreparation;
    private MachineSelectionMode machineSelectionMode = MachineSelectionMode.AUTO;

    /** Preview mode: compute plan and send GUI to client. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview) {
        this(recipeId, preview, Collections.emptyMap(), null, null, 1);
    }

    /** Preview mode with forced recipe overrides (for OR-path selection). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes) {
        this(recipeId, preview, forcedRecipes, null, null, 1);
    }

    /** Convenience: without explicit machine binding. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos) {
        this(recipeId, preview, forcedRecipes, dim, pos, 1);
    }

    /** Convenience: with repeat count, no infer mode. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, false);
    }

    /** Convenience: with infer mode, no base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, null);
    }

    /** All parameters, including JEI base item for FA smithing prefill. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, null);
    }

    /** Master constructor: adds the JEI ghost-output target for NBT-variant recipes. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this.recipeId = recipeId;
        this.preview = preview;
        this.forcedRecipes = forcedRecipes != null ? forcedRecipes : Collections.emptyMap();
        this.dim = dim;
        this.pos = pos;
        this.repeatCount = Math.max(1, Math.min(repeatCount, RSIntegrationConfig.REPEAT_COUNT_MAX.get()));
        this.inferMode = inferMode;
        this.baseItem = baseItem != null ? baseItem.copy() : null;
        this.targetOutput = targetOutput != null && !targetOutput.isEmpty() ? targetOutput.copy() : null;
        this.requestId = 0L;
        this.storageReference = null;
    }

    /** Full constructor with an explicit preview correlation id. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput,
                              long requestId) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, targetOutput);
        if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("requestId out of range");
        }
        // Constructors delegate through the legacy master constructor; assign
        // the bounded id only after all common field normalization is complete.
        this.requestId = requestId;
    }

    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput,
                              long requestId, OutputDestination outputDestination) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, requestId);
        this.outputDestination = outputDestination == null
                ? OutputDestination.RS_NETWORK : outputDestination;
    }

    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput,
                              long requestId, OutputDestination outputDestination,
                              @Nullable StorageReference storageReference) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, requestId, outputDestination);
        this.storageReference = storageReference;
    }

    public static GenericCraftPacket maxPreview(ResourceLocation recipeId,
                                                 Map<String, String> forcedRecipes,
                                                 @Nullable ResourceLocation dim,
                                                 @Nullable BlockPos pos,
                                                 @Nullable ItemStack baseItem,
                                                 @Nullable ItemStack targetOutput,
                                                 long requestId) {
        GenericCraftPacket packet = new GenericCraftPacket(recipeId, true, forcedRecipes,
                dim, pos, 1, false, baseItem, targetOutput, requestId,
                OutputDestination.RS_NETWORK);
        packet.maximize = true;
        return packet;
    }

    /** Convenience: execute mode. */
    public GenericCraftPacket(ResourceLocation recipeId) {
        this(recipeId, false, Collections.emptyMap(), null, null, 1);
    }

    /** JEI-initiated preview for a mod recipe with known machine location. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, 1);
    }

    /** JEI-initiated preview with repeat, inferMode, and specific base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem);
    }

    /** JEI-initiated preview carrying the clicked ghost output (NBT-variant target). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem, targetOutput);
    }

    public void encode(FriendlyByteBuf buf) {
        GenericCraftPacketCodec.write(buf, wireData());
    }

    public static GenericCraftPacket decode(FriendlyByteBuf buf) {
        return fromWireData(GenericCraftPacketCodec.read(buf));
    }

    private GenericCraftPacketCodec.WireData wireData() {
        return new GenericCraftPacketCodec.WireData(
                recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode,
                baseItem, targetOutput, requestId, outputDestination, storageReference,
                maximize, machineSelectionMode, materialLocks, partialPreparation,
                planningPreferences, confirmedPreviewRequestId);
    }

    private static GenericCraftPacket fromWireData(GenericCraftPacketCodec.WireData data) {
        GenericCraftPacket packet = new GenericCraftPacket(
                data.recipeId(), data.preview(), data.forcedRecipes(), data.dimension(),
                data.position(), data.repeatCount(), data.inferMode(), data.baseItem(),
                data.targetOutput(), data.requestId(), data.outputDestination(),
                data.storageReference());
        packet.maximize = data.maximize();
        packet.machineSelectionMode = data.machineSelectionMode();
        packet.materialLocks = data.materialLocks();
        packet.planningPreferences = data.planningPreferences();
        packet.partialPreparation = data.partialPreparation();
        packet.confirmedPreviewRequestId = data.confirmedPreviewRequestId();
        return packet;
    }

    /** 确认哪一份已展示计划；缺失或过期时拒绝执行，不能悄悄搜索另一条路线。 */
    public GenericCraftPacket withConfirmedPreview(long previewRequestId) {
        if (previewRequestId < 0L || preview || partialPreparation) {
            throw new IllegalArgumentException("invalid confirmed preview request");
        }
        confirmedPreviewRequestId = previewRequestId;
        return this;
    }

    public GenericCraftPacket withMachineSelectionMode(MachineSelectionMode mode) {
        machineSelectionMode = mode == null ? MachineSelectionMode.AUTO : mode;
        return this;
    }

    public GenericCraftPacket withMaterialLocks(Map<String, ItemStack> locks) {
        materialLocks = MaterialLocks.immutableCopy(locks);
        return this;
    }

    public GenericCraftPacket withPlanningPreferences(@Nullable PlanningPreferences preferences) {
        planningPreferences = preferences == null
                ? PlanningPreferences.empty() : preferences;
        return this;
    }

    /** Explicitly opt this execution into intermediate-material preparation. */
    public GenericCraftPacket withPartialPreparation(boolean enabled) {
        partialPreparation = enabled;
        return this;
    }

    boolean isPartialPreparation() {
        return partialPreparation;
    }

    boolean isPreviewRequest() {
        return preview;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public GenericCraftPacket withRecipeId(ResourceLocation value) {
        recipeId = Objects.requireNonNull(value, "recipeId");
        return this;
    }

    Map<String, ItemStack> materialLocks() {
        return materialLocks;
    }

    Map<String, String> forcedRecipes() {
        return forcedRecipes;
    }

    PlanningPreferences planningPreferences() {
        return planningPreferences;
    }

    public GenericCraftPacket withStorageReference(@Nullable StorageReference reference) {
        this.storageReference = reference;
        return this;
    }

    @Nullable
    public StorageReference storageReference() {
        return storageReference;
    }

    boolean isMaximizeRequest() {
        return maximize;
    }

    MachineSelectionMode machineSelectionMode() {
        return machineSelectionMode;
    }

    OutputDestination outputDestination() {
        return outputDestination;
    }

    public static void handle(GenericCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        handle(packet, contextSupplier, packet.partialPreparation);
    }

    static void handlePreparation(GenericCraftPacket packet,
                                  Supplier<NetworkEvent.Context> contextSupplier) {
        handle(packet, contextSupplier, true);
    }

    private static void handle(GenericCraftPacket packet,
                               Supplier<NetworkEvent.Context> contextSupplier,
                               boolean preparationRequest) {
        RSIntegrationMod.debug("[RSI-Generic] handle() ENTRY: recipeId={} preview={} dim={} pos={} repeat={} storage={}",
                packet.recipeId, packet.preview, packet.dim, packet.pos, packet.repeatCount,
                packet.storageReference == null ? "default" : packet.storageReference);
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: player is null, recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        if (player instanceof FakePlayer) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: FakePlayer, recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        // Supplementary: catch fake players that extend ServerPlayer directly
        // without implementing FakePlayer (some mods do this).
        if (player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: not in player list (fake?), recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        if (packet.preview && PreviewRateLimiter.isRateLimited(player.getUUID())) {
            RSIntegrationMod.debug("[RSI-Generic] handle() DROP: rate-limited, recipeId={} player={}",
                    packet.recipeId, player.getGameProfile().getName());
            Component message = Component.translatable("rsi.plan.failure.request_pending");
            player.sendSystemMessage(message);
            context.enqueueWork(() -> PlanningProgressServer.failDirect(
                    player, packet.requestId, packet.recipeId, message));
            context.setPacketHandled(true);
            return;
        }
        final long previewGeneration = packet.preview
                ? CraftRequestCoordinator.beginPreview(player, packet.requestId, packet.recipeId) : 0L;
        if (packet.preview && previewGeneration == 0L) {
            context.enqueueWork(() -> PlanningProgressServer.failDirect(player, packet.requestId,
                    packet.recipeId, Component.translatable("rsi.plan.failure.planner_busy")));
            context.setPacketHandled(true);
            return;
        }
        CraftRequestCoordinator.Request request = new CraftRequestCoordinator.Request(
                packet.recipeId, packet.preview, packet.forcedRecipes, packet.dim, packet.pos,
                packet.repeatCount, packet.inferMode, packet.baseItem, packet.targetOutput,
                packet.requestId, packet.confirmedPreviewRequestId, packet.outputDestination,
                packet.storageReference, packet.maximize, packet.machineSelectionMode,
                packet.materialLocks, packet.planningPreferences);
        RSIntegrationMod.debug("[RSI-Generic] handle() enqueueWork: recipeId={} preview={}",
                packet.recipeId, packet.preview);
        context.enqueueWork(() -> {
            CraftRequestCoordinator.accept(player, request, previewGeneration, preparationRequest);
        });
        context.setPacketHandled(true);
    }
}
