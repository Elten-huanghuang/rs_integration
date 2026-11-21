package com.huanghuang.rsintegration.mods.slashblade;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.common.collect.ImmutableMultimap;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView.StoredStack;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class NineSwordBladeSelectionTest extends BootstrapTest {
    private ServerPlayer player;
    private Inventory inventory;
    private ItemSlashBlade blade;
    private MockedStatic<ResonanceStorageResolvers> resolvers;

    @BeforeAll
    static void initializeBladeCapabilities() throws ClassNotFoundException {
        try (var manager = mockStatic(CapabilityManager.class)) {
            manager.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(Capability.class));
            Class.forName(ItemSlashBlade.class.getName());
        }
    }

    @BeforeEach
    void setUp() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
        player = mock(ServerPlayer.class);
        inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        blade = mock(ItemSlashBlade.class);
        resolvers = mockStatic(ResonanceStorageResolvers.class);
        resolvers.when(() -> ResonanceStorageResolvers.resolveAll(player)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        resolvers.close();
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void hotbarAndBothBackendsShareDefaultLimit() {
        fillHotbar(7);
        inventory.setItem(7, new ItemStack(Items.DIAMOND_SWORD));
        inventory.setItem(9, blade(100, 1));
        ResonanceStorageView rs = disk(new StoredStack(5, blade(20, 1)));
        ResonanceStorageView bd = disk(new StoredStack(10, blade(100, 1)),
                new StoredStack(2, blade(30, 1)));
        resolvers.when(() -> ResonanceStorageResolvers.resolveAll(player)).thenReturn(List.of(rs, bd));

        assertEquals(9, NineSwordBladeSelection.count(player));
        assertEquals(64f, NineSwordBladeSelection.totalDamage(player));
    }

    @Test
    void fullHotbarDoesNotReadResonance() {
        fillHotbar(9);
        ResonanceStorageView disk = disk(new StoredStack(0, blade(100, 1)));
        resolvers.when(() -> ResonanceStorageResolvers.resolveAll(player)).thenReturn(List.of(disk));

        assertEquals(9, NineSwordBladeSelection.count(player));
        assertEquals(18f, NineSwordBladeSelection.totalDamage(player));
        resolvers.verifyNoInteractions();
    }

    @Test
    void customLimitAlsoAppliesWithoutResonance() {
        RSIntegrationConfig.NINE_SWORD_MAX_COUNT.set(3);
        RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.set(false);
        fillHotbar(5);

        assertEquals(3, NineSwordBladeSelection.count(player));
        assertEquals(6f, NineSwordBladeSelection.totalDamage(player));
        resolvers.verifyNoInteractions();
    }

    @Test
    void groupedLegacyBladesConsumeAnIndividualSlotInTheBudget() {
        fillHotbar(7);
        ResonanceStorageView disk = disk(new StoredStack(0, blade(5, 4)));
        resolvers.when(() -> ResonanceStorageResolvers.resolveAll(player)).thenReturn(List.of(disk));

        assertEquals(9, NineSwordBladeSelection.count(player));
        assertEquals(24f, NineSwordBladeSelection.totalDamage(player));
    }

    private void fillHotbar(int count) {
        ItemStack stack = blade(2, 1);
        for (int slot = 0; slot < count; slot++) {
            inventory.setItem(slot, stack);
        }
    }

    private ItemStack blade(float damage, int count) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getItem()).thenReturn(blade);
        when(stack.getCount()).thenReturn(count);
        when(stack.copy()).thenReturn(stack);
        when(blade.getAttributeModifiers(EquipmentSlot.MAINHAND, stack)).thenReturn(
                ImmutableMultimap.of(
                        Attributes.ATTACK_DAMAGE,
                        new AttributeModifier("blade damage", damage, AttributeModifier.Operation.ADDITION),
                        Attributes.ATTACK_DAMAGE,
                        new AttributeModifier("multiplier", 2, AttributeModifier.Operation.MULTIPLY_TOTAL)));
        return stack;
    }

    private static ResonanceStorageView disk(StoredStack... stacks) {
        ResonanceStorageView disk = mock(ResonanceStorageView.class);
        when(disk.storedStacks()).thenReturn(List.of(stacks));
        return disk;
    }
}
