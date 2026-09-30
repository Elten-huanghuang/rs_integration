package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BiomancyBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private Recipe<?> recipe;
    private boolean sharedMaterials;
    private final List<PlacedInput> placedInputs = new ArrayList<>();
    private final List<ItemStack> unplacedInputs = new ArrayList<>();
    private final List<ItemStack> injectedFuel = new ArrayList<>();
    private List<ItemStack> bioForgeRecoveredInputs = List.of();
    private boolean bioForgeStarted;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.pos = pos;
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null || !resolved.hasChunkAt(pos)) return false;
        BlockEntity machine = resolved.getBlockEntity(pos);
        Recipe<?> found = BiomancyDigestingRecipeResolver.resolve(resolved, recipeId);
        if (machine == null || found == null || !found.getClass().getName().startsWith(
                "com.github.elenterius.biomancy.crafting.recipe.")) return false;
        if (!machineMatchesRecipe(machine, found)) return false;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.recipe = found;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        this.bioForgeStarted = false;
        this.bioForgeRecoveredInputs = List.of();
        this.unplacedInputs.clear();
        this.placedInputs.clear();
        this.injectedFuel.clear();
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : ModRecipeHandlers.handlerFor(recipe) == null
                ? null : ModRecipeHandlers.handlerFor(recipe).getIngredients(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;
        if (storageEndpoint() == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        }
        if (network == null && !hasStorageAccess()) return false;

        List<ItemStack> inputs = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            ledger.setStorageEndpoint(storageEndpoint());
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, dimension, pos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) return false;
                inputs.add(reserved.copy());
            }
            if (!ledger.commit(network, player)) return false;
            if (!ensureNutrients(player)) {
                for (ItemStack input : inputs) insertIntoStorage(player, input, false);
                return false;
            }
            sharedMaterials = false;
            if (isBioForge()) return startBioForge(player, inputs);
            return placeInputs(player, inputs);
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.sharedMaterials = true;
        if (isBioForge()) {
            bioForgeRecoveredInputs = materials.stream().filter(stack -> stack != null && !stack.isEmpty())
                    .map(ItemStack::copy).toList();
        } else {
            rememberUnplacedInputs(materials);
        }
        if (sharedLedger.storageEndpoint() != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        if (storageEndpoint() == null) network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        if (!ensureNutrients(player)) return false;
        if (isBioForge()) return startBioForge(player, materials);
        return placeInputs(player, materials);
    }

    private boolean isBioForge() {
        return ModIds.ID_BIOMANCY_BIO_FORGE.equals(typeForRecipe(recipe));
    }

    private boolean startBioForge(ServerPlayer player, List<ItemStack> materials) {
        if (level == null || recipe == null || materials == null || materials.isEmpty()
                || !level.hasChunkAt(pos)) return false;
        BlockEntity forge = level.getBlockEntity(pos);
        if (forge == null) return false;

        bioForgeRecoveredInputs = materials.stream().filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::copy).toList();
        try (BiomancyBioForgeInventory inventory = BiomancyBioForgeInventory.open(
                player.getInventory().items, materials)) {
            if (inventory == null) {
                return false;
            }
            AbstractContainerMenu menu = null;
            try {
                menu = createBioForgeMenu(forge, player);
                if (menu == null || !selectBioForgeRecipe(menu, player)) return false;
                ItemStack taken = takeBioForgeResult(menu, player);
                if (taken.isEmpty()) return false;
                results.add(taken.copy());
                results.addAll(inventory.remainingInputs());
                bioForgeStarted = true;
                markCraftStarted();
                return true;
            } finally {
                bioForgeRecoveredInputs = bioForgeStarted ? List.of() : inventory.remainingInputs();
                if (menu != null) menu.removed(player);
            }
        } finally {
            if (!bioForgeStarted) refundBioForgeInputs(player);
        }
    }

    @Nullable
    private AbstractContainerMenu createBioForgeMenu(BlockEntity forge, ServerPlayer player) {
        try {
            Class<?> menuClass = Class.forName("com.github.elenterius.biomancy.menu.BioForgeMenu");
            for (Method method : menuClass.getMethods()) {
                if (!method.getName().equals("createServerMenu") || method.getParameterCount() != 3) continue;
                Object menu = method.invoke(null, -1, player.getInventory(), forge);
                return menu instanceof AbstractContainerMenu container ? container : null;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private boolean selectBioForgeRecipe(Object menu, ServerPlayer player) {
        return invokeVoid(menu, "setSelectedRecipe", recipe, player);
    }

    static ItemStack takeBioForgeResult(AbstractContainerMenu menu, ServerPlayer player) {
        Slot result = menu.getSlot(37);
        ItemStack displayed = result.getItem();
        return displayed.isEmpty() ? ItemStack.EMPTY
                : result.safeTake(displayed.getCount(), displayed.getCount(), player);
    }

    private final List<ItemStack> results = new ArrayList<>();

    private void refundBioForgeInputs(ServerPlayer player) {
        if (!sharedMaterials) {
            for (ItemStack stack : bioForgeRecoveredInputs) insertIntoStorage(player, stack, false);
        }
        recordFailureRecoveredInputs(bioForgeRecoveredInputs);
    }

    private boolean placeInputs(ServerPlayer player, List<ItemStack> materials) {
        rememberUnplacedInputs(materials);
        if (level == null || !level.hasChunkAt(pos)) return false;
        BlockEntity machine = level.getBlockEntity(pos);
        Object inventory = invoke(machine, "getInputInventory");
        int slots = number(invoke(inventory, "getSlots"), 0);
        if (machine == null || inventory == null || slots == 0) return false;
        placedInputs.clear();
        if (ModIds.ID_BIOMANCY_BIO_LAB.equals(typeForRecipe(recipe))) {
            Object reactant = invoke(recipe, "getReactant");
            List<IngredientSpec> specs = getRequiredMaterials();
            if (!(inventory instanceof IItemHandler input) || !(reactant instanceof Ingredient ingredient)
                    || specs == null || !placeBioLabInputs(input, specs, ingredient, materials)) return false;
            machine.setChanged();
            markCraftStarted();
            this.player = player;
            return true;
        }
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) continue;
            boolean inserted = false;
            for (int slot = 0; slot < slots; slot++) {
                ItemStack existing = stack(invoke(inventory, "getStackInSlot", slot));
                if (!existing.isEmpty()) continue;
                Object valid = invoke(inventory, "isItemValid", slot, material);
                if (valid instanceof Boolean accepted && !accepted) continue;
                ItemStack remainder = stack(invoke(inventory, "insertItem", slot,
                        material.copy(), true));
                if (!remainder.isEmpty()) continue;
                remainder = stack(invoke(inventory, "insertItem", slot, material.copy(), false));
                int insertedCount = material.getCount() - remainder.getCount();
                if (insertedCount > 0) {
                    placedInputs.add(new PlacedInput(slot, material.copyWithCount(insertedCount)));
                    removeUnplacedInput(material, insertedCount);
                }
                if (!remainder.isEmpty()) {
                    rollbackInputs(inventory);
                    return false;
                }
                inserted = true;
                break;
            }
            if (!inserted) {
                rollbackInputs(inventory);
                return false;
            }
        }
        machine.setChanged();
        markCraftStarted();
        this.player = player;
        return true;
    }

    boolean placeBioLabInputs(IItemHandler inventory, List<IngredientSpec> specs,
                             Ingredient reactant, List<ItemStack> materials) {
        rememberUnplacedInputs(materials);
        List<BiomancyBioLabInputLayout.SlotInput> inputs = BiomancyBioLabInputLayout.plan(
                specs, reactant, materials);
        if (inputs == null || inventory.getSlots() != BiomancyBioLabInputLayout.INPUT_SLOTS) return false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) return false;
        }
        for (BiomancyBioLabInputLayout.SlotInput input : inputs) {
            if (!inventory.isItemValid(input.slot(), input.stack())
                    || !inventory.insertItem(input.slot(), input.stack().copy(), true).isEmpty()) return false;
        }
        for (BiomancyBioLabInputLayout.SlotInput input : inputs) {
            ItemStack remainder = inventory.insertItem(input.slot(), input.stack().copy(), false);
            int inserted = input.stack().getCount() - remainder.getCount();
            if (inserted > 0) {
                placedInputs.add(new PlacedInput(input.slot(), input.stack().copyWithCount(inserted)));
                removeUnplacedInput(input.stack(), inserted);
            }
            if (!remainder.isEmpty()) {
                rollbackInputs(inventory);
                return false;
            }
        }
        return true;
    }

    private void rememberUnplacedInputs(List<ItemStack> materials) {
        unplacedInputs.clear();
        for (ItemStack material : materials) {
            if (material != null && !material.isEmpty()) unplacedInputs.add(material.copy());
        }
    }

    private void removeUnplacedInput(ItemStack material, int count) {
        for (ItemStack unplaced : unplacedInputs) {
            if (!ItemStack.isSameItemSameTags(unplaced, material)) continue;
            int removed = Math.min(count, unplaced.getCount());
            unplaced.shrink(removed);
            count -= removed;
            if (count == 0) return;
        }
    }

    @Override
    @NotNull
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level,
                                                    @NotNull BlockEntity machine) {
        if (isBioForge() && bioForgeStarted) return doneObservation();
        if (!isBioForge() && isMachineCraftFinished(level, machine)) {
            return super.observeMachineCraft(level, machine);
        }
        if (!ensureNutrients(player)) {
            return failObservation("Biomancy machine ran out of valid nutrient fuel");
        }
        return super.observeMachineCraft(level, machine);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity machine) {
        if (isBioForge()) return bioForgeStarted;
        Object input = invoke(machine, "getInputInventory");
        Object output = invoke(machine, "getOutputInventory");
        return allSlotsEmpty(input) && (hasAnyStack(output)
                || ModIds.ID_BIOMANCY_DECOMPOSER.equals(typeForRecipe(recipe)));
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        if (isBioForge()) {
            List<ItemStack> output = List.copyOf(results);
            results.clear();
            bioForgeRecoveredInputs = List.of();
            return output;
        }
        if (level == null || !level.hasChunkAt(pos)) return List.of();
        BlockEntity machine = level.getBlockEntity(pos);
        Object output = invoke(machine, "getOutputInventory");
        int slots = number(invoke(output, "getSlots"), 0);
        if (output == null || slots == 0) return List.of();
        List<ItemStack> results = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack current = stack(invoke(output, "getStackInSlot", slot));
            if (current.isEmpty()) continue;
            ItemStack extracted = stack(invoke(output, "extractItem", slot,
                    current.getCount(), false));
            if (!extracted.isEmpty()) results.add(extracted);
        }
        if (!results.isEmpty()) machine.setChanged();
        return results;
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    protected void clearMachineState(BlockEntity machine, ServerPlayer player) {
        if (isBioForge()) {
            recordFailureRecoveredInputs(bioForgeRecoveredInputs);
            recoverInjectedFuel(machine, player);
            bioForgeStarted = false;
            results.clear();
            machine.setChanged();
            resetState();
            return;
        }
        Object inventory = invoke(machine, "getInputInventory");
        List<ItemStack> recovered = new ArrayList<>(unplacedInputs);
        recovered.addAll(removePlacedInputs(inventory));
        recordFailureRecoveredInputs(recovered);
        if (!sharedMaterials) {
            for (ItemStack input : recovered) insertIntoStorage(player, input, false);
        }
        recoverInjectedFuel(machine, player);
        machine.setChanged();
        placedInputs.clear();
        unplacedInputs.clear();
        resetState();
    }

    @Override
    protected boolean isFailureRefundSafe() {
        return false;
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        placedInputs.clear();
        unplacedInputs.clear();
        injectedFuel.clear();
        results.clear();
        bioForgeRecoveredInputs = List.of();
        bioForgeStarted = false;
        resetState();
    }

    private boolean ensureNutrients(@Nullable ServerPlayer player) {
        if (player == null || level == null || recipe == null || !level.hasChunkAt(pos)) return false;
        BlockEntity machine = level.getBlockEntity(pos);
        if (machine == null) return false;
        int required = nutrientCost(machine);
        Object handler = fuelHandler(machine);
        int capacity = number(invoke(handler, "getMaxFuelAmount"), -1);
        if (required < 0 || capacity < required) return false;
        boolean triedAutoCraft = false;
        for (int attempt = 0; attempt < 64 && !hasEnoughFuel(machine); attempt++) {
            int previous = fuelAmount(machine);
            ItemStack current = stack(invoke(machine, "getStackInFuelSlot"));
            if (!current.isEmpty()) {
                if (!invokeVoid(machine, "refuel") || fuelAmount(machine) <= previous) return false;
                continue;
            }
            injectedFuel.clear();
            int deficit = required - previous;
            if (deficit <= 0) return false;
            ItemStack fuel = reserveFuel(player, nutrientPasteIngredient(), deficit);
            if (fuel.isEmpty() && !triedAutoCraft) {
                triedAutoCraft = true;
                try (ExtractionLedger fuelLedger = new ExtractionLedger()) {
                    fuelLedger.setStorageEndpoint(storageEndpoint());
                    ItemStack paste = CraftPacketUtils.ensureMaterialAvailable(player, dimension, pos,
                            nutrientPasteIngredient(), 1, fuelLedger, network);
                    if (!paste.isEmpty() && fuelLedger.commit(network, player)) fuel = paste.copy();
                }
            }
            if (fuel.isEmpty()) fuel = reserveFallbackFuel(player, deficit);
            if (fuel.isEmpty()) return false;
            if (!invokeVoid(machine, "setStackInFuelSlot", fuel.copy())) {
                refundFuel(player, fuel);
                return false;
            }
            injectedFuel.add(fuel.copy());
            if (!invokeVoid(machine, "refuel") || fuelAmount(machine) <= previous) return false;
        }
        return hasEnoughFuel(machine);
    }

    private void recoverInjectedFuel(BlockEntity machine, @Nullable ServerPlayer player) {
        if (injectedFuel.isEmpty()) return;
        ItemStack current = stack(invoke(machine, "getStackInFuelSlot"));
        if (current.isEmpty()) {
            injectedFuel.clear();
            return;
        }
        int refundable = 0;
        for (ItemStack injected : injectedFuel) {
            if (ItemStack.isSameItemSameTags(injected, current)) refundable += injected.getCount();
        }
        refundable = Math.min(refundable, current.getCount());
        if (refundable > 0) {
            ItemStack returned = current.copyWithCount(refundable);
            current.shrink(refundable);
            invoke(machine, "setStackInFuelSlot", current);
            refundFuel(player, returned);
        }
        injectedFuel.clear();
    }

    private boolean hasEnoughFuel(BlockEntity machine) {
        if (isBioForge()) {
            int cost = nutrientCost(machine);
            return cost >= 0 && fuelAmount(machine) >= cost;
        }
        Object enough = invokeCompatible(machine, "hasEnoughFuel", recipe);
        return Boolean.TRUE.equals(enough);
    }

    private boolean invokeVoid(Object receiver, String name, Object... args) {
        if (receiver == null) return false;
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try {
                method.invoke(receiver, args);
                return true;
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }

    private static Ingredient nutrientPasteIngredient() {
        Item paste = ForgeRegistries.ITEMS.getValue(new ResourceLocation("biomancy", "nutrient_paste"));
        return paste == null ? Ingredient.EMPTY : Ingredient.of(paste);
    }

    private ItemStack reserveFuel(ServerPlayer player, Ingredient ingredient, int deficit) {
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) return ItemStack.EMPTY;
        ItemStack template = options[0];
        int requested = BiomancyFuelPolicy.requiredItems(deficit, fuelValue(template), template.getMaxStackSize());
        for (int count = requested; count > 0; count /= 2) {
            try (ExtractionLedger fuelLedger = new ExtractionLedger()) {
                fuelLedger.setStorageEndpoint(storageEndpoint());
                ItemStack fuel = fuelLedger.reserve(ingredient, count, network, player, dimension, pos);
                if (!fuel.isEmpty() && fuelLedger.commit(network, player)) return fuel.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack reserveFallbackFuel(ServerPlayer player, int deficit) {
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, storageEndpoint());
        List<ItemStack> fuels = new ArrayList<>();
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            ItemStack candidate = entry.getKey().toStack();
            if (entry.getValue() > 0 && isValidFuel(candidate) && fuelValue(candidate) > 0) fuels.add(candidate);
        }
        fuels.sort((first, second) -> BiomancyFuelPolicy.compare(fuelValue(first), fuelId(first),
                fuelValue(second), fuelId(second)));
        for (ItemStack candidate : fuels) {
            ItemStack fuel = reserveFuel(player, Ingredient.of(candidate), deficit);
            if (!fuel.isEmpty()) return fuel;
        }
        return ItemStack.EMPTY;
    }

    private void refundFuel(@Nullable ServerPlayer player, ItemStack fuel) {
        ItemStack remainder = insertIntoStorage(player, fuel, false);
        if (!remainder.isEmpty()) PlayerUtils.safeGiveToPlayer(player != null ? player : this.player, remainder, network);
    }

    private static String fuelId(ItemStack fuel) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(fuel.getItem());
        return id == null ? "" : id.toString();
    }

    private static int fuelValue(ItemStack fuel) {
        try {
            Class<?> nutrients = Class.forName("com.github.elenterius.biomancy.api.nutrients.Nutrients");
            return number(nutrients.getMethod("getFuelValue", ItemStack.class).invoke(null, fuel), 0);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return 0;
        }
    }

    private int nutrientCost(BlockEntity machine) {
        return isBioForge() ? number(invoke(recipe, "getCraftingCostNutrients"), -1)
                : number(invokeCompatible(machine, "getFuelCost", recipe), -1);
    }

    private static int fuelAmount(BlockEntity machine) {
        return number(invoke(fuelHandler(machine), "getFuelAmount"), -1);
    }

    @Nullable
    private static Object fuelHandler(BlockEntity machine) {
        for (Class<?> type = machine.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod("getFuelHandler");
                if (!method.trySetAccessible()) return null;
                return method.invoke(machine);
            } catch (NoSuchMethodException ignored) {
                continue;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isValidFuel(ItemStack stack) {
        try {
            Class<?> nutrients = Class.forName("com.github.elenterius.biomancy.api.nutrients.Nutrients");
            Method method = nutrients.getMethod("isValidFuel", ItemStack.class);
            return Boolean.TRUE.equals(method.invoke(null, stack));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean machineMatchesRecipe(BlockEntity machine, Recipe<?> recipe) {
        String machineName = machine.getClass().getSimpleName();
        return switch (typeForRecipe(recipe)) {
            case ModIds.ID_BIOMANCY_DIGESTER -> machineName.equals("DigesterBlockEntity");
            case ModIds.ID_BIOMANCY_BIO_LAB -> machineName.equals("BioLabBlockEntity");
            case ModIds.ID_BIOMANCY_DECOMPOSER -> machineName.equals("DecomposerBlockEntity");
            case ModIds.ID_BIOMANCY_BIO_FORGE -> machineName.equals("BioForgeBlockEntity");
            default -> false;
        };
    }

    private static String typeForRecipe(Recipe<?> recipe) {
        if (BiomancyRecipeHandler.matchesKind(recipe, BiomancyRecipeHandler.Kind.DIGESTER)) return ModIds.ID_BIOMANCY_DIGESTER;
        if (BiomancyRecipeHandler.matchesKind(recipe, BiomancyRecipeHandler.Kind.BIO_LAB)) return ModIds.ID_BIOMANCY_BIO_LAB;
        if (BiomancyRecipeHandler.matchesKind(recipe, BiomancyRecipeHandler.Kind.DECOMPOSER)) return ModIds.ID_BIOMANCY_DECOMPOSER;
        if (BiomancyRecipeHandler.matchesKind(recipe, BiomancyRecipeHandler.Kind.BIO_FORGE)) return ModIds.ID_BIOMANCY_BIO_FORGE;
        return "";
    }

    private List<ItemStack> removePlacedInputs(Object inventory) {
        List<ItemStack> recovered = new ArrayList<>();
        for (PlacedInput placed : placedInputs) {
            ItemStack current = stack(invoke(inventory, "getStackInSlot", placed.slot()));
            if (current.isEmpty() || !ItemStack.isSameItemSameTags(current, placed.stack())) continue;
            ItemStack extracted = stack(invoke(inventory, "extractItem", placed.slot(),
                    Math.min(current.getCount(), placed.stack().getCount()), false));
            if (!extracted.isEmpty()) recovered.add(extracted);
        }
        return recovered;
    }

    private void rollbackInputs(Object inventory) {
        unplacedInputs.addAll(removePlacedInputs(inventory));
        if (!sharedMaterials) {
            for (ItemStack recovered : unplacedInputs) {
                if (!recovered.isEmpty()) insertIntoStorage(player, recovered, false);
            }
            unplacedInputs.clear();
        }
        placedInputs.clear();
    }

    private static boolean allSlotsEmpty(Object inventory) {
        int slots = number(invoke(inventory, "getSlots"), 0);
        if (inventory == null || slots == 0) return false;
        for (int slot = 0; slot < slots; slot++) {
            if (!stack(invoke(inventory, "getStackInSlot", slot)).isEmpty()) return false;
        }
        return true;
    }

    private static boolean hasAnyStack(Object inventory) {
        int slots = number(invoke(inventory, "getSlots"), 0);
        for (int slot = 0; slot < slots; slot++) {
            if (!stack(invoke(inventory, "getStackInSlot", slot)).isEmpty()) return true;
        }
        return false;
    }

    private static ItemStack stack(@Nullable Object value) {
        return value instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
    }

    private static int number(@Nullable Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    @Nullable
    private static Object invoke(@Nullable Object receiver, String name, Object... args) {
        if (receiver == null) return null;
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try {
                return method.invoke(receiver, args);
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException ignored) {
                continue;
            }
        }
        return null;
    }

    @Nullable
    private static Object invokeCompatible(@Nullable Object receiver, String name, Object argument) {
        if (receiver == null || argument == null) return null;
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isInstance(argument)) continue;
            try {
                return method.invoke(receiver, argument);
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                return null;
            }
        }
        return null;
    }

    private record PlacedInput(int slot, ItemStack stack) {}
}
