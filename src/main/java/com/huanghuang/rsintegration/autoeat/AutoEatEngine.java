package com.huanghuang.rsintegration.autoeat;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.autoeat.network.AutoEatSyncPacket;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;

public final class AutoEatEngine {

    private static final String NBT_KEY = "rsi:food_blacklist";
    private static final int MAX_BLACKLIST_SIZE = 512;
    private static final Set<UUID> runningTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // MethodHandles for SolCarrot (com.cazsius.solcarrot)
    private static MethodHandle foodList_get;
    private static MethodHandle foodList_hasEaten;
    private static MethodHandle foodList_addFood;
    private static MethodHandle solConfig_shouldCount;
    private static MethodHandle maxHealth_update;
    private static MethodHandle solApi_sync;

    // MethodHandles for Diet (com.illusivesoulworks.diet)
    private static MethodHandle lazyOptional_orElse;
    private static MethodHandle dietCapability_get;
    private static MethodHandle dietApi_getInstance;
    private static MethodHandle dietApi_getGroupsForStack;
    private static MethodHandle dietTracker_getValues;
    private static MethodHandle dietTracker_consume;
    private static MethodHandle dietTracker_sync;
    private static MethodHandle dietGroup_getName;

    static {
        initSolCarrot();
        initDiet();
    }

    private static void initSolCarrot() {
        if (!ModList.get().isLoaded("solcarrot")) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            Class<?> foodListCls = Class.forName("com.cazsius.solcarrot.tracking.FoodList");
            foodList_get = lookup.findStatic(foodListCls, "get",
                    MethodType.methodType(foodListCls, net.minecraft.world.entity.player.Player.class));
            foodList_hasEaten = lookup.findVirtual(foodListCls, "hasEaten",
                    MethodType.methodType(boolean.class, Item.class));
            foodList_addFood = lookup.findVirtual(foodListCls, "addFood",
                    MethodType.methodType(boolean.class, Item.class));

            Class<?> cfgCls = Class.forName("com.cazsius.solcarrot.SOLCarrotConfig");
            solConfig_shouldCount = lookup.findStatic(cfgCls, "shouldCount",
                    MethodType.methodType(boolean.class, Item.class));

            Class<?> mhCls = Class.forName("com.cazsius.solcarrot.tracking.MaxHealthHandler");
            maxHealth_update = lookup.findStatic(mhCls, "updateFoodHPModifier",
                    MethodType.methodType(boolean.class, net.minecraft.world.entity.player.Player.class));

            Class<?> apiCls = Class.forName("com.cazsius.solcarrot.api.SOLCarrotAPI");
            solApi_sync = lookup.findStatic(apiCls, "syncFoodList",
                    MethodType.methodType(void.class, net.minecraft.world.entity.player.Player.class));
        } catch (Throwable e) {
            foodList_get = null;
        }
    }

    private static void initDiet() {
        if (!ModList.get().isLoaded("diet")) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            lazyOptional_orElse = lookup.findVirtual(
                    net.minecraftforge.common.util.LazyOptional.class, "orElse",
                    MethodType.methodType(Object.class, Object.class));

            Class<?> capCls = Class.forName("com.illusivesoulworks.diet.common.capability.DietCapability");
            dietCapability_get = lookup.findStatic(capCls, "get",
                    MethodType.methodType(net.minecraftforge.common.util.LazyOptional.class,
                            net.minecraft.world.entity.player.Player.class));

            Class<?> apiCls = Class.forName("com.illusivesoulworks.diet.api.DietApi");
            dietApi_getInstance = lookup.findStatic(apiCls, "getInstance",
                    MethodType.methodType(apiCls));
            dietApi_getGroupsForStack = lookup.findVirtual(apiCls, "getGroups",
                    MethodType.methodType(Set.class, net.minecraft.world.entity.player.Player.class,
                            ItemStack.class));

            Class<?> trackerCls = Class.forName("com.illusivesoulworks.diet.api.type.IDietTracker");
            dietTracker_getValues = lookup.findVirtual(trackerCls, "getValues",
                    MethodType.methodType(Map.class));
            dietTracker_consume = lookup.findVirtual(trackerCls, "consume",
                    MethodType.methodType(void.class, ItemStack.class));
            dietTracker_sync = lookup.findVirtual(trackerCls, "sync",
                    MethodType.methodType(void.class));

            Class<?> groupCls = Class.forName("com.illusivesoulworks.diet.api.type.IDietGroup");
            dietGroup_getName = lookup.findVirtual(groupCls, "getName",
                    MethodType.methodType(String.class));
        } catch (Throwable e) {
            dietCapability_get = null;
        }
    }

    private AutoEatEngine() {}

    // ── Public API ──────────────────────────────────────────────

    public static void execute(ServerPlayer player, AutoEatMode mode, ResourceLocation selectedItem) {
        if (!runningTasks.add(player.getUUID())) return;
        try {
            executeInner(player, mode, selectedItem);
        } finally {
            runningTasks.remove(player.getUUID());
        }
    }

    public static void stop(ServerPlayer player) {
        runningTasks.remove(player.getUUID());
    }

    // ── Blacklist (player NBT) ──────────────────────────────────

    public static Set<ResourceLocation> getBlacklist(net.minecraft.world.entity.player.Player player) {
        Set<ResourceLocation> set = new HashSet<>();
        CompoundTag data = player.getPersistentData();
        ListTag list = data.getList(NBT_KEY, 8);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation rl = ResourceLocation.tryParse(list.getString(i));
            if (rl != null) set.add(rl);
        }
        return set;
    }

    public static void updateBlacklist(net.minecraft.world.entity.player.Player player,
                                        Set<ResourceLocation> added, Set<ResourceLocation> removed) {
        Set<ResourceLocation> current = getBlacklist(player);
        current.addAll(added);
        current.removeAll(removed);
        if (current.size() > MAX_BLACKLIST_SIZE) {
            return;
        }
        ListTag list = new ListTag();
        for (ResourceLocation rl : current) {
            list.add(StringTag.valueOf(rl.toString()));
        }
        player.getPersistentData().put(NBT_KEY, list);
    }

    // ── Inner execution ─────────────────────────────────────────

    private static void executeInner(ServerPlayer player, AutoEatMode mode, ResourceLocation selectedItem) {
        if (!(player.containerMenu instanceof GridContainerMenu gridMenu)) return;
        if (gridMenu.getGrid().getGridType() != GridType.CRAFTING) return;
        if (!(gridMenu.getGrid() instanceof INetworkAwareGrid awareGrid)) return;
        if (!gridMenu.getGrid().isGridActive()) return;
        INetwork network = awareGrid.getNetwork();
        if (network == null) return;
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return;

        // Cost check
        String requiredEffect = RSIntegrationConfig.AUTO_EAT_REQUIRED_EFFECT.get();
        if (!requiredEffect.isEmpty()) {
            String[] parts = requiredEffect.split(":", 2);
            if (parts.length != 2) {
                RSIntegrationMod.LOGGER.warn("[RSI-AutoEat] requiredEffect '{}' is malformed — must be namespace:path. "
                        + "Auto-eat blocked until config is fixed.", requiredEffect);
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new AutoEatSyncPacket(mode, 0,
                                Component.translatable("rsi.autoeat.invalid_effect_config", requiredEffect)));
                return;
            }
            ResourceLocation rl = new ResourceLocation(parts[0], parts[1]);
            MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(rl);
            if (effect == null || !player.hasEffect(effect)) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new AutoEatSyncPacket(mode, 0,
                                Component.translatable("rsi.autoeat.missing_effect", requiredEffect)));
                return;
            }
        }

        switch (mode) {
            case DIVERSITY -> executeDiversity(player, network, cache);
            case STACK -> executeStack(player, network, cache, selectedItem);
            case DIET -> executeDiet(player, network, cache);
        }
    }

    // ── Mode 1: Diversity ───────────────────────────────────────

    private static void executeDiversity(ServerPlayer player, INetwork network,
                                         IStorageCache<ItemStack> cache) {
        if (foodList_get == null) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIVERSITY, 0,
                            Component.literal("§cSolCarrot not installed")));
            return;
        }

        Object foodList;
        try {
            foodList = foodList_get.invoke(player);
        } catch (Throwable e) { return; }
        if (foodList == null) return;

        Set<ResourceLocation> blacklist = getBlacklist(player);
        int maxPerBatch = RSIntegrationConfig.AUTO_EAT_MAX_PER_BATCH.get();
        int eaten = 0;

        // Collect stacks first (avoid concurrent mod during iteration)
        List<ItemStack> stacks = new ArrayList<>();
        for (var entry : cache.getList().getStacks()) {
            stacks.add(entry.getStack());
        }

        for (ItemStack stack : stacks) {
            if (!runningTasks.contains(player.getUUID())) break;
            if (eaten >= maxPerBatch) break;
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (!item.isEdible()) continue;

            try {
                if (!(boolean) solConfig_shouldCount.invoke(item)) continue;
                if ((boolean) foodList_hasEaten.invoke(foodList, item)) continue;
            } catch (Throwable e) { continue; }

            ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
            if (key != null && blacklist.contains(key)) continue;

            ItemStack taken = network.extractItem(stack.copy(), 1, Action.PERFORM);
            if (taken.isEmpty()) continue;

            if (!payCost(network, player, AutoEatMode.DIVERSITY)) {
                network.insertItem(taken, taken.getCount(), Action.PERFORM);
                break;
            }

            ItemStack beforeEat = taken.copy();
            ItemStack remainder = taken.finishUsingItem(player.level(), player);
            remainder = fireEatEvent(player, beforeEat, remainder);
            // Recover container items (bowls, bottles, etc.) returned by eat()
            if (!remainder.isEmpty()) {
                ItemStack leftover = network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
                if (!leftover.isEmpty()) {
                    if (!player.getInventory().add(leftover)) {
                        player.drop(leftover, false);
                    }
                }
            }
            try {
                foodList_addFood.invoke(foodList, item);
            } catch (Throwable ignored) {}
            eaten++;
        }

        if (eaten > 0) {
            try {
                maxHealth_update.invoke(player);
                solApi_sync.invoke(player);
            } catch (Throwable ignored) {}
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIVERSITY, eaten,
                            Component.translatable("rsi.autoeat.result.diversity", eaten)));
        } else {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIVERSITY, 0,
                            Component.translatable("rsi.autoeat.result.none")));
        }
    }

    // ── Cost deduction ────────────────────────────────────────────

    private static boolean payCost(INetwork network, ServerPlayer player, AutoEatMode mode) {
        int perItem = RSIntegrationConfig.AUTO_EAT_COST_PER_ITEM.get();
        if (perItem <= 0) return true;
        String costStr = RSIntegrationConfig.AUTO_EAT_COST_ITEM.get();
        ResourceLocation rl = ResourceLocation.tryParse(costStr);
        if (rl == null) return true;
        Item costItem = ForgeRegistries.ITEMS.getValue(rl);
        if (costItem == null) return true;

        ItemStack template = new ItemStack(costItem, perItem);
        ItemStack extracted = network.extractItem(template, perItem, Action.PERFORM);
        if (extracted.getCount() < perItem) {
            if (!extracted.isEmpty()) {
                network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            }
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(mode, 0,
                            Component.translatable("rsi.autoeat.missing_cost")));
            return false;
        }
        return true;
    }

    // ── Mode 2: Stack ───────────────────────────────────────────

    private static void executeStack(ServerPlayer player, INetwork network,
                                     IStorageCache<ItemStack> cache, ResourceLocation selectedItem) {
        if (selectedItem == null) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.STACK, 0,
                            Component.translatable("rsi.autoeat.no_item_selected")));
            return;
        }
        Item targetItem = ForgeRegistries.ITEMS.getValue(selectedItem);
        if (targetItem == null || !targetItem.isEdible()) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.STACK, 0,
                            Component.translatable("rsi.autoeat.not_edible", selectedItem.toString())));
            return;
        }

        int maxStackSize = targetItem.getMaxStackSize();
        int maxPerBatch = RSIntegrationConfig.AUTO_EAT_MAX_PER_BATCH.get();
        int toExtract = Math.min(maxStackSize, maxPerBatch);
        ItemStack template = new ItemStack(targetItem, toExtract);
        ItemStack extracted = network.extractItem(template, toExtract, Action.PERFORM);
        if (extracted.isEmpty()) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.STACK, 0,
                            Component.translatable("rsi.autoeat.result.none")));
            return;
        }

        // Resolve Diet tracker once (so Diet nutrition values update correctly)
        Object dietTracker = null;
        if (dietCapability_get != null) {
            try {
                Object lazyOpt = dietCapability_get.invoke(player);
                dietTracker = lazyOptional_orElse.invoke(lazyOpt, (Object) null);
            } catch (Throwable ignored) {}
        }

        int count = extracted.getCount();
        int eaten = 0;
        for (int i = 0; i < count; i++) {
            // Stop force-feeding once hunger is full. STACK mode's only goal is
            // topping up hunger, so eating past full just destroys the surplus
            // (saturation clamps). Respect always-edible foods (golden apple).
            net.minecraft.world.food.FoodProperties fp = extracted.getFoodProperties(player);
            boolean alwaysEat = fp != null && fp.canAlwaysEat();
            if (!player.canEat(alwaysEat)) {
                break; // untouched `extracted` is reinserted below
            }
            if (!payCost(network, player, AutoEatMode.STACK)) {
                if (!extracted.isEmpty()) {
                    network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
                }
                break;
            }
            ItemStack one = extracted.split(1);
            Item foodItem = one.getItem();
            ItemStack beforeEat = one.copy();
            ItemStack remainder = one.finishUsingItem(player.level(), player);
            remainder = fireEatEvent(player, beforeEat, remainder);
            eaten++;
            if (foodList_get != null) {
                try {
                    Object fl = foodList_get.invoke(player);
                    if (fl != null) foodList_addFood.invoke(fl, foodItem);
                } catch (Throwable ignored) {}
            }
            if (dietTracker != null) {
                try {
                    dietTracker_consume.invoke(dietTracker, new ItemStack(foodItem));
                } catch (Throwable ignored) {}
            }
            if (!remainder.isEmpty()) {
                ItemStack leftover = network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
                if (!leftover.isEmpty()) {
                    if (!player.getInventory().add(leftover)) {
                        player.drop(leftover, false);
                    }
                }
            }
        }
        if (!extracted.isEmpty()) {
            ItemStack leftover = network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            if (!leftover.isEmpty()) {
                if (!player.getInventory().add(leftover)) {
                    player.drop(leftover, false);
                }
            }
        }

        if (dietTracker != null) {
            try {
                dietTracker_sync.invoke(dietTracker);
            } catch (Throwable ignored) {}
        }

        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AutoEatSyncPacket(AutoEatMode.STACK, eaten,
                        Component.translatable("rsi.autoeat.result.stack", eaten,
                                targetItem.getDescription().getString())));
    }

    // ── Mode 3: Diet ────────────────────────────────────────────

    private static void executeDiet(ServerPlayer player, INetwork network,
                                    IStorageCache<ItemStack> cache) {
        if (dietCapability_get == null) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIET, 0,
                            Component.literal("§cDiet mod not installed")));
            return;
        }

        Object tracker;
        Object dietApi;
        Map<String, Float> values;
        try {
            Object lazyOpt = dietCapability_get.invoke(player);
            tracker = lazyOptional_orElse.invoke(lazyOpt, (Object) null);
            if (tracker == null) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new AutoEatSyncPacket(AutoEatMode.DIET, 0,
                                Component.translatable("rsi.autoeat.error.diet_tracker_missing")));
                return;
            }

            dietApi = dietApi_getInstance.invoke();

            @SuppressWarnings("unchecked")
            Map<String, Float> raw = (Map<String, Float>) dietTracker_getValues.invoke(tracker);
            values = new HashMap<>(raw);
        } catch (Throwable e) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIET, 0,
                            Component.literal("§cDiet API error: " + e.getMessage())));
            return;
        }
        if (values.isEmpty()) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIET, 0,
                            Component.translatable("rsi.autoeat.error.diet_no_groups")));
            return;
        }

        Set<ResourceLocation> blacklist = getBlacklist(player);
        int maxPerBatch = RSIntegrationConfig.AUTO_EAT_MAX_PER_BATCH.get();
        int eaten = 0;

        List<ItemStack> stacks = new ArrayList<>();
        for (var entry : cache.getList().getStacks()) {
            stacks.add(entry.getStack());
        }

        while (eaten < maxPerBatch && runningTasks.contains(player.getUUID())) {
            String lowestGroup = null;
            float lowestValue = Float.MAX_VALUE;
            for (Map.Entry<String, Float> entry : values.entrySet()) {
                if (entry.getValue() < lowestValue) {
                    lowestValue = entry.getValue();
                    lowestGroup = entry.getKey();
                }
            }
            if (lowestGroup == null || lowestValue >= 1.0f) break;

            ItemStack foodToEat = null;
            for (ItemStack stack : stacks) {
                if (!stack.getItem().isEdible()) continue;
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key != null && blacklist.contains(key)) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Set<Object> itemGroups = (Set<Object>) dietApi_getGroupsForStack.invoke(
                            dietApi, player, stack);
                    if (itemGroups != null) {
                        for (Object g : itemGroups) {
                            if (lowestGroup.equals(dietGroup_getName.invoke(g))) {
                                foodToEat = stack;
                                break;
                            }
                        }
                    }
                } catch (Throwable e) { continue; }
                if (foodToEat != null) break;
            }

            if (foodToEat == null) {
                values.put(lowestGroup, 1.0f);
                continue;
            }

            ItemStack taken = network.extractItem(foodToEat.copy(), 1, Action.PERFORM);
            if (taken.isEmpty()) {
                values.put(lowestGroup, 1.0f);
                continue;
            }

            if (!payCost(network, player, AutoEatMode.DIET)) {
                network.insertItem(taken, taken.getCount(), Action.PERFORM);
                break;
            }

            Item foodItem = taken.getItem();
            ItemStack beforeEat = taken.copy();
            ItemStack remainder = taken.finishUsingItem(player.level(), player);
            remainder = fireEatEvent(player, beforeEat, remainder);
            eaten++;
            if (foodList_get != null) {
                try {
                    Object fl = foodList_get.invoke(player);
                    if (fl != null) foodList_addFood.invoke(fl, foodItem);
                } catch (Throwable ignored) {}
            }
            try {
                dietTracker_consume.invoke(tracker, new ItemStack(foodItem));
            } catch (Throwable ignored) {}

            try {
                @SuppressWarnings("unchecked")
                Map<String, Float> fresh = (Map<String, Float>) dietTracker_getValues.invoke(tracker);
                values.putAll(fresh);
            } catch (Throwable ignored) {}

            if (!remainder.isEmpty()) {
                ItemStack leftover = network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
                if (!leftover.isEmpty()) {
                    if (!player.getInventory().add(leftover)) {
                        player.drop(leftover, false);
                    }
                }
            }
        }

        try {
            dietTracker_sync.invoke(tracker);
        } catch (Throwable ignored) {}

        if (eaten > 0) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIET, eaten,
                            Component.translatable("rsi.autoeat.result.diet", eaten)));
        } else {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutoEatSyncPacket(AutoEatMode.DIET, 0,
                            Component.translatable("rsi.autoeat.result.none")));
        }
    }

    private static ItemStack fireEatEvent(ServerPlayer player, ItemStack eaten, ItemStack remainder) {
        LivingEntityUseItemEvent.Finish ev = new LivingEntityUseItemEvent.Finish(player, eaten, 0, remainder);
        MinecraftForge.EVENT_BUS.post(ev);
        ItemStack result = ev.getResultStack();
        return result != null ? result : remainder;
    }
}
