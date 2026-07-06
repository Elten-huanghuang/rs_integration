package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class GoetyGuiSelectRitualPacket {

    private final ResourceLocation recipeId;
    @Nullable private final ResourceLocation dim;
    private final BlockPos pos;

    public GoetyGuiSelectRitualPacket(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
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

    public static GoetyGuiSelectRitualPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new GoetyGuiSelectRitualPacket(recipeId, dim, buf.readBlockPos());
    }

    public static void handle(GoetyGuiSelectRitualPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerLevel level = resolveLevel(player.server, packet.dim, player);
            if (level == null) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
                return;
            }

            Recipe<?> recipe = level.getRecipeManager().byKey(packet.recipeId).orElse(null);
            if (GoetyReflection.ritualRecipeClass == null || !GoetyReflection.ritualRecipeClass.isInstance(recipe)) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", packet.recipeId.toString()));
                RSIntegrationMod.LOGGER.warn("Player {} selected unknown Goety ritual: {}", player.getName().getString(), packet.recipeId);
                return;
            }
            Object ritualRecipe = recipe;

            ChunkUtils.loadChunk(level, packet.pos);
            BlockEntity be = level.getBlockEntity(packet.pos);
            if (GoetyReflection.darkAltarBEClass == null || !GoetyReflection.darkAltarBEClass.isInstance(be)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.altar_not_found"));
                return;
            }
            Object altar = be;

            if (!tryStartRitual(player, altar, ritualRecipe)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            }
        });
        context.setPacketHandled(true);
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, @Nullable ResourceLocation dim, ServerPlayer player) {
        return CraftPacketUtils.resolveLevel(server, dim, player);
    }

    @SuppressWarnings("unchecked")
    private static boolean tryStartRitual(ServerPlayer player, Object altar, Object recipe) {
        Level beLevel = Reflect.<Level>invoke(altar, "getLevel").orElse(null);
        Level level = beLevel != null ? beLevel : player.level();
        BlockPos pos = Reflect.<BlockPos>invoke(altar, "getBlockPos").orElse(player.blockPosition());
        ResourceKey<Level> altarDim = level.dimension();

        if (Reflect.getField(altar, "currentRitualRecipe").orElse(null) != null) {
            player.displayClientMessage(Component.translatable("rsi.goety.warn.ritual_running"), true);
            return false;
        }

        Object ritual = Reflect.invoke(recipe, "getRitual").orElse(null);
        if (ritual == null) return false;

        List<Object> pedestals;
        try {
            pedestals = (List<Object>) ritual.getClass()
                    .getMethod("getPedestals", Level.class, BlockPos.class)
                    .invoke(ritual, level, pos);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Goety] Failed to get pedestals", e);
            return false;
        }

        List<Ingredient> requiredIngredients = new ArrayList<>(((Recipe<?>) recipe).getIngredients());

        if (pedestals.size() < requiredIngredients.size()) {
            player.displayClientMessage(Component.translatable("rsi.wr.error.pedestals_insufficient", requiredIngredients.size(), pedestals.size()), true);
            return false;
        }

        // 1. Check soul energy FIRST (before any extraction)
        int soulCost = Reflect.<Integer>invoke(recipe, "getSoulCost").orElse(0);
        if (soulCost > 0) {
            try {
                var cageOpt = Reflect.getField(altar, "cursedCageTile");
                if (cageOpt.isPresent() && cageOpt.get() != null) {
                    Object cage = cageOpt.get();
                    int available = (int) cage.getClass().getMethod("getSouls").invoke(cage);
                    if (available < soulCost) {
                        player.displayClientMessage(Component.translatable(
                                "rsi.goety.error.insufficient_souls", soulCost, available), true);
                        return false;
                    }
                }
                // If cage is null (link gem/Arca), skip validation
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Goety] Soul check failed, skipping", e);
            }
        }

        // 2. Validate ritual prerequisites BEFORE touching any inventory/pedestals
        // 2a. Filter unsupported ritual subtypes
        if ((GoetyReflection.convertRitualClass != null && GoetyReflection.convertRitualClass.isInstance(ritual))
                || (GoetyReflection.teleportRitualClass != null && GoetyReflection.teleportRitualClass.isInstance(ritual))) {
            player.displayClientMessage(Component.translatable(
                    "rsi.goety.error.unsupported_ritual_type", ritual.getClass().getSimpleName()), true);
            return false;
        }
        // 2b. Reject sacrifice-requiring rituals
        if (Reflect.<Boolean>invoke(recipe, "requiresSacrifice").orElse(false)) {
            Component name = Reflect.<Component>invoke(recipe, "getEntityToSacrificeDisplayName")
                    .orElse(Component.literal("?"));
            player.displayClientMessage(Component.translatable(
                    "rsi.goety.error.requires_sacrifice", name), true);
            return false;
        }
        // 2c. Research check
        String researchId = Reflect.<String>invoke(recipe, "getResearch").orElse(null);
        if (researchId != null && !researchId.isEmpty()) {
            try {
                if (GoetyReflection.researchListClass != null && GoetyReflection.seHelperClass != null) {
                    Object research = GoetyReflection.researchListClass
                            .getMethod("getResearch", String.class).invoke(null, researchId);
                    if (research != null) {
                        boolean hasResearch = (boolean) GoetyReflection.seHelperClass.getMethod("hasResearch",
                                Player.class, research.getClass())
                                .invoke(null, player, research);
                        if (!hasResearch) {
                            player.displayClientMessage(Component.translatable(
                                    "rsi.goety.error.research_required",
                                    resolveResearchName(researchId)), true);
                            return false;
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Goety] Research check failed, skipping", e);
            }
        }
        // 2d. Enchant XP check
        if (GoetyReflection.enchantItemRitualClass != null && GoetyReflection.enchantItemRitualClass.isInstance(ritual)) {
            int xpCost = Reflect.<Integer>invoke(recipe, "getXPLevelCost").orElse(0);
            if (xpCost > 0 && player.experienceLevel < xpCost) {
                player.displayClientMessage(Component.translatable(
                        "rsi.goety.error.insufficient_xp", xpCost, player.experienceLevel), true);
                return false;
            }
        }
        // 2e. Ritual.isValid() pre-flight — validate BEFORE any extraction
        try {
            boolean valid = (boolean) ritual.getClass()
                    .getMethod("isValid", Level.class, BlockPos.class, GoetyReflection.darkAltarBEClass,
                            ServerPlayer.class, ItemStack.class, List.class)
                    .invoke(ritual, level, pos, altar, player, ItemStack.EMPTY, requiredIngredients);
            if (!valid) {
                player.displayClientMessage(Component.translatable("rsi.goety.error.one_click_failed"), true);
                RSIntegrationMod.LOGGER.warn("[RSI-Goety] ritual.isValid() returned false before extraction for {}",
                        ((Recipe<?>) recipe).getId());
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Goety] ritual.isValid check failed", e);
            return false;
        }

        // 3. Save old pedestal items so we can restore them on failure
        List<ItemStack> oldPedestalItems = new ArrayList<>();
        for (Object p : pedestals) {
            oldPedestalItems.add(extractAllFromPedestal(p));
        }

        // 4. Collect activation item
        Ingredient activationIngredient = Reflect.<Ingredient>invoke(recipe, "getActivationItem").orElse(Ingredient.EMPTY);
        ItemStack activationItem = takeFromHandler(altar, 0, activationIngredient, 1);
        if (activationItem.isEmpty()) {
            activationItem = CraftPacketUtils.ensureMaterialAvailable(player, altarDim, pos, activationIngredient, 1);
        }
        if (activationItem.isEmpty()) {
            restorePedestalItems(pedestals, oldPedestalItems);
            player.displayClientMessage(Component.translatable("rsi.goety.error.missing_activation",
                    CraftPacketUtils.describeIngredient(activationIngredient)), true);
            return false;
        }

        // 5. Collect ingredients
        List<ItemStack> extractedIngredients = new ArrayList<>();
        for (Ingredient ingredient : requiredIngredients) {
            ItemStack extracted = ItemStack.EMPTY;
            for (Object p : pedestals) {
                extracted = takeFromPedestal(p, ingredient, 1);
                if (!extracted.isEmpty()) break;
            }
            if (extracted.isEmpty()) {
                extracted = CraftPacketUtils.ensureMaterialAvailable(player, altarDim, pos, ingredient, 1);
            }
            if (extracted.isEmpty()) {
                refundItems(player, activationItem, extractedIngredients);
                restorePedestalItems(pedestals, oldPedestalItems);
                player.displayClientMessage(Component.translatable("rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(ingredient)), true);
                return false;
            }
            extractedIngredients.add(extracted);
        }

        // 6. Place ingredients on pedestals
        for (int i = 0; i < requiredIngredients.size(); i++) {
            Object pedestal = pedestals.get(i);
            final int idx = i;
            var handlerOpt = Reflect.<Object>getField(pedestal, "itemStackHandler");
            handlerOpt.ifPresent(obj -> {
                LazyOptional<IItemHandler> lazy = (LazyOptional<IItemHandler>) obj;
                lazy.ifPresent(handler -> {
                    handler.insertItem(0, extractedIngredients.get(idx).copy(), false);
                });
            });
        }

        // 7. Give saved old pedestal items to player
        for (ItemStack old : oldPedestalItems) {
            if (!old.isEmpty()) giveToPlayer(player, old);
        }

        // 8. Start ritual
        try {
            GoetyReflection.darkAltarBEClass.getMethod("startRitual", Player.class, ItemStack.class, GoetyReflection.ritualRecipeClass)
                    .invoke(altar, player, activationItem, recipe);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Goety] startRitual threw after extraction — refunding activation item", e);
            if (!activationItem.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, activationItem.copy());
            }
            return false;
        }
        RSIntegrationMod.LOGGER.debug("Player {} started Goety ritual '{}' via remote crafting.",
                player.getName().getString(), ((Recipe<?>) recipe).getId());
        return true;
    }

    @SuppressWarnings("unchecked")
    private static ItemStack takeFromPedestal(Object pedestal, Ingredient ingredient, int count) {
        if (pedestal == null) return ItemStack.EMPTY;
        var opt = Reflect.<Object>getField(pedestal, "itemStackHandler");
        if (opt.isEmpty()) return ItemStack.EMPTY;
        return ((LazyOptional<IItemHandler>) opt.get()).map(handler -> {
            ItemStack stack = handler.getStackInSlot(0);
            if (ingredient.test(stack) && stack.getCount() >= count) {
                return handler.extractItem(0, count, false);
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static ItemStack extractAllFromPedestal(Object pedestal) {
        if (pedestal == null) return ItemStack.EMPTY;
        var opt = Reflect.<Object>getField(pedestal, "itemStackHandler");
        if (opt.isEmpty()) return ItemStack.EMPTY;
        return ((LazyOptional<IItemHandler>) opt.get()).map(handler ->
                handler.extractItem(0, 64, false)
        ).orElse(ItemStack.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static void restorePedestalItems(List<Object> pedestals, List<ItemStack> items) {
        for (int i = 0; i < pedestals.size() && i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (!item.isEmpty()) {
                var handlerOpt = Reflect.<Object>getField(pedestals.get(i), "itemStackHandler");
                handlerOpt.ifPresent(obj -> {
                    LazyOptional<IItemHandler> lazy = (LazyOptional<IItemHandler>) obj;
                    lazy.ifPresent(handler -> handler.insertItem(0, item.copy(), false));
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ItemStack takeFromHandler(Object altarBlockEntity, int startSlot, Ingredient ingredient, int count) {
        try {
            var handlerOpt = Reflect.<Object>getField(altarBlockEntity, "itemStackHandler");
            if (handlerOpt.isEmpty()) return ItemStack.EMPTY;
            return ((LazyOptional<IItemHandler>) handlerOpt.get()).map(handler -> {
                for (int s = startSlot; s < handler.getSlots(); s++) {
                    ItemStack stack = handler.getStackInSlot(s);
                    if (ingredient.test(stack) && stack.getCount() >= count) {
                        return handler.extractItem(s, count, false);
                    }
                }
                return ItemStack.EMPTY;
            }).orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }

    private static void giveToPlayer(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        var inv = player.getInventory();
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack) && s.getCount() < s.getMaxStackSize()) {
                int space = s.getMaxStackSize() - s.getCount();
                int moved = Math.min(space, stack.getCount());
                s.grow(moved);
                stack.shrink(moved);
                if (stack.isEmpty()) return;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, stack.copy());
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack) && s.getCount() < s.getMaxStackSize()) {
                int space = s.getMaxStackSize() - s.getCount();
                int moved = Math.min(space, stack.getCount());
                s.grow(moved);
                stack.shrink(moved);
                if (stack.isEmpty()) return;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, stack.copy());
                return;
            }
        }
        player.drop(stack, false);
    }

    private static void refundItems(ServerPlayer player, ItemStack activation, List<ItemStack> ingredients) {
        if (!activation.isEmpty()) {
            ItemHandlerHelper.giveItemToPlayer(player, activation);
        }
        for (ItemStack ingredient : ingredients) {
            ItemHandlerHelper.giveItemToPlayer(player, ingredient);
        }
    }

    private static String resolveResearchName(String researchId) {
        try {
            Item item = BuiltInRegistries.ITEM.get(
                    new ResourceLocation("goety", researchId + "_scroll"));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return item.getDefaultInstance().getDisplayName().getString();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Goety] resolveResearchName failed for {}", researchId, e);
            /* fall through */
        }
        return researchId;
    }
}
