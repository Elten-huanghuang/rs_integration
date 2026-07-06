package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.ModVersionDelegateRegistry;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
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
        // Each child extracts its own materials independently via tryStartSingleCraft.
        return null;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (children.isEmpty()) return false;

        int startedCount = 0;
        for (ChildSlot child : children) {
            try {
                if (child.delegate.tryStartSingleCraft(player)) {
                    startedCount++;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Child startCraft failed at {}: {}",
                        child.pos, e.getMessage(), e);
            }
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
    }

    @Override
    public BlockPos getMachinePos() {
        return representativePos;
    }

    // ── helpers ────────────────────────────────────────────────────

    @Nullable
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
