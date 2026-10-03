package com.huanghuang.rsintegration.resonance.item;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.util.DiskNameStyle;
import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.storage.StorageType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.ItemStorageType;
import com.refinedmods.refinedstorage.item.StorageDiskItem;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public final class ResonanceDiskItem extends StorageDiskItem {

    public static final ResonanceDiskItem INSTANCE = new ResonanceDiskItem();

    static final int RESONANCE_CAPACITY = 36 * 64; // 36 slots × 64 max stack = 2304

    private ResonanceDiskItem() {
        super(ItemStorageType.FOUR_K);
    }

    @Override
    public Component getName(ItemStack stack) {
        return DiskNameStyle.resonance(super.getName(stack));
    }

    @Override
    public int getCapacity(ItemStack stack) {
        return RESONANCE_CAPACITY;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // StorageDiskItem treats an empty sneaking disk as dismantleable and
        // returns the storage part matching its constructor type. This custom
        // disk uses FOUR_K only as an RS compatibility token, so inheriting
        // that behavior incorrectly turns it into a 4K part and disk housing.
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        int baseTooltipSize = tooltip.size();
        super.appendHoverText(stack, level, tooltip, flag);
        // RS 原生容量/类型行也纳入共振主题，避免顶部仍显示默认灰色。
        for (int i = baseTooltipSize; i < tooltip.size(); i++) {
            tooltip.set(i, DiskTooltipEffects.flow(tooltip.get(i),
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MAIN,
                    (i - baseTooltipSize) * 0.12F));
        }
        tooltip.add(Component.empty());

        if (!Screen.hasShiftDown()) {
            tooltip.add(DiskTooltipEffects.flow(
                    "item.rs_integration.resonance_storage_disk.tooltip",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MAIN, 0.0F)
                    .copy().withStyle(ChatFormatting.BOLD));
            tooltip.add(DiskTooltipEffects.flow(
                    "item.rs_integration.resonance_storage_disk.tooltip.subtitle",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MAIN, 0.35F));
            tooltip.add(DiskTooltipEffects.flow(
                    "item.rs_integration.resonance_storage_disk.tooltip.expand",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.HINT, 0.7F));
            return;
        }

        tooltip.add(DiskTooltipEffects.flow(
                "item.rs_integration.resonance_storage_disk.tooltip.title",
                DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.HINT, 0.0F)
                .copy().withStyle(ChatFormatting.BOLD));
        for (int line = 1; line <= 8; line++) {
            tooltip.add(DiskTooltipEffects.flow(
                    TextBuilder.of("• ").append(Component.translatable(
                            "item.rs_integration.resonance_storage_disk.tooltip.detail_" + line)).build(),
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MUTED, line * 0.09F));
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        // isValid() checks hasTag() before reading the Id key, so it is null-safe
        // for freshly-created disks (creative tab / give / datapack) that have no
        // NBT yet. Calling getId() directly here would NPE inside RS's getTag().
        if (level.isClientSide() || isValid(stack) || !(entity instanceof Player player)) {
            return;
        }

        UUID id = UUID.randomUUID();
        IRSAPI api = API.instance();
        ServerLevel serverLevel = (ServerLevel) level;

        IStorageDisk<ItemStack> inner = api.createDefaultItemDisk(serverLevel, RESONANCE_CAPACITY, player);
        ResonanceDiskWrapper wrapper = new ResonanceDiskWrapper(inner);

        api.getStorageDiskManager(serverLevel).set(id, wrapper);
        api.getStorageDiskManager(serverLevel).markForSaving();
        setId(stack, id);
    }
}
