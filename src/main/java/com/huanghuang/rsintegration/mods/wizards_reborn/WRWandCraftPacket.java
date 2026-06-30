package com.huanghuang.rsintegration.mods.wizards_reborn;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.network.RSIntegration;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
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
        if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) {
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

        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, dim, pos);
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

        // Phase 1.5: check wissen energy before extracting items from RS
        if (!checkWissen(player, be, recipe)) return false;

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-WR] Ledger commit failed for WissenCrystallizer");
            return false;
        }

        // Phase 3: place items in slots
        try {
            for (int i = 0; i < templates.size(); i++) {
                ItemStack taken = templates.get(i);
                if (!taken.isEmpty()) {
                    rsi$setContainerItem(be, i, taken);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-WR] WissenCrystallizer item placement failed, rolling back", e);
            // Clear slots that may have been filled before the exception
            for (int j = 0; j < templates.size(); j++) {
                if (!templates.get(j).isEmpty()) {
                    try { rsi$setContainerItem(be, j, ItemStack.EMPTY); } catch (Exception ignored) {}
                }
            }
            refundTemplates(network, player, templates);
            return false;
        }

        try {
            rsi$getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            rsi$syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-WR] wissenWandFunction invoke failed, rolling back", e);
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
            pedestals = (List<?>) rsi$getMethod(be.getClass(), "getPedestals").invoke(be);
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

        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, dim, pos);
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

        // Phase 1.5: check wissen energy before extracting items from RS
        if (!checkWissen(player, be, recipe)) return false;

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
                rsi$syncBlockEntity(pedestal);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-WR] Failed to place item on ArcaneIterator pedestal {}: {}", i, e.getMessage());
                for (int j = 0; j < i; j++) {
                    try { rsi$setContainerItem(pedestals.get(j), 0, ItemStack.EMPTY); rsi$syncBlockEntity(pedestals.get(j)); } catch (Exception ign) {}
                }
                refundTemplates(network, player, templates);
                return false;
            }
        }

        try {
            rsi$getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            rsi$syncBlockEntity(be);
        } catch (Exception ex) {
            for (int i = 0; i < templates.size(); i++) {
                try { rsi$setContainerItem(pedestals.get(i), 0, ItemStack.EMPTY); rsi$syncBlockEntity(pedestals.get(i)); } catch (Exception ign) {}
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

        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, dim, pos);
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

        // Phase 1.5: check wissen energy before extracting items from RS
        if (!checkWissen(player, be, recipe)) return false;

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
            rsi$getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            rsi$syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-WR] wissenWandFunction invoke failed, rolling back", e);
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
                ritual = rsi$getMethod(be.getClass(), "getCrystalRitual").invoke(be);
            } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
        }
        if (ritual == null) {
            RSIntegrationMod.LOGGER.warn("Failed to extract CrystalRitual from recipe {} or CrystalBlock", recipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.ritual_data_failed"));
            return false;
        }

        Level beLevel = be.getLevel();
        BlockPos pos = be.getBlockPos();
        ResourceKey<Level> dim = beLevel != null ? beLevel.dimension() : Level.OVERWORLD;

        // Crystal must already be in the crystal block (placed by player).
        // RS does NOT supply the crystal — the player chooses the correct
        // crystal type manually.
        try {
            Object crystalItem = rsi$getMethod(be.getClass(), "getCrystalItem").invoke(be);
            if (crystalItem == null || ((ItemStack) crystalItem).isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.wr.error.crystal_ritual_no_crystal"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Failed to check crystal item in CrystalBlockEntity", e);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.crystal_ritual_no_crystal"));
            return false;
        }

        Object area;
        try {
            area = rsi$getMethod(ritual.getClass(), "getArea", be.getClass()).invoke(ritual, be);
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
            pedestals = (List<?>) rsi$getMethod(crystalRitualClass, "getPedestalsWithArea", Level.class, BlockPos.class, ritualAreaClass)
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

        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, dim, pos);
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

        // Phase 1.5: check wissen energy before extracting items from RS
        if (!checkWissen(player, be, recipe)) return false;

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
                rsi$syncBlockEntity(pedestal);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("Failed to place item on ArcanePedestal slot {}: {}", i, e.getMessage());
                for (int j = 0; j < i; j++) {
                    if (!templates.get(j).isEmpty()) {
                        try { rsi$setContainerItem(pedestals.get(j), 0, ItemStack.EMPTY); rsi$syncBlockEntity(pedestals.get(j)); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                    }
                }
                refundTemplates(network, player, templates);
                return false;
            }
        }

        try {
            rsi$getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            rsi$syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("Failed to invoke wissenWandFunction on CrystalBlockEntity", e);
            for (int i = 0; i < templates.size(); i++) {
                if (!templates.get(i).isEmpty()) {
                    try { rsi$setContainerItem(pedestals.get(i), 0, ItemStack.EMPTY); rsi$syncBlockEntity(pedestals.get(i)); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
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
                    } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
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
                ItemStack leftover = network.insertItem(refund, refund.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (!leftover.isEmpty()) {
                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
                }
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

    @Nullable
    private static IItemHandler rsi$getForgeItemHandler(Object be) {
        return WRContainerHelper.getForgeItemHandler(be);
    }

    @Nullable
    private static net.minecraft.world.SimpleContainer rsi$getLiveSimpleContainer(Object be) {
        return WRContainerHelper.getLiveSimpleContainer(be);
    }

    @Nullable
    private static net.minecraft.world.SimpleContainer rsi$getSimpleContainer(Object be) {
        return WRContainerHelper.getSimpleContainer(be);
    }

    private static int rsi$getContainerSize(Object be) {
        return WRContainerHelper.getContainerSize(be);
    }

    private static ItemStack rsi$getContainerItem(Object be, int slot) {
        return WRContainerHelper.getContainerItem(be, slot);
    }

    private static void rsi$setContainerItem(Object be, int slot, ItemStack stack) {
        WRContainerHelper.setContainerItem(be, slot, stack);
    }

    private static java.lang.reflect.Method rsi$getMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(
                clazz, name, paramTypes);
        if (m == null) throw new NoSuchMethodException(clazz.getName() + "." + name);
        return m;
    }

    // ── Wissen energy check ───────────────────────────────────────

    /**
     * Check whether the machine has enough Wissen energy for the recipe.
     * Returns true if wissen is sufficient or if the check can't be performed.
     * Must be called BEFORE ledger commit to avoid extracting items that can't be used.
     */
    private static boolean checkWissen(ServerPlayer player, BlockEntity be, Recipe<?> recipe) {
        int cost = readWissenCost(recipe);
        if (cost <= 0) return true; // no wissen cost, pass

        int current = readCurrentWissen(be);
        if (current < cost) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.wr.error.insufficient_wissen",
                    String.format("%,d", current), String.format("%,d", cost)));
            return false;
        }
        return true;
    }

    private static int readWissenCost(Recipe<?> recipe) {
        try {
            java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(
                    recipe.getClass(), "getWissen", new Class<?>[0]);
            if (m != null) return (int) m.invoke(recipe);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-WR] Failed to read wissen cost", e);
        }
        return 0;
    }

    private static int readCurrentWissen(BlockEntity be) {
        try {
            java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(
                    be.getClass(), "getWissen", new Class<?>[0]);
            if (m != null) return (int) m.invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] Failed to read current wissen", e);
        }
        // Fallback: try reading the public `wissen` field directly
        try {
            Class<?> clazz = be.getClass();
            while (clazz != null && clazz != Object.class) {
                try {
                    java.lang.reflect.Field f = clazz.getDeclaredField("wissen");
                    f.setAccessible(true);
                    return f.getInt(be);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] Failed to read wissen field", e);
        }
        return -1; // can't determine — fail safe
    }

    private static void rsi$syncBlockEntity(Object be) {
        WRContainerHelper.syncBlockEntity(be);
    }
}
