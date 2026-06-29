package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ModRecipeIndex;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch delegate for Touhou Little Maid's altar multiblock crafting.
 * <p>
 * The TLM altar reads input items from the 8 surrounding storage block handlers
 * ({@code TileEntityAltar.handler.setStackInSlot(0, item)}), not from dropped
 * ItemEntities. The output is spawned as an ItemEntity at the centre position.
 */
public final class TlmAltarBatchDelegate extends AbstractBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile Class<?> altarBEClass;
    private static volatile Class<?> blockAltarClass;
    private static volatile Class<?> altarRecipeClass;
    private static volatile Object powerCapToken;

    private static void ensureClasses() {
        if (!com.huanghuang.rsintegration.util.ModClassLoader.ensureClasses("touhou_little_maid",
                "com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityAltar",
                "com.github.tartaricacid.touhoulittlemaid.block.BlockAltar",
                "com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe",
                "com.github.tartaricacid.touhoulittlemaid.capability.PowerCapabilityProvider")) return;
        try {
            altarBEClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityAltar");
            blockAltarClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.block.BlockAltar");
            altarRecipeClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe");
            Class<?> providerClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.capability.PowerCapabilityProvider");
            java.lang.reflect.Field capField = providerClass.getField("POWER_CAP");
            capField.setAccessible(true);
            powerCapToken = capField.get(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] Failed to load TLM classes", e);
        }
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object altar;              // TileEntityAltar (main)
    private Object blockAltar;         // BlockAltar instance
    // Computed in validateAndInit: output spawns at centrePos.above(2)
    @Nullable
    private BlockPos centrePos;
    private Recipe<?> recipe;
    private boolean craftEverConfirmed;

    // Storage block positions and their TileEntityAltars (the 8 surrounding blocks)
    private List<BlockPos> storagePositions;
    private List<Object> storageBlockEntities; // TileEntityAltar for each position
               // centre of storage positions, up(2) = output spawn
    private boolean[] slotsFilled;            // tracks which slots we put items into

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        if (altarBEClass == null || blockAltarClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Touhou Little Maid"));
            return false;
        }

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        if (!level.isLoaded(pos)) {
            RSIntegrationMod.LOGGER.info("[RSI-Batch-TLM] Chunk unloaded at {} — force-loading", pos);
            level.getChunk(pos);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !altarBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.tlm.error.altar_not_found"));
            return false;
        }
        this.altar = be;

        // Check multiblock formed
        try {
            Object storageState = Reflect.getMethodOrThrow(altarBEClass, "getStorageState", "getStorageState").invoke(altar);
            if (storageState == null) {
                player.sendSystemMessage(Component.translatable("rsi.tlm.error.multiblock_incomplete"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Storage state check failed", e);
            player.sendSystemMessage(Component.translatable("rsi.tlm.error.multiblock_incomplete"));
            return false;
        }

        BlockState state = level.getBlockState(pos);
        this.blockAltar = state.getBlock();

        // Resolve recipe
        this.recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (this.recipe == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] validateAndInit: recipe not found {}", recipeId);
        }

        // Verify recipe type — reject non-altar TLM recipes (e.g. maid crafting)
        if (this.recipe != null && altarRecipeClass != null && !altarRecipeClass.isInstance(this.recipe)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] validateAndInit: recipe {} is not an AltarRecipe", recipeId);
            player.sendSystemMessage(Component.literal("§c" + Component.translatable("rsi.generic.error.wrong_recipe_type").getString()
                    + " [" + recipeId + " expected=AltarRecipe got=" + this.recipe.getClass().getSimpleName() + "]"));
            return false;
        }

        // Resolve storage block positions and their TileEntityAltars
        try {
            Object posList = Reflect.getMethodOrThrow(altarBEClass, "getCanPlaceItemPosList", "getCanPlaceItemPosList").invoke(altar);
            this.storagePositions = Reflect.invokeExact(posList, "getData",
                    new Class<?>[0]).map(l -> (List<BlockPos>) l).orElse(null);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Failed to get storage positions", e);
        }
        if (storagePositions == null || storagePositions.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.tlm.error.multiblock_incomplete"));
            return false;
        }

        // Compute output centre position using the same logic as TLM's
        // BlockAltar.getCentrePos(PosListData, BlockPos):
        //   averages X/Z of all blocks in blockPosList whose Y == altarY-2,
        //   then output spawns at centrePos.above(2)
        try {
            Object blockPosData = Reflect.getMethodOrThrow(altarBEClass, "getBlockPosList", "getBlockPosList").invoke(altar);
            List<BlockPos> allBlockPositions = Reflect.invokeExact(blockPosData, "getData",
                    new Class<?>[0]).map(l -> (List<BlockPos>) l).orElse(null);
            if (allBlockPositions != null && !allBlockPositions.isEmpty()) {
                int targetY = myPos.getY() - 2;
                long sumX = 0, sumZ = 0;
                int count = 0;
                for (BlockPos bp : allBlockPositions) {
                    if (bp.getY() == targetY) {
                        sumX += bp.getX();
                        sumZ += bp.getZ();
                        count++;
                    }
                }
                if (count > 0) {
                    this.centrePos = new BlockPos((int)(sumX / count), targetY, (int)(sumZ / count));
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] CentrePos computed: {} (altar: {})",
                            centrePos, myPos);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Failed to compute centrePos, falling back to myPos", e);
        }


        this.storageBlockEntities = new ArrayList<>();
        for (BlockPos p : storagePositions) {
            com.huanghuang.rsintegration.util.ChunkUtils.loadChunk(level, p);
            BlockEntity storageBe = level.getBlockEntity(p);
            if (storageBe != null && altarBEClass.isInstance(storageBe)) {
                storageBlockEntities.add(storageBe);
            } else {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Storage block at {} is not a TileEntityAltar", p);
                storageBlockEntities.add(null);
            }
        }

        this.slotsFilled = new boolean[storagePositions.size()];

        // Reject if any storage handler has items — another task may be running.
        if (!checkAllHandlersEmpty(player)) {
            player.sendSystemMessage(Component.translatable("rsi.tlm.error.handler_not_empty"));
            return false;
        }

        // Pre-check power so the player knows before attempting to craft
        float powerCost = readPowerCost();
        if (powerCost > 0) {
            Float current = readCurrentPower(player);
            if (current != null && current < powerCost) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Power insufficient at bind time: have {} need {}",
                        current, powerCost);
                player.sendSystemMessage(Component.translatable(
                        "rsi.tlm.warn.insufficient_power_bind",
                        String.format("%.1f", current), String.format("%.1f", powerCost)));
                // Don't block binding — just warn so the player knows why it
                // will fail later.
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] validateAndInit OK: recipe={}, storageBlocks={}",
                recipeId, storageBlockEntities.size());
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.craftEverConfirmed = false;
        this.usingSharedLedger = false;
        clearSlotsFilled();

        // Verify the cached BlockEntity is still valid
        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            BlockEntity current = player.serverLevel().getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }

        if (!checkAllHandlersEmpty(player)) return false;
        if (!checkPower(player)) return false;

        ServerLevel level = player.serverLevel();
        if (recipe == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] tryStartSingleCraft: no recipe set");
            return false;
        }

        List<Ingredient> ingredients = recipe.getIngredients();
        int nonEmptyCount = 0;
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) nonEmptyCount++;
        }
        if (nonEmptyCount > storageBlockEntities.size()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Need {} items but only {} storage blocks",
                    nonEmptyCount, storageBlockEntities.size());
            return false;
        }

        // Phase 1: Reserve items via ledger
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.isEmpty()) continue;
            ItemStack t = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (t.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Ingredient[{}] extraction failed", i);
                return false;
            }
            templates.add(t);
        }

        // Phase 2: Place items into storage block handlers.
        try {
            int templateIdx = 0;
            for (int i = 0; i < ingredients.size(); i++) {
                if (ingredients.get(i).isEmpty()) continue;
                if (i >= storageBlockEntities.size()) break;
                Object storageBe = storageBlockEntities.get(i);
                if (storageBe == null) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Storage block {} is null", i);
                    rollbackAll();
                    ledger.rollback(player);
                    return false;
                }
                ItemStack placed = templates.get(templateIdx).copy();
                ItemStackHandler handler = rsi$tlmsGetHandler(storageBe);
                if (!level.isLoaded(storagePositions.get(i))) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-TLM] Storage chunk unloaded at {} — aborting placement", storagePositions.get(i));
                    rollbackAll();
                    ledger.rollback(player);
                    return false;
                }
                handler.setStackInSlot(0, placed);
                slotsFilled[i] = true;
                Reflect.invoke(storageBe, "refresh");
                templateIdx++;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] Item placement into handlers failed:", e);
            rollbackAll();
            ledger.rollback(player);
            return false;
        }

        // Phase 3: Commit ledger first — extract items from RS BEFORE starting craft
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] Ledger commit failed");
            rollbackAll();
            return false;
        }

        // Phase 4: Trigger altarCraft AFTER committing ledger
        if (!invokeAltarCraft(level)) {
            rollbackAll();
            ledger.refundCommitted(network, player);
            return false;
        }

        // altarCraft is synchronous. If recipe matched, handlers are now empty
        // and the output ItemEntity is already spawned. If handlers still have
        // items, altarCraft silently failed (recipe mismatch / insufficient power).
        if (!areAllHandlersEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] altarCraft did not consume items — recipe mismatch?");
            rollbackAll();
            ledger.refundCommitted(network, player);
            return false;
        }
        this.craftEverConfirmed = true;

        return true;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.usingSharedLedger = true;
        this.craftEverConfirmed = false;
        clearSlotsFilled();

        // Verify the cached BlockEntity is still valid
        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            BlockEntity current = player.serverLevel().getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }

        if (!checkAllHandlersEmpty(player)) return false;
        if (recipe != null && !checkPower(player)) return false;

        if (materials.size() > storageBlockEntities.size()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] {} materials > {} storage blocks",
                    materials.size(), storageBlockEntities.size());
            return false;
        }

        ServerLevel level = player.serverLevel();
        try {
            for (int i = 0; i < materials.size() && i < storageBlockEntities.size(); i++) {
                ItemStack stack = materials.get(i);
                if (stack.isEmpty()) continue;
                Object storageBe = storageBlockEntities.get(i);
                if (storageBe == null) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Storage block {} is null", i);
                    rollbackAll();
                    return false;
                }
                ItemStackHandler handler = rsi$tlmsGetHandler(storageBe);
                if (!level.isLoaded(storagePositions.get(i))) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-TLM] Storage chunk unloaded at {} — aborting material placement", storagePositions.get(i));
                    rollbackAll();
                    return false;
                }
                handler.setStackInSlot(0, stack.copy());
                slotsFilled[i] = true;
                Reflect.invoke(storageBe, "refresh");
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] Material placement failed:", e);
            rollbackAll();
            return false;
        }

        if (!invokeAltarCraft(level)) {
            rollbackAll();
            return false;
        }

        // altarCraft is synchronous — verify it consumed items
        if (!areAllHandlersEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] altarCraft did not consume items (shared path)");
            rollbackAll();
            return false;
        }
        this.craftEverConfirmed = true;

        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (!craftEverConfirmed) return false;
        // altarCraft is fully synchronous: addFreshEntity() has already run.
        // Return true immediately — no need to poll for ItemEntity existence.
        return true;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // Primary: capture the spawned ItemEntity for full NBT fidelity
        // (copyInput recipes copy NBT from ingredients into the output entity).
        BlockPos scanCenter = centrePos != null ? centrePos : myPos;
        BlockPos outputPos = scanCenter.above(2); // exactly where TLM spawns it
        List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(outputPos).inflate(2.0));
        for (ItemEntity entity : entities) {
            if (!entity.isRemoved()) {
                ItemStack stack = entity.getItem();
                if (!stack.isEmpty()) {
                    entity.discard();
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Collected ItemEntity: {} at {}",
                            stack, outputPos);
                    return stack.copy();
                }
            }
        }

        // Fallback: read output directly from recipe (guaranteed correct for non-copyInput recipes).
        if (recipe != null) {
            ItemStack result = ModRecipeIndex.tryGetResultItem(recipe, level.registryAccess());
            if (!result.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Falling back to recipe resultItem: {} (no ItemEntity found at {})",
                        result, outputPos);
                return result.copy();
            }
        }

        RSIntegrationMod.LOGGER.warn("[RSI-Batch-TLM] No output: no ItemEntity at {} and no recipe resultItem",
                outputPos);
        return ItemStack.EMPTY;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        rollbackAll();
        resetState();
        craftEverConfirmed = false;
        clearSlotsFilled();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearHandlers();
        resetState();
        craftEverConfirmed = false;
        clearSlotsFilled();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean checkPower(ServerPlayer player) {
        if (powerCapToken == null || recipe == null) return true;
        float cost = readPowerCost();
        if (cost <= 0) return true;

        Float current = readCurrentPower(player);
        if (current == null) {
            player.sendSystemMessage(Component.translatable("rsi.tlm.error.no_power"));
            return false;
        }

        if (current < cost) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Insufficient power: have {} need {}", current, cost);
            player.sendSystemMessage(Component.translatable(
                    "rsi.tlm.error.insufficient_power",
                    String.format("%.1f", current), String.format("%.1f", cost)));
            return false;
        }
        return true;
    }

    /** Read the P-point cost from the recipe. Returns 0 if not available. */
    private float readPowerCost() {
        if (recipe == null) return 0;
        try {
            Method getPowerCost = Reflect.findMethod(recipe.getClass(), "getPowerCost", new Class<?>[0]);
            if (getPowerCost == null) return 0;
            Object result = getPowerCost.invoke(recipe);
            if (result instanceof Float f) return f;
            if (result instanceof Double d) return d.floatValue();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] readPowerCost failed", e);
        }
        return 0;
    }

    /** Read current P-points from the player. Returns null if capability not available. */
    @Nullable
    private Float readCurrentPower(ServerPlayer player) {
        if (powerCapToken == null) return null;
        try {
            var capInstance = player.getCapability(
                    (net.minecraftforge.common.capabilities.Capability<?>) powerCapToken)
                    .resolve();
            if (capInstance.isEmpty()) return null;

            Class<?> powerCapClass = capInstance.get().getClass();
            Method getter = Reflect.findMethod(powerCapClass, "get", new Class<?>[0]);
            if (getter == null) return null;
            Object result = getter.invoke(capInstance.get());
            if (result instanceof Float f) return f;
            if (result instanceof Double d) return d.floatValue();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] readCurrentPower failed", e);
        }
        return null;
    }

    /** Check all storage block handlers are empty before starting a craft. */
    private boolean checkAllHandlersEmpty(ServerPlayer player) {
        try {
            for (int i = 0; i < storageBlockEntities.size(); i++) {
                Object storageBe = storageBlockEntities.get(i);
                if (storageBe == null) continue;
                ItemStackHandler handler = rsi$tlmsGetHandler(storageBe);
                if (!handler.getStackInSlot(0).isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Storage block {} not empty", i);
                    return false;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Handler empty check failed", e);
            return false;
        }
        return true;
    }

    /** Check if all storage block handlers are empty (no player notification). */
    private boolean areAllHandlersEmpty() {
        try {
            for (int i = 0; i < storageBlockEntities.size(); i++) {
                Object storageBe = storageBlockEntities.get(i);
                if (storageBe == null) continue;
                ItemStackHandler handler = rsi$tlmsGetHandler(storageBe);
                if (!handler.getStackInSlot(0).isEmpty()) return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] areAllHandlersEmpty check failed", e);
            return false;
        }
        return true;
    }

    /** Invoke altarCraft via reflection. Returns true if no exception thrown. */
    private boolean invokeAltarCraft(ServerLevel level) {
        try {
            Method altarCraftMethod = Reflect.findMethod(blockAltarClass, "altarCraft",
                    new Class<?>[]{Level.class, altarBEClass, net.minecraft.world.entity.player.Player.class});
            if (altarCraftMethod == null) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] altarCraft method not found");
                return false;
            }
            altarCraftMethod.invoke(blockAltar, level, altar, player);
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] altarCraft failed: {}", root.toString());
            return false;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-TLM] altarCraft invocation failed:", e);
            return false;
        }
    }

    /** Clear items from handlers we filled, return them to RS network. */
    private void rollbackAll() {
        clearHandlers();
        // Entity output cleanup: skip item recovery when using shared committed
        // ledger — refund is centralized via ledger.refundCommitted().
        if (usingSharedLedger) return;
        // Also clear any output entity near the altar (match expected output)
        try {
            ServerLevel level = player != null ? player.serverLevel() : null;
            if (level != null && myPos != null) {
                ItemStack expected = ItemStack.EMPTY;
                if (recipe != null && level != null) {
                    expected = ModRecipeIndex.tryGetResultItem(recipe, level.registryAccess());
                }
                for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class,
                        new net.minecraft.world.phys.AABB(myPos).inflate(2.5))) {
                    if (entity.isRemoved()) continue;
                    ItemStack stack = entity.getItem();
                    if (stack.isEmpty()) continue;
                    // Only recover entities that match the expected recipe output
                    if (!expected.isEmpty() && !ItemStack.isSameItemSameTags(stack, expected)) {
                        continue;
                    }
                    if (network != null) {
                        ItemStack leftover = network.insertItem(stack, stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        if (!leftover.isEmpty()) {
                            ItemHandlerHelper.giveItemToPlayer(player, leftover);
                        }
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                    entity.discard();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Output cleanup failed", e);
        }
    }

    /** Clear items from storage block handlers. When using shared committed
     *  ledger, items are template copies — clear slots without returning. */
    private void clearHandlers() {
        if (storageBlockEntities == null || slotsFilled == null) return;
        try {
            for (int i = 0; i < storageBlockEntities.size(); i++) {
                if (!slotsFilled[i]) continue;
                Object storageBe = storageBlockEntities.get(i);
                if (storageBe == null) continue;
                ItemStackHandler handler = rsi$tlmsGetHandler(storageBe);
                ItemStack stack = handler.getStackInSlot(0);
                if (!stack.isEmpty()) {
                    if (!usingSharedLedger) {
                        if (network != null) {
                            ItemStack leftover = network.insertItem(stack, stack.getCount(),
                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                            if (!leftover.isEmpty()) {
                                ItemHandlerHelper.giveItemToPlayer(player, leftover);
                            }
                        } else if (player != null) {
                            ItemHandlerHelper.giveItemToPlayer(player, stack);
                        }
                    }
                    if (player.serverLevel().isLoaded(storagePositions.get(i))) {
                        handler.setStackInSlot(0, ItemStack.EMPTY);
                        Reflect.invoke(storageBe, "refresh");
                    } else {
                        RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Skipping clear — chunk unloaded at {}", storagePositions.get(i));
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-TLM] Clear handlers failed", e);
        }
    }

    private void clearSlotsFilled() {
        if (slotsFilled != null) {
            for (int i = 0; i < slotsFilled.length; i++) slotsFilled[i] = false;
        }
    }

    // ── Reflection helpers ───────────────────────────────────────

    private static ItemStackHandler rsi$tlmsGetHandler(Object storageBe)
            throws NoSuchFieldException, IllegalAccessException {
        java.lang.reflect.Field f = altarBEClass.getDeclaredField("handler");
        f.setAccessible(true);
        return (ItemStackHandler) f.get(storageBe);
    }
}
