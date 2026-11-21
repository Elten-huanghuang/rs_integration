package com.huanghuang.rsintegration.mods.tarotcards;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView.StoredStack;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import shiroroku.tarotcards.Configuration;
import shiroroku.tarotcards.Item.TarotDeck.TarotDeckItem;
import shiroroku.tarotcards.Item.TarotItem;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TarotResonanceAccessTest extends BootstrapTest {
    private ServerPlayer player;
    private MockedStatic<ResonanceStorageResolvers> resolvers;

    @BeforeEach
    void setUp() {
        var resonanceConfig = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(resonanceConfig);
        RSIntegrationConfig.COMMON_SPEC.setConfig(resonanceConfig);
        var tarotConfig = CommentedConfig.inMemory();
        Configuration.config.correct(tarotConfig);
        Configuration.config.setConfig(tarotConfig);
        player = mock(ServerPlayer.class);
        resolvers = mockStatic(ResonanceStorageResolvers.class);
    }

    @AfterEach
    void tearDown() {
        resolvers.close();
        Configuration.config.setConfig(null);
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void nativeMixinTargetMatchesInstalledVersion() {
        assertTrue(new RSIntegrationMixinPlugin().shouldApplyMixin(TarotItem.class.getName(),
                "com.huanghuang.rsintegration.mixin.tarotcards.TarotItemMixin"));
    }

    @Test
    void looseCardsMustBeActiveAndMatchTheRequestedItem() {
        setDiskContents(inactiveCard(), new ItemStack(Items.BOOK));
        assertFalse(hasPaperCard());

        setDiskContents(inactiveCard(), new ItemStack(Items.PAPER));
        assertTrue(hasPaperCard());
    }

    @Test
    void deckEffectsRespectBothTheDeckSettingAndCardActivation() {
        ItemStackHandler contents = new ItemStackHandler(2);
        contents.setStackInSlot(0, inactiveCard());
        setDiskContents(deckWith(contents));
        assertFalse(hasPaperCard());

        contents.setStackInSlot(1, new ItemStack(Items.PAPER));
        assertTrue(hasPaperCard());

        Configuration.tarot_deck_applies_effects.set(false);
        assertFalse(hasPaperCard());
    }

    @Test
    void curioRequirementAndResonanceSwitchBothPreventDiskEffects() {
        setDiskContents(new ItemStack(Items.PAPER));
        Configuration.require_card_in_curio.set(true);
        assertFalse(hasPaperCard());
        resolvers.verifyNoInteractions();

        Configuration.require_card_in_curio.set(false);
        RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.set(false);
        assertFalse(hasPaperCard());
        resolvers.verifyNoInteractions();
    }

    private boolean hasPaperCard() {
        return TarotResonanceAccess.hasActiveCard(player, Items.PAPER);
    }

    private void setDiskContents(ItemStack... stacks) {
        List<StoredStack> stored = IntStream.range(0, stacks.length)
                .mapToObj(slot -> new StoredStack(slot, stacks[slot]))
                .toList();
        ResonanceStorageView disk = mock(ResonanceStorageView.class, CALLS_REAL_METHODS);
        when(disk.storedStacks()).thenReturn(stored);
        resolvers.when(() -> ResonanceStorageResolvers.resolveAll(player)).thenReturn(List.of(disk));
    }

    private static ItemStack inactiveCard() {
        ItemStack card = new ItemStack(Items.PAPER);
        card.getOrCreateTag().putBoolean("deactivated", true);
        return card;
    }

    private static ItemStack deckWith(IItemHandler contents) {
        TarotDeckItem item = mock(TarotDeckItem.class);
        ItemStack deck = mock(ItemStack.class);
        when(deck.getItem()).thenReturn(item);
        when(deck.copy()).thenReturn(deck);
        when(deck.getCapability(ForgeCapabilities.ITEM_HANDLER)).thenReturn(LazyOptional.of(() -> contents));
        return deck;
    }
}
