package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.PlayerArmorInvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.items.wrapper.PlayerOffhandInvWrapper;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Client→Server: direct RS-network-to-player-inventory transfer for JEI recipe
 * ingredients, triggered by the side-panel "+" button on JEI recipe pages.
 * Extracts one set of recipe ingredients from the RS network and inserts them
 * into the player's full inventory (main + armor + offhand).
 */
public final class RSInventoryTransferPacket {

    final ResourceLocation recipeId;

    public RSInventoryTransferPacket(ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
    }

    static RSInventoryTransferPacket decode(FriendlyByteBuf buf) {
        return new RSInventoryTransferPacket(buf.readResourceLocation());
    }

    static void handle(RSInventoryTransferPacket packet,
                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            try {
                execute(player, packet.recipeId);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-InvTransfer] Failed for {}:",
                        packet.recipeId, e);
                player.sendSystemMessage(Component.translatable(
                        "rsi.side_panel.transfer_failed", packet.recipeId.toString()));
            }
        });
        context.setPacketHandled(true);
    }

    private static void execute(ServerPlayer player, ResourceLocation recipeId) {
        if (!RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) return;

        Recipe<?> recipe = player.serverLevel().getRecipeManager()
                .byKey(recipeId).orElse(null);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.side_panel.transfer_no_recipe", recipeId.toString()));
            return;
        }

        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        RSIntegrationMod.LOGGER.debug("[RSI-InvTransfer] Recipe {} → {} ingredient specs",
                recipeId, specs != null ? specs.size() : 0);
        if (specs != null) {
            for (int i = 0; i < specs.size(); i++) {
                IngredientSpec s = specs.get(i);
                ItemStack[] items = s.ingredient().getItems();
                RSIntegrationMod.LOGGER.debug("[RSI-InvTransfer]   spec[{}]: count={}, items={}",
                        i, s.count(), items.length > 0 ? items[0].getDescriptionId() : "(empty)");
            }
        }
        // Safety filter: strip WR crystal items from the transfer specs.
        // WR crystal infusion/ritual recipes should not extract the crystal
        // (it stays in-world as a catalyst).  filterWRCrystal covers most
        // paths but this is a defense-in-depth check at the transfer boundary.
        String recipeClassName = recipe.getClass().getName();
        if (recipeClassName.startsWith("mod.maxbogomol.wizards_reborn.")
                && !recipeClassName.endsWith("WissenCrystallizerRecipe")) {
            specs = filterCrystalSpecs(specs, recipe);
        }
        if (specs == null || specs.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.side_panel.transfer_no_ingredients"));
            return;
        }

        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.side_panel.transfer_no_network"));
            return;
        }

        if (network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.side_panel.transfer_no_permission"));
            return;
        }

        var inv = new CombinedInvWrapper(
                new PlayerMainInvWrapper(player.getInventory()),
                new PlayerArmorInvWrapper(player.getInventory()),
                new PlayerOffhandInvWrapper(player.getInventory()));

        var cacheList = network.getItemStorageCache().getList();
        var cacheStacks = cacheList != null
                ? cacheList.getStacks().stream().map(e -> e.getStack()).toList()
                : List.<ItemStack>of();

        int transferred = 0;
        int missing = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            int needed = spec.count();

            for (ItemStack stored : cacheStacks) {
                if (needed <= 0) break;
                if (!spec.ingredient().test(stored)) continue;
                int take = Math.min(needed, stored.getCount());
                if (take <= 0) continue;

                ItemStack req = stored.copy();
                req.setCount(take);
                ItemStack extracted = network.extractItem(req, take, Action.PERFORM);
                if (!extracted.isEmpty()) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(inv, extracted, false);
                    if (!remainder.isEmpty()) player.drop(remainder, false);
                    transferred += extracted.getCount() - remainder.getCount();
                    needed -= extracted.getCount();
                }
            }
            if (needed > 0) missing++;
        }

        player.containerMenu.broadcastChanges();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1, player.containerMenu.getStateId(), -1, player.containerMenu.getCarried()));

        int finalTransferred = transferred;
        if (finalTransferred > 0) {
            player.displayClientMessage(Component.translatable(
                    "rsi.side_panel.transferred", finalTransferred), false);
        }
        if (missing > 0) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.side_panel.transfer_missing", missing));
        }
    }

    /**
     * Strip crystal items from ingredient specs for WR recipes.
     * Uses the same class-based detection as {@link CraftPacketUtils#filterWRCrystal}
     * but operates on the spec list directly, serving as a safety net at the
     * transfer boundary.
     */
    private static List<IngredientSpec> filterCrystalSpecs(List<IngredientSpec> specs, Object recipe) {
        Set<net.minecraft.world.item.Item> crystalItems = new java.util.HashSet<>();

        // Try to use the ritual's getCrystalType API first.
        // CrystalRitualRecipe has getRitual(); ArcaneIteratorRecipe has getCrystalRitual().
        try {
            Object ritual = null;
            for (String methodName : new String[]{"getRitual", "getCrystalRitual"}) {
                java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect
                        .findMethod(recipe.getClass(), methodName, new Class<?>[0]);
                if (m != null) {
                    Object r = m.invoke(recipe);
                    if (r != null) { ritual = r; break; }
                }
            }
            if (ritual != null) {
                java.lang.reflect.Method getCrystalType = com.huanghuang.rsintegration.util.Reflect
                        .findMethod(ritual.getClass(), "getCrystalType",
                                new Class<?>[]{net.minecraft.world.item.ItemStack.class});
                if (getCrystalType != null) {
                    for (IngredientSpec spec : specs) {
                        for (net.minecraft.world.item.ItemStack is : spec.ingredient().getItems()) {
                            try {
                                if (getCrystalType.invoke(ritual, is) != null) {
                                    crystalItems.add(is.getItem());
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) { /* fall through to class-based detection */ }

        // Fallback: class-based crystal detection
        if (crystalItems.isEmpty()) {
            try {
                Class<?> crystalItemClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.item.CrystalItem");
                Class<?> fracturedClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.item.FracturedCrystalItem");
                Class<?> precisionClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.item.PrecisionCrystalItem");
                for (IngredientSpec spec : specs) {
                    for (net.minecraft.world.item.ItemStack is : spec.ingredient().getItems()) {
                        net.minecraft.world.item.Item item = is.getItem();
                        if (crystalItemClass.isInstance(item)
                                || fracturedClass.isInstance(item)
                                || precisionClass.isInstance(item)) {
                            crystalItems.add(item);
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-InvTransfer] WR crystal class detection failed: {}", e.toString());
            }
        }

        if (crystalItems.isEmpty()) return specs;

        List<IngredientSpec> filtered = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            boolean isCrystal = false;
            for (net.minecraft.world.item.ItemStack is : spec.ingredient().getItems()) {
                if (crystalItems.contains(is.getItem())) { isCrystal = true; break; }
            }
            if (!isCrystal) filtered.add(spec);
        }
        RSIntegrationMod.LOGGER.info("[RSI-InvTransfer] WR crystal filter: {} specs → {} ({} crystal specs removed)",
                specs.size(), filtered.size(), specs.size() - filtered.size());
        return filtered;
    }
}
