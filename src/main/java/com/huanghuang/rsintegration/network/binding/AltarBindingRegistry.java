package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps world positions to altar bindings (mod machine type
 * and RS network info). Bindings survive server restarts via item NBT.
 *
 * <p><b>Limitation (Ship-of-Theseus):</b> If a bound machine is destroyed
 * and replaced with the same block type at the same coordinates, the
 * binding remains valid. This is mitigated by:
 * <ul>
 *   <li>{@code validateAndInit()} re-verifies BE state fresh each craft</li>
 *   <li>Remote block-entity capability checks are fresh each access</li>
 *   <li>Machine-missing detection in execution paths triggers lazy cleanup</li>
 * </ul>
 */
public final class AltarBindingRegistry {

    private static final Map<GlobalPos, List<AltarBinding>> BINDINGS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, IBindingHook> HOOKS = new ConcurrentHashMap<>();

    // Per-player tick-scoped cache for hasAnyBindingForType.  During recipe
    // resolution the same inventory is probed repeatedly for different ModTypes;
    // building the full set once per tick avoids redundant NBT scans of 41+ slots.
    private static final Map<UUID, TickCache> SCAN_CACHE = new ConcurrentHashMap<>();

    private static class TickCache {
        final long tick;
        final Set<String> modTypeIds;
        final Map<String, Set<String>> blockKeysByType;
        TickCache(long tick, Set<String> modTypeIds, Map<String, Set<String>> blockKeysByType) {
            this.tick = tick;
            this.modTypeIds = modTypeIds;
            this.blockKeysByType = blockKeysByType;
        }
    }

    // ── inventory iteration helpers ──────────────────────────────

    /** Apply action to every stack-group: main, offhand, armor, and one
     *  call per Curios slot.  Eliminates the 4-tier iteration pattern
     *  that was duplicated across 7 methods (~150 lines). */
    private static void forEachInventoryGroup(ServerPlayer player, Consumer<List<ItemStack>> action) {
        var inv = player.getInventory();
        action.accept(inv.items);
        action.accept(inv.offhand);
        action.accept(inv.armor);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        action.accept(List.of(stacks.getStackInSlot(s)));
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Curios scan failed", e);
        }
    }

    /** Search inventory stack-groups with early return.  Returns the first
     *  non-null value produced by extractor, or null if no group matched. */
    private static <T> T findInInventory(ServerPlayer player, Function<List<ItemStack>, T> extractor) {
        var inv = player.getInventory();
        T result = extractor.apply(inv.items);
        if (result != null) return result;
        result = extractor.apply(inv.offhand);
        if (result != null) return result;
        result = extractor.apply(inv.armor);
        if (result != null) return result;
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        result = extractor.apply(List.of(stacks.getStackInSlot(s)));
                        if (result != null) return result;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Curios scan failed", e);
        }
        return null;
    }

    public static void invalidateScanCache() {
        SCAN_CACHE.clear();
    }

    private AltarBindingRegistry() {}

    public static void registerHook(ResourceLocation type, IBindingHook hook) {
        HOOKS.put(type, hook);
    }

    public static Optional<IBindingHook> findHook(ItemStack held) {
        for (IBindingHook hook : HOOKS.values()) {
            if (hook.matches(held)) return Optional.of(hook);
        }
        return Optional.empty();
    }

    @Nullable
    public static IBindingHook getHook(ResourceLocation type) {
        return HOOKS.get(type);
    }

    public static void bind(ResourceKey<Level> dim, BlockPos altarPos, AltarBinding binding) {
        GlobalPos key = GlobalPos.of(dim, altarPos);
        BINDINGS.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(binding);
    }

    public static void unbind(ResourceKey<Level> dim, BlockPos altarPos, ResourceLocation type) {
        GlobalPos key = GlobalPos.of(dim, altarPos);
        List<AltarBinding> list = BINDINGS.get(key);
        if (list != null) {
            synchronized (list) {
                list.removeIf(b -> b.type().equals(type));
                if (list.isEmpty()) BINDINGS.remove(key);
            }
        }
    }

    public static void unbindAll(ResourceKey<Level> dim, BlockPos altarPos) {
        BINDINGS.remove(GlobalPos.of(dim, altarPos));
    }

    public static Optional<AltarBinding> getBinding(ResourceKey<Level> dim, BlockPos altarPos, ResourceLocation type) {
        List<AltarBinding> list = BINDINGS.get(GlobalPos.of(dim, altarPos));
        if (list == null) return Optional.empty();
        synchronized (list) {
            return list.stream().filter(b -> b.type().equals(type)).findFirst();
        }
    }

    public static boolean isBound(ResourceKey<Level> dim, BlockPos altarPos) {
        List<AltarBinding> list = BINDINGS.get(GlobalPos.of(dim, altarPos));
        return list != null && !list.isEmpty();
    }

    /**
     * Check binding with NBT fallback. The in-memory {@link #BINDINGS} map
     * is lost on server restart; this variant scans the player's inventory
     * items' NBT and rebuilds the in-memory entry if found.
     */
    public static boolean isBound(ResourceKey<Level> dim, BlockPos altarPos, ServerPlayer player) {
        // Never trust the process-wide cache alone: it may contain a binding
        // reconstructed for a different player's NetworkItem.  Ownership is
        // established by the requester's own item NBT.
        if (rebuildBindingFromNBT(player, dim, altarPos)) return true;
        return findBindingEntry(player, dim.location(), altarPos) != null;
    }

    /** Scan player inventory for a NetworkItem with a binding entry matching
     *  the given dim+pos, and if found, re-create the in-memory AltarBinding. */
    private static boolean rebuildBindingFromNBT(ServerPlayer player,
                                                 ResourceKey<Level> dim,
                                                 BlockPos altarPos) {
        ResourceLocation dimLoc = dim.location();
        ItemStack found = findInInventory(player, stacks -> findBindingItem(stacks, dimLoc, altarPos));
        if (found == null || !com.refinedmods.refinedstorage.item.NetworkItem.isValid(found))
            return false;
        Optional<AltarBinding> binding = RSBindingHook.INSTANCE.createBinding(found);
        if (binding.isPresent()) {
            bind(dim, altarPos, binding.get());
            return true;
        }
        return false;
    }

    @Nullable
    public static BindingStorage.BindingEntry findBindingEntry(ServerPlayer player,
                                                                ResourceLocation dim,
                                                                BlockPos pos) {
        return findInInventory(player, stacks -> {
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                    if (entry.dim().equals(dim) && entry.pos().equals(pos)) return entry;
                }
            }
            return null;
        });
    }

    private static ItemStack findBindingItem(List<ItemStack> stacks,
                                              ResourceLocation dimLoc, BlockPos pos) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (BindingStorage.hasBinding(stack, dimLoc, pos)) return stack;
        }
        return null;
    }

    public static boolean isBound(ResourceKey<Level> dim, BlockPos altarPos, ResourceLocation type) {
        return getBinding(dim, altarPos, type).isPresent();
    }

    /**
     * Check whether a binding is still valid at the block-entity level.
     * Requires a {@link ServerLevel} to inspect the block at the bound
     * position. When {@code level} is null the check is skipped and this
     * returns true (conservative — can't verify without access to the
     * dimension).
     *
     * <p>This verifies that the chunk is loaded, the BE still exists,
     * and the block's description ID matches the one recorded at binding
     * time.  Migrated (destroyed-and-replaced) blocks with the same type
     * are accepted — that is an inherent limitation of matching by
     * description ID without persistent BE UUIDs.</p>
     */
    public static boolean isBindingFresh(@Nullable ServerLevel level,
                                         ResourceKey<Level> dim, BlockPos pos,
                                         String blockKey) {
        if (level == null) return true;
        if (!level.dimension().equals(dim)) {
            var server = level.getServer();
            if (server == null) return true;
            level = server.getLevel(dim);
            if (level == null) return true;
        }
        // Never force-load a chunk during a freshness probe — if the chunk
        // is asleep no-one can have broken the machine, so remain optimistic.
        if (!level.isLoaded(pos)) return true;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        String currentId;
        if (be != null) {
            currentId = be.getBlockState().getBlock().getDescriptionId();
        } else {
            // Some bound blocks (e.g. Confluence WorkshopBlock) have no
            // BlockEntity.  Verify via block state directly — this also
            // covers removed blocks (air → "block.minecraft.air" won't match).
            currentId = level.getBlockState(pos).getBlock().getDescriptionId();
        }
        if (blockKey != null && blockKey.contains("||")) {
            String expectedId = blockKey.substring(blockKey.indexOf("||") + 2);
            if (currentId.equals(expectedId)) return true;
            // Some blocks (e.g. YHK cooking pots) change their descriptionId
            // during operation: small_iron_pot → cooking_small_iron_pot.
            // Strip "cooking_" from both sides so the binding stays fresh.
            String normCurrent = currentId.contains(".cooking_")
                    ? currentId.replace(".cooking_", ".") : currentId;
            String normExpected = expectedId.contains(".cooking_")
                    ? expectedId.replace(".cooking_", ".") : expectedId;
            return normCurrent.equals(normExpected);
        }
        return currentId.equals(blockKey);
    }

    /**
     * When a binding is detected as stale (machine no longer exists), purge
     * the in-memory registration and remove NBT entries from all online
     * players.  Only fires when the block at the position is definitively
     * AIR — blocks replaced by other mod content are left alone (the player
     * may have temporarily swapped the machine and will restore it later).
     */
    private static void lazyCleanupGhostBinding(net.minecraft.server.MinecraftServer server,
                                                 ResourceKey<Level> dim, BlockPos pos) {
        ServerLevel level = server.getLevel(dim);
        if (level == null || !level.isLoaded(pos)) return;
        if (!level.getBlockState(pos).isAir()) return;

        unbindAll(dim, pos);
        SCAN_CACHE.clear();
        RSIntegrationMod.LOGGER.debug("[RSI-Bind] Lazy-cleaned ghost binding at dim={} pos={} (block is air)",
                dim.location(), pos);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            cleanupPlayerNBT(p, dim.location(), pos);
        }
    }

    /**
     * Resolve the RS network for an altar without extracting any items.
     * Checks registered bindings first, then falls back to scanning the
     * player's inventory for bound NetworkItems.
     */
    @Nullable
    public static INetwork resolveNetworkForAltar(ServerPlayer player, ResourceKey<Level> dim,
                                                   BlockPos altarPos) {
        List<AltarBinding> list = BINDINGS.get(GlobalPos.of(dim, altarPos));
        if (list != null) {
            synchronized (list) {
            for (AltarBinding binding : list) {
                if (!binding.type().equals(AltarBinding.RS_NETWORK)) continue;
                try {
                    CompoundTag data = binding.data();
                    ResourceLocation dimId = ResourceLocation.tryParse(data.getString("dim"));
                    if (dimId == null) continue;
                    ResourceKey<Level> netDim = ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dimId);
                    BlockPos netPos = new BlockPos(
                            data.getInt("x"), data.getInt("y"), data.getInt("z"));
                    INetwork net = RSIntegrationNetwork.resolveNetwork(player.server, netDim, netPos);
                    if (net != null) return net;
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            }
        }
        // Fallback: scan player inventory for bound NetworkItems
        return resolveNetworkFromPlayerItems(player, dim, altarPos);
    }

    private static INetwork resolveNetworkFromPlayerItems(ServerPlayer player, ResourceKey<Level> dim,
                                                           BlockPos altarPos) {
        return findInInventory(player, stacks -> scanForNetwork(player, stacks, dim, altarPos));
    }

    private static INetwork scanForNetwork(ServerPlayer player, List<ItemStack> stacks,
                                            ResourceKey<Level> dim, BlockPos altarPos) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (!BindingStorage.hasBinding(stack, dim.location(), altarPos)) continue;
            if (com.refinedmods.refinedstorage.item.NetworkItem.isValid(stack)) {
                return RSIntegrationNetwork.resolveNetwork(player.server,
                        com.refinedmods.refinedstorage.item.NetworkItem.getDimension(stack),
                        new BlockPos(com.refinedmods.refinedstorage.item.NetworkItem.getX(stack),
                                com.refinedmods.refinedstorage.item.NetworkItem.getY(stack),
                                com.refinedmods.refinedstorage.item.NetworkItem.getZ(stack)));
            }
        }
        return null;
    }

    public static ItemStack tryExtractFromBindings(ServerPlayer player, ResourceKey<Level> dim,
                                                    BlockPos altarPos, Ingredient ingredient, int count) {
        List<AltarBinding> list = BINDINGS.get(GlobalPos.of(dim, altarPos));
        if (list != null) {
            synchronized (list) {
            for (AltarBinding binding : list) {
                IBindingHook hook = HOOKS.get(binding.type());
                if (hook != null) {
                    ItemStack stack = hook.extractItem(player, binding, ingredient, count);
                    if (!stack.isEmpty()) return stack;
                }
            }
            }
        }
        // Fallback: rebuild binding from player items (survives server restart)
        return tryExtractFromPlayerItems(player, dim, altarPos, ingredient, count);
    }

    private static ItemStack tryExtractFromPlayerItems(ServerPlayer player, ResourceKey<Level> dim,
                                                        BlockPos altarPos, Ingredient ingredient, int count) {
        ItemStack result = findInInventory(player, stacks -> {
            ItemStack s = scanForNetworkItem(player, stacks, dim, altarPos, ingredient, count);
            return s.isEmpty() ? null : s;
        });
        return result != null ? result : ItemStack.EMPTY;
    }

    private static ItemStack scanForNetworkItem(ServerPlayer player, List<ItemStack> stacks,
                                                  ResourceKey<Level> dim, BlockPos altarPos,
                                                  Ingredient ingredient, int count) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (!BindingStorage.hasBinding(stack, dim.location(), altarPos)) continue;
            if (com.refinedmods.refinedstorage.item.NetworkItem.isValid(stack)) {
                INetwork net = RSIntegrationNetwork.resolveNetwork(player.server,
                        com.refinedmods.refinedstorage.item.NetworkItem.getDimension(stack),
                        new BlockPos(com.refinedmods.refinedstorage.item.NetworkItem.getX(stack),
                                com.refinedmods.refinedstorage.item.NetworkItem.getY(stack),
                                com.refinedmods.refinedstorage.item.NetworkItem.getZ(stack)));
                if (net != null) {
                    ItemStack extracted = RSIntegrationNetwork.extractFromNetwork(net, ingredient, count, player);
                    if (!extracted.isEmpty()) return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    // ── network resolution via any binding ───────────────────────

    /**
     * Resolve the RS network by scanning all player inventory items that carry
     * an altar binding and using the bound machine's recorded network info.
     * This is the fallback when no RS terminal is open and no NetworkItem is
     * in the inventory.
     */
    @Nullable
    public static INetwork resolveNetworkFromAnyBinding(ServerPlayer player) {
        return findInInventory(player, stacks -> resolveNetFromBindings(player, stacks));
    }

    private static INetwork resolveNetFromBindings(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                BlockPos altarPos = entry.pos();
                List<AltarBinding> altarBindings = BINDINGS.get(GlobalPos.of(altarDim, altarPos));
                if (altarBindings == null) continue;
                synchronized (altarBindings) {
                    for (AltarBinding ab : altarBindings) {
                    if (!ab.type().equals(AltarBinding.RS_NETWORK)) continue;
                    try {
                        CompoundTag data = ab.data();
                        ResourceLocation dimId = ResourceLocation.tryParse(data.getString("dim"));
                        if (dimId == null) continue;
                        ResourceKey<Level> netDim = ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION, dimId);
                        BlockPos netPos = new BlockPos(
                                data.getInt("x"), data.getInt("y"), data.getInt("z"));
                        INetwork net = RSIntegrationNetwork.resolveNetwork(player.server, netDim, netPos);
                        if (net != null) return net;
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                    }
                    }
                }
        }
        return null;
    }

    // ── multi-block machine query ───────────────────────────────

    public record BoundMachine(ResourceLocation dim, BlockPos pos, ModType type,
                                @javax.annotation.Nullable String blockKey) {}

    /**
     * Check whether the player has a bound machine compatible with the given
     * recipe. For mods with multiple machine sub-types (e.g. WR has crystallizer,
     * workbench, iterator, crystal ritual — all under WIZARDS_REBORN), this
     * extracts the machine hint from the recipe ID path and matches it against
     * the block key of each bound machine.
     */
    public static boolean hasBindingForRecipe(ServerPlayer player, net.minecraft.world.item.crafting.Recipe<?> recipe) {
        ModType type = ModType.classifyRecipe(recipe);
        if (type == null || type == ModType.GENERIC) return true;
        // Prefer the recipe registry type for Aether machines. This remains
        // stable when Aether renames Java recipe classes and prevents the broad
        // parent `aether` fallback from hiding freezer/incubator/altar recipes.
        ResourceLocation recipeType = recipe.getType() != null
                ? net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()) : null;
        if (recipeType != null && "aether".equals(recipeType.getNamespace())) {
            String mapped = switch (recipeType.getPath()) {
                case "freezing" -> "aether_freezer";
                case "incubation" -> "aether_incubator";
                case "enchanting", "repairing" -> "aether_altar";
                default -> null;
            };
            if (mapped != null && ModType.byId(mapped) != null) type = ModType.byId(mapped);
        }

        String recipePath = recipe.getId().getPath();
        String subType = null;
        int slashIdx = recipePath.indexOf('/');
        if (slashIdx > 0) {
            subType = recipePath.substring(0, slashIdx).toLowerCase(java.util.Locale.ROOT);
        }
        subType = normalizeSubType(subType, type);

        TickCache cache = getTickCache(player);
        if (!cache.modTypeIds.contains(type.id())) return false;
        if (subType == null) return true;
        Set<String> keys = cache.blockKeysByType.get(type.id());
        if (keys == null) return false;
        for (String bk : keys) {
            if (bk != null && bk.toLowerCase(java.util.Locale.ROOT).contains(subType)) return true;
        }
        return false;
    }

    private static TickCache getTickCache(ServerPlayer player) {
        long tick = player.level().getGameTime();
        UUID pid = player.getUUID();
        TickCache cache = SCAN_CACHE.get(pid);
        if (cache != null && cache.tick == tick) return cache;

        Set<String> modTypeIds = new HashSet<>();
        Map<String, Set<String>> blockKeysByType = new HashMap<>();
        forEachInventoryGroup(player, stacks -> collectBoundInfo(stacks, player, modTypeIds, blockKeysByType));

        cache = new TickCache(tick, modTypeIds, blockKeysByType);
        SCAN_CACHE.put(pid, cache);
        return cache;
    }

    /**
     * Check whether the player has at least one binding to a machine of the
     * given mod type.  Uses a per-player tick-scoped cache so that repeated
     * probes during recipe resolution only scan the full inventory once.
     */
    public static boolean hasAnyBindingForType(ServerPlayer player, ModType type) {
        if (type == ModType.GENERIC) return true;
        TickCache cache = getTickCache(player);
        return cache.modTypeIds.contains(type.id());
    }

    /** Collect all ModType IDs and blockKeys that this stack list has fresh bindings for. */
    private static void collectBoundInfo(List<ItemStack> stacks, ServerPlayer player,
                                            Set<String> modTypeIds,
                                            Map<String, Set<String>> blockKeysByType) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ModType entryType = ModType.fromBlockKey(entry.blockKey());
                if (entryType == null) continue;
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                ServerLevel entryLevel = player.server.getLevel(altarDim);
                if (!isBindingFresh(entryLevel, altarDim, entry.pos(), entry.blockKey())) {
                    lazyCleanupGhostBinding(player.server, altarDim, entry.pos());
                    continue;
                }
                INetwork net = resolveNetworkForAltar(player, altarDim, entry.pos());
                if (net != null) {
                    addCompatibleTypeIds(entryType, entry.blockKey(), modTypeIds, blockKeysByType);
                }
            }
        }
    }

    private static void addCompatibleTypeIds(ModType entryType, String blockKey,
                                             Set<String> modTypeIds,
                                             Map<String, Set<String>> blockKeysByType) {
        addTypeId(entryType.id(), blockKey, modTypeIds, blockKeysByType);
        String id = entryType.id();
        if ("ironfurnaces_furnace".equals(id)) {
            addTypeId("vanilla_furnace", blockKey, modTypeIds, blockKeysByType);
        } else if ("ironfurnaces_blast_furnace".equals(id)) {
            addTypeId("vanilla_blast_furnace", blockKey, modTypeIds, blockKeysByType);
        } else if ("ironfurnaces_smoker".equals(id)) {
            addTypeId("vanilla_smoker", blockKey, modTypeIds, blockKeysByType);
        }
    }

    private static void addTypeId(String typeId, String blockKey,
                                  Set<String> modTypeIds,
                                  Map<String, Set<String>> blockKeysByType) {
        modTypeIds.add(typeId);
        blockKeysByType.computeIfAbsent(typeId, key -> new HashSet<>()).add(blockKey);
    }

    public static boolean isCompatibleMachineType(ModType requested, ModType bound) {
        if (requested == null || bound == null) return false;
        if (requested.id().equals(bound.id())) return true;
        return switch (bound.id()) {
            case "ironfurnaces_furnace" -> "vanilla_furnace".equals(requested.id());
            case "ironfurnaces_blast_furnace" -> "vanilla_blast_furnace".equals(requested.id());
            case "ironfurnaces_smoker" -> "vanilla_smoker".equals(requested.id());
            default -> false;
        };
    }

    // ── event handlers ──────────────────────────────────────────

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BINDINGS.clear();
        SCAN_CACHE.clear();
        RSIntegrationNetwork.clearNetworkResolutionCache();
    }

    @SubscribeEvent
    public static void onBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (!RSIntegrationConfig.ENABLE_BINDING.get()) return;
        if (!(event.getLevel() instanceof Level level)) return;
        ResourceKey<Level> dim = level.dimension();
        BlockPos pos = event.getPos();
        if (!isBound(dim, pos)) return;

        unbindAll(dim, pos);
        SCAN_CACHE.clear();
        RSIntegrationMod.LOGGER.debug("[RSI-Bind] Auto-cleaned binding at dim={} pos={} (block broken)",
                dim.location(), pos);

        // Clean up all online players' NBT and sync their side panels.
        // Only cleaning the breaker would leave stale bindings on other
        // players' items and out-of-date MachineHub tabs on their clients.
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                cleanupPlayerNBT(p, dim.location(), pos);
                RSIntegrationNetwork.invalidateNetworkResolution(p.getUUID());
                RSSidePanelNetworkHandler.sendBindingSync(p);
            }
        }
    }

    private static void cleanupPlayerNBT(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        forEachInventoryGroup(player, stacks -> {
            for (ItemStack stack : stacks) cleanupBindingEntry(stack, dim, pos);
        });
    }

    private static void cleanupBindingEntry(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        if (stack.isEmpty()) return;
        if (BindingStorage.hasBinding(stack, dim, pos)) {
            BindingStorage.removeBinding(stack, dim, pos);
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] Removed stale NBT binding: dim={} pos={}", dim, pos);
        }
    }

    /**
     * Enumerate all bound machines of a given mod type. Used to find a
     * suitable machine for executing a multi-block craft step.
     */
    public static List<BoundMachine> getBoundMachinesForType(ServerPlayer player, ModType type) {
        return getBoundMachinesForType(player, type, null);
    }

    /**
     * Enumerate all bound machines of a given mod type, optionally filtered
     * by a machine sub-type hint (e.g. "wissen_crystallizer" for WR recipes).
     * This avoids probing machines of the wrong sub-type during async chains.
     */
    // Aether sub-type IDs under the "aether" parent fallback.
    // When a recipe resolves to the generic "aether" ModType we also
    // search these concrete machine types — machine blockKeys are
    // prefixed with the sub-type name (e.g. "aether_freezer||..."),
    // so fromBlockKey returns the sub-type, not "aether".
    private static final String[] AETHER_SUB_IDS = {"aether_freezer", "aether_incubator", "aether_altar"};

    public static List<BoundMachine> getBoundMachinesForType(ServerPlayer player, ModType type,
                                                              @Nullable String subTypeHint) {
        List<BoundMachine> result = new ArrayList<>();
        forEachInventoryGroup(player, stacks -> collectBindingsForType(stacks, type, subTypeHint, player, result));
        // Aether fallback: the generic "aether" ModType acts as a recipe
        // classifier but machines are bound under concrete sub-types.
        if ("aether".equals(type.id()) && result.isEmpty()) {
            for (String subId : AETHER_SUB_IDS) {
                ModType subType = ModType.byId(subId);
                if (subType != ModType.GENERIC) {
                    forEachInventoryGroup(player, stacks ->
                            collectBindingsForType(stacks, subType, subTypeHint, player, result));
                }
            }
        }
        return result;
    }

    private static boolean scanBindingsForType(List<ItemStack> stacks, ServerPlayer player, ModType type) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ModType entryType = ModType.fromBlockKey(entry.blockKey());
                if (!isCompatibleMachineType(type, entryType)) continue;
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                ServerLevel entryLevel = player.server.getLevel(altarDim);
                if (!isBindingFresh(entryLevel, altarDim, entry.pos(), entry.blockKey())) {
                    lazyCleanupGhostBinding(player.server, altarDim, entry.pos());
                    continue;
                }
                INetwork net = resolveNetworkForAltar(player, altarDim, entry.pos());
                if (net != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectBindingsForType(List<ItemStack> stacks, ModType type,
                                                String subTypeHint,
                                                ServerPlayer player,
                                                List<BoundMachine> out) {
        // Normalize recipe sub-type hint to canonical machine prefix.
        // Recipe IDs use a different naming scheme than blockKey prefixes
        // (e.g. "crystal_infusion" recipe type vs "crystal_ritual" machine prefix).
        String normalized = normalizeSubType(subTypeHint, type);

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ModType entryType = ModType.fromBlockKey(entry.blockKey());
                if (!isCompatibleMachineType(type, entryType)) continue;
                if (!isExecutableBinding(type, entry.blockKey())) continue;
                if (normalized != null && entry.blockKey() != null
                        && !entry.blockKey().toLowerCase(java.util.Locale.ROOT).contains(normalized)) {
                    continue;
                }
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                ServerLevel entryLevel = player.server.getLevel(altarDim);
                if (!isBindingFresh(entryLevel, altarDim, entry.pos(), entry.blockKey())) {
                    lazyCleanupGhostBinding(player.server, altarDim, entry.pos());
                    continue;
                }
                out.add(new BoundMachine(entry.dim(), entry.pos(), type, entry.blockKey()));
            }
        }
    }

    static boolean isExecutableBinding(ModType type, String blockKey) {
        if (type == null || blockKey == null) return false;
        if (!"goety".equals(type.id())) return true;
        String key = blockKey.toLowerCase(java.util.Locale.ROOT);
        return !key.startsWith("goety_component")
                && !key.contains("cursed_cage")
                && !key.contains("soul_candlestick");
    }

    /** Map recipe-ID sub-type names to canonical machine-prefix names.
     *  Wizards Reborn names its recipe category "crystal_infusion"
     *  but the machine prefix used during binding is "crystal_ritual".
     *  WR recipes registered by third-party mods (e.g. Goety Cataclysm)
     *  may use their own path prefixes like "focus" — these are still
     *  crystal ritual recipes executed on the same Wissen Crystallizer.
     *  Malum names its recipe category "spirit_infusion" but the
     *  machine prefix used during binding is "spirit_altar". */
    private static String normalizeSubType(String hint, ModType type) {
        if (hint == null) return null;
        if (type == null) return hint;
        // These vanilla ModTypes each represent exactly one machine. A slash in a
        // datapack/KubeJS recipe ID (for example minecraft:kjs/win_15) is only a
        // namespace-like folder and must not be treated as a machine subtype.
        if (type.id().startsWith("vanilla_") || "smithing".equals(type.id())) {
            return null;
        }
        if (ModIds.WIZARDS_REBORN.equals(type.id())) {
            if ("crystal_infusion".equals(hint)) {
                return "crystal_ritual";
            }
            if ("crystal_ritual".equals(hint)) {
                return "arcane_iterator";
            }
            if ("focus".equals(hint)) {
                return null;
            }
        }
        if (ModIds.MALUM.equals(type.id()) && "spirit_infusion".equals(hint)) {
            return "spirit_altar";
        }
        if (ModIds.ID_FA_CLIBANO.equals(type.id())) {
            return null;
        }
        // malum_spirit_crucible is a leaf type — all recipes are Spirit Crucible
        // recipes and all bindings are Spirit Crucible bindings.  Sub-type
        // filtering is unnecessary and can cause false negatives when the
        // blockKey format doesn't cleanly contain the recipe path prefix.
        if ("malum_spirit_crucible".equals(type.id())) {
            return null;
        }
        // malum_runic_workbench — same situation.  The JEI recipe type
        // "runeworking" is not a substring of "runic_workbench", so the
        // sub-type filter would reject all bindings.
        if ("malum_runic_workbench".equals(type.id())) {
            return null;
        }
        // Aetherworks names its recipe category "aetherium_anvil" but the
        // block description ID uses "forge_anvil".
        if (ModType.byId(ModIds.ID_AETHERWORKS_ANVIL) == type && "aetherium_anvil".equals(hint)) {
            return "forge_anvil";
        }
        // CrockPot registers campfire recipes under "campfire_cooking/" but
        // the blockKey uses vanilla description IDs (e.g. "block.minecraft.campfire").
        // When FD is loaded, farmersdelight_skillet handles all campfire recipes
        // and campfire bindings (including vanilla campfires).  The blockKey
        // prefix "farmersdelight_skillet||" needs "campfire" extracted from
        // the hint so it can match against the description-ID segment.
        if ("campfire_cooking".equals(hint)
                && ("vanilla_campfire".equals(type.id()) || "farmersdelight_skillet".equals(type.id()))) {
            return "campfire";
        }
        // CrockPot registers smoker recipes under "smoking/" but the
        // blockKey uses "block.minecraft.smoker" — not "smoking".
        if ("vanilla_smoker".equals(type.id()) && "smoking".equals(hint)) {
            return "smoker";
        }
        if (ModIds.CROCKPOT.equals(type.id())) {
            return null;
        }
        // TACZ gun smith table handles all recipe types (gun/ammo/attachments).
        // Sub-type filtering would reject ammo & attachment recipes since the
        // blockKey "tacz||block.tacz.workbench_a" doesn't contain those prefixes.
        if (ModIds.TACZ.equals(type.id())) {
            return null;
        }
        // Goety recipe folders identify content groups, not machine subtypes.
        // Confluence workshop recipe IDs likewise use folders such as "goety/"
        // to group imported recipes; every one executes on the same workshop.
        if (ModIds.GOETY.equals(type.id()) || "confluence".equals(type.id())) {
            return null;
        }
        // farmersrespite_kettle is a leaf type — every KettleRecipe runs on the
        // Kettle block.  The recipe path prefix is "brewing" (the recipe type /
        // data directory), which is NOT a machine sub-type; the blockKey
        // "farmersrespite_kettle||block.farmersrespite.kettle" does not contain
        // "brewing", so filtering by it would drop the only correct binding.
        if (ModIds.ID_FR_KETTLE.equals(type.id())) {
            return null;
        }
        // Botania recipe paths are data-pack folders, not machine sub-types.
        if (type.id().startsWith("botania_")) {
            return null;
        }
        // farmersdelight_skillet handles both skillet and campfire blocks —
        // sub-type filtering by recipe path (e.g. "campfire_cooking") would
        // reject skillet bindings even though they accept the recipe.
        if (ModIds.ID_FD_SKILLET.equals(type.id())) {
            return null;
        }
        // Aether recipe path prefixes (freezing/incubating/enchanting) don't
        // match block keys (freezer/incubator/altar).  Sub-type filtering
        // is handled by validateAndInit() in AetherFurnaceBatchDelegate.
        // Covers both the generic "aether" fallback and the split sub-types
        // (aether_freezer, aether_incubator, aether_altar).
        if (ModIds.AETHER.equals(type.id()) || type.id().startsWith(ModIds.AETHER + "_")) {
            return null;
        }
        return hint;
    }

}
