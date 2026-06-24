package com.huanghuang.rsintegration.module.wizards_reborn;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class WRWandCraftPacket {

    private final ResourceLocation recipeId;
    @Nullable private final ResourceLocation dim;
    private final BlockPos pos;

    public WRWandCraftPacket(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        this.recipeId = recipeId;
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static WRWandCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new WRWandCraftPacket(recipeId, dim, buf.readBlockPos());
    }

    public static void handle(WRWandCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            try {
            ServerLevel level = resolveLevel(player.server, packet.dim, player);
            if (level == null) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
                return;
            }

            Recipe<?> recipe = level.getRecipeManager().byKey(packet.recipeId).orElse(null);
            if (recipe == null) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", packet.recipeId.toString()));
                return;
            }

            if (!level.isLoaded(packet.pos)) {
                level.getChunk(packet.pos);
            }
            BlockEntity be = level.getBlockEntity(packet.pos);
            if (be == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-WR] No BE at pos={} dim={} block={}",
                        packet.pos, packet.dim, level.getBlockState(packet.pos).getBlock().getDescriptionId());
                player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
                return;
            }

            if (rsi$isInstance(be, "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlockEntity")) {
                handleWissenCrystallizer(player, be, recipe);
            } else if (rsi$isInstance(be, "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlockEntity")) {
                handleArcaneIterator(player, be, recipe);
            } else if (rsi$isInstance(be, "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlockEntity")) {
                handleArcaneWorkbench(player, be, recipe);
            } else if (rsi$isInstance(be, "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlockEntity")) {
                handleCrystalRitual(player, be, recipe);
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-WR] Unsupported BE type for recipe {}: {}",
                        packet.recipeId, be.getClass().getName());
                player.sendSystemMessage(Component.translatable("rsi.generic.error.unsupported_machine", be.getClass().getName()));
            }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-WR] Craft failed for recipe {}:", packet.recipeId, e);
                player.sendSystemMessage(Component.translatable("rsi.wr.error.parse_failed"));
            }
        });
        context.setPacketHandled(true);
    }

    private static boolean handleWissenCrystallizer(ServerPlayer player, BlockEntity be, Recipe<?> recipe) {
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("Failed to get ingredients from WR recipe: {}", recipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.parse_failed"));
            return false;
        }

        Level beLevel = be.getLevel();
        BlockPos pos = be.getBlockPos();
        ResourceKey<Level> dim = beLevel != null ? beLevel.dimension() : Level.OVERWORLD;

        int totalSlots = rsi$getContainerSize(be);
        if (totalSlots <= 0) {
            RSIntegrationMod.LOGGER.warn("Failed to get container size from WissenCrystallizer");
            return false;
        }

        if (!tryAutoCraftMissing(player, ingredients, dim, pos)) return false;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        ExtractionLedger ledger = new ExtractionLedger();

        // Phase 1: reserve all ingredients + validate slots
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size() && i < totalSlots; i++) {
            Ingredient ing = ingredients.get(i);
            ItemStack existing = rsi$getContainerItem(be, i);
            if (!existing.isEmpty()) {
                if (ing.test(existing)) {
                    templates.add(ItemStack.EMPTY);
                    continue;
                }
                player.displayClientMessage(
                        Component.translatable("rsi.wr.error.slot_occupied", i, existing.getHoverName()), true);
                return false;
            }

            ItemStack taken = ensureMaterialAvailable(player, dim, pos, ing, 1, ledger);
            if (taken.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("rsi.generic.error.missing_materials",
                                CraftPacketUtils.describeIngredient(ing)), true);
                return false;
            }
            templates.add(taken);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-WR] Ledger commit failed for WissenCrystallizer");
            return false;
        }

        // Phase 3: place items in slots
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (!taken.isEmpty()) {
                rsi$setContainerItem(be, i, taken);
            }
        }

        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            for (int i = 0; i < templates.size(); i++) {
                if (!templates.get(i).isEmpty()) {
                    rsi$setContainerItem(be, i, ItemStack.EMPTY);
                }
            }
            refundTemplates(network, player, templates);
            return false;
        }

        RSIntegrationMod.LOGGER.debug("Player {} remote-crafted WR recipe '{}' via WissenCrystallizer.",
                player.getName().getString(), recipe.getId());
        return true;
    }

    private static boolean handleArcaneIterator(ServerPlayer player, BlockEntity be, Recipe<?> recipe) {
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("Failed to get ingredients from WR recipe: {}", recipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.parse_failed"));
            return false;
        }

        Level beLevel = be.getLevel();
        BlockPos pos = be.getBlockPos();
        ResourceKey<Level> dim = beLevel != null ? beLevel.dimension() : Level.OVERWORLD;

        List<?> pedestals;
        try {
            pedestals = (List<?>) be.getClass().getMethod("getPedestals").invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-WR] Failed to get pedestals from ArcaneIterator", e);
            return false;
        }

        if (pedestals.size() < ingredients.size()) {
            player.displayClientMessage(
                    Component.translatable("rsi.wr.error.pedestals_insufficient", ingredients.size(), pedestals.size()), true);
            return false;
        }

        if (!tryAutoCraftMissing(player, ingredients, dim, pos)) return false;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        ExtractionLedger ledger = new ExtractionLedger();

        // Phase 1: reserve all ingredients
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            ItemStack taken = ensureMaterialAvailable(player, dim, pos, ing, 1, ledger);
            if (taken.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("rsi.generic.error.missing_materials",
                                CraftPacketUtils.describeIngredient(ing)), true);
                return false;
            }
            templates.add(taken);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-WR] Ledger commit failed for ArcaneIterator");
            return false;
        }

        // Phase 3: place items on pedestals
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            try {
                Object pedestal = pedestals.get(i);
                ItemStack existing = rsi$getContainerItem(pedestal, 0);
                if (!existing.isEmpty()) {
                    ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
                }
                rsi$setContainerItem(pedestal, 0, taken);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-WR] Failed to place item on ArcaneIterator pedestal {}: {}", i, e.getMessage());
                for (int j = 0; j < i; j++) {
                    try { rsi$setContainerItem(pedestals.get(j), 0, ItemStack.EMPTY); } catch (Exception ign) {}
                }
                refundTemplates(network, player, templates);
                return false;
            }
        }

        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ex) {
            for (int i = 0; i < templates.size(); i++) {
                try { rsi$setContainerItem(pedestals.get(i), 0, ItemStack.EMPTY); } catch (Exception ign) {}
            }
            refundTemplates(network, player, templates);
            return false;
        }

        RSIntegrationMod.LOGGER.debug("Player {} remote-crafted WR recipe '{}' via ArcaneIterator.",
                player.getName().getString(), recipe.getId());
        return true;
    }

    private static boolean handleArcaneWorkbench(ServerPlayer player, BlockEntity be, Recipe<?> recipe) {
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("Failed to get ingredients from WR recipe: {}", recipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.parse_failed"));
            return false;
        }

        Level beLevel = be.getLevel();
        BlockPos pos = be.getBlockPos();
        ResourceKey<Level> dim = beLevel != null ? beLevel.dimension() : Level.OVERWORLD;

        net.minecraftforge.items.ItemStackHandler itemHandler;
        try {
            itemHandler = (net.minecraftforge.items.ItemStackHandler) be.getClass()
                    .getField("itemHandler").get(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-WR] Failed to get itemHandler from ArcaneWorkbench", e);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.cant_access_inventory"));
            return false;
        }

        int totalSlots = itemHandler.getSlots();
        if (ingredients.size() > totalSlots) {
            player.displayClientMessage(
                    Component.translatable("rsi.wr.error.slots_insufficient", ingredients.size(), totalSlots), true);
            return false;
        }

        if (!tryAutoCraftMissing(player, ingredients, dim, pos)) return false;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        ExtractionLedger ledger = new ExtractionLedger();

        // Phase 1: reserve all ingredients + validate slots
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack existing = itemHandler.getStackInSlot(i);
            if (!existing.isEmpty()) {
                if (ing.test(existing)) {
                    templates.add(ItemStack.EMPTY);
                    continue;
                }
                player.displayClientMessage(
                        Component.translatable("rsi.wr.error.slot_occupied", i, existing.getHoverName()), true);
                return false;
            }

            ItemStack taken = ensureMaterialAvailable(player, dim, pos, ing, 1, ledger);
            if (taken.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("rsi.generic.error.missing_materials",
                                CraftPacketUtils.describeIngredient(ing)), true);
                return false;
            }
            templates.add(taken);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-WR] Ledger commit failed for ArcaneWorkbench");
            return false;
        }

        // Phase 3: place items in slots
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (!taken.isEmpty()) {
                itemHandler.setStackInSlot(i, taken);
            }
        }

        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            for (int i = 0; i < templates.size(); i++) {
                if (!templates.get(i).isEmpty()) {
                    itemHandler.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            refundTemplates(network, player, templates);
            return false;
        }

        RSIntegrationMod.LOGGER.debug("Player {} remote-crafted WR recipe '{}' via ArcaneWorkbench.",
                player.getName().getString(), recipe.getId());
        return true;
    }

    private static boolean handleCrystalRitual(ServerPlayer player, BlockEntity be, Recipe<?> recipe) {
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("Failed to get ingredients from crystal ritual/infusion recipe: {}", recipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.parse_failed"));
            return false;
        }

        Object ritual = rsi$extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = be.getClass().getMethod("getCrystalRitual").invoke(be);
            } catch (Exception ignored) {}
        }
        if (ritual == null) {
            RSIntegrationMod.LOGGER.warn("Failed to extract CrystalRitual from recipe {} or CrystalBlock", recipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.ritual_data_failed"));
            return false;
        }

        Level beLevel = be.getLevel();
        BlockPos pos = be.getBlockPos();
        ResourceKey<Level> dim = beLevel != null ? beLevel.dimension() : Level.OVERWORLD;

        try {
            Object crystalItem = be.getClass().getMethod("getCrystalItem").invoke(be);
            if (crystalItem == null || ((ItemStack) crystalItem).isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.wr.error.no_crystal"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("Failed to check crystal item in CrystalBlockEntity", e);
        }

        Object area;
        try {
            area = ritual.getClass().getMethod("getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("Failed to get ritual area from CrystalRitual", e);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.cant_get_area"));
            return false;
        }

        List<?> pedestals;
        try {
            Class<?> crystalRitualClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.api.crystalritual.CrystalRitual");
            Class<?> ritualAreaClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.api.crystalritual.CrystalRitualArea");
            pedestals = (List<?>) crystalRitualClass
                    .getMethod("getPedestalsWithArea", Level.class, BlockPos.class, ritualAreaClass)
                    .invoke(null, beLevel, pos, area);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("Failed to find Arcane Pedestals for crystal ritual", e);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.cant_find_pedestals"));
            return false;
        }

        if (pedestals.size() < ingredients.size()) {
            player.displayClientMessage(
                    Component.translatable("rsi.wr.error.pedestals_insufficient", ingredients.size(), pedestals.size()), true);
            return false;
        }

        if (!tryAutoCraftMissing(player, ingredients, dim, pos)) return false;

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        ExtractionLedger ledger = new ExtractionLedger();

        // Phase 1: reserve all ingredients
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            Object pedestal = pedestals.get(i);
            ItemStack existing = rsi$getContainerItem(pedestal, 0);
            if (!existing.isEmpty() && ing.test(existing)) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            ItemStack taken = ensureMaterialAvailable(player, dim, pos, ing, 1, ledger);
            if (taken.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("rsi.generic.error.missing_materials",
                                CraftPacketUtils.describeIngredient(ing)), true);
                return false;
            }
            templates.add(taken);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-WR] Ledger commit failed for CrystalRitual");
            return false;
        }

        // Phase 3: place items on pedestals
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (taken.isEmpty()) continue;

            Object pedestal = pedestals.get(i);
            ItemStack existing = rsi$getContainerItem(pedestal, 0);
            if (!existing.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
            }

            try {
                rsi$setContainerItem(pedestal, 0, taken);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("Failed to place item on ArcanePedestal slot {}: {}", i, e.getMessage());
                for (int j = 0; j < i; j++) {
                    if (!templates.get(j).isEmpty()) {
                        try { rsi$setContainerItem(pedestals.get(j), 0, ItemStack.EMPTY); } catch (Exception ignored) {}
                    }
                }
                refundTemplates(network, player, templates);
                return false;
            }
        }

        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("Failed to invoke wissenWandFunction on CrystalBlockEntity", e);
            for (int i = 0; i < templates.size(); i++) {
                if (!templates.get(i).isEmpty()) {
                    try { rsi$setContainerItem(pedestals.get(i), 0, ItemStack.EMPTY); } catch (Exception ignored) {}
                }
            }
            refundTemplates(network, player, templates);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.cant_start"));
            return false;
        }

        RSIntegrationMod.LOGGER.debug("Player {} remote-crafted CrystalRitual '{}' via CrystalBlock.",
                player.getName().getString(), recipe.getId());
        return true;
    }

    @Nullable
    private static Object rsi$extractRitual(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (field.getName().equals("ritual")) {
                    field.setAccessible(true);
                    try {
                        return field.get(recipe);
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                      BlockPos altarPos, Ingredient ingredient, int count,
                                                      ExtractionLedger ledger) {
        return CraftPacketUtils.ensureMaterialAvailable(player, altarDim, altarPos, ingredient, count, ledger);
    }

    private static void refundTemplates(@Nullable INetwork network, ServerPlayer player, List<ItemStack> templates) {
        for (ItemStack t : templates) {
            if (t.isEmpty()) continue;
            ItemStack refund = t.copy();
            if (network != null) {
                network.insertItem(refund, refund.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            } else {
                ItemHandlerHelper.giveItemToPlayer(player, refund);
            }
        }
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, @Nullable ResourceLocation dim, ServerPlayer player) {
        return CraftPacketUtils.resolveLevel(server, dim, player);
    }

    private static boolean tryAutoCraftMissing(ServerPlayer player, List<Ingredient> ingredients,
                                                ResourceKey<Level> dim, BlockPos pos) {
        if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) return true;

        // Filter to ingredients with non-empty item options
        List<Ingredient> filtered = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            ItemStack[] opts = ing.getItems();
            if (opts.length > 0 && !opts[0].isEmpty()) {
                filtered.add(ing);
            }
        }
        if (filtered.isEmpty()) return true;

        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, dim, pos);
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        List<String> missing = new ArrayList<>();
        List<ResourceLocation> autoSteps = CraftingResolver.resolveStepsForIngredients(
                filtered,
                available.entrySet().stream().map(e -> {
                    ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                    if (e.getKey().tag() != null) {
                        try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}: {}", e.getKey(), ex.toString()); }
                    }
                    return s;
                }).toList(),
                player.serverLevel(),
                missing);

        if (!missing.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("rsi.generic.error.missing_materials", String.join(", ", missing)), true);
            return false;
        }

        if (!autoSteps.isEmpty() && network != null) {
            player.displayClientMessage(
                    Component.translatable("rsi.generic.info.auto_crafting", autoSteps.size()), true);
            if (!CraftPacketUtils.executeCraftingSteps(player, autoSteps, network)) {
                player.displayClientMessage(
                        Component.translatable("rsi.generic.error.auto_craft_failed"), true);
                return false;
            }
        }

        return true;
    }

    private static boolean rsi$isInstance(Object obj, String className) {
        try {
            return Class.forName(className).isInstance(obj);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static int rsi$getContainerSize(Object be) {
        try {
            return (int) be.getClass().getMethod("getContainerSize").invoke(be);
        } catch (Exception e) {
            try {
                return (int) be.getClass().getMethod("m_6643_").invoke(be);
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    private static ItemStack rsi$getContainerItem(Object be, int slot) {
        try {
            return (ItemStack) be.getClass().getMethod("getItem", int.class).invoke(be, slot);
        } catch (Exception e) {
            try {
                return (ItemStack) be.getClass().getMethod("m_8020_", int.class).invoke(be, slot);
            } catch (Exception e2) {
                try {
                    Object handler = be.getClass().getMethod("getItemHandler").invoke(be);
                    return (ItemStack) handler.getClass().getMethod("getItem", int.class).invoke(handler, slot);
                } catch (Exception e3) {
                    try {
                        Object handler = be.getClass().getMethod("getItemHandler").invoke(be);
                        return (ItemStack) handler.getClass().getMethod("m_8020_", int.class).invoke(handler, slot);
                    } catch (Exception e4) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }
    }

    private static void rsi$setContainerItem(Object be, int slot, ItemStack stack) {
        try {
            be.getClass().getMethod("setItem", int.class, ItemStack.class).invoke(be, slot, stack);
        } catch (Exception e) {
            try {
                be.getClass().getMethod("m_6836_", int.class, ItemStack.class).invoke(be, slot, stack);
            } catch (Exception e2) {
                try {
                    Object handler = be.getClass().getMethod("getItemHandler").invoke(be);
                    handler.getClass().getMethod("setItem", int.class, ItemStack.class).invoke(handler, slot, stack);
                } catch (Exception e3) {
                    try {
                        Object handler = be.getClass().getMethod("getItemHandler").invoke(be);
                        handler.getClass().getMethod("m_6836_", int.class, ItemStack.class).invoke(handler, slot, stack);
                    } catch (Exception e4) {
                        RSIntegrationMod.LOGGER.warn("Failed to set container item in WissenCrystallizer", e4);
                    }
                }
            }
        }
    }
}
