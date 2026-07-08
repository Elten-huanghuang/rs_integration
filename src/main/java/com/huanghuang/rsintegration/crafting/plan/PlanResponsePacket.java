package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
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
        RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] encode() called: recipeId={} success={} steps={}",
                plan.recipeId(), plan.success(), plan.steps().size());
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
            buf.writeVarInt(step.warnings().size());
            for (String w : step.warnings()) {
                buf.writeUtf(w);
            }
        }
        // Materials
        buf.writeVarInt(plan.materials().size());
        for (Map.Entry<Item, PlanResponse.Availability> e : plan.materials().entrySet()) {
            buf.writeResourceLocation(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(e.getKey()));
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
        // Mod warnings (Goety research/structure, FA essences)
        buf.writeVarInt(plan.modWarnings().size());
        for (String w : plan.modWarnings()) buf.writeUtf(w);
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
            for (String s : plan.embersAspectNames()) buf.writeUtf(s);
        }
        buf.writeBoolean(plan.embersInputNames() != null);
        if (plan.embersInputNames() != null) {
            buf.writeVarInt(plan.embersInputNames().length);
            for (String s : plan.embersInputNames()) buf.writeUtf(s);
        }
        buf.writeVarLong(plan.embersSeed());
        buf.writeBoolean(plan.embersCanInfer());
        buf.writeBoolean(plan.embersCodeFromCache());
        buf.writeBoolean(plan.executionMachineSupportsGui());
        buf.writeBoolean(plan.baseItem() != null);
        if (plan.baseItem() != null) buf.writeItem(plan.baseItem());
        // boundMachineTypes availability passport
        buf.writeVarInt(plan.boundMachineTypes().size());
        for (String mt : plan.boundMachineTypes()) buf.writeUtf(mt);
        // Leftovers (overproduction from integer batch rounding)
        buf.writeVarInt(plan.leftovers().size());
        for (Map.Entry<Item, Integer> e : plan.leftovers().entrySet()) {
            buf.writeResourceLocation(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(e.getKey()));
            buf.writeVarInt(e.getValue());
        }
    }

    public static PlanResponsePacket decode(FriendlyByteBuf buf) {
        RSIntegrationMod.LOGGER.debug("[RSI-PlanPkt] decode() called");
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
            int warnCount = buf.readVarInt();
            List<String> warnings = new ArrayList<>(warnCount);
            for (int j = 0; j < warnCount; j++) {
                warnings.add(buf.readUtf());
            }
            steps.add(new PlanStep(rid, output, batches, inputs, alternatives, modType,
                    depth, hasOrSiblings, recipeWidth, recipeHeight, alternativeModTypes,
                    warnings));
        }
        // Materials
        int matCount = buf.readVarInt();
        Map<Item, PlanResponse.Availability> materials = new LinkedHashMap<>();
        for (int i = 0; i < matCount; i++) {
            Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(buf.readResourceLocation());
            if (item != null) {
                materials.put(item, new PlanResponse.Availability(buf.readVarInt(), buf.readVarInt()));
            } else {
                buf.readVarInt(); buf.readVarInt(); // skip counts for unknown item
            }
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
        // Mod warnings
        int modWarnCount = buf.readVarInt();
        List<String> modWarnings = new ArrayList<>(modWarnCount);
        for (int i = 0; i < modWarnCount; i++) modWarnings.add(buf.readUtf());
        int repeatCount = buf.readVarInt();
        // Embers alchemy pedestal data
        int[] embersCode = null;
        if (buf.readBoolean()) {
            int len = buf.readVarInt();
            embersCode = new int[len];
            for (int i = 0; i < len; i++) embersCode[i] = buf.readVarInt();
        }
        String[] embersAspectNames = null;
        if (buf.readBoolean()) {
            int len = buf.readVarInt();
            embersAspectNames = new String[len];
            for (int i = 0; i < len; i++) embersAspectNames[i] = buf.readUtf();
        }
        String[] embersInputNames = null;
        if (buf.readBoolean()) {
            int len = buf.readVarInt();
            embersInputNames = new String[len];
            for (int i = 0; i < len; i++) embersInputNames[i] = buf.readUtf();
        }
        long embersSeed = buf.readVarLong();
        boolean embersCanInfer = buf.readBoolean();
        boolean embersCodeFromCache = buf.readBoolean();
        boolean executionMachineSupportsGui = buf.readBoolean();
        ItemStack baseItem = buf.readBoolean() ? buf.readItem() : null;
        // boundMachineTypes availability passport
        int boundMtCount = buf.readVarInt();
        Set<String> boundMachineTypes = new LinkedHashSet<>();
        for (int i = 0; i < boundMtCount; i++) boundMachineTypes.add(buf.readUtf());
        // Leftovers (overproduction)
        int leftoverCount = buf.readVarInt();
        Map<Item, Integer> leftovers = new LinkedHashMap<>();
        for (int i = 0; i < leftoverCount; i++) {
            Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(buf.readResourceLocation());
            int cnt = buf.readVarInt();
            if (item != null) leftovers.put(item, cnt);
        }
        return new PlanResponsePacket(new PlanResponse(success, targetName, targetResult,
                steps, materials, missing, recipeId,
                execModType, execDim, execX, execY, execZ, modWarnings, repeatCount,
                embersCode, embersAspectNames, embersInputNames, embersSeed, embersCanInfer,
                embersCodeFromCache, executionMachineSupportsGui, baseItem, boundMachineTypes,
                leftovers));
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
            openScreen(packet.plan);
        });
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(PlanResponse plan) {
        RSIntegrationMod.LOGGER.debug(
                "[RSI-PlanPkt] openScreen called: success={} steps={}",
                plan.success(), plan.steps().size());
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-PlanPkt] openScreen ABORT: mc.player is null");
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
            existing.updatePlan(plan);
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
