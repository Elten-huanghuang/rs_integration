package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.reflection.probes.AetherworksReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for Aetherworks Tool Station. */
public final class AetherworksToolStationBatchDelegate extends AbstractBatchDelegate {

    private static final int INPUT_SLOT_COUNT = 5;
    private static final int OUTPUT_SLOT = 5;

    // Instance state
    private ServerLevel level;
    private BlockPos machinePos;
    private BlockPos forgePos;
    private Object toolStationBE;
    private Object forgeBE;
    private Recipe<?> recipe;
    private final List<ItemStack> placedInputs = new ArrayList<>();
    private boolean materialsPlaced;
    private boolean failureRefundSafe = true;
    private int recipeTemperature;
    private final List<BlockPos> coolerPositions = new ArrayList<>();
    private boolean tempControlAvailable;
    private int abortTimer;
    private boolean craftTimedOut;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        if (!AetherworksReflection.isAvailable()) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Aetherworks"));
            return false;
        }
        ServerLevel lvl = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (lvl == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.level = lvl;
        this.machinePos = pos;

        if (!lvl.hasChunkAt(pos)) return false;

        BlockEntity be = lvl.getBlockEntity(pos);
        if (be == null || AetherworksReflection.toolStationBEClass == null
                || !AetherworksReflection.toolStationBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.not_tool_station"));
            return false;
        }
        this.toolStationBE = be;

        BlockEntity attachedForge = AetherworksMachineSafety.findAttachedForge(lvl, pos, be);
        if (attachedForge == null) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.no_attached_forge"));
            return false;
        }
        this.forgeBE = attachedForge;
        this.forgePos = attachedForge.getBlockPos().immutable();
        findNearbyCoolers(lvl, forgePos, this.coolerPositions);
        this.tempControlAvailable = !this.coolerPositions.isEmpty();

        Recipe<?> r = lvl.getRecipeManager().byKey(recipeId).orElse(null);
        if (r == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (AetherworksReflection.toolStationRecipeClass != null && !AetherworksReflection.toolStationRecipeClass.isInstance(r)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.wrong_recipe_type"));
            return false;
        }
        this.recipe = r;

        try {
            this.recipeTemperature = (int) r.getClass().getMethod("getTemperature").invoke(r);
        } catch (Exception e) {
            this.recipeTemperature = 2800;
        }

        if (storageEndpoint() == null) {
            this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }
        this.materialsPlaced = false;
        this.placedInputs.clear();
        this.failureRefundSafe = true;
        return true;
    }

    private static void findNearbyCoolers(Level level, BlockPos center, List<BlockPos> out) {
        out.clear();
        if (AetherworksReflection.coolerBEClass == null) return;
        BlockPos.MutableBlockPos mpos = center.mutable();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    mpos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(mpos);
                    if (be != null && AetherworksReflection.coolerBEClass.isInstance(be)) {
                        out.add(mpos.immutable());
                    }
                }
            }
        }
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Ingredient> inputs = (List<Ingredient>) recipe.getClass()
                    .getMethod("getDisplayInputs").invoke(recipe);
            if (inputs != null) {
                for (Ingredient ing : inputs) {
                    if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] getDisplayInputs failed", e);
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        return false; // use shared-ledger path exclusively
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;

        if (toolStationBE == null || recipe == null || materials.isEmpty()
                || materials.size() > INPUT_SLOT_COUNT) return false;

        BlockEntity current = level.getBlockEntity(machinePos);
        if (current == null || current.isRemoved() || !AetherworksReflection.toolStationBEClass.isInstance(current)) {
            player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
            return false;
        }
        this.toolStationBE = current;

        BlockEntity attachedForge = AetherworksMachineSafety.findAttachedForge(level, machinePos, current);
        if (attachedForge == null) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.no_attached_forge"));
            return false;
        }
        this.forgeBE = attachedForge;
        this.forgePos = attachedForge.getBlockPos().immutable();

        Object inv = Reflect.getField(toolStationBE, "inventory").orElse(null);
        if (inv == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Aetherworks] Cannot access tool station inventory");
            return false;
        }

        // Validate every slot before placing anything so a late conflict cannot
        // leave an untracked physical copy behind.
        for (int i = 0; i < materials.size(); i++) {
            ItemStack existing = Reflect.invoke(inv, "getStackInSlot", i)
                    .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).orElse(ItemStack.EMPTY);
            if (!existing.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.slot_occupied"));
                return false;
            }
        }
        ItemStack existingOutput = Reflect.invoke(inv, "getStackInSlot", OUTPUT_SLOT)
                .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).orElse(ItemStack.EMPTY);
        if (!existingOutput.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.slot_occupied"));
            return false;
        }
        placedInputs.clear();
        for (int i = 0; i < materials.size(); i++) {
            ItemStack input = materials.get(i).copy();
            input.setCount(1);
            Reflect.invoke(inv, "setStackInSlot", i, input);
            placedInputs.add(input.copy());
        }
        ((BlockEntity) toolStationBE).setChanged();

        this.materialsPlaced = true;
        this.failureRefundSafe = true;
        this.abortTimer = 0;
        this.craftTimedOut = false;

        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Placed {} materials in tool station",
                materials.size());
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!materialsPlaced || AetherworksReflection.toolStationBEClass == null) return false;

        if (!AetherworksReflection.toolStationBEClass.isInstance(be)) {
            warnOnce("toolstation_be_removed", "[RSI-Aetherworks] ToolStation BE removed during craft");
            return true;
        }
        this.toolStationBE = be;

        // Refresh forge reference
        if (forgePos != null) {
            BlockEntity fbe = level.getBlockEntity(forgePos);
            if (fbe != null && AetherworksReflection.forgeBEClass != null && AetherworksReflection.forgeBEClass.isInstance(fbe)) {
                this.forgeBE = fbe;
            }
        }
        if (!AetherworksMachineSafety.isAttachedToForge(forgeBE, be)) {
            craftTimedOut = true;
            warnOnce("toolstation_detached",
                    "[RSI-Aetherworks] Tool station detached from forge during craft");
            return true;
        }

        // Temperature control
        if (forgeBE != null) {
            try {
                Object heatCap = Reflect.getField(forgeBE, "heatCapability").orElse(null);
                if (heatCap != null) {
                    double currentHeat = Reflect.<Double>invoke(heatCap, "getHeat").orElse(0.0);
                    double target = recipeTemperature;

                    if (tempControlAvailable) {
                        if (currentHeat > target + 50) {
                            for (BlockPos cp : coolerPositions) {
                                BlockEntity cbe = level.getBlockEntity(cp);
                                if (cbe != null && AetherworksReflection.coolerBEClass.isInstance(cbe)) {
                                    double excess = currentHeat - target;
                                    double cd = Math.max(1.0, 100.0 - excess / 5.0);
                                    Reflect.setField(cbe, "cooldown", cd);
                                }
                            }
                        } else if (currentHeat < target - 50) {
                            Reflect.invoke(forgeBE, "transferHeat", 5.0f, false);
                        }
                    } else {
                        if (Math.abs(currentHeat - target) > 30) {
                            Reflect.invokeExact(heatCap, "setHeat", new Class<?>[]{double.class}, target);
                            double sh = Reflect.<Double>getField(forgeBE, "storedHeat").orElse(0.0);
                            if (sh > 0) Reflect.setField(forgeBE, "storedHeat", 0.0);
                        }
                    }
                }
            } catch (Exception e) {
                abortTimer++;
                if (abortTimer > 300) {
                    craftTimedOut = true;
                    warnOnce("toolstation_temp_regulation_failed", "[RSI-Aetherworks] ToolStation temperature regulation failed >300 ticks, aborting craft", e);
                }
            }
        }

        // Sync hasEmber/hasHeat from forge, then auto-hammer
        if (forgeBE != null) {
            try {
                Reflect.invoke(be, "onForgeTick", forgeBE);
            } catch (Exception e) {
                abortTimer++;
                if (abortTimer > 300) {
                    craftTimedOut = true;
                    warnOnce("toolstation_on_forge_tick_failed", "[RSI-Aetherworks] ToolStation onForgeTick failed >300 ticks, aborting craft", e);
                }
            }
        }
        try {
            boolean hasEmber = Reflect.<Boolean>getField(be, "hasEmber").orElse(false);
            boolean hasHeat = Reflect.<Boolean>getField(be, "hasHeat").orElse(false);
            if (hasEmber && hasHeat) {
                Reflect.invoke(be, "onHit");
            }
        } catch (Exception e) {
            abortTimer++;
            if (abortTimer > 300) {
                craftTimedOut = true;
                warnOnce("toolstation_auto_hammer_failed", "[RSI-Aetherworks] ToolStation auto-hammer reflection failed >300 ticks, aborting craft", e);
            }
        }

        if (craftTimedOut) return true;

        // Aetherworks consumes input slots 0..4 and writes the result to slot 5.
        try {
            Object inv = Reflect.getField(be, "inventory").orElse(null);
            if (inv == null) return false;
            ItemStack output = Reflect.invoke(inv, "getStackInSlot", OUTPUT_SLOT)
                    .filter(ItemStack.class::isInstance).map(ItemStack.class::cast)
                    .orElse(ItemStack.EMPTY);
            if (!output.isEmpty()) return true;

            for (int i = 0; i < placedInputs.size(); i++) {
                ItemStack input = Reflect.invoke(inv, "getStackInSlot", i)
                        .filter(ItemStack.class::isInstance).map(ItemStack.class::cast)
                        .orElse(ItemStack.EMPTY);
                if (input.isEmpty()) {
                    craftTimedOut = true;
                    warnOnce("toolstation_input_missing",
                            "[RSI-Aetherworks] Tool station input disappeared before output was produced");
                    return true;
                }
            }
        } catch (Exception e) {
            abortTimer++;
            if (abortTimer > 300) {
                craftTimedOut = true;
                warnOnce("toolstation_completion_detection_failed",
                        "[RSI-Aetherworks] ToolStation completion detection failed >300 ticks, aborting craft", e);
            }
        }

        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (craftTimedOut || toolStationBE == null) return ItemStack.EMPTY;

        Object inv = Reflect.getField(toolStationBE, "inventory").orElse(null);
        if (inv == null) return ItemStack.EMPTY;

        ItemStack slot = Reflect.invoke(inv, "getStackInSlot", OUTPUT_SLOT)
                .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).orElse(ItemStack.EMPTY);
        if (slot.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = Reflect.invoke(inv, "extractItem", OUTPUT_SLOT, 64, false)
                .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).orElse(ItemStack.EMPTY);
        if (result.isEmpty()) {
            Reflect.invoke(inv, "setStackInSlot", OUTPUT_SLOT, ItemStack.EMPTY);
            result = slot.copy();
        }
        ((BlockEntity) toolStationBE).setChanged();
        materialsPlaced = false;
        placedInputs.clear();
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // Remove only inputs that still match this operation. The output slot and
        // externally replaced stacks remain untouched and are never input refunds.
        failureRefundSafe = !materialsPlaced;
        if (!materialsPlaced) {
            recordFailureRecoveredInputs(List.of());
            return;
        }
        Object inv = Reflect.getField(be, "inventory").orElse(null);
        List<ItemStack> recovered = new ArrayList<>();
        if (inv != null) {
            List<ItemStack> recoveredSlots = new ArrayList<>(placedInputs.size());
            for (int i = 0; i < placedInputs.size(); i++) {
                ItemStack expected = placedInputs.get(i);
                ItemStack visible = Reflect.invoke(inv, "getStackInSlot", i)
                        .filter(ItemStack.class::isInstance).map(ItemStack.class::cast)
                        .orElse(ItemStack.EMPTY);
                ItemStack leftover = ItemStack.EMPTY;
                if (!visible.isEmpty() && ItemStack.isSameItemSameTags(visible, expected)) {
                    leftover = Reflect.invoke(inv, "extractItem", i,
                                    Math.min(visible.getCount(), expected.getCount()), false)
                            .filter(ItemStack.class::isInstance).map(ItemStack.class::cast)
                            .orElse(ItemStack.EMPTY);
                }
                recoveredSlots.add(leftover.copy());
                if (!leftover.isEmpty()) recovered.add(leftover.copy());
                if (!leftover.isEmpty() && !usingSharedLedger) {
                    refundToRSNetwork(leftover, player);
                }
            }
            failureRefundSafe = AetherworksMachineSafety.recoveredExpectedSlots(
                    recoveredSlots, placedInputs);
        }
        recordFailureRecoveredInputs(recovered);
        materialsPlaced = false;
        placedInputs.clear();
    }

    @Override
    protected boolean isFailureRefundSafe() {
        return failureRefundSafe;
    }

    private void refundToRSNetwork(ItemStack stack, ServerPlayer player) {
        ItemStack remainder = insertIntoStorage(player, stack, false);
        if (!remainder.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, remainder);
        }
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        materialsPlaced = false;
        placedInputs.clear();
        failureRefundSafe = true;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return machinePos != null ? machinePos : BlockPos.ZERO;
    }

    @SuppressWarnings("unused")
    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        if (AetherworksReflection.toolStationRecipeClass == null || !AetherworksReflection.toolStationRecipeClass.isInstance(recipe)) return warnings;

        try {
            int temp = (int) recipe.getClass().getMethod("getTemperature").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.warn.temp_target", temp));
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] ToolStation getTemperature reflection failed in plan warnings", e);
        }

        try {
            double rate = (double) recipe.getClass().getMethod("getTemperatureRate").invoke(recipe);
            if (rate > 0) {
                warnings.add(Component.translatable("rsi.aetherworks.info.temp_rate",
                        String.format("%.1f", rate)));
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] ToolStation getTemperatureRate reflection failed in plan warnings", e);
        }

        if (dim != null && pos != null) {
            try {
                ServerLevel lvl = CraftPacketUtils.resolveLevel(player.server, dim, player);
                if (lvl != null && lvl.isLoaded(pos)) {
                    BlockEntity be = lvl.getBlockEntity(pos);
                    if (be != null && AetherworksReflection.toolStationBEClass != null && AetherworksReflection.toolStationBEClass.isInstance(be)) {
                        if (AetherworksMachineSafety.findAttachedForge(lvl, pos, be) == null) {
                            warnings.add(Component.translatable("rsi.aetherworks.warn.no_forge"));
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] ToolStation forge proximity check failed in plan warnings", e);
            }
        }

        return warnings;
    }
}
