package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.ModVersionDelegateRegistry;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps multiple {@link IBatchDelegate} instances (one per machine) so the
 * chain treats them as a single delegate.  Each child runs one craft on its
 * assigned machine; the group is "complete" when every child has finished.
 *
 * <p>Unlike single-machine delegates, this does NOT extend
 * {@code AbstractBatchDelegate} — the template-method guards
 * ({@code isCraftComplete} checks one machine's chunk) don't apply
 * when managing N independent machines.</p>
 *
 * <p>Material extraction: each child uses its own independent ledger.
 * A child that fails to extract materials refunds itself without affecting
 * siblings.</p>
 */
public final class ParallelCraftGroup implements IBatchDelegate {

    private final List<ChildSlot> children = new ArrayList<>();
    private final ModType modType;
    private final ResourceLocation recipeId;
    private BlockPos representativePos = BlockPos.ZERO;
    private boolean started;
    private ExtractionLedger sharedLedger;
    private boolean usingSharedLedger;

    private record ChildSlot(IBatchDelegate delegate, BlockPos pos) {}

    // ── construction ───────────────────────────────────────────────

    /**
     * Create a group from bound machines.  Constructor internally creates
     * and validates one delegate per machine; machines that fail validation
     * are skipped.
     */
    public ParallelCraftGroup(List<BoundMachine> machines, ModType modType,
                              ResourceLocation recipeId, ServerPlayer player) {
        this.modType = modType;
        this.recipeId = recipeId;
        for (BoundMachine m : machines) {
            IBatchDelegate child = createChildDelegate(modType);
            if (child == null) continue;
            try {
                if (child.validateAndInit(player, recipeId, m.dim(), m.pos())) {
                    if (child instanceof AbstractBatchDelegate abd) {
                        abd.setMachineDim(m.dim());
                    }
                    children.add(new ChildSlot(child, m.pos()));
                    if (this.representativePos.equals(BlockPos.ZERO))
                        this.representativePos = m.pos();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child validateAndInit threw at {}: {}",
                        m.pos(), e.getMessage(), e);
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Created with {}/{} children for {}",
                children.size(), machines.size(), recipeId);
    }

    // ── IBatchDelegate ─────────────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        return !children.isEmpty();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (children.isEmpty()) return null;
        // Aggregate: each child needs the same materials, multiplied by child count.
        // This lets the chain pre-reserve from virtualInventory (feed-forward) instead
        // of each child independently extracting from the RS network.
        IBatchDelegate first = children.get(0).delegate;
        List<IngredientSpec> base = null;
        if (first instanceof AbstractBatchDelegate abd) {
            base = abd.getRequiredMaterials();
        }
        if (base == null || base.isEmpty()) return null;
        List<IngredientSpec> aggregated = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            aggregated.addAll(base);
        }
        return aggregated;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player,
                                         List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (children.isEmpty() || materials.isEmpty()) return false;
        IBatchDelegate first = children.get(0).delegate;
        List<IngredientSpec> base = null;
        if (first instanceof AbstractBatchDelegate abd) {
            base = abd.getRequiredMaterials();
        }
        if (base == null) return false;
        int perChild = base.size();
        if (perChild == 0) return false;

        int startedCount = 0;
        // Collect failed children to remove after iteration
        List<ChildSlot> failed = null;
        for (int i = 0; i < children.size(); i++) {
            int from = i * perChild;
            int to = from + perChild;
            if (to > materials.size()) break;
            List<ItemStack> childMats = new ArrayList<>(materials.subList(from, to));
            try {
                if (children.get(i).delegate.tryStartWithMaterials(player, childMats, sharedLedger)) {
                    startedCount++;
                } else {
                    RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child tryStartWithMaterials failed at {}",
                            children.get(i).pos);
                    refundChildMaterials(player, childMats);
                    if (failed == null) failed = new ArrayList<>();
                    failed.add(children.get(i));
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child tryStartWithMaterials threw at {}: {}",
                        children.get(i).pos, e.getMessage(), e);
                refundChildMaterials(player, childMats);
                if (failed == null) failed = new ArrayList<>();
                failed.add(children.get(i));
            }
        }

        if (failed != null && !failed.isEmpty()) {
            children.removeAll(failed);
            RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Removed {} failed children for {}",
                    failed.size(), recipeId);
        }

        if (startedCount == 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] No children started with materials for {}", recipeId);
            return false;
        }

        this.usingSharedLedger = true;
        this.sharedLedger = sharedLedger;
        started = true;
        RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Started {}/{} children with materials for {}",
                startedCount, children.size(), recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (children.isEmpty()) return false;

        int startedCount = 0;
        List<ChildSlot> failed = null;
        for (ChildSlot child : children) {
            try {
                if (child.delegate.tryStartSingleCraft(player)) {
                    startedCount++;
                } else {
                    if (failed == null) failed = new ArrayList<>();
                    failed.add(child);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child startCraft failed at {}: {}",
                        child.pos, e.getMessage(), e);
                if (failed == null) failed = new ArrayList<>();
                failed.add(child);
            }
        }

        if (failed != null && !failed.isEmpty()) {
            children.removeAll(failed);
            RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Removed {} failed children for {}",
                    failed.size(), recipeId);
        }

        if (startedCount == 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] No children started for {}", recipeId);
            return false;
        }

        started = true;
        RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Started {}/{} children for {}",
                startedCount, children.size(), recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        // Pre-reserve path: aggregate materials, let chain extract from virtualInventory
        // first, then distribute to children via tryStartWithMaterials.
        // Fall back to per-child self-extraction when children don't expose specs.
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs != null && !specs.isEmpty()) {
            // Signal to the chain that we support the pre-reserve flow.
            // The chain will call tryStartWithMaterials after pre-reserving.
            return tryStartSingleCraft(player);
        }
        // Legacy: each child self-extracts (no virtualInventory visibility)
        return tryStartSingleCraft(player);
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (!started || children.isEmpty()) return false;

        int done = 0;
        for (ChildSlot child : children) {
            try {
                if (child.delegate.isCraftComplete(level)) {
                    done++;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child complete check failed at {}: {}",
                        child.pos, e.getMessage(), e);
                done++; // treat crashed as done
            }
        }
        return done >= children.size();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (children.isEmpty()) return ItemStack.EMPTY;

        ItemStack primary = ItemStack.EMPTY;
        int collected = 0;
        for (ChildSlot child : children) {
            try {
                ItemStack result = child.delegate.collectResult(player);
                if (!result.isEmpty()) {
                    collected++;
                    if (primary.isEmpty()) {
                        primary = result;
                    } else {
                        // Extra results to player
                        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, result);
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child collectResult failed at {}: {}",
                        child.pos, e.getMessage(), e);
            }
        }
        RSIntegrationMod.LOGGER.info("[RSI-ParallelGroup] Parallel craft done: {}/{} children produced output, recipe={}",
                collected, children.size(), recipeId);
        return primary;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        if (usingSharedLedger) {
            children.clear();
            started = false;
            sharedLedger = null;
            usingSharedLedger = false;
            return;
        }
        for (ChildSlot child : children) {
            try {
                child.delegate.onBatchFailed(player, reason);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Child onBatchFailed error at {}: {}",
                        child.pos, e.getMessage(), e);
            }
        }
        children.clear();
        started = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        for (ChildSlot child : children) {
            try {
                child.delegate.onBatchFinished(player);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Child onBatchFinished error at {}: {}",
                        child.pos, e.getMessage(), e);
            }
        }
        children.clear();
        started = false;
        sharedLedger = null;
        usingSharedLedger = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return representativePos;
    }

    /** Number of child delegates that successfully validated. */
    public int getChildCount() {
        return children.size();
    }

    /** Propagate server reference to all child delegates for offline cleanup. */
    public void setMachineServer(net.minecraft.server.MinecraftServer server) {
        for (ChildSlot child : children) {
            if (child.delegate instanceof AbstractBatchDelegate abd) {
                abd.setMachineServer(server);
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────

    /**
     * Return a failed child's material slice to the player. These items were
     * committed from the shared ledger by the chain but the child placed them
     * nowhere, so without this they would be lost. Mirrors the stranded-result
     * handling in {@link #collectResult}.
     */
    private static void refundChildMaterials(ServerPlayer player, List<ItemStack> childMats) {
        if (player == null) return;
        for (ItemStack mat : childMats) {
            if (mat != null && !mat.isEmpty()) {
                net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, mat.copy());
            }
        }
    }

    private static IBatchDelegate createChildDelegate(ModType type) {
        if (type == ModType.GENERIC) return null;
        Class<? extends IBatchDelegate> versioned = ModVersionDelegateRegistry.resolve(type);
        if (versioned != null) {
            try {
                return versioned.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-ParallelGroup] Versioned delegate instantiation failed: {}",
                        versioned.getName(), e);
            }
        }
        return type.createDelegate();
    }
}
