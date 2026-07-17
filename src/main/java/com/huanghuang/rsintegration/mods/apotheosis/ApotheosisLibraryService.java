package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.EnchantmentInfo;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.Entry;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.EntryStatus;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.ImportStats;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Server-authoritative operations for Apotheosis library levels and RS book imports. */
public final class ApotheosisLibraryService {
    private static final String LIBRARY_MENU =
            "dev.shadowsoffire.apotheosis.ench.library.EnchLibraryContainer";
    private static final String LIBRARY_TILE =
            "dev.shadowsoffire.apotheosis.ench.library.EnchLibraryTile";
    private static final int OUTPUT_SLOT = 1;
    private static final int MAX_IMPORT_PER_REQUEST = 64;
    private static final long SNAPSHOT_TTL_MS = 30_000L;
    private static final long REQUEST_COOLDOWN_MS = 150L;

    private static final Field MENU_POS = resolveField(
            "dev.shadowsoffire.placebo.menu.BlockEntityMenu", "pos");
    private static final Field MENU_TILE = resolveField(
            "dev.shadowsoffire.placebo.menu.BlockEntityMenu", "tile");
    private static final Method TILE_CAN_EXTRACT = resolveMethod(
            LIBRARY_TILE, "canExtract", Enchantment.class, int.class, int.class);
    private static final Method TILE_EXTRACT = resolveMethod(
            LIBRARY_TILE, "extractEnchant", ItemStack.class, Enchantment.class, int.class);
    private static final Method TILE_GET_MAX = resolveMethod(
            LIBRARY_TILE, "getMax", Enchantment.class);
    private static final Method TILE_GET_POINTS = resolveMethod(LIBRARY_TILE, "getPointsMap");
    private static final Method TILE_GET_LEVELS = resolveMethod(LIBRARY_TILE, "getLevelsMap");

    private static final AtomicLong NEXT_SNAPSHOT_ID = new AtomicLong(1L);
    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private static final Set<GlobalPos> ACTIVE_IMPORTS = ConcurrentHashMap.newKeySet();

    private ApotheosisLibraryService() {}

    public enum LevelAction {
        PREVIOUS,
        NEXT
    }

    public record ScanResult(long snapshotId, List<Entry> entries, @Nullable String errorKey) {
        public boolean success() {
            return errorKey == null;
        }
    }

    public record ImportResult(ImportStats stats, ScanResult scan, @Nullable String errorKey) {
        public boolean success() {
            return errorKey == null;
        }
    }

    public static int targetLevel(LevelAction action, int currentLevel) {
        return action == LevelAction.NEXT ? currentLevel + 1 : currentLevel - 1;
    }

    public static boolean changeLevel(ServerPlayer player, ResourceLocation dimension, BlockPos pos,
                                      ResourceLocation enchantmentId, LevelAction action) {
        Context context = validateContext(player, dimension, pos, false);
        if (context == null || !adapterReady()) return false;
        Enchantment enchantment = ForgeRegistries.ENCHANTMENTS.getValue(enchantmentId);
        if (enchantment == null) return false;

        AbstractContainerMenu menu = player.containerMenu;
        ItemStack output = menu.getSlot(OUTPUT_SLOT).getItem();
        Map<Enchantment, Integer> outputEnchantments = EnchantmentHelper.getEnchantments(output);
        if (!output.isEmpty() && !outputEnchantments.isEmpty()
                && !outputEnchantments.containsKey(enchantment)) return false;

        int current = outputEnchantments.getOrDefault(enchantment, 0);
        int target = targetLevel(action, current);
        if (target < 1) return false;
        try {
            int max = (int) TILE_GET_MAX.invoke(context.tile, enchantment);
            if (target > max || !(boolean) TILE_CAN_EXTRACT.invoke(context.tile, enchantment, target, current)) {
                return false;
            }
            ItemStack result = output.isEmpty() ? new ItemStack(Items.ENCHANTED_BOOK) : output.copy();
            TILE_EXTRACT.invoke(context.tile, result, enchantment, target);
            menu.getSlot(OUTPUT_SLOT).set(result);
            menu.broadcastChanges();
            return true;
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Apotheosis] Failed to change library level", e);
            return false;
        }
    }

    public static ScanResult scan(ServerPlayer player, ResourceLocation dimension, BlockPos pos) {
        if (!allowRequest(player)) return failure("rsi.apotheosis.library.busy");
        Context context = validateContext(player, dimension, pos, true);
        if (context == null) return failure("rsi.apotheosis.library.context_changed");
        if (!adapterReady()) return failure("rsi.apotheosis.library.unavailable");
        INetwork network = resolveNetwork(player, context);
        if (network == null) return failure("rsi.apotheosis.library.no_network");

        List<Entry> entries = buildEntries(network, context.handler);
        long snapshotId = NEXT_SNAPSHOT_ID.getAndIncrement();
        SNAPSHOTS.put(player.getUUID(), new Snapshot(snapshotId, System.currentTimeMillis(),
                dimension, pos.immutable(), copyEntries(entries)));
        return new ScanResult(snapshotId, entries, null);
    }

    public static ImportResult importEntries(ServerPlayer player, ResourceLocation dimension, BlockPos pos,
                                             long snapshotId, Set<Integer> requestedIds) {
        Context context = validateContext(player, dimension, pos, true);
        if (context == null) return importFailure("rsi.apotheosis.library.context_changed");
        Snapshot snapshot = SNAPSHOTS.get(player.getUUID());
        long now = System.currentTimeMillis();
        if (snapshot == null || snapshot.id != snapshotId || now - snapshot.createdAt > SNAPSHOT_TTL_MS
                || !snapshot.dimension.equals(dimension) || !snapshot.pos.equals(pos)) {
            return importFailure("rsi.apotheosis.library.snapshot_expired");
        }
        if (requestedIds.isEmpty() || requestedIds.size() > ApotheosisLibraryModels.MAX_IMPORT_IDS) {
            return importFailure("rsi.apotheosis.library.invalid_request");
        }
        Set<Integer> knownIds = new HashSet<>();
        for (Entry entry : snapshot.entries) knownIds.add(entry.id());
        if (!knownIds.containsAll(requestedIds)) {
            return importFailure("rsi.apotheosis.library.invalid_request");
        }
        INetwork network = resolveNetwork(player, context);
        if (network == null) return importFailure("rsi.apotheosis.library.no_network");

        GlobalPos key = GlobalPos.of(context.dimensionKey, pos);
        if (!ACTIVE_IMPORTS.add(key)) return importFailure("rsi.apotheosis.library.busy");
        int imported = 0;
        int skipped = 0;
        int refunded = 0;
        int dropped = 0;
        try {
            int processed = 0;
            for (Entry entry : snapshot.entries) {
                if (processed >= MAX_IMPORT_PER_REQUEST) break;
                if (!requestedIds.contains(entry.id()) || entry.status() != EntryStatus.IMPORTABLE) continue;
                int attempts = Math.min(entry.count(), MAX_IMPORT_PER_REQUEST - processed);
                for (int i = 0; i < attempts; i++) {
                    processed++;
                    ItemStack extracted = RSIntegrationNetwork.extractExactFromNetwork(
                            network, entry.stack(), 1, player);
                    if (extracted.isEmpty()) {
                        skipped++;
                        break;
                    }
                    State before = captureState(context.tile);
                    ItemStack remainder;
                    boolean committedAfterFailure = false;
                    try {
                        remainder = context.handler.insertItem(0, extracted.copyWithCount(1), false);
                    } catch (Throwable error) {
                        State after = captureState(context.tile);
                        committedAfterFailure = before != null && after != null && !before.equals(after);
                        remainder = committedAfterFailure ? ItemStack.EMPTY : extracted;
                        RSIntegrationMod.LOGGER.error(
                                "[RSI-Apotheosis] Library insert threw; committed={}", committedAfterFailure, error);
                    }
                    ItemStack inserted = InsertedStackDelta.between(extracted, remainder);
                    if (!inserted.isEmpty() || committedAfterFailure) imported++;
                    else skipped++;
                    if (!remainder.isEmpty()) {
                        RefundOutcome outcome = refund(network, player, remainder);
                        if (outcome == RefundOutcome.REFUNDED) refunded++;
                        else if (outcome == RefundOutcome.DROPPED) dropped++;
                    }
                }
            }
        } finally {
            ACTIVE_IMPORTS.remove(key);
        }
        ScanResult refreshed = scanWithoutRateLimit(player, dimension, pos);
        return new ImportResult(new ImportStats(imported, skipped, refunded, dropped), refreshed, null);
    }

    private static ScanResult scanWithoutRateLimit(ServerPlayer player, ResourceLocation dimension, BlockPos pos) {
        Context context = validateContext(player, dimension, pos, true);
        if (context == null) return failure("rsi.apotheosis.library.context_changed");
        INetwork network = resolveNetwork(player, context);
        if (network == null) return failure("rsi.apotheosis.library.no_network");
        List<Entry> entries = buildEntries(network, context.handler);
        long id = NEXT_SNAPSHOT_ID.getAndIncrement();
        SNAPSHOTS.put(player.getUUID(), new Snapshot(id, System.currentTimeMillis(), dimension,
                pos.immutable(), copyEntries(entries)));
        return new ScanResult(id, entries, null);
    }

    private static List<Entry> buildEntries(INetwork network, IItemHandler handler) {
        List<Entry> result = new ArrayList<>();
        var cache = network.getItemStorageCache();
        if (cache == null || cache.getList() == null) return result;
        Map<StackIdentity, MutableEntry> grouped = new HashMap<>();
        for (var storedEntry : cache.getList().getStacks()) {
            ItemStack stored = storedEntry.getStack();
            if (stored.isEmpty() || !stored.is(Items.ENCHANTED_BOOK)) continue;
            ItemStack display = stored.copyWithCount(1);
            StackIdentity identity = new StackIdentity(display);
            MutableEntry groupedEntry = grouped.computeIfAbsent(identity,
                    ignored -> new MutableEntry(display, 0));
            groupedEntry.count += stored.getCount();
        }
        List<MutableEntry> ordered = new ArrayList<>(grouped.values());
        ordered.sort((left, right) -> left.stack.getHoverName().getString()
                .compareToIgnoreCase(right.stack.getHoverName().getString()));
        int id = 0;
        for (MutableEntry groupedEntry : ordered) {
            if (id >= ApotheosisLibraryModels.MAX_ENTRIES) break;
            EntryStatus status = classify(groupedEntry.stack, handler);
            result.add(new Entry(id++, groupedEntry.stack.copy(), groupedEntry.count, status,
                    describeEnchantments(groupedEntry.stack)));
        }
        result.sort((left, right) -> left.stack().getHoverName().getString()
                .compareToIgnoreCase(right.stack().getHoverName().getString()));
        List<Entry> reindexed = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            Entry entry = result.get(i);
            reindexed.add(new Entry(i, entry.stack(), entry.count(), entry.status(), entry.enchantments()));
        }
        return reindexed;
    }

    private static List<EnchantmentInfo> describeEnchantments(ItemStack stack) {
        List<EnchantmentInfo> result = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
            ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(entry.getKey());
            if (id != null && entry.getValue() > 0) {
                result.add(new EnchantmentInfo(id, entry.getKey().getDescriptionId(), entry.getValue()));
            }
        }
        result.sort((left, right) -> left.id().toString().compareTo(right.id().toString()));
        return result;
    }

    public static EntryStatus classify(ItemStack stack, @Nullable IItemHandler handler) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.ENCHANTED_BOOK)
                || EnchantmentHelper.getEnchantments(stack).isEmpty()) {
            return EntryStatus.INVALID_BOOK;
        }
        if (hasUnsupportedNbt(stack)) return EntryStatus.SPECIAL_NBT;
        if (handler == null) return EntryStatus.LIBRARY_REJECTED;
        try {
            ItemStack remainder = handler.insertItem(0, stack.copyWithCount(1), true);
            return remainder.isEmpty() ? EntryStatus.IMPORTABLE : EntryStatus.LIBRARY_REJECTED;
        } catch (Throwable ignored) {
            return EntryStatus.LIBRARY_REJECTED;
        }
    }

    static boolean hasUnsupportedNbt(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        Set<String> allowed = Set.of("StoredEnchantments", "RepairCost");
        for (String key : tag.getAllKeys()) {
            if (!allowed.contains(key)) return true;
        }
        ListTag enchantments = tag.getList("StoredEnchantments", Tag.TAG_COMPOUND);
        return enchantments.isEmpty();
    }

    @Nullable
    private static Context validateContext(ServerPlayer player, ResourceLocation dimension,
                                           BlockPos pos, boolean requireHandler) {
        if (!RSIntegrationConfig.ENABLE_APOTHEOSIS.get()
                || !LIBRARY_MENU.equals(player.containerMenu.getClass().getName())) return null;
        if (!player.level().dimension().location().equals(dimension)) return null;
        BlockPos currentPos = menuPos(player.containerMenu);
        if (currentPos == null || !currentPos.equals(pos)) return null;
        var binding = AltarBindingRegistry.findBindingEntry(player, dimension, pos);
        if (binding == null || !ApotheosisLibraryBinding.isLibrary(binding.blockRegKey())) return null;
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimension);
        ServerLevel level = player.server.getLevel(dimensionKey);
        if (level == null || !level.isLoaded(pos)) return null;
        ResourceLocation currentBlock = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        if (!ApotheosisLibraryBinding.matchesSavedBlock(binding.blockRegKey(), currentBlock)) return null;
        Object tile = menuTile(player.containerMenu);
        if (tile == null || !LIBRARY_TILE.equals(tile.getClass().getSuperclass() != null
                ? tile.getClass().getSuperclass().getName() : tile.getClass().getName())
                && !isLibraryTile(tile)) return null;
        IItemHandler handler = level.getBlockEntity(pos) == null ? null
                : level.getBlockEntity(pos).getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (requireHandler && handler == null) return null;
        return new Context(dimensionKey, level, tile, handler);
    }

    private static boolean isLibraryTile(Object tile) {
        Class<?> type = tile.getClass();
        while (type != null) {
            if (LIBRARY_TILE.equals(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    @Nullable
    private static INetwork resolveNetwork(ServerPlayer player, Context context) {
        INetwork network = AltarBindingRegistry.resolveNetworkForAltar(
                player, context.dimensionKey, menuPos(player.containerMenu));
        if (network == null) return null;
        var security = network.getSecurityManager();
        return security == null || security.hasPermission(Permission.EXTRACT, player) ? network : null;
    }

    private static RefundOutcome refund(INetwork network, ServerPlayer player, ItemStack stack) {
        ItemStack remaining = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
        if (remaining.isEmpty()) return RefundOutcome.REFUNDED;
        ItemStack inventoryCopy = remaining.copy();
        if (player.getInventory().add(inventoryCopy)) return RefundOutcome.REFUNDED;
        player.drop(inventoryCopy, false);
        return RefundOutcome.DROPPED;
    }

    @Nullable
    private static State captureState(Object tile) {
        if (TILE_GET_POINTS == null || TILE_GET_LEVELS == null) return null;
        try {
            return new State(String.valueOf(TILE_GET_POINTS.invoke(tile)),
                    String.valueOf(TILE_GET_LEVELS.invoke(tile)));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean allowRequest(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long previous = LAST_REQUEST.put(player.getUUID(), now);
        LAST_REQUEST.entrySet().removeIf(entry -> now - entry.getValue() > SNAPSHOT_TTL_MS * 2);
        return previous == null || now - previous >= REQUEST_COOLDOWN_MS;
    }

    private static boolean adapterReady() {
        return MENU_POS != null && MENU_TILE != null && TILE_CAN_EXTRACT != null
                && TILE_EXTRACT != null && TILE_GET_MAX != null;
    }

    @Nullable
    private static BlockPos menuPos(AbstractContainerMenu menu) {
        if (MENU_POS == null) return null;
        try {
            return (BlockPos) MENU_POS.get(menu);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Object menuTile(AbstractContainerMenu menu) {
        if (MENU_TILE == null) return null;
        try {
            return MENU_TILE.get(menu);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Field resolveField(String owner, String name) {
        try {
            Field field = Class.forName(owner).getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method resolveMethod(String owner, String name, Class<?>... params) {
        try {
            Method method = Class.forName(owner).getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static ScanResult failure(String errorKey) {
        return new ScanResult(0L, List.of(), errorKey);
    }

    private static ImportResult importFailure(String errorKey) {
        return new ImportResult(new ImportStats(0, 0, 0, 0), failure(errorKey), errorKey);
    }

    private static List<Entry> copyEntries(List<Entry> entries) {
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            copy.add(new Entry(entry.id(), entry.stack().copy(), entry.count(), entry.status(),
                    entry.enchantments()));
        }
        return copy;
    }

    private record Context(ResourceKey<Level> dimensionKey, ServerLevel level,
                           Object tile, @Nullable IItemHandler handler) {}

    private record Snapshot(long id, long createdAt, ResourceLocation dimension,
                            BlockPos pos, List<Entry> entries) {}

    private record State(String points, String levels) {}

    private enum RefundOutcome { REFUNDED, DROPPED }

    private static final class MutableEntry {
        private final ItemStack stack;
        private int count;

        private MutableEntry(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }
    }

    private static final class StackIdentity {
        private final ItemStack stack;
        private final int hash;

        private StackIdentity(ItemStack stack) {
            this.stack = stack.copyWithCount(1);
            this.hash = 31 * ForgeRegistries.ITEMS.getKey(stack.getItem()).hashCode()
                    + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StackIdentity identity
                    && ItemStack.isSameItemSameTags(stack, identity.stack);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
