package com.huanghuang.rsintegration.mods.tarotcards;

import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import shiroroku.tarotcards.Configuration;
import shiroroku.tarotcards.Item.TarotDeck.TarotDeckItem;
import shiroroku.tarotcards.Item.TarotItem;

/** 共振盘按普通背包处理，卡牌效果仍由塔罗牌模组执行。 */
public final class TarotResonanceAccess {
    private TarotResonanceAccess() {}

    public static boolean hasActiveCard(ServerPlayer player, Item card) {
        if (Configuration.require_card_in_curio.get()) return false;
        boolean includeDecks = Configuration.tarot_deck_applies_effects.get();
        return ResonanceInventoryBridge.hasItem(player, stack ->
                isActiveCard(stack, card) || (includeDecks
                        && stack.getItem() instanceof TarotDeckItem && deckContainsActiveCard(stack, card)));
    }

    private static boolean isActiveCard(ItemStack stack, Item card) {
        return !stack.isEmpty() && stack.is(card) && TarotItem.isActivated(stack);
    }

    private static boolean deckContainsActiveCard(ItemStack deck, Item card) {
        IItemHandler contents = deck.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (contents == null) return false;
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            if (isActiveCard(contents.getStackInSlot(slot), card)) return true;
        }
        return false;
    }
}
