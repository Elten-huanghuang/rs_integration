package com.huanghuang.rsintegration.mods.goety;

import com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity;
import com.Polarice3.Goety.common.blocks.entities.PedestalBlockEntity;
import com.Polarice3.Goety.common.crafting.RitualRecipe;
import com.Polarice3.Goety.common.ritual.Ritual;
import com.Polarice3.Goety.utils.SEHelper;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
            if (!(recipe instanceof RitualRecipe ritualRecipe)) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", packet.recipeId.toString()));
                RSIntegrationMod.LOGGER.warn("Player {} selected unknown Goety ritual: {}", player.getName().getString(), packet.recipeId);
                return;
            }

            if (!level.isLoaded(packet.pos)) level.getChunk(packet.pos);
            BlockEntity be = level.getBlockEntity(packet.pos);
            if (!(be instanceof DarkAltarBlockEntity altar)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.altar_not_found"));
                return;
            }

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

    private static boolean tryStartRitual(ServerPlayer player, DarkAltarBlockEntity altar, RitualRecipe recipe) {
        Level beLevel = altar.getLevel();
        Level level = beLevel != null ? beLevel : player.level();
        BlockPos pos = altar.getBlockPos();
        ResourceKey<Level> altarDim = level.dimension();

        if (altar.getCurrentRitualRecipe() != null) {
            player.displayClientMessage(Component.translatable("rsi.goety.warn.ritual_running"), true);
            return false;
        }

        Ritual ritual = recipe.getRitual();
        List<PedestalBlockEntity> pedestals = ritual.getPedestals(level, pos);
        List<Ingredient> requiredIngredients = new ArrayList<>(recipe.getIngredients());

        if (pedestals.size() < requiredIngredients.size()) {
            player.displayClientMessage(Component.translatable("rsi.wr.error.pedestals_insufficient", requiredIngredients.size(), pedestals.size()), true);
            return false;
        }

        // 1. Check soul energy FIRST (before any extraction)
        int soulCost = recipe.getSoulCost();
        int playerSouls = SEHelper.getSESouls(player);
        if (playerSouls < soulCost) {
            player.displayClientMessage(Component.translatable("rsi.goety.error.soul_insufficient", soulCost, playerSouls), true);
            return false;
        }

        // 2. Validate ritual prerequisites BEFORE touching any inventory/pedestals
        try {
            java.lang.reflect.Method canStartMethod = ritual.getClass().getMethod("canStart",
                    Level.class, BlockPos.class, net.minecraft.world.entity.player.Player.class);
            boolean ok = (boolean) canStartMethod.invoke(ritual, level, pos, player);
            if (!ok) {
                player.displayClientMessage(Component.translatable("rsi.goety.error.ritual_prerequisites"), true);
                return false;
            }
        } catch (NoSuchMethodException ignored) {
            // Older Goety versions may not have canStart
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Goety] canStart check failed, proceeding anyway", e);
        }

        // 3. Save old pedestal items so we can restore them on failure
        List<ItemStack> oldPedestalItems = new ArrayList<>();
        for (PedestalBlockEntity p : pedestals) {
            oldPedestalItems.add(extractAllFromPedestal(p));
        }

        // 4. Collect activation item
        Ingredient activationIngredient = recipe.getActivationItem();
        ItemStack activationItem = takeFromHandler(altar.itemStackHandler, 0, activationIngredient, 1);
        if (activationItem.isEmpty()) {
            activationItem = CraftPacketUtils.ensureMaterialAvailable(player, altarDim, pos, activationIngredient, 1);
        }
        if (activationItem.isEmpty()) {
            restorePedestalItems(pedestals, oldPedestalItems);
            player.displayClientMessage(Component.translatable("rsi.goety.error.missing_activation", CraftPacketUtils.describeIngredient(activationIngredient)), true);
            return false;
        }

        // 5. Collect ingredients
        List<ItemStack> extractedIngredients = new ArrayList<>();
        for (Ingredient ingredient : requiredIngredients) {
            ItemStack extracted = ItemStack.EMPTY;
            for (PedestalBlockEntity p : pedestals) {
                extracted = takeFromPedestal(p, ingredient, 1);
                if (!extracted.isEmpty()) break;
            }
            if (extracted.isEmpty()) {
                extracted = CraftPacketUtils.ensureMaterialAvailable(player, altarDim, pos, ingredient, 1);
            }
            if (extracted.isEmpty()) {
                refundItems(player, activationItem, extractedIngredients);
                restorePedestalItems(pedestals, oldPedestalItems);
                player.displayClientMessage(Component.translatable("rsi.generic.error.missing_materials", CraftPacketUtils.describeIngredient(ingredient)), true);
                return false;
            }
            extractedIngredients.add(extracted);
        }

        // 6. Place ingredients on pedestals
        for (int i = 0; i < requiredIngredients.size(); i++) {
            PedestalBlockEntity pedestal = pedestals.get(i);
            final int idx = i;
            pedestal.itemStackHandler.ifPresent(handler -> {
                handler.insertItem(0, extractedIngredients.get(idx).copy(), false);
            });
        }

        // 7. Give saved old pedestal items to player
        for (ItemStack old : oldPedestalItems) {
            if (!old.isEmpty()) giveToPlayer(player, old);
        }

        // 8. Start ritual
        altar.startRitual(player, activationItem, recipe);
        RSIntegrationMod.LOGGER.debug("Player {} started Goety ritual '{}' via remote crafting.", player.getName().getString(), recipe.getId());
        return true;
    }

    private static ItemStack takeFromPedestal(PedestalBlockEntity pedestal, Ingredient ingredient, int count) {
        if (pedestal == null) return ItemStack.EMPTY;
        return pedestal.itemStackHandler.map(handler -> {
            ItemStack stack = handler.getStackInSlot(0);
            if (ingredient.test(stack) && stack.getCount() >= count) {
                return handler.extractItem(0, count, false);
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
    }

    private static ItemStack extractAllFromPedestal(PedestalBlockEntity pedestal) {
        if (pedestal == null) return ItemStack.EMPTY;
        return pedestal.itemStackHandler.map(handler ->
                handler.extractItem(0, 64, false)
        ).orElse(ItemStack.EMPTY);
    }

    private static void restorePedestalItems(List<PedestalBlockEntity> pedestals, List<ItemStack> items) {
        for (int i = 0; i < pedestals.size() && i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (!item.isEmpty()) {
                final int idx = i;
                pedestals.get(i).itemStackHandler.ifPresent(handler ->
                        handler.insertItem(0, item.copy(), false));
            }
        }
    }

    private static ItemStack takeFromHandler(net.minecraftforge.common.util.LazyOptional<?> lazyHandler,
                                               int startSlot, Ingredient ingredient, int count) {
        return lazyHandler.map(obj -> {
            var handler = (net.minecraftforge.items.IItemHandler) obj;
            for (int s = startSlot; s < handler.getSlots(); s++) {
                ItemStack stack = handler.getStackInSlot(s);
                if (ingredient.test(stack) && stack.getCount() >= count) {
                    return handler.extractItem(s, count, false);
                }
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
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

}
