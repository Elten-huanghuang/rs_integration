package com.huanghuang.rsintegration.module.malum;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
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
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class MalumCraftPacket {

    private final ResourceLocation recipeId;
    @Nullable private final ResourceLocation dim;
    private final BlockPos pos;

    public MalumCraftPacket(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
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

    public static MalumCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new MalumCraftPacket(recipeId, dim, buf.readBlockPos());
    }

    public static void handle(MalumCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }        context.enqueueWork(() -> {
            try {
                tryCraft(player, packet.recipeId, packet.dim, packet.pos);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("Malum craft failed for recipe {}:", packet.recipeId, e);
                player.sendSystemMessage(Component.translatable("rsi.malum.error.craft_failed", e.getMessage()));
            }
        });
        context.setPacketHandled(true);
    }

    private static void tryCraft(ServerPlayer player, ResourceLocation recipeId,
                                  @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return;
        }

        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        Object altar = castSpiritAltar(be);
        if (altar == null) {
            player.sendSystemMessage(Component.translatable("rsi.malum.error.altar_not_found"));
            return;
        }

        ResourceKey<Level> altarDim = level.dimension();

        // -- Phase 1: validate all preconditions --
        Object invMain = getField(altar, "inventory");
        Object invSpirit = getField(altar, "spiritInventory");
        if (invMain == null || invSpirit == null) {
            player.sendSystemMessage(Component.translatable("rsi.malum.error.inventory_error"));
            return;
        }

        Boolean crafting = (Boolean) getField(altar, "isCrafting");
        if (Boolean.TRUE.equals(crafting)) {
            player.sendSystemMessage(Component.translatable("rsi.malum.warn.already_crafting"));
            return;
        }

        try {
            boolean mainEmpty = (boolean) invMain.getClass().getMethod("isEmpty").invoke(invMain);
            if (!mainEmpty) {
                player.sendSystemMessage(Component.translatable("rsi.malum.warn.not_empty"));
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Malum] isEmpty() check failed", e);
        }

        List<?> pedestals;
        try {
            pedestals = capturePedestals(level, pos);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Malum] Failed to capture pedestals", e);
            player.sendSystemMessage(Component.translatable("rsi.malum.error.pedestal_failed", e.getMessage()));
            return;
        }
        int emptyPedestalSlots = countEmptyPedestalSlots(pedestals);

        // Read recipe data
        Object inputObj = getField(recipe, "input");
        int centerCount = CraftPacketUtils.readIngredientCount(inputObj, 1);

        List<?> extraItems = (List<?>) getField(recipe, "extraItems");
        List<?> spirits = (List<?>) getField(recipe, "spirits");

        int extraCount = extraItems != null ? extraItems.size() : 0;
        int spiritCount = spirits != null ? spirits.size() : 0;

        if (extraCount > emptyPedestalSlots) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestals_insufficient", extraCount, emptyPedestalSlots));
            return;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Malum] Preconditions OK: recipe={} centerCount={} extraItems={} spirits={} emptyPedestals={}",
                recipeId, centerCount, extraCount, spiritCount, emptyPedestalSlots);

        // -- Phase 2: reserve all items (deferred extraction via ledger) --
        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, altarDim, pos);
        ExtractionLedger ledger = new ExtractionLedger();
        List<Integer> filledPedestalIndices = new ArrayList<>();
        try {
            // 1. Center item -> altar inventory slot 0
            if (inputObj != null) {
                Ingredient centerIng = (Ingredient) getField(inputObj, "ingredient");
                if (centerIng != null) {
                    ItemStack stack = ensureMaterialAvailable(player, altarDim, pos, centerIng, centerCount, ledger);
                    if (stack.isEmpty()) {
                        player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                                CraftPacketUtils.describeIngredient(centerIng)));
                        return;
                    }
                    setIHandlerSlot(invMain, 0, stack);
                }
            }

            // 2. Extra items -> runewood pedestals
            int pedIdx = 0;
            for (int i = 0; i < extraCount; i++) {
                Object eItem = extraItems.get(i);
                Ingredient ing = (Ingredient) getField(eItem, "ingredient");
                if (ing == null) continue;
                int itemCount = CraftPacketUtils.readIngredientCount(eItem, 1);
                ItemStack stack = ensureMaterialAvailable(player, altarDim, pos, ing, itemCount, ledger);
                if (stack.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                            CraftPacketUtils.describeIngredient(ing)));
                    clearAltarSlots(invMain, invSpirit, inputObj != null ? 1 : 0, 0);
                    clearPedestalsByIndex(pedestals, filledPedestalIndices);
                    return;
                }
                int placedIdx = placeOnNextEmptyPedestal(pedestals, pedIdx, stack);
                filledPedestalIndices.add(placedIdx - 1);
                pedIdx = placedIdx;
            }

            // 3. Spirit items -> altar spiritInventory
            for (int i = 0; i < spiritCount; i++) {
                Object swc = spirits.get(i);
                int sCount = CraftPacketUtils.readIngredientCount(swc, 1);
                Item spiritItem = (Item) swc.getClass().getMethod("getItem").invoke(swc);
                Ingredient spiritIng = Ingredient.of(spiritItem);
                ItemStack stack = ensureMaterialAvailable(player, altarDim, pos, spiritIng, sCount, ledger);
                if (stack.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                            CraftPacketUtils.describeIngredient(spiritIng)));
                    clearAltarSlots(invMain, invSpirit, inputObj != null ? 1 : 0, i);
                    clearPedestalsByIndex(pedestals, filledPedestalIndices);
                    return;
                }
                setIHandlerSlot(invSpirit, i, stack);
            }

        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Malum] Placement failed for recipe {}:", recipeId, e);
            clearAltarSlots(invMain, invSpirit, inputObj != null ? 1 : 0, spiritCount);
            clearPedestalsByIndex(pedestals, filledPedestalIndices);
            player.sendSystemMessage(Component.translatable("rsi.malum.error.craft_failed", e.getMessage()));
            return;
        }

        // Commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Malum] Ledger commit failed for recipe {}", recipeId);
            clearAltarSlots(invMain, invSpirit, inputObj != null ? 1 : 0, spiritCount);
            clearPedestalsByIndex(pedestals, filledPedestalIndices);
            player.sendSystemMessage(Component.translatable("rsi.malum.error.craft_failed", "commit failed"));
            return;
        }

        // -- Phase 3: start the infusion --
        boolean startedWithAnimation = false;
        try {
            for (java.lang.reflect.Method m : altar.getClass().getMethods()) {
                if (m.getName().equals("craft") && m.getParameterCount() == 1
                        && Recipe.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    m.invoke(altar, recipe);
                    startedWithAnimation = true;
                    break;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Malum] craft(Recipe) threw for recipe {}:", recipeId, e);
            player.sendSystemMessage(Component.translatable("rsi.malum.error.start_failed", e.getMessage()));
            refundAndClearAltar(invMain, invSpirit, inputObj != null ? 1 : 0, spiritCount, network, player);
            refundAndClearPedestals(pedestals, filledPedestalIndices, network, player);
            return;
        }

        if (!startedWithAnimation) {
            try {
                setField(altar, "recipe", recipe);
                altar.getClass().getMethod("craft").invoke(altar);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Malum] craft() threw for recipe {}:", recipeId, e);
                player.sendSystemMessage(Component.translatable("rsi.malum.error.start_failed", e.getMessage()));
                refundAndClearAltar(invMain, invSpirit, inputObj != null ? 1 : 0, spiritCount, network, player);
                refundAndClearPedestals(pedestals, filledPedestalIndices, network, player);
                return;
            }

            // Fallback cleanup: no-arg craft() doesn't consume items
            try {
                if (inputObj != null) {
                    setIHandlerSlot(invMain, 0, ItemStack.EMPTY);
                }
                for (int i = 0; i < spiritCount; i++) {
                    setIHandlerSlot(invSpirit, i, ItemStack.EMPTY);
                }
                clearPedestalsByIndex(pedestals, filledPedestalIndices);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Malum] Failed to clear items after craft: {}", e.getMessage());
            }
        }

        player.displayClientMessage(Component.translatable("rsi.malum.info.craft_started", getRecipeName(recipe)), true);
        RSIntegrationMod.LOGGER.debug("[RSI-Malum] Player {} started spirit infusion '{}'",
                player.getName().getString(), recipeId);
    }

    // -- Pedestal helpers --

    private static void clearAltarSlots(Object invMain, Object invSpirit, int mainSlots, int spiritSlots) {
        for (int i = 0; i < mainSlots; i++) {
            try { setIHandlerSlot(invMain, i, ItemStack.EMPTY); } catch (Exception ignored) {}
        }
        for (int i = 0; i < spiritSlots; i++) {
            try { setIHandlerSlot(invSpirit, i, ItemStack.EMPTY); } catch (Exception ignored) {}
        }
    }

    private static void clearPedestalsByIndex(List<?> pedestals, List<Integer> indices) {
        for (int idx : indices) {
            if (idx < 0 || idx >= pedestals.size()) continue;
            try {
                Object ap = pedestals.get(idx);
                Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(inv, 0, ItemStack.EMPTY);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Malum] Failed to clear pedestal {}: {}", idx, e.getMessage());
            }
        }
    }

    private static void refundAndClearAltar(Object invMain, Object invSpirit, int mainSlots, int spiritSlots,
                                            @Nullable INetwork network, ServerPlayer player) {
        for (int i = 0; i < mainSlots; i++) {
            try {
                ItemStack stack = (ItemStack) invMain.getClass()
                        .getMethod("getStackInSlot", int.class).invoke(invMain, i);
                if (!stack.isEmpty()) {
                    if (network != null) {
                        network.insertItem(stack.copy(), stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
                    }
                    setIHandlerSlot(invMain, i, ItemStack.EMPTY);
                }
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < spiritSlots; i++) {
            try {
                ItemStack stack = (ItemStack) invSpirit.getClass()
                        .getMethod("getStackInSlot", int.class).invoke(invSpirit, i);
                if (!stack.isEmpty()) {
                    if (network != null) {
                        network.insertItem(stack.copy(), stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
                    }
                    setIHandlerSlot(invSpirit, i, ItemStack.EMPTY);
                }
            } catch (Exception ignored) {}
        }
    }

    private static void refundAndClearPedestals(List<?> pedestals, List<Integer> indices,
                                                 @Nullable INetwork network, ServerPlayer player) {
        for (int idx : indices) {
            if (idx < 0 || idx >= pedestals.size()) continue;
            try {
                Object ap = pedestals.get(idx);
                Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                ItemStack stack = (ItemStack) inv.getClass()
                        .getMethod("getStackInSlot", int.class).invoke(inv, 0);
                if (!stack.isEmpty()) {
                    if (network != null) {
                        network.insertItem(stack.copy(), stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
                    }
                }
                inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(inv, 0, ItemStack.EMPTY);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Malum] Failed to refund/clear pedestal {}: {}", idx, e.getMessage());
            }
        }
    }

    private static List<?> capturePedestals(ServerLevel level, BlockPos altarPos) throws Exception {
        Class<?> helperClass = Class.forName(
                "com.sammy.malum.common.block.curiosities.spirit_altar.AltarCraftingHelper");
        return (List<?>) helperClass.getMethod("capturePedestals", Level.class, BlockPos.class)
                .invoke(null, level, altarPos);
    }

    private static int countEmptyPedestalSlots(List<?> pedestals) {
        int count = 0;
        for (Object ap : pedestals) {
            try {
                Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                boolean empty = (boolean) inv.getClass().getMethod("isEmpty").invoke(inv);
                if (empty) count++;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Malum] Failed to check pedestal: {}", e.getMessage());
            }
        }
        return count;
    }

    private static int placeOnNextEmptyPedestal(List<?> pedestals, int startIdx, ItemStack stack) throws Exception {
        for (int i = startIdx; i < pedestals.size(); i++) {
            Object ap = pedestals.get(i);
            Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
            boolean empty = (boolean) inv.getClass().getMethod("isEmpty").invoke(inv);
            if (empty) {
                inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(inv, 0, stack);
                return i + 1;
            }
        }
        throw new IllegalStateException("No empty pedestal slot found from index " + startIdx);
    }

    // -- Reflection helpers (safe for third-party Malum internals) --

    @Nullable
    private static Object castSpiritAltar(BlockEntity be) {
        if (be == null) return null;
        try {
            Class<?> clazz = Class.forName(
                    "com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlockEntity");
            if (clazz.isInstance(be)) return be;
        } catch (ClassNotFoundException ignored) {}
        return null;
    }

    @Nullable
    private static Object getField(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setIHandlerSlot(Object handler, int slot, ItemStack stack) throws Exception {
        handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(handler, slot, stack);
    }

    private static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                      BlockPos altarPos, Ingredient ingredient, int count,
                                                      @Nullable ExtractionLedger ledger) {
        return CraftPacketUtils.ensureMaterialAvailable(player, altarDim, altarPos, ingredient, count, ledger);
    }

    private static String getRecipeName(Recipe<?> recipe) {
        try {
            Object result = recipe.getClass().getMethod("getResultItem").invoke(recipe);
            if (result instanceof ItemStack stack && !stack.isEmpty()) {
                return stack.getDisplayName().getString();
            }
        } catch (Exception ignored) {}
        return recipe.getId().toString();
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, @Nullable ResourceLocation dim,
                                            ServerPlayer player) {
        return CraftPacketUtils.resolveLevel(server, dim, player);
    }
}
