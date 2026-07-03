package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;
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
        TickCache(long tick, Set<String> modTypeIds) {
            this.tick = tick;
            this.modTypeIds = modTypeIds;
        }
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
        return list.stream().filter(b -> b.type().equals(type)).findFirst();
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
        if (isBound(dim, altarPos)) return true;
        return rebuildBindingFromNBT(player, dim, altarPos);
    }

    /** Scan player inventory for a NetworkItem with a binding entry matching
     *  the given dim+pos, and if found, re-create the in-memory AltarBinding. */
    private static boolean rebuildBindingFromNBT(ServerPlayer player,
                                                 ResourceKey<Level> dim,
                                                 BlockPos altarPos) {
        ResourceLocation dimLoc = dim.location();
        var inv = player.getInventory();

        // Scan all inventory slots
        ItemStack found = findBindingItem(inv.items, dimLoc, altarPos);
        if (found == null) found = findBindingItem(inv.offhand, dimLoc, altarPos);
        if (found == null) found = findBindingItem(inv.armor, dimLoc, altarPos);
        if (found == null) {
            try {
                var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                if (opt.isPresent()) {
                    for (var stacksHandler : opt.get().getCurios().values()) {
                        var stacks = stacksHandler.getStacks();
                        for (int s = 0; s < stacks.getSlots(); s++) {
                            found = findBindingItem(
                                    List.of(stacks.getStackInSlot(s)), dimLoc, altarPos);
                            if (found != null) break;
                        }
                        if (found != null) break;
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI] Curios NBT scan failed", e);
            }
        }

        if (found == null || !com.refinedmods.refinedstorage.item.NetworkItem.isValid(found))
            return false;

        Optional<AltarBinding> binding = RSBindingHook.INSTANCE.createBinding(found);
        if (binding.isPresent()) {
            bind(dim, altarPos, binding.get());
            return true;
        }
        return false;
    }

    @javax.annotation.Nullable
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
            return currentId.equals(expectedId);
        }
        return currentId.equals(blockKey);
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
                    INetwork net = RSIntegration.resolveNetwork(player.server, netDim, netPos);
                    if (net != null) return net;
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            }
        }
        // Fallback: scan player inventory for bound NetworkItems
        return resolveNetworkFromPlayerItems(player, dim, altarPos);
    }

    @Nullable
    private static INetwork resolveNetworkFromPlayerItems(ServerPlayer player, ResourceKey<Level> dim,
                                                           BlockPos altarPos) {
        var inv = player.getInventory();
        INetwork net = scanForNetwork(player, inv.items, dim, altarPos);
        if (net != null) return net;
        net = scanForNetwork(player, inv.offhand, dim, altarPos);
        if (net != null) return net;
        net = scanForNetwork(player, inv.armor, dim, altarPos);
        if (net != null) return net;
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var stacksHandler : opt.get().getCurios().values()) {
                    var stacks = stacksHandler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        net = scanForNetwork(player, List.of(stacks.getStackInSlot(s)), dim, altarPos);
                        if (net != null) return net;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return null;
    }

    @Nullable
    private static INetwork scanForNetwork(ServerPlayer player, List<ItemStack> stacks,
                                            ResourceKey<Level> dim, BlockPos altarPos) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (!BindingStorage.hasBinding(stack, dim.location(), altarPos)) continue;
            if (com.refinedmods.refinedstorage.item.NetworkItem.isValid(stack)) {
                return RSIntegration.resolveNetwork(player.server,
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
        // Check main inventory + offhand + armor
        var inv = player.getInventory();
        ItemStack result = scanForNetworkItem(player, inv.items, dim, altarPos, ingredient, count);
        if (!result.isEmpty()) return result;
        result = scanForNetworkItem(player, inv.offhand, dim, altarPos, ingredient, count);
        if (!result.isEmpty()) return result;
        result = scanForNetworkItem(player, inv.armor, dim, altarPos, ingredient, count);
        if (!result.isEmpty()) return result;
        // Check Curios
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var stacksHandler : opt.get().getCurios().values()) {
                    var stacks = stacksHandler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        result = scanForNetworkItem(player, List.of(stacks.getStackInSlot(s)),
                                dim, altarPos, ingredient, count);
                        if (!result.isEmpty()) return result;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return ItemStack.EMPTY;
    }

    private static ItemStack scanForNetworkItem(ServerPlayer player, List<ItemStack> stacks,
                                                  ResourceKey<Level> dim, BlockPos altarPos,
                                                  Ingredient ingredient, int count) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (!BindingStorage.hasBinding(stack, dim.location(), altarPos)) continue;
            if (com.refinedmods.refinedstorage.item.NetworkItem.isValid(stack)) {
                INetwork net = RSIntegration.resolveNetwork(player.server,
                        com.refinedmods.refinedstorage.item.NetworkItem.getDimension(stack),
                        new BlockPos(com.refinedmods.refinedstorage.item.NetworkItem.getX(stack),
                                com.refinedmods.refinedstorage.item.NetworkItem.getY(stack),
                                com.refinedmods.refinedstorage.item.NetworkItem.getZ(stack)));
                if (net != null) {
                    ItemStack extracted = RSIntegration.extractFromNetwork(net, ingredient, count);
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
        var inv = player.getInventory();
        INetwork net = resolveNetFromBindings(player, inv.items);
        if (net != null) return net;
        net = resolveNetFromBindings(player, inv.offhand);
        if (net != null) return net;
        net = resolveNetFromBindings(player, inv.armor);
        if (net != null) return net;
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        net = resolveNetFromBindings(player, List.of(stacks.getStackInSlot(s)));
                        if (net != null) return net;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return null;
    }

    @Nullable
    private static INetwork resolveNetFromBindings(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                BlockPos altarPos = entry.pos();
                List<AltarBinding> altarBindings = BINDINGS.get(GlobalPos.of(altarDim, altarPos));
                if (altarBindings == null) continue;
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
                        INetwork net = RSIntegration.resolveNetwork(player.server, netDim, netPos);
                        if (net != null) return net;
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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

        String recipePath = recipe.getId().getPath();
        String subType = null;
        int slashIdx = recipePath.indexOf('/');
        if (slashIdx > 0) {
            subType = recipePath.substring(0, slashIdx).toLowerCase(java.util.Locale.ROOT);
        }

        // Normalize recipe sub-type names to canonical machine prefixes.
        // e.g. "crystal_infusion" recipe type → "crystal_ritual" binding prefix.
        subType = normalizeSubType(subType, type);

        return scanBindingsForRecipe(player, type, subType);
    }

    private static boolean scanBindingsForRecipe(ServerPlayer player, ModType type,
                                                  @Nullable String subType) {
        var inv = player.getInventory();
        if (scanForRecipe(inv.items, type, subType)) return true;
        if (scanForRecipe(inv.offhand, type, subType)) return true;
        if (scanForRecipe(inv.armor, type, subType)) return true;
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        if (scanForRecipe(List.of(stacks.getStackInSlot(s)), type, subType))
                            return true;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return false;
    }

    private static boolean scanForRecipe(List<ItemStack> stacks, ModType type,
                                          @Nullable String subType) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ModType entryType = ModType.fromBlockKey(entry.blockKey());
                if (entryType == null || !entryType.id().equals(type.id())) continue;
                // If we can't determine sub-type, accept any machine of this mod
                if (subType == null) return true;
                // Match sub-type against block key (e.g. block key contains "wissen_crystallizer")
                if (entry.blockKey() != null
                        && entry.blockKey().toLowerCase(java.util.Locale.ROOT).contains(subType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check whether the player has at least one binding to a machine of the
     * given mod type.  Uses a per-player tick-scoped cache so that repeated
     * probes during recipe resolution only scan the full inventory once.
     */
    public static boolean hasAnyBindingForType(ServerPlayer player, ModType type) {
        if (type == ModType.GENERIC) return true;

        long tick = player.level().getGameTime();
        UUID pid = player.getUUID();
        TickCache cache = SCAN_CACHE.get(pid);
        if (cache != null && cache.tick == tick) {
            boolean result = cache.modTypeIds.contains(type.id());
            RSIntegrationMod.LOGGER.debug("[RSI-DIAG] hasAnyBindingForType (cached): type={} → {}",
                    type.id(), result);
            return result;
        }

        // Build the full set of ModType IDs this player has bindings for.
        Set<String> found = new HashSet<>();
        var inv = player.getInventory();
        collectBoundTypeIds(inv.items, player, found);
        collectBoundTypeIds(inv.offhand, player, found);
        collectBoundTypeIds(inv.armor, player, found);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        collectBoundTypeIds(List.of(stacks.getStackInSlot(s)), player, found);
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        SCAN_CACHE.put(pid, new TickCache(tick, found));
        boolean result = found.contains(type.id());
        RSIntegrationMod.LOGGER.debug("[RSI-DIAG] hasAnyBindingForType (fresh scan): type={} player={} → {} types={}",
                type.id(), player.getName().getString(), result, found);
        return result;
    }

    /** Collect all ModType IDs that this stack list has fresh bindings for. */
    private static void collectBoundTypeIds(List<ItemStack> stacks, ServerPlayer player,
                                            Set<String> out) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ModType entryType = ModType.fromBlockKey(entry.blockKey());
                if (entryType == null) continue;
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                ServerLevel entryLevel = player.server.getLevel(altarDim);
                if (!isBindingFresh(entryLevel, altarDim, entry.pos(), entry.blockKey())) continue;
                INetwork net = resolveNetworkForAltar(player, altarDim, entry.pos());
                if (net != null) {
                    out.add(entryType.id());
                }
            }
        }
    }

    // ── event handlers ──────────────────────────────────────────

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BINDINGS.clear();
        SCAN_CACHE.clear();
    }

    @SubscribeEvent
    public static void onBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (!com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_BINDING.get()) return;
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
                com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler.sendBindingSync(p);
            }
        }
    }

    private static void cleanupPlayerNBT(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        var inv = player.getInventory();
        for (ItemStack stack : inv.items) cleanupBindingEntry(stack, dim, pos);
        for (ItemStack stack : inv.offhand) cleanupBindingEntry(stack, dim, pos);
        for (ItemStack stack : inv.armor) cleanupBindingEntry(stack, dim, pos);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    for (int s = 0; s < handler.getStacks().getSlots(); s++) {
                        cleanupBindingEntry(handler.getStacks().getStackInSlot(s), dim, pos);
                    }
                }
            }
        } catch (Exception e) { /* curios not present */ }
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
    public static List<BoundMachine> getBoundMachinesForType(ServerPlayer player, ModType type,
                                                              @Nullable String subTypeHint) {
        List<BoundMachine> result = new ArrayList<>();
        collectBindingsForType(player.getInventory().items, type, subTypeHint, player, result);
        collectBindingsForType(player.getInventory().offhand, type, subTypeHint, player, result);
        collectBindingsForType(player.getInventory().armor, type, subTypeHint, player, result);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        collectBindingsForType(List.of(stacks.getStackInSlot(s)), type, subTypeHint, player, result);
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return result;
    }

    private static boolean scanBindingsForType(List<ItemStack> stacks, ServerPlayer player, ModType type) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                ModType entryType = ModType.fromBlockKey(entry.blockKey());
                var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        stack.getItem());
                String itemId = rl != null ? rl.toString() : "unknown";
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-DIAG] scanBindingsForType: item={}, blockKey={}, entryType={}, lookingFor={}",
                        itemId, entry.blockKey(),
                        entryType != null ? entryType.id() : "null",
                        type.id());
                if (entryType != type) continue;
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                ServerLevel entryLevel = player.server.getLevel(altarDim);
                if (!isBindingFresh(entryLevel, altarDim, entry.pos(), entry.blockKey())) continue;
                INetwork net = resolveNetworkForAltar(player, altarDim, entry.pos());
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-DIAG] scanBindingsForType: resolveNetworkForAltar({}, {}, {}) → {}",
                        entry.dim(), entry.pos(), type.id(), net != null ? "FOUND" : "null");
                if (net != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectBindingsForType(List<ItemStack> stacks, ModType type,
                                                @Nullable String subTypeHint,
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
                if (entryType == null || !entryType.id().equals(type.id())) continue;
                if (normalized != null && entry.blockKey() != null
                        && !entry.blockKey().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                    continue;
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                ServerLevel entryLevel = player.server.getLevel(altarDim);
                if (!isBindingFresh(entryLevel, altarDim, entry.pos(), entry.blockKey())) continue;
                out.add(new BoundMachine(entry.dim(), entry.pos(), type, entry.blockKey()));
            }
        }
    }

    /** Map recipe-ID sub-type names to canonical machine-prefix names.
     *  Wizards Reborn names its recipe category "crystal_infusion"
     *  but the machine prefix used during binding is "crystal_ritual".
     *  WR recipes registered by third-party mods (e.g. Goety Cataclysm)
     *  may use their own path prefixes like "focus" — these are still
     *  crystal ritual recipes executed on the same Wissen Crystallizer.
     *  Malum names its recipe category "spirit_infusion" but the
     *  machine prefix used during binding is "spirit_altar". */
    private static String normalizeSubType(@Nullable String hint, ModType type) {
        if (hint == null) return null;
        if (type == null) return hint;
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
        // malum_spirit_crucible is a leaf type — all recipes are Spirit Crucible
        // recipes and all bindings are Spirit Crucible bindings.  Sub-type
        // filtering is unnecessary and can cause false negatives when the
        // blockKey format doesn't cleanly contain the recipe path prefix.
        if ("malum_spirit_crucible".equals(type.id())) {
            return null;
        }
        // Aetherworks names its recipe category "aetherium_anvil" but the
        // block description ID uses "forge_anvil".
        if (ModType.byId("aetherworks_anvil") == type && "aetherium_anvil".equals(hint)) {
            return "forge_anvil";
        }
        // TACZ gun smith table handles all recipe types (gun/ammo/attachments).
        // Sub-type filtering would reject ammo & attachment recipes since the
        // blockKey "tacz||block.tacz.workbench_a" doesn't contain those prefixes.
        if (ModIds.TACZ.equals(type.id())) {
            return null;
        }
        if (ModIds.GOETY.equals(type.id())) {
            return null;
        }
        // CrockPot recipe IDs use "crock_pot_cooking" as the path prefix,
        // but the block registry key is "crockpot:crock_pot".  There's only
        // one machine type so sub-type filtering is unnecessary.
        if (ModIds.CROCKPOT.equals(type.id())) {
            return null;
        }
        // Aether recipe path prefixes (freezing/incubating/enchanting) don't
        // match block keys (freezer/incubator/altar).  Sub-type filtering
        // is handled by validateAndInit() in AetherFurnaceBatchDelegate.
        if (ModIds.AETHER.equals(type.id())) {
            return null;
        }
        return hint;
    }

}
