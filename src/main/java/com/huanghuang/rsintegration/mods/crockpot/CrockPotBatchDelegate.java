package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.MachineSlotOwnershipPolicy;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import static com.huanghuang.rsintegration.mods.crockpot.CrockPotInventory.getBlockPotLevel;
import static com.huanghuang.rsintegration.mods.crockpot.CrockPotInventory.getItemHandler;

/** Batch delegate for Crock Pot cooking recipes (all pot levels). */
public final class CrockPotBatchDelegate extends AbstractBatchDelegate {

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // Each pot owns its input/output slots; the chain reserves one recipe
        // operation per worker before any physical insertion occurs.
        return BatchConcurrencyCapabilities.machineSlot();
    }

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;
    private int potLevel;        // block's actual pot level (input slot count)
    private int recipeMinLevel;  // recipe's minimum required pot level
    private ItemStack suppliedFuelType = ItemStack.EMPTY;
    private int suppliedFuelCount;
    private ItemStack baselineFuel = ItemStack.EMPTY;
    private ItemStack[] suppliedInputTypes = new ItemStack[0];
    private int[] suppliedInputCounts = new int[0];
    private boolean inventoryLease;

    // Food-value category constraint state
    private boolean hasCatConstraints;
    private final float[] catMins = new float[CrockPotRecipeHandler.CAT_COUNT];
    private final float[] catMaxs = new float[CrockPotRecipeHandler.CAT_COUNT];

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myLevel = level;
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, myDim, myPos).orElse(null));
        }

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.suppliedFuelType = ItemStack.EMPTY;
        this.suppliedFuelCount = 0;
        this.recipeMinLevel = CrockPotRecipeHandler.getPotLevel(found);
        this.potLevel = getBlockPotLevel(level, pos);
        if (potLevel <= 0) this.potLevel = recipeMinLevel;
        resetInventoryOwnership();

        if (potLevel < recipeMinLevel) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.crockpot.error.pot_level_too_low", recipeMinLevel, potLevel));
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Block potLevel={} < recipeMinLevel={} for {}",
                    potLevel, recipeMinLevel, recipeId);
            return false;
        }

        BlockEntity be = level.getBlockEntity(pos);
        IItemHandler inventory = be != null && CrockPotReflection.crockPotBEClass.isInstance(be)
                ? getItemHandler(be) : null;
        if (inventory == null || inventory.getSlots() < potLevel + 2) return false;
        for (int slot = 0; slot < potLevel; slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) return false;
        }

        // Parse category constraints
        this.hasCatConstraints = CrockPotRecipeHandler.hasCategoryConstraints(found);
        if (hasCatConstraints) {
            float[][] constraints = CrockPotRecipeHandler.parseCategoryConstraints(found);
            System.arraycopy(constraints[0], 0, catMins, 0, CrockPotRecipeHandler.CAT_COUNT);
            System.arraycopy(constraints[1], 0, catMaxs, 0, CrockPotRecipeHandler.CAT_COUNT);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] validateAndInit OK: recipe={} blockPotLevel={} recipeMinLevel={} hasCatConstraints={}",
                recipeId, potLevel, recipeMinLevel, hasCatConstraints);
        return true;
    }

    public boolean usesPlannedCategoryMaterials() {
        return hasCatConstraints;
    }

    private CrockPotCategoryPlanning.Context categoryContext() {
        return CrockPotCategoryPlanning.capture(materialAccess(), recipe, null, storageEndpoint(), player, myLevel, potLevel);
    }

    @Override
    public MaterialPlan materialPlan() {
        return MaterialPlan.graphSlots(recipeSlots());
    }

    List<IngredientSpec> recipeSlots() {
        if (hasCatConstraints) {
            return CrockPotCategoryPlanning.ingredients(categoryContext());
        }

        var handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, myDim, myPos).orElse(null));
        }
        if (!hasStorageAccess()) return false;

        try (ExtractionLedger ledger = materialAccess().newLedger()) {
            ledger.setStorageEndpoint(storageEndpoint());
            List<ItemStack> materials = new ArrayList<>();

            if (hasCatConstraints) {
                // 固定原料允许补产；补位按规划选定的库存身份预留。
                var plan = CrockPotCategoryPlanning.solve(categoryContext());
                if (plan == null) {
                    player.sendSystemMessage(Component.translatable("rsi.crockpot.error.food_values"));
                    String blocked = buildBlockedCategoriesMessage();
                    if (!blocked.isEmpty()) {
                        player.sendSystemMessage(Component.literal(blocked));
                    }
                    return false; // ledger auto-rollback via close()
                }
                for (IngredientSpec spec : plan.fixed()) {
                    if (spec.isEmpty()) continue;
                    ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                            player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                    if (reserved.isEmpty()) return false; // ledger auto-rollback via close()
                    materials.add(reserved.copy());
                }
                for (ItemStack want : plan.filler()) {
                    var endpoint = storageEndpoint();
                    ItemStack reserved = endpoint == null ? ItemStack.EMPTY
                            : ledger.reserveFromEndpoint(
                                    CrockPotCategoryPlanning.ingredient(want), 1, endpoint, player);
                    if (reserved.isEmpty()) return false; // network changed, auto-rollback
                    materials.add(reserved.copy());
                }
            } else {
                // Non-category recipes: fixed ingredients (padded with config filler).
                List<IngredientSpec> specs = getIngredients();
                if (specs != null) {
                    for (IngredientSpec spec : specs) {
                        if (spec.isEmpty()) continue;
                        ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                                player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                        if (reserved.isEmpty()) return false; // ledger auto-rollback via close()
                        materials.add(reserved.copy());
                    }
                }
            }

            // All reservations done — commit (performs actual extraction)
            if (!ledger.commitToEndpoint(storageEndpoint(), player)) return false;

            this.usingSharedLedger = false;
            if (!startPreparedOperationImpl(player, materials, ledger, false)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        insertIntoStorage(player, mat, false);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        return startPreparedOperationImpl(player, materials, sharedLedger, true);
    }

    private boolean startPreparedOperationImpl(ServerPlayer player, List<ItemStack> materials,
                                              ExtractionLedger sharedLedger,
                                              boolean shared) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = shared;
        this.craftDone = false;

        long requestedSlots = materials.stream()
                .filter(stack -> !stack.isEmpty())
                .mapToLong(ItemStack::getCount)
                .sum();
        if (requestedSlots != potLevel) return false;

        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!CrockPotReflection.crockPotBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Wrong BE type: {}", be.getClass().getName());
            return false;
        }

        IItemHandler itemHandler = getItemHandler(be);
        int expectedSlots = potLevel + 2; // input + fuel + output
        if (itemHandler == null || itemHandler.getSlots() < expectedSlots) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Item handler too small: {} < {}",
                    itemHandler != null ? itemHandler.getSlots() : 0, expectedSlots);
            return false;
        }
        if (!hasStorageAccess() || !acquireInventory(itemHandler, be)) return false;

        int simulatedSlot = 0;
        for (ItemStack material : materials) {
            for (int i = 0; i < material.getCount(); i++) {
                ItemStack single = material.copyWithCount(1);
                if (!itemHandler.insertItem(simulatedSlot++, single, true).isEmpty()) {
                    resetInventoryOwnership();
                    return false;
                }
            }
        }

        forceChunkLoad(true);

        // Place materials into input slots — each item occupies one slot
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            int count = mat.getCount();
            for (int i = 0; i < count; i++) {
                if (slot >= potLevel) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Too many items for potLevel={}", potLevel);
                    break;
                }
                ItemStack single = mat.copyWithCount(1);
                ItemStack remainder = itemHandler.insertItem(slot, single, false);
                if (!remainder.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Failed to insert into slot {}: {}", slot,
                            remainder);
                    return rollbackRejectedStart(itemHandler, be);
                }
                suppliedInputTypes[slot] = single.copyWithCount(1);
                suppliedInputCounts[slot]++;
                slot++;
            }
        }
        be.setChanged();

        // Fuel is outside the material ledger. Fill the slot so short-burning
        // fallback fuels cannot strand an otherwise valid asynchronous craft.
        boolean fuelReady = topUpFuel(itemHandler);
        if (!fuelReady && !isBurning(be)) {
            player.sendSystemMessage(Component.translatable("rsi.crockpot.no_fuel"));
            return rollbackRejectedStart(itemHandler, be);
        }
        be.setChanged();
        markCraftStarted();

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] Materials inserted, cooking should start next tick");
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!CrockPotReflection.crockPotBEClass.isInstance(be))
            return false;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return false;

        int outputSlot = potLevel + 1;
        ItemStack output = itemHandler.getStackInSlot(outputSlot);
        if (output.isEmpty()) return false;

        ExpectedProduction expected = getExpectedProduction();
        return expected != null
                && ItemStack.isSameItem(output, expected.item())
                && output.getCount() >= expected.count();
    }

    @NotNull
    @Override
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        IItemHandler observedHandler = getItemHandler(be);
        if (observedHandler != null) {
            ItemStack visibleOutput = observedHandler.getStackInSlot(potLevel + 1);
            if (!visibleOutput.isEmpty() && !isExpectedOutput(visibleOutput)) {
                return failObservation("Crock Pot output slot contains another item");
            }
        }
        if (isMachineCraftFinished(level, be)) return doneObservation();

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null || itemHandler.getSlots() < potLevel + 2) {
            return failObservation("Crock Pot item handler unavailable");
        }
        if (!isBurning(be)) {
            if (topUpFuel(itemHandler)) {
                be.setChanged();
            } else {
                warnOnce("fuel-empty", "[RSI-Batch-CrockPot] Waiting for fuel at {}", myPos);
            }
        }
        return workingObservation();
    }

    @Override public OperationOutputSource outputSource() {
        return OperationOutputSource.single(this::takeResult, outputContract());
    }

    private ItemStack takeResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack visible = itemHandler.getStackInSlot(potLevel + 1);
        if (!isExpectedOutput(visible)) return ItemStack.EMPTY;
        ExpectedProduction expected = getExpectedProduction();
        int amount = expected == null ? visible.getCount()
                : Math.min(visible.getCount(), expected.count());
        ItemStack result = itemHandler.extractItem(potLevel + 1, amount, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        recordFailureRecoveredInputs(clearMachineSlotsAndRefund(true));
        forceChunkLoad(false);
        craftDone = false;
        resetInventoryOwnership();
    }

    @Override
    public void cleanup(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        forceChunkLoad(false);
        clearMachineSlotsAndRefund(false);
        craftDone = false;
        resetInventoryOwnership();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    /** Build a message listing categories whose max=0 block all available items. */
    private String buildBlockedCategoriesMessage() {
        String[] names = {"MEAT", "MONSTER", "FISH", "EGG", "FRUIT",
                "VEGGIE", "DAIRY", "SWEETENER", "FROZEN", "INEDIBLE"};
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < CrockPotRecipeHandler.CAT_COUNT; c++) {
            if (catMaxs[c] <= 0.001f) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(names[c]).append("=0");
            }
        }
        if (sb.length() == 0) return "";
        return "§7Blocked (max=0): " + sb;
    }

    // ── ingredient extraction (no filler for category recipes) ───

    private List<IngredientSpec> getIngredients() {
        // For category-constraint recipes, only extract the actual required
        // ingredients — remaining slots are filled by food-value selection.
        // For non-category recipes, include filler items as usual, padding to
        // the block's REAL input-slot count (this.potLevel, set from the block
        // in validateAndInit) — the pot won't cook until every input slot is
        // filled. Falls back to the fixed slot count if potLevel is unset.
        boolean pad = !hasCatConstraints;
        int targetSlots = potLevel > 0 ? potLevel : CrockPotRecipeHandler.INPUT_SLOT_COUNT;
        return CrockPotRecipeHandler.getSpecificIngredients(recipe, pad, targetSlots);
    }

    // ── plan warnings ────────────────────────────────────────────

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Long> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Long> neededCounts,
                                       long requiredBurnUnits) {
        if (!"crockpot".equals(recipeModTypeId)) return;

        // Match recursive-plan fuel selection to runtime auto-refueling.
        Item preferred = null;
        for (String id : RSIntegrationConfig.CROCKPOT_FUEL_PRIORITY.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item != null && item != Items.AIR && itemAvailable.getOrDefault(item, 0L) > 0
                    && ForgeHooks.getBurnTime(new ItemStack(item), null) > 0) {
                preferred = item;
                break;
            }
        }
        if (preferred != null) {
            int burnTime = Math.max(1,
                    ForgeHooks.getBurnTime(new ItemStack(preferred), null));
            int fuelNeeded = (int) Math.min(Integer.MAX_VALUE,
                    InputConsumption.fuel(1L).requiredPhysicalAmount(
                            requiredBurnUnits, 1L, burnTime));
            neededCounts.merge(preferred, (long) fuelNeeded, SaturatingLongMath::signedAdd);
            itemSource.putIfAbsent(preferred, Ingredient.of(preferred));
            return;
        }
        CraftPacketUtils.addFuelToMaterials(
                itemAvailable, itemSource, neededCounts, requiredBurnUnits);
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        int blockPotLevel = getBlockPotLevel(level, pos);
        // Fall back to the fixed input-slot count (not the recipe's potLevel,
        // which is only the pot-tier gate) when no specific machine is selected.
        if (blockPotLevel <= 0) blockPotLevel = CrockPotRecipeHandler.INPUT_SLOT_COUNT;
        int slotReqs = CrockPotRecipeHandler.countSlotRequirements(recipe);
        int remaining = blockPotLevel - slotReqs;

        if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
            float[][] constraints = CrockPotRecipeHandler.parseCategoryConstraints(recipe);
            float[] mins = constraints[0];
            float[] maxs = constraints[1];

            if (remaining > 0) {
                warnings.add(Component.translatable("rsi.crockpot.food_value_filler",
                        remaining));
            }

            for (int c = 0; c < mins.length; c++) {
                if (mins[c] > 0) {
                    String catName = getCategoryName(c);
                    warnings.add(Component.translatable("rsi.crockpot.cat_min",
                            catName, String.format("%.1f", mins[c])));
                }
            }
            for (int c = 0; c < maxs.length; c++) {
                if (maxs[c] < Float.MAX_VALUE) {
                    String catName = getCategoryName(c);
                    warnings.add(Component.translatable("rsi.crockpot.cat_max",
                            catName, String.format("%.1f", maxs[c])));
                }
            }
        } else if (remaining > 0) {
            String fillerId = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
            warnings.add(Component.translatable("rsi.crockpot.filler_needed",
                    remaining, fillerId));
        }

        warnings.add(Component.translatable("rsi.crockpot.fuel_warning"));

        return warnings;
    }

    private static String getCategoryName(int ordinal) {
        // Names match FoodCategory enum: MEAT, MONSTER, FISH, EGG, FRUIT,
        // VEGGIE, DAIRY, SWEETENER, FROZEN, INEDIBLE
        String[] names = {"MEAT", "MONSTER", "FISH", "EGG", "FRUIT",
                "VEGGIE", "DAIRY", "SWEETENER", "FROZEN", "INEDIBLE"};
        return ordinal >= 0 && ordinal < names.length ? names[ordinal] : "?";
    }

    private static boolean isBurning(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isBurning");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isFuel(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return ForgeHooks.getBurnTime(stack, null) > 0;
    }

    private boolean topUpFuel(IItemHandler handler) {
        int fuelSlot = potLevel;
        ItemStack current = handler.getStackInSlot(fuelSlot);
        if (!current.isEmpty() && !isFuel(current)) return false;

        if (current.isEmpty() && !suppliedFuelType.isEmpty()) {
            suppliedFuelType = ItemStack.EMPTY;
            suppliedFuelCount = 0;
        }
        if (!hasStorageAccess()) return isFuel(current);

        ItemStack fuelType = current.isEmpty() ? selectFuelFromStorage() : current.copyWithCount(1);
        if (fuelType.isEmpty()) return isFuel(current);

        if (!suppliedFuelType.isEmpty()
                && !ItemStack.isSameItemSameTags(suppliedFuelType, fuelType)) {
            suppliedFuelType = ItemStack.EMPTY;
            suppliedFuelCount = 0;
        }

        int room = CrockPotFuelPolicy.insertionRoom(
                current, fuelType, handler.getSlotLimit(fuelSlot));
        if (room <= 0) return isFuel(current);

        int burnTime = Math.max(1, ForgeHooks.getBurnTime(fuelType, null));
        long requiredBurnUnits = CraftPacketUtils.estimatedFuelBurnUnits(recipe, 1);
        int required = (int) Math.min(Integer.MAX_VALUE,
                InputConsumption.fuel(1L).requiredPhysicalAmount(
                        requiredBurnUnits, 1L, burnTime));
        int missing = Math.max(0, required - current.getCount());
        if (missing <= 0) return isFuel(current);
        room = Math.min(room, missing);

        ItemStack extracted = extractExactFromStorage(player, fuelType.copyWithCount(1), room, false);
        if (extracted.isEmpty()) return isFuel(current);

        ItemStack remainder = handler.insertItem(fuelSlot, extracted, false);
        int inserted = extracted.getCount() - remainder.getCount();
        if (!remainder.isEmpty()) {
            refundMaterial(player, remainder);
        }
        if (inserted > 0) {
            if (suppliedFuelType.isEmpty()) {
                suppliedFuelType = extracted.copyWithCount(1);
            }
            suppliedFuelCount += inserted;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Batch-CrockPot] Supplied {} fuel item(s) at {}", inserted, myPos);
        }
        return isFuel(handler.getStackInSlot(fuelSlot));
    }

    private ItemStack selectFuelFromStorage() {
        List<ItemStack> candidates = new ArrayList<>();
        var endpoint = storageEndpoint();
        if (endpoint == null) return ItemStack.EMPTY;
        var snapshot = materialAccess().snapshot(player, endpoint).snapshot().orElse(null);
        if (snapshot == null) return ItemStack.EMPTY;
        for (var entry : snapshot.items()) {
            candidates.add(entry.stack());
        }
        ItemStack selected = CrockPotFuelPolicy.select(
                candidates, RSIntegrationConfig.CROCKPOT_FUEL_PRIORITY.get(),
                stack -> ForgeHooks.getBurnTime(stack, null));
        return selected != null ? selected : ItemStack.EMPTY;
    }

    private List<ItemStack> clearMachineSlotsAndRefund(boolean failureCleanup) {
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (!myLevel.hasChunkAt(myPos)) return recoveredInputs;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return recoveredInputs;
        if (!CrockPotReflection.crockPotBEClass.isInstance(be))
            return recoveredInputs;

        IItemHandler handler = getItemHandler(be);
        if (handler == null || handler.getSlots() < potLevel + 2) return recoveredInputs;

        if (!inventoryLease) return recoveredInputs;
        for (int slot = 0; slot < potLevel; slot++) {
            ItemStack current = handler.getStackInSlot(slot);
            int removable = MachineSlotOwnershipPolicy.removableAddedCount(
                    ItemStack.EMPTY, suppliedInputTypes[slot], suppliedInputCounts[slot], current);
            if (removable <= 0) continue;
            ItemStack s = handler.extractItem(slot, removable, false);
            if (!s.isEmpty()) recoveredInputs.add(s.copy());
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        ItemStack visibleOut = handler.getStackInSlot(potLevel + 1);
        if (!failureCleanup && !visibleOut.isEmpty() && isExpectedOutput(visibleOut)) {
            ItemStack out = handler.extractItem(potLevel + 1, visibleOut.getCount(), false);
            if (!out.isEmpty() && !usingSharedLedger) refundToRSNetwork(out);
        }
        refundSuppliedFuel(handler);
        be.setChanged();
        return recoveredInputs;
    }

    private boolean rollbackRejectedStart(IItemHandler handler, BlockEntity be) {
        clearMachineSlotsAndRefund(false);
        be.setChanged();
        forceChunkLoad(false);
        resetInventoryOwnership();
        return false;
    }

    private void refundSuppliedFuel(IItemHandler handler) {
        ItemStack current = handler.getStackInSlot(potLevel);
        int refundable = MachineSlotOwnershipPolicy.removableAddedCount(
                baselineFuel, suppliedFuelType, suppliedFuelCount, current);
        if (refundable > 0) {
            ItemStack refund = handler.extractItem(potLevel, refundable, false);
            if (!refund.isEmpty()) {
                ItemStack remainder = refundMaterial(player, refund);
                if (!remainder.isEmpty()) refundToRSNetwork(remainder);
            }
        }
        suppliedFuelType = ItemStack.EMPTY;
        suppliedFuelCount = 0;
    }

    private boolean acquireInventory(IItemHandler handler, BlockEntity be) {
        if (inventoryLease) return false;
        for (int slot = 0; slot < potLevel; slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) return false;
        }
        ItemStack output = handler.getStackInSlot(potLevel + 1);
        if (!output.isEmpty()) {
            ItemStack removed = handler.extractItem(potLevel + 1, output.getCount(), false);
            if (removed.isEmpty()) return false;
            refundToRSNetwork(removed);
            be.setChanged();
        }
        if (!handler.getStackInSlot(potLevel + 1).isEmpty()) return false;
        baselineFuel = handler.getStackInSlot(potLevel).copy();
        suppliedInputTypes = new ItemStack[potLevel];
        suppliedInputCounts = new int[potLevel];
        Arrays.fill(suppliedInputTypes, ItemStack.EMPTY);
        suppliedFuelType = ItemStack.EMPTY;
        suppliedFuelCount = 0;
        inventoryLease = true;
        return true;
    }

    private boolean isExpectedOutput(ItemStack stack) {
        ExpectedProduction expected = getExpectedProduction();
        return expected != null && IBatchDelegate.matchesProducedItem(stack, expected.item());
    }

    private void resetInventoryOwnership() {
        inventoryLease = false;
        baselineFuel = ItemStack.EMPTY;
        suppliedFuelType = ItemStack.EMPTY;
        suppliedFuelCount = 0;
        suppliedInputTypes = new ItemStack[0];
        suppliedInputCounts = new int[0];
    }

    private void refundToRSNetwork(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }
}
