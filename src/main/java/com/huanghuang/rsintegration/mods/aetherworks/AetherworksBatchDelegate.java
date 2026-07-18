package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.reflection.probes.AetherworksReflection;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for Aetherworks Aetherium Anvil (Forge Anvil). */
public final class AetherworksBatchDelegate extends AbstractBatchDelegate {

    // Instance state
    private ServerLevel level;
    private BlockPos machinePos;
    private BlockPos forgePos;
    private Object anvilBE;
    private Object forgeBE;
    private Recipe<?> recipe;
    private Item recordedInputItem;
    private boolean materialsPlaced;
    private int tempMin;
    private int tempMax;
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

        ChunkUtils.loadChunk(lvl, pos);

        BlockEntity be = lvl.getBlockEntity(pos);
        if (be == null || !AetherworksReflection.anvilBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.not_anvil"));
            return false;
        }
        this.anvilBE = be;

        // Find nearby forge and cooler/vent blocks
        this.forgePos = findNearbyForge(lvl, pos);
        if (forgePos != null) {
            BlockEntity fbe = lvl.getBlockEntity(forgePos);
            if (fbe != null && AetherworksReflection.forgeBEClass.isInstance(fbe)) {
                this.forgeBE = fbe;
            }
            findNearbyCoolers(lvl, forgePos, this.coolerPositions);
        }
        this.tempControlAvailable = !this.coolerPositions.isEmpty();

        Recipe<?> r = lvl.getRecipeManager().byKey(recipeId).orElse(null);
        if (r == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (AetherworksReflection.anvilRecipeClass != null && !AetherworksReflection.anvilRecipeClass.isInstance(r)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.wrong_recipe_type"));
            return false;
        }
        this.recipe = r;

        try {
            this.tempMin = (int) r.getClass().getMethod("getTemperatureMin").invoke(r);
            this.tempMax = (int) r.getClass().getMethod("getTemperatureMax").invoke(r);
        } catch (Exception e) {
            this.tempMin = 0;
            this.tempMax = 3000;
        }

        this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        this.materialsPlaced = false;
        this.recordedInputItem = null;
        return true;
    }

    private static BlockPos findNearbyForge(Level level, BlockPos center) {
        if (AetherworksReflection.forgeBEClass == null) return null;
        BlockPos.MutableBlockPos mpos = center.mutable();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    mpos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(mpos);
                    if (be != null && AetherworksReflection.forgeBEClass.isInstance(be)) {
                        return mpos.immutable();
                    }
                }
            }
        }
        return null;
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
            java.lang.reflect.Method m = recipe.getClass().getMethod("getDisplayInput");
            Object result = m.invoke(recipe);
            if (result instanceof Ingredient ing && !ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1));
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] getDisplayInput failed", e);
        }
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getAddition");
            Object result = m.invoke(recipe);
            if (result instanceof Ingredient ing && !ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1));
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] getAddition reflection failed", e);
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

        if (anvilBE == null || recipe == null || materials.isEmpty()) return false;

        BlockEntity current = level.getBlockEntity(machinePos);
        if (current == null || current.isRemoved() || !AetherworksReflection.anvilBEClass.isInstance(current)) {
            player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
            return false;
        }
        this.anvilBE = current;

        Object inv = Reflect.getField(anvilBE, "inventory").orElse(null);
        if (inv == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Aetherworks] Cannot access anvil inventory field");
            return false;
        }
        ItemStack existing = Reflect.invoke(inv, "getStackInSlot", 0)
                .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);
        if (!existing.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.slot_occupied"));
            return false;
        }

        ItemStack input = materials.get(0).copy();
        input.setCount(1);
        Reflect.invoke(inv, "setStackInSlot", 0, input);
        ((BlockEntity) anvilBE).setChanged();

        this.recordedInputItem = input.getItem();
        this.materialsPlaced = true;
        this.abortTimer = 0;
        this.craftTimedOut = false;

        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Placed {} in anvil slot 0",
                input.getHoverName().getString());
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!materialsPlaced || AetherworksReflection.anvilBEClass == null) return false;

        if (!AetherworksReflection.anvilBEClass.isInstance(be)) {
            warnOnce("anvil_be_removed", "[RSI-Aetherworks] Anvil BE removed during craft");
            return true; // trigger cleanup
        }
        this.anvilBE = be;

        // Refresh forge reference
        if (forgePos != null) {
            BlockEntity fbe = level.getBlockEntity(forgePos);
            if (fbe != null && AetherworksReflection.forgeBEClass != null && AetherworksReflection.forgeBEClass.isInstance(fbe)) {
                this.forgeBE = fbe;
            }
        }

        // 1. Temperature control — prefer cooler blocks, fall back to direct setHeat if none found
        if (forgeBE != null) {
            try {
                Object heatCap = Reflect.getField(forgeBE, "heatCapability").orElse(null);
                if (heatCap != null) {
                    double currentHeat = Reflect.<Double>invoke(heatCap, "getHeat").orElse(0.0);
                    double target = (tempMin + tempMax) / 2.0;

                    if (tempControlAvailable) {
                        // Balanced mode: regulate via cooler/heater blocks
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
                            // Gently add to forge's storedHeat buffer for gradual heating
                            Reflect.invoke(forgeBE, "transferHeat", 5.0f, false);
                        }
                    } else {
                        // Fallback: no cooler blocks found, use direct temperature control
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
                    warnOnce("anvil_temp_regulation_failed", "[RSI-Aetherworks] Temperature regulation failed >300 ticks, aborting craft", e);
                }
            }
        }

        // 2. Auto-hammer — call onHit() when hitTimeout > 0 and hasEmber
        try {
            int ht = Reflect.getIntField(be, "hitTimeout").orElse(0);
            boolean ember = Reflect.<Boolean>getField(be, "hasEmber").orElse(false);
            if (ht > 0 && ember) {
                Reflect.invoke(be, "onHit");
            }
        } catch (Exception e) {
            abortTimer++;
            if (abortTimer > 300) {
                craftTimedOut = true;
                warnOnce("anvil_auto_hammer_failed", "[RSI-Aetherworks] Auto-hammer reflection failed >300 ticks, aborting craft", e);
            }
        }

        if (craftTimedOut) return true;

        // 3. Craft completion — check if slot 0 item type changed
        Object inv = Reflect.getField(be, "inventory").orElse(null);
        if (inv == null) return false;
        ItemStack slotItem = Reflect.invoke(inv, "getStackInSlot", 0)
                .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);

        if (!slotItem.isEmpty() && recordedInputItem != null
                && slotItem.getItem() != recordedInputItem) {
            RSIntegrationMod.debug("[RSI-Aetherworks] isCraftComplete=TRUE: item transformed {} -> {}",
                    recordedInputItem, slotItem.getItem());
            return true;
        }

        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        materialsPlaced = false;

        if (anvilBE == null) return ItemStack.EMPTY;

        Object inv = Reflect.getField(anvilBE, "inventory").orElse(null);
        if (inv == null) return ItemStack.EMPTY;

        ItemStack result = Reflect.invoke(inv, "extractItem", 0, 64, false)
                .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);

        if (result.isEmpty()) {
            result = Reflect.invoke(inv, "getStackInSlot", 0)
                    .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);
            if (!result.isEmpty()) {
                Reflect.invoke(inv, "setStackInSlot", 0, ItemStack.EMPTY);
                ((BlockEntity) anvilBE).setChanged();
            }
        } else {
            ((BlockEntity) anvilBE).setChanged();
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Collected result: {}",
                result.isEmpty() ? "EMPTY" : result.getHoverName().getString());
        recordedInputItem = null;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        Object inv = Reflect.getField(be, "inventory").orElse(null);
        if (inv != null) {
            ItemStack leftover = Reflect.invoke(inv, "extractItem", 0, 64, false)
                    .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);
            if (!leftover.isEmpty() && network != null) {
                network.insertItem(leftover, leftover.getCount(), Action.PERFORM);
            }
        }
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        materialsPlaced = false;
        recordedInputItem = null;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return machinePos != null ? machinePos : BlockPos.ZERO;
    }

    // ── Plan warnings ──────────────────────────────────────────

    @SuppressWarnings("unused")
    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (AetherworksReflection.anvilRecipeClass == null || !AetherworksReflection.anvilRecipeClass.isInstance(recipe)) return warnings;

        try {
            int min = (int) recipe.getClass().getMethod("getTemperatureMin").invoke(recipe);
            int max = (int) recipe.getClass().getMethod("getTemperatureMax").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.warn.temp_range", min, max).getString());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] getTemperatureMin/Max reflection failed in plan warnings", e);
        }

        try {
            int hits = (int) recipe.getClass().getMethod("getNumberOfHits").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.info.hits_required", hits).getString());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] getNumberOfHits reflection failed in plan warnings", e);
        }

        try {
            int ember = (int) recipe.getClass().getMethod("getEmberPerHit").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.info.ember_per_hit", ember).getString());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] getEmberPerHit reflection failed in plan warnings", e);
        }

        if (dim != null && pos != null) {
            try {
                ServerLevel lvl = CraftPacketUtils.resolveLevel(player.server, dim, player);
                if (lvl != null && lvl.isLoaded(pos)) {
                    BlockEntity be = lvl.getBlockEntity(pos);
                    if (be != null && AetherworksReflection.anvilBEClass != null && AetherworksReflection.anvilBEClass.isInstance(be)) {
                        if (findNearbyForge(lvl, pos) == null) {
                            warnings.add(Component.translatable("rsi.aetherworks.warn.no_forge").getString());
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Forge proximity check failed in plan warnings", e);
            }
        }

        return warnings;
    }
}
