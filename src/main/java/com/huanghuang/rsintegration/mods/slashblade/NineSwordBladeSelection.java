package com.huanghuang.rsintegration.mods.slashblade;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 勇者章节的九剑九意按同一批拔刀剑计算剑数和额外伤害。 */
public final class NineSwordBladeSelection {
    private NineSwordBladeSelection() {}

    public static int count(ServerPlayer player) {
        return collect(player).size();
    }

    public static float totalDamage(ServerPlayer player) {
        float total = 0f;
        for (ItemStack stack : collect(player)) {
            ItemSlashBlade blade = (ItemSlashBlade) stack.getItem();
            var modifiers = blade.getAttributeModifiers(EquipmentSlot.MAINHAND, stack);
            for (AttributeModifier modifier : modifiers.get(Attributes.ATTACK_DAMAGE)) {
                if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                    total += (float) modifier.getAmount();
                }
            }
        }
        return total;
    }

    private static List<ItemStack> collect(ServerPlayer player) {
        int limit = RSIntegrationConfig.NINE_SWORD_MAX_COUNT.get();
        List<ItemStack> blades = new ArrayList<>(limit);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            addBlade(blades, inventory.items.get(slot), limit);
            if (blades.size() == limit) return blades;
        }
        for (ResonanceStorageView view : ResonanceInventoryBridge.getViews(player)) {
            List<ResonanceStorageView.StoredStack> stacks = new ArrayList<>(view.storedStacks());
            stacks.sort(Comparator.comparingInt(ResonanceStorageView.StoredStack::slot));
            for (ResonanceStorageView.StoredStack stored : stacks) {
                addBlade(blades, stored.stack(), limit);
                if (blades.size() == limit) return blades;
            }
        }
        return blades;
    }

    private static void addBlade(List<ItemStack> blades, ItemStack stack, int limit) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemSlashBlade)) return;

        // 旧存档可能把多把刀存在同一组，按实际把数占用名额。
        int count = Math.min(stack.getCount(), limit - blades.size());
        for (int i = 0; i < count; i++) {
            blades.add(stack);
        }
    }
}
