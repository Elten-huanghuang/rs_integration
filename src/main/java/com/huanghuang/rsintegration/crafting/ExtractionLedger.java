package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExtractionLedger {

    private final List<Entry> entries = new ArrayList<>();
    private final Map<CraftingResolver.StackKey, Integer> pendingNet = new HashMap<>();
    private final Map<CraftingResolver.StackKey, Integer> pendingInv = new HashMap<>();
    private boolean committed;

    private final Map<INetwork, List<ItemStack>> networkEntryCache = new HashMap<>();

    public ItemStack reserve(Ingredient ingredient, int count,
                             @Nullable INetwork network, ServerPlayer player,
                             @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos) {
        if (count <= 0) return ItemStack.EMPTY;
        if (ingredient.isEmpty()) return ItemStack.EMPTY;

        if (network != null) {
            ItemStack matched = findAvailableInNetwork(network, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                // 💡 修复点：传入原始 Ingredient 而不是用 Ingredient.of(result)，解决 Tag 无法批量跨品种提取的 Bug
                // pendingNet already tracked inside findAvailableInNetwork
                entries.add(new Entry(Source.NETWORK, ingredient, result.copy(), null, null, null, network));
                return result;
            }
        }

        if (altarDim != null && altarPos != null) {
            INetwork bindingNet = AltarBindingRegistry.resolveNetworkForAltar(
                    player, altarDim, altarPos);
            if (bindingNet != null) {
                ItemStack matched = findAvailableInNetwork(bindingNet, ingredient, count);
                if (!matched.isEmpty()) {
                    ItemStack result = matched.copyWithCount(count);
                    entries.add(new Entry(Source.ALTAR_BINDING, ingredient, result.copy(),
                            null, altarDim, altarPos, bindingNet));
                    // pendingNet already tracked inside findAvailableInNetwork
                    return result;
                }
            }
        }

        {
            ItemStack matched = findAvailableInInventory(player, ingredient, count);
            if (!matched.isEmpty()) {
                ItemStack result = matched.copyWithCount(count);
                entries.add(new Entry(Source.PLAYER_INVENTORY, ingredient, result.copy(), null, null, null, null));
                // pendingInv already tracked inside findAvailableInInventory
                return result;
            }
        }

        return ItemStack.EMPTY;
    }

    public ItemStack reserveFromNetwork(Ingredient ingredient, int count, INetwork network) {
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;

        ItemStack matched = findAvailableInNetwork(network, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        entries.add(new Entry(Source.NETWORK, ingredient, template.copy(), null, null, null, network));
        // pendingNet already tracked inside findAvailableInNetwork
        return template;
    }

    public ItemStack reserveFromInventory(Ingredient ingredient, int count, ServerPlayer player) {
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;

        ItemStack matched = findAvailableInInventory(player, ingredient, count);
        if (matched.isEmpty()) return ItemStack.EMPTY;

        ItemStack template = matched.copyWithCount(count);
        entries.add(new Entry(Source.PLAYER_INVENTORY, ingredient, template.copy(), null, null, null, null));
        // pendingInv already tracked inside findAvailableInInventory
        return template;
    }

    public boolean commit(@Nullable INetwork network, ServerPlayer player) {
        if (committed) return true;
        committed = true;

        // Group extracted items with their source entry so rollback
        // can return each item to the correct network / inventory.
        List<Entry> extracted = new ArrayList<>();
        try {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                ItemStack item = extractOne(entry, network, player);

                if (item.isEmpty() || item.getCount() < entry.count) {
                    rollbackExtracted(extracted, player);
                    return false;
                }
                extracted.add(new Entry(entry.source, entry.originalIngredient, item.copy(),
                        null, entry.altarDim, entry.altarPos, entry.sourceNetwork));
            }
            MaterialSources.invalidateFor(player);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Commit exception, rolling back {} extractions", extracted.size(), e);
            rollbackExtracted(extracted, player);
            return false;
        }
    }

    /**
     * Return already-extracted items to their original source.
     * ALTAR_BINDING items go back to the bound network, NETWORK items
     * to the player's RS network, PLAYER_INVENTORY items to the player.
     */
    private static void rollbackExtracted(List<Entry> extracted, ServerPlayer player) {
        for (Entry e : extracted) {
            ItemStack s = e.template;
            if (s.isEmpty()) continue;
            switch (e.source) {
                case ALTAR_BINDING -> {
                    if (e.sourceNetwork != null) {
                        e.sourceNetwork.insertItem(s, s.getCount(), Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, s);
                    }
                }
                case NETWORK -> {
                    if (e.sourceNetwork != null) {
                        e.sourceNetwork.insertItem(s, s.getCount(), Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, s);
                    }
                }
                case PLAYER_INVENTORY -> ItemHandlerHelper.giveItemToPlayer(player, s);
            }
        }
    }

    public int size() { return entries.size(); }
    public boolean isCommitted() { return committed; }

    public void reset() {
        entries.clear();
        pendingNet.clear();
        pendingInv.clear();
        networkEntryCache.clear();
        committed = false;
    }

    private enum Source { NETWORK, PLAYER_INVENTORY, ALTAR_BINDING }

    private static final AtomicInteger entryIdSeq = new AtomicInteger();

    private static final class Entry {
        final int id;
        final Source source;
        final Ingredient originalIngredient;
        final ItemStack template;
        final int count;
        @Nullable final ItemStack preExtracted;
        @Nullable final ResourceKey<Level> altarDim;
        @Nullable final BlockPos altarPos;
        @Nullable final INetwork sourceNetwork;

        Entry(Source source, Ingredient originalIngredient, ItemStack template, @Nullable ItemStack preExtracted,
              @Nullable ResourceKey<Level> altarDim, @Nullable BlockPos altarPos,
              @Nullable INetwork sourceNetwork) {
            this.id = entryIdSeq.incrementAndGet();
            this.source = source;
            this.originalIngredient = originalIngredient;
            this.template = template;
            this.count = template.getCount();
            this.preExtracted = preExtracted;
            this.altarDim = altarDim;
            this.altarPos = altarPos;
            this.sourceNetwork = sourceNetwork;
        }
    }

    private static ItemStack extractOne(Entry entry, @Nullable INetwork network, ServerPlayer player) {
        return switch (entry.source) {
            case ALTAR_BINDING -> {
                if (entry.preExtracted != null) yield entry.preExtracted.copy();
                // 使用原版原始 Ingredient 防止因为聚合提取而抛出错误
                yield AltarBindingRegistry.tryExtractFromBindings(player,
                        entry.altarDim, entry.altarPos,
                        entry.originalIngredient, entry.count);
            }
            case NETWORK -> {
                if (network == null) yield ItemStack.EMPTY;
                yield RSIntegration.extractFromNetwork(network, entry.originalIngredient, entry.count);
            }
            case PLAYER_INVENTORY -> {
                // 💡 修复点：背包跨多槽位提取（例如：玩家有两组骨粉，各为30个，需求50个时之前的代码直接报错中止）
                ItemStack extracted = ItemStack.EMPTY;
                int needed = entry.count;
                for (ItemStack stack : player.getInventory().items) {
                    if (entry.originalIngredient.test(stack) && stack.getCount() > 0) {
                        int take = Math.min(needed, stack.getCount());
                        if (extracted.isEmpty()) {
                            extracted = stack.split(take);
                        } else {
                            stack.shrink(take);
                            extracted.grow(take);
                        }
                        needed -= take;
                        if (needed <= 0) break;
                    }
                }
                yield extracted;
            }
        };
    }

    private ItemStack findAvailableInNetwork(INetwork network, Ingredient ingredient, int needed) {
        try {
            List<ItemStack> stacks = networkEntryCache.computeIfAbsent(network, n -> {
                List<ItemStack> list = new ArrayList<>();
                var cache = n.getItemStorageCache();
                if (cache != null) {
                    for (var entry : cache.getList().getStacks()) {
                        ItemStack s = entry.getStack();
                        if (!s.isEmpty()) list.add(s);
                    }
                }
                return list;
            });
            if (stacks.isEmpty()) return ItemStack.EMPTY;

            // Single-stack fast path: one stack has enough → track pendingNet here
            for (ItemStack stored : stacks) {
                if (!ingredient.test(stored)) continue;
                int available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                if (available <= 0) continue;
                if (available >= needed) {
                    ItemStack template = stored.copy();
                    template.setCount(1);
                    pendingNet.merge(CraftingResolver.StackKey.of(stored, true), needed, Integer::sum);
                    return template;
                }
            }

            // Multi-stack aggregation: distribute pendingNet across all
            // contributing stacks so subsequent reservations see accurate counts
            ItemStack pass1Template = ItemStack.EMPTY;
            int pass1Total = 0;
            ItemStack pass2Template = ItemStack.EMPTY;
            int pass2Total = 0;

            for (ItemStack stored : stacks) {
                if (!ingredient.test(stored)) continue;
                int available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                if (available <= 0) continue;

                if (!stored.hasTag()) {
                    pass1Total += available;
                    if (pass1Template.isEmpty()) {
                        pass1Template = stored.copy();
                        pass1Template.setCount(1);
                    }
                } else {
                    pass2Total += available;
                    if (pass2Template.isEmpty()) {
                        pass2Template = stored.copy();
                        pass2Template.setCount(1);
                    }
                }
            }

            ItemStack chosen = ItemStack.EMPTY;
            if (pass1Total >= needed && !pass1Template.isEmpty()) chosen = pass1Template;
            else if (pass1Total + pass2Total >= needed) {
                if (!pass1Template.isEmpty()) chosen = pass1Template;
                else if (!pass2Template.isEmpty()) chosen = pass2Template;
            }
            if (chosen.isEmpty()) return ItemStack.EMPTY;

            // Distribute pendingNet proportionally across all contributing stacks
            int remaining = needed;
            for (ItemStack stored : stacks) {
                if (remaining <= 0) break;
                if (!ingredient.test(stored)) continue;
                int available = stored.getCount()
                        - pendingNet.getOrDefault(CraftingResolver.StackKey.of(stored, true), 0);
                if (available <= 0) continue;
                // Only distribute within the chosen pass (no-tag priority)
                if (chosen == pass1Template && stored.hasTag()) continue;
                int contrib = Math.min(remaining, available);
                pendingNet.merge(CraftingResolver.StackKey.of(stored, true), contrib, Integer::sum);
                remaining -= contrib;
            }
            return chosen;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ledger] Error scanning network storage", e);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack findAvailableInInventory(ServerPlayer player, Ingredient ingredient, int needed) {
        // Single-stack fast path: one stack has enough → track pendingInv here
        for (ItemStack stack : player.getInventory().items) {
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;

            if (!stack.hasTag() && available >= needed) {
                ItemStack template = stack.copy();
                template.setCount(1);
                pendingInv.merge(CraftingResolver.StackKey.of(stack, true), needed, Integer::sum);
                return template;
            }
        }

        ItemStack pass1Template = ItemStack.EMPTY;
        int pass1Total = 0;
        ItemStack pass2Template = ItemStack.EMPTY;
        int pass2Total = 0;

        for (ItemStack stack : player.getInventory().items) {
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;

            if (!stack.hasTag()) {
                pass1Total += available;
                if (pass1Template.isEmpty()) {
                    pass1Template = stack.copy();
                    pass1Template.setCount(1);
                }
            } else {
                pass2Total += available;
                if (pass2Template.isEmpty()) {
                    pass2Template = stack.copy();
                    pass2Template.setCount(1);
                }
            }
        }

        ItemStack chosen = ItemStack.EMPTY;
        if (pass1Total >= needed && !pass1Template.isEmpty()) chosen = pass1Template;
        else if (pass1Total + pass2Total >= needed) {
            if (!pass1Template.isEmpty()) chosen = pass1Template;
            else if (!pass2Template.isEmpty()) chosen = pass2Template;
        }
        if (chosen.isEmpty()) return ItemStack.EMPTY;

        // Distribute pendingInv across all contributing stacks
        int remaining = needed;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (!ingredient.test(stack)) continue;
            int available = stack.getCount()
                    - pendingInv.getOrDefault(CraftingResolver.StackKey.of(stack, true), 0);
            if (available <= 0) continue;
            if (chosen == pass1Template && stack.hasTag()) continue;
            int contrib = Math.min(remaining, available);
            pendingInv.merge(CraftingResolver.StackKey.of(stack, true), contrib, Integer::sum);
            remaining -= contrib;
        }
        return chosen;
    }

    public String describePending() {
        StringBuilder sb = new StringBuilder();
        for (var e : pendingNet.entrySet()) {
            if (e.getValue() > 0) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey().item());
                if (rl != null) sb.append("net:").append(rl).append("=").append(e.getValue()).append(" ");
            }
        }
        for (var e : pendingInv.entrySet()) {
            if (e.getValue() > 0) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey().item());
                if (rl != null) sb.append("inv:").append(rl).append("=").append(e.getValue()).append(" ");
            }
        }
        return sb.toString();
    }

    public void releaseReservations(List<ItemStack> stacks) {
        // Collect entry ids to remove upfront so we match by identity, not by
        // (item, count) which can collide across entries.
        java.util.BitSet idsToRemove = new java.util.BitSet();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            CraftingResolver.StackKey key = CraftingResolver.StackKey.of(stack, true);
            int count = stack.getCount();
            pendingNet.computeIfPresent(key, (k, v) -> {
                int nv = v - count;
                return nv <= 0 ? null : nv;
            });
            pendingInv.computeIfPresent(key, (k, v) -> {
                int nv = v - count;
                return nv <= 0 ? null : nv;
            });
            // Find the matching entry by (template item, count) and record its id.
            // Search in reverse so we match the most recently added entry first.
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (!idsToRemove.get(e.id)
                        && ItemStack.isSameItem(e.template, stack)
                        && e.count == stack.getCount()) {
                    idsToRemove.set(e.id);
                    break;
                }
            }
        }
        // Remove matched entries by id (in reverse to avoid index shifting issues).
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (idsToRemove.get(entries.get(i).id)) {
                entries.remove(i);
            }
        }
    }
}