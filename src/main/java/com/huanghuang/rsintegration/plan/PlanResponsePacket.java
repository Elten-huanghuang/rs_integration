package com.huanghuang.rsintegration.plan;

import com.huanghuang.rsintegration.batch.ModType;
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

    public PlanResponsePacket(PlanResponse plan) {
        this.plan = plan;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(plan.success());
        buf.writeUtf(plan.recipeId() != null ? plan.recipeId() : "");
        // Always send full plan data — even infeasible plans need the recipe
        // chain structure so the player can see what intermediate steps are
        // required and which leaf materials are missing.
        buf.writeUtf(plan.targetName());
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
            if (step.modType() != null) buf.writeUtf(step.modType().id());
            buf.writeVarInt(step.depth());
            buf.writeBoolean(step.hasOrSiblings());
            buf.writeVarInt(step.recipeWidth());
            buf.writeVarInt(step.recipeHeight());
            buf.writeVarInt(step.alternativeModTypes().size());
            for (String mt : step.alternativeModTypes()) {
                buf.writeUtf(mt);
            }
        }
        // Materials
        buf.writeVarInt(plan.materials().size());
        for (Map.Entry<Item, PlanResponse.Availability> e : plan.materials().entrySet()) {
            buf.writeVarInt(Item.getId(e.getKey()));
            buf.writeVarInt(e.getValue().needed());
            buf.writeVarInt(e.getValue().available());
        }
        // Missing
        buf.writeVarInt(plan.missing().size());
        for (String m : plan.missing()) {
            buf.writeUtf(m);
        }
        // Execution routing
        buf.writeBoolean(plan.executionModTypeId() != null);
        if (plan.executionModTypeId() != null) buf.writeUtf(plan.executionModTypeId());
        buf.writeBoolean(plan.executionDim() != null);
        if (plan.executionDim() != null) buf.writeUtf(plan.executionDim());
        buf.writeVarInt(plan.executionPosX());
        buf.writeVarInt(plan.executionPosY());
        buf.writeVarInt(plan.executionPosZ());
    }

    public static PlanResponsePacket decode(FriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        String recipeId = buf.readUtf();
        // Always read full plan data — the server sends the recipe chain
        // structure even for infeasible plans so the client can render
        // intermediate steps and material shortages.
        String targetName = buf.readUtf();
        ItemStack targetResult = buf.readItem();
        // Steps
        int stepCount = buf.readVarInt();
        List<PlanStep> steps = new ArrayList<>(stepCount);
        for (int i = 0; i < stepCount; i++) {
            var rid = buf.readResourceLocation();
            ItemStack output = buf.readItem();
            int batches = buf.readVarInt();
            int inCount = buf.readVarInt();
            List<ItemStack> inputs = new ArrayList<>(inCount);
            for (int j = 0; j < inCount; j++) {
                inputs.add(buf.readItem());
            }
            int altCount = buf.readVarInt();
            List<ResourceLocation> alternatives = new ArrayList<>(altCount);
            for (int j = 0; j < altCount; j++) {
                alternatives.add(buf.readResourceLocation());
            }
            ModType modType = null;
            if (buf.readBoolean()) {
                modType = ModType.byId(buf.readUtf());
            }
            int depth = buf.readVarInt();
            boolean hasOrSiblings = buf.readBoolean();
            int recipeWidth = buf.readVarInt();
            int recipeHeight = buf.readVarInt();
            int altModCount = buf.readVarInt();
            List<String> alternativeModTypes = new ArrayList<>(altModCount);
            for (int j = 0; j < altModCount; j++) {
                alternativeModTypes.add(buf.readUtf());
            }
            steps.add(new PlanStep(rid, output, batches, inputs, alternatives, modType,
                    depth, hasOrSiblings, recipeWidth, recipeHeight, alternativeModTypes));
        }
        // Materials
        int matCount = buf.readVarInt();
        Map<Item, PlanResponse.Availability> materials = new LinkedHashMap<>();
        for (int i = 0; i < matCount; i++) {
            Item item = Item.byId(buf.readVarInt());
            materials.put(item, new PlanResponse.Availability(buf.readVarInt(), buf.readVarInt()));
        }
        // Missing
        int missCount = buf.readVarInt();
        List<String> missing = new ArrayList<>(missCount);
        for (int i = 0; i < missCount; i++) {
            missing.add(buf.readUtf());
        }
        // Execution routing
        String execModType = buf.readBoolean() ? buf.readUtf() : null;
        String execDim = buf.readBoolean() ? buf.readUtf() : null;
        int execX = buf.readVarInt();
        int execY = buf.readVarInt();
        int execZ = buf.readVarInt();
        return new PlanResponsePacket(new PlanResponse(success, targetName, targetResult,
                steps, materials, missing, recipeId,
                execModType, execDim, execX, execY, execZ));
    }

    @SuppressWarnings("resource")
    public static void handle(PlanResponsePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> openScreen(packet.plan));
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(PlanResponse plan) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new CraftingPlanScreen(plan));
    }
}
