package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

public final class BatchCraftStartPacket {

    private final ResourceLocation recipeId;
    @Nullable private final ResourceLocation dim;
    private final BlockPos pos;
    private final int quantity;
    private final ModType modType;

    public BatchCraftStartPacket(ResourceLocation recipeId, @Nullable ResourceLocation dim,
                                 BlockPos pos, int quantity, ModType modType) {
        this.recipeId = recipeId;
        this.dim = dim;
        this.pos = pos;
        this.quantity = quantity;
        this.modType = modType;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        buf.writeVarInt(quantity);
        buf.writeUtf(modType.id());
    }

    public static BatchCraftStartPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        BlockPos pos = buf.readBlockPos();
        int quantity = buf.readVarInt();
        ModType modType = ModType.byId(buf.readUtf());
        return new BatchCraftStartPacket(recipeId, dim, pos, quantity, modType);
    }

    public static void handle(BatchCraftStartPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            ctx.setPacketHandled(true);
            return;
        }
        ctx.enqueueWork(() -> {
            if (packet.modType == null) {
                player.sendSystemMessage(Component.translatable("rsi.batch.error.unsupported"));
                return;
            }

            int qty = Math.max(1, Math.min(packet.quantity, RSIntegrationConfig.BATCH_CRAFT_MAX.get()));

            // For GENERIC recipes, try recursive resolution first.
            // If intermediates need multi-block machines, use the AsyncCraftChain
            // path (one chain per iteration) instead of the direct delegate.
            if (packet.modType == ModType.GENERIC
                    && RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()) {
                List<ResolutionStep> steps = tryResolveRecursiveChain(player, packet.recipeId);
                if (steps != null && !steps.isEmpty()
                        && steps.stream().anyMatch(s -> s.modType() != ModType.GENERIC)) {
                    INetwork network = CraftPacketUtils.resolveNetworkForCraft(
                            player,
                            packet.dim != null
                                    ? net.minecraft.resources.ResourceKey.create(
                                            net.minecraft.core.registries.Registries.DIMENSION, packet.dim)
                                    : null,
                            packet.pos);
                    if (network != null) {
                        BatchCraftTask task = new BatchCraftTask(
                                player.getUUID(), packet.recipeId, qty, steps, network);
                        BatchCraftManager.getInstance().addTask(task);
                        player.displayClientMessage(
                                Component.translatable("rsi.batch.started",
                                        Component.translatable("rsi.batch.mod.generic"),
                                        qty),
                                true);
                        RSIntegrationMod.LOGGER.debug(
                                "[RSI-Batch] Player {} started recursive batch craft: {} x{} ({} steps)",
                                player.getName().getString(), packet.recipeId, qty, steps.size());
                        return;
                    }
                }
            }

            IBatchDelegate delegate = createDelegate(packet.modType);
            if (delegate == null) {
                player.sendSystemMessage(Component.translatable("rsi.batch.error.unsupported"));
                return;
            }

            if (!delegate.validateAndInit(player, packet.recipeId, packet.dim, packet.pos)) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
                return;
            }

            BatchCraftTask task = new BatchCraftTask(player.getUUID(), packet.recipeId, qty, delegate);
            BatchCraftManager.getInstance().addTask(task);

            player.displayClientMessage(
                    Component.translatable("rsi.batch.started",
                            Component.translatable("rsi.batch.mod." + packet.modType.id()),
                            qty),
                    true);
            RSIntegrationMod.LOGGER.debug("[RSI-Batch] Player {} started batch craft: {} x{}",
                    player.getName().getString(), packet.recipeId, qty);
        });
        ctx.setPacketHandled(true);
    }

    @Nullable
    private static IBatchDelegate createDelegate(ModType modType) {
        return modType.createDelegate();
    }

    /**
     * Try to resolve the full recipe chain for a GENERIC recipe including
     * multi-block intermediates. Returns null if resolution fails or if no
     * multi-block steps are needed.
     */
    @Nullable
    private static List<ResolutionStep> tryResolveRecursiveChain(ServerPlayer player, ResourceLocation recipeId) {
        Recipe<?> recipe = player.serverLevel().getRecipeManager().byKey(recipeId).orElse(null);
        if (!(recipe instanceof CraftingRecipe cr)) return null;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) return null;

        List<ResolutionStep> allSteps = CraftPacketUtils.resolveIntermediateSteps(player, network, cr);
        if (allSteps == null) return null;

        // Append the final vanilla recipe so the chain produces the target item
        allSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                new ResourceLocation("minecraft:crafting")));
        return allSteps;
    }
}
