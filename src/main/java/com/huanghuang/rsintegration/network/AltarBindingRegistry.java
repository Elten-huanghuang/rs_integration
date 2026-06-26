package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AltarBindingRegistry {

    private static final Map<GlobalPos, List<AltarBinding>> BINDINGS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, IBindingHook> HOOKS = new ConcurrentHashMap<>();

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

    public static boolean isBound(ResourceKey<Level> dim, BlockPos altarPos, ResourceLocation type) {
        return getBinding(dim, altarPos, type).isPresent();
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
                } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
            for (AltarBinding binding : list) {
                IBindingHook hook = HOOKS.get(binding.type());
                if (hook != null) {
                    ItemStack stack = hook.extractItem(player, binding, ingredient, count);
                    if (!stack.isEmpty()) return stack;
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
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
                    } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
        }
        return null;
    }

    // ── multi-block machine query ───────────────────────────────

    public record BoundMachine(ResourceLocation dim, BlockPos pos, ModType type) {}

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
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return false;
    }

    private static boolean scanForRecipe(List<ItemStack> stacks, ModType type,
                                          @Nullable String subType) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (ModType.fromBlockKey(entry.blockKey()) != type) continue;
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
     * given mod type. Scans main inventory, offhand, armor, and Curios slots.
     */
    public static boolean hasAnyBindingForType(ServerPlayer player, ModType type) {
        if (type == ModType.GENERIC) return true;
        com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                "[RSI-DIAG] hasAnyBindingForType called: type={}, player={}",
                type.id(), player.getName().getString());
        var inv = player.getInventory();
        if (scanBindingsForType(inv.items, player, type)) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                    "[RSI-DIAG] hasAnyBindingForType → TRUE (main inventory)");
            return true;
        }
        if (scanBindingsForType(inv.offhand, player, type)) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                    "[RSI-DIAG] hasAnyBindingForType → TRUE (offhand)");
            return true;
        }
        if (scanBindingsForType(inv.armor, player, type)) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                    "[RSI-DIAG] hasAnyBindingForType → TRUE (armor)");
            return true;
        }
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        if (scanBindingsForType(List.of(stacks.getStackInSlot(s)), player, type)) {
                            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                                    "[RSI-DIAG] hasAnyBindingForType → TRUE (curios slot {})", s);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                "[RSI-DIAG] hasAnyBindingForType → FALSE for type={}", type.id());
        return false;
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
        collectBindingsForType(player.getInventory().items, type, subTypeHint, result);
        collectBindingsForType(player.getInventory().offhand, type, subTypeHint, result);
        collectBindingsForType(player.getInventory().armor, type, subTypeHint, result);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        collectBindingsForType(List.of(stacks.getStackInSlot(s)), type, subTypeHint, result);
                    }
                }
            }
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
                        "[RSI-DIAG] scanBindingsForType: item={}, blockKey={}, entryType={}, lookingFor={}",
                        itemId, entry.blockKey(),
                        entryType != null ? entryType.id() : "null",
                        type.id());
                if (entryType != type) continue;
                ResourceKey<Level> altarDim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, entry.dim());
                INetwork net = resolveNetworkForAltar(player, altarDim, entry.pos());
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.info(
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
                                                List<BoundMachine> out) {
        // Normalize recipe sub-type hint to canonical machine prefix.
        // Recipe IDs use a different naming scheme than blockKey prefixes
        // (e.g. "crystal_infusion" recipe type vs "crystal_ritual" machine prefix).
        String normalized = normalizeSubType(subTypeHint, type);

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                if (ModType.fromBlockKey(entry.blockKey()) != type) continue;
                if (normalized != null && entry.blockKey() != null
                        && !entry.blockKey().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                    continue;
                out.add(new BoundMachine(entry.dim(), entry.pos(), type));
            }
        }
    }

    /** Map recipe-ID sub-type names to canonical machine-prefix names.
     *  Wizards Reborn names its recipe category "crystal_infusion"
     *  but the machine prefix used during binding is "crystal_ritual". */
    private static String normalizeSubType(@Nullable String hint, ModType type) {
        if (hint == null) return null;
        if (type == ModType.WIZARDS_REBORN && "crystal_infusion".equals(hint)) {
            return "crystal_ritual";
        }
        return hint;
    }

}
