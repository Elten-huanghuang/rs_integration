package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractFurnaceAccessor;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch delegate for Aether furnace-type machines (Freezer, Incubator, Altar).
 * <p>
 * Freezer and Altar extend {@link AbstractFurnaceBlockEntity} with slots
 * 0=INPUT / 1=FUEL / 2=OUTPUT.  Incubator extends {@code BaseContainerBlockEntity}
 * with slots 0=INPUT / 1=FUEL and <b>no</b> output slot (it produces entities).
 * <p>
 * All three override their fuel system: the fuel slot accepts items from the
 * machine-specific processing map (freezing / enchanting / incubating), NOT
 * vanilla furnace fuel.  {@link ForgeHooks#getBurnTime} will return 0 for all
 * valid Aether fuel items, so we must use the machine's own
 * {@code getBurnDuration()} (furnace) or {@code getIncubatingMap()} (incubator).
 */
public final class AetherFurnaceBatchDelegate extends AbstractBatchDelegate {

    // Cached incubator fuel map — resolved once via reflection (no SRG names needed
    // because this is a public static method, not a vanilla method).
    @Nullable
    private static volatile Map<Item, Integer> cachedIncubatorMap;
    private static volatile boolean incubatorMapProbed;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean isIncubator;

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

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        BlockEntity be = level.getBlockEntity(pos);
        this.isIncubator = be != null && be.getClass().getName().endsWith(".IncubatorBlockEntity");

        // Validate recipe-machine type match
        if (be != null && !validateMachineForRecipe(be, found)) {
            return false;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] validateAndInit OK: recipe={} isIncubator={}", recipeId, isIncubator);
        return true;
    }

    /** Ensure the recipe type matches the machine BlockEntity. */
    private static boolean validateMachineForRecipe(BlockEntity be, Recipe<?> recipe) {
        String recipeName = recipe.getClass().getSimpleName().toLowerCase();
        String beName = be.getClass().getSimpleName().toLowerCase();
        if (recipeName.contains("freezable") && !beName.contains("freezer")) return false;
        if (recipeName.contains("incubat") && !beName.contains("incubator")) return false;
        if (recipeName.contains("enchant") && !beName.contains("altar")) return false;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        ExtractionLedger ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (reserved.isEmpty()) {
                ledger.rollback(player);
                return false;
            }
            materials.add(reserved.copy());
        }

        if (!ledger.commit(network, player)) return false;

        this.usingSharedLedger = false;
        if (!tryStartWithMaterials(player, materials, ledger)) {
            // Craft couldn't start — refund materials back to RS.
            for (ItemStack mat : materials) {
                if (!mat.isEmpty())
                    network.insertItem(mat.copy(), mat.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Aether] No BE at {}", myPos);
            return false;
        }

        IItemHandler handler = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                .orElse(null);
        if (handler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Aether] No IItemHandler at {}", myPos);
            return false;
        }

        forceChunkLoad(true);

        // ── Phase 1: Insert into input slot (slot 0 for all three machines) ──
        ItemStack inputSlot = handler.getStackInSlot(0);
        if (!inputSlot.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Aether] Input slot occupied at {}", myPos);
            return false;
        }

        if (!materials.isEmpty() && !materials.get(0).isEmpty()) {
            ItemStack toInsert = materials.get(0).copyWithCount(1);
            ItemStack remainder = handler.insertItem(0, toInsert, false);
            if (!remainder.isEmpty() && network != null) {
                network.insertItem(remainder, remainder.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
        }

        // ── Phase 2: Ensure fuel (slot 1 for all three machines) ──
        ItemStack fuelSlot = handler.getStackInSlot(1);
        boolean hasFuel = !fuelSlot.isEmpty() && isValidFuelForMachine(be, fuelSlot);
        if (!hasFuel && network != null) {
            ItemStack fuel = extractFuelForMachine(be, network);
            if (!fuel.isEmpty()) {
                ItemStack fuelRemainder = handler.insertItem(1, fuel, false);
                if (!fuelRemainder.isEmpty()) {
                    // Machine rejected fuel — refund to RS.
                    network.insertItem(fuelRemainder, fuelRemainder.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
            }
        }

        be.setChanged();
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] Materials inserted at {}, waiting for cooking", myPos);
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            // Freezer/Altar: check output slot
            return !furnace.getItem(2).isEmpty();
        }

        // Incubator: no output slot; completion is detected by cooking progress
        // returning to 0 after having been > 0 (batch runner polls, so we rely on
        // the tick-based auto-eject in the incubator's serverTick or recipe completion)
        // Since incubation produces entities, the RS result is always EMPTY.
        IItemHandler handler = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                .orElse(null);
        if (handler == null) return true; // can't check, assume done

        ItemStack input = handler.getStackInSlot(0);
        return input.isEmpty(); // input consumed → craft done
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack result = furnace.getItem(2).copy();
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
            return result;
        }

        // Incubator: no item output
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // Clear machine slots to prevent item duplication.
        // In the shared-ledger (chain) path the chain already refunded materials
        // via refundCommitted(), so we just void the machine slots.
        // In the private-ledger (direct) path we need to refund back to RS.
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack slot0 = furnace.getItem(0);
            if (!slot0.isEmpty()) {
                furnace.setItem(0, ItemStack.EMPTY);
                refundToRSNetwork(slot0);
            }
            ItemStack slot2 = furnace.getItem(2);
            if (!slot2.isEmpty()) {
                furnace.setItem(2, ItemStack.EMPTY);
                refundToRSNetwork(slot2);
            }
            furnace.setChanged();
        } else {
            IItemHandler handler = be.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                    .orElse(null);
            if (handler != null) {
                ItemStack slot0 = handler.extractItem(0, 64, false);
                if (!slot0.isEmpty()) refundToRSNetwork(slot0);
            }
        }
        forceChunkLoad(false);
    }

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        forceChunkLoad(false);
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // ── plan warnings ──

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (recipe.getClass().getSimpleName().equals("IncubationRecipe")) {
            warnings.add(Component.translatable("rsi.aether.incubation_warning").getString());
        }
        warnings.add(Component.translatable("rsi.aether.fuel_warning").getString());
        return warnings;
    }

    // ── fuel helpers ──

    /**
     * Check whether {@code stack} is valid fuel for the specific Aether machine.
     * Freezer/Altar override {@code getBurnDuration()} to consult their item→time
     * maps; Incubator uses its own static {@code getIncubatingMap()}.
     */
    private static boolean isValidFuelForMachine(BlockEntity be, ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            try {
                return ((AbstractFurnaceAccessor) furnace).rsi$callGetBurnDuration(stack) > 0;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] getBurnDuration probe failed, falling back to ForgeHooks", e);
                return ForgeHooks.getBurnTime(stack, null) > 0;
            }
        }

        // Incubator — cached map lookup
        Map<Item, Integer> map = getIncubatorFuelMap();
        return map != null && map.containsKey(stack.getItem());
    }

    /** Resolve the incubator fuel map once, caching the result. */
    private static Map<Item, Integer> getIncubatorFuelMap() {
        if (incubatorMapProbed) return cachedIncubatorMap;
        incubatorMapProbed = true;
        try {
            Class<?> clz = Class.forName("com.aetherteam.aether.blockentity.IncubatorBlockEntity");
            @SuppressWarnings("unchecked")
            Map<Item, Integer> map = (Map<Item, Integer>) clz.getMethod("getIncubatingMap").invoke(null);
            cachedIncubatorMap = map;
            return map;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] Incubator fuel probe failed", e);
            return null;
        }
    }

    /** Extract one fuel item from the RS network that is valid for this machine. */
    private static ItemStack extractFuelForMachine(BlockEntity be, INetwork network) {
        for (var entry : new java.util.ArrayList<>(network.getItemStorageCache().getList().getStacks())) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            if (isValidFuelForMachine(be, stack)) {
                var extracted = network.extractItem(stack.copyWithCount(1), 1,
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (!extracted.isEmpty()) return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] Chunk load failed", e);
        }
    }
}
