package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
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
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AetherworksBatchDelegate extends AbstractBatchDelegate {

    private static volatile boolean classesLoaded;
    private static volatile boolean classesAvailable;
    private static volatile Class<?> anvilBEClass;
    private static volatile Class<?> forgeBEClass;
    private static volatile Class<?> anvilRecipeClass;
    private static volatile Class<?> coolerBEClass;
    private static volatile Class<?> heaterBEClass;

    private static void ensureClasses() {
        if (classesLoaded) return;
        classesLoaded = true;
        if (!ModList.get().isLoaded("aetherworks")) {
            classesAvailable = false;
            return;
        }
        try {
            anvilBEClass = Class.forName("net.sirplop.aetherworks.blockentity.AetheriumAnvilBlockEntity");
            forgeBEClass = Class.forName("net.sirplop.aetherworks.blockentity.AetherForgeBlockEntity");
            anvilRecipeClass = Class.forName("net.sirplop.aetherworks.recipe.IAetheriumAnvilRecipe");
            try { coolerBEClass = Class.forName("net.sirplop.aetherworks.blockentity.ForgeCoolerBlockEntity"); }
            catch (ClassNotFoundException e) { coolerBEClass = null; }
            try { heaterBEClass = Class.forName("net.sirplop.aetherworks.blockentity.ForgeHeaterBlockEntity"); }
            catch (ClassNotFoundException e) { heaterBEClass = null; }
            classesAvailable = true;
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Classes not found: {}", e.toString());
            classesAvailable = false;
        }
    }

    // Instance state
    private ServerLevel level;
    private BlockPos machinePos;
    @Nullable private BlockPos forgePos;
    private Object anvilBE;
    @Nullable private Object forgeBE;
    private Recipe<?> recipe;
    private Item recordedInputItem;
    private boolean materialsPlaced;
    private int tempMin;
    private int tempMax;
    private final List<BlockPos> coolerPositions = new ArrayList<>();
    private boolean tempControlAvailable;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        if (!classesAvailable) {
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
        if (be == null || !anvilBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.aetherworks.error.not_anvil"));
            return false;
        }
        this.anvilBE = be;

        // Find nearby forge and cooler/vent blocks
        this.forgePos = findNearbyForge(lvl, pos);
        if (forgePos != null) {
            BlockEntity fbe = lvl.getBlockEntity(forgePos);
            if (fbe != null && forgeBEClass.isInstance(fbe)) {
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
        if (anvilRecipeClass != null && !anvilRecipeClass.isInstance(r)) {
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

        this.network = RSIntegration.resolveNetworkFromPlayer(player);
        this.materialsPlaced = false;
        this.recordedInputItem = null;
        return true;
    }

    @Nullable
    private static BlockPos findNearbyForge(Level level, BlockPos center) {
        if (forgeBEClass == null) return null;
        BlockPos.MutableBlockPos mpos = center.mutable();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    mpos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(mpos);
                    if (be != null && forgeBEClass.isInstance(be)) {
                        return mpos.immutable();
                    }
                }
            }
        }
        return null;
    }

    private static void findNearbyCoolers(Level level, BlockPos center, List<BlockPos> out) {
        out.clear();
        if (coolerBEClass == null) return;
        BlockPos.MutableBlockPos mpos = center.mutable();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    mpos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(mpos);
                    if (be != null && coolerBEClass.isInstance(be)) {
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
        } catch (Exception ignored) {}
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
        if (current == null || current.isRemoved() || !anvilBEClass.isInstance(current)) {
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

        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Placed {} in anvil slot 0",
                input.getHoverName().getString());
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (!materialsPlaced || anvilBEClass == null) return false;

        BlockEntity current = level.getBlockEntity(machinePos);
        if (current == null || current.isRemoved() || !anvilBEClass.isInstance(current)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Anvil BE removed during craft");
            return true; // trigger cleanup
        }
        this.anvilBE = current;

        // Refresh forge reference
        if (forgePos != null) {
            BlockEntity fbe = level.getBlockEntity(forgePos);
            if (fbe != null && forgeBEClass != null && forgeBEClass.isInstance(fbe)) {
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
                                if (cbe != null && coolerBEClass.isInstance(cbe)) {
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
            } catch (Exception ignored) {}
        }

        // 2. Auto-hammer — call onHit() when hitTimeout > 0 and hasEmber
        try {
            int ht = Reflect.getIntField(anvilBE, "hitTimeout").orElse(0);
            boolean ember = Reflect.<Boolean>getField(anvilBE, "hasEmber").orElse(false);
            if (ht > 0 && ember) {
                Reflect.invoke(anvilBE, "onHit");
            }
        } catch (Exception ignored) {}

        // 3. Craft completion — check if slot 0 item type changed
        Object inv = Reflect.getField(anvilBE, "inventory").orElse(null);
        if (inv == null) return false;
        ItemStack slotItem = Reflect.invoke(inv, "getStackInSlot", 0)
                .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);

        if (!slotItem.isEmpty() && recordedInputItem != null
                && slotItem.getItem() != recordedInputItem) {
            RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] isCraftComplete=TRUE: item transformed {} -> {}",
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
    public void onBatchFailed(ServerPlayer player, String reason) {
        materialsPlaced = false;
        if (anvilBE != null) {
            Object inv = Reflect.getField(anvilBE, "inventory").orElse(null);
            if (inv != null) {
                ItemStack leftover = Reflect.invoke(inv, "extractItem", 0, 64, false)
                        .map(o -> (ItemStack) o).orElse(ItemStack.EMPTY);
                if (!leftover.isEmpty() && network != null) {
                    network.insertItem(leftover, leftover.getCount(), Action.PERFORM);
                }
            }
        }
        recordedInputItem = null;
        if (reason != null && !reason.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.craft_failed", reason));
        }
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
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
        ensureClasses();
        List<String> warnings = new ArrayList<>();
        if (anvilRecipeClass == null || !anvilRecipeClass.isInstance(recipe)) return warnings;

        try {
            int min = (int) recipe.getClass().getMethod("getTemperatureMin").invoke(recipe);
            int max = (int) recipe.getClass().getMethod("getTemperatureMax").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.warn.temp_range", min, max).getString());
        } catch (Exception ignored) {}

        try {
            int hits = (int) recipe.getClass().getMethod("getNumberOfHits").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.info.hits_required", hits).getString());
        } catch (Exception ignored) {}

        try {
            int ember = (int) recipe.getClass().getMethod("getEmberPerHit").invoke(recipe);
            warnings.add(Component.translatable("rsi.aetherworks.info.ember_per_hit", ember).getString());
        } catch (Exception ignored) {}

        if (dim != null && pos != null) {
            try {
                ServerLevel lvl = CraftPacketUtils.resolveLevel(player.server, dim, player);
                if (lvl != null && lvl.isLoaded(pos)) {
                    BlockEntity be = lvl.getBlockEntity(pos);
                    if (be != null && anvilBEClass != null && anvilBEClass.isInstance(be)) {
                        if (findNearbyForge(lvl, pos) == null) {
                            warnings.add(Component.translatable("rsi.aetherworks.warn.no_forge").getString());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return warnings;
    }
}
