package com.huanghuang.rsintegration.feature.resonance.bd;

import com.huanghuang.rsintegration.util.DiskNameStyle;
import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection;

import java.util.List;
import java.util.UUID;

/** BD-native resonance disk. Its contents never enter BD UnifiedStorage. */
public final class BDResonanceDiskItem extends Item {
    public static final int CAPACITY = BDResonanceDiskData.CAPACITY;

    public BDResonanceDiskItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        return DiskNameStyle.resonance(super.getName(stack));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResultHolder.pass(stack);

        UUID diskId = BDResonanceDiskAccess.getDiskId(stack);
        if (diskId == null) {
            diskId = UUID.randomUUID();
            BDResonanceDiskAccess.bind(stack, diskId, -1);
            BDResonanceDiskData.get(serverPlayer.server).getOrCreate(diskId);
        }

        int networkId = BDResonanceDiskAccess.getNetworkId(stack);
        if (networkId < 0) {
            networkId = BDResonanceDiskAccess.resolvePrimaryNetworkId(serverPlayer);
            if (networkId < 0) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "rsi.resonance.bd.no_network"));
                return InteractionResultHolder.fail(stack);
            }
            if (!BDResonanceDiskAccess.bindToNetwork(
                    serverPlayer, stack, diskId, networkId)) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "rsi.resonance.bd.bind_failed"));
                return InteractionResultHolder.fail(stack);
            }
            serverPlayer.sendSystemMessage(Component.translatable(
                    "rsi.resonance.bd.bound", networkId));
            return InteractionResultHolder.success(stack);
        }

        int rebound = BDResonanceDiskAccess.rebindAfterMergedNetwork(
                serverPlayer, stack, diskId, networkId);
        if (rebound >= 0) {
            networkId = rebound;
            UUID reboundDiskId = BDResonanceDiskAccess.getDiskId(stack);
            if (reboundDiskId != null) diskId = reboundDiskId;
        }

        if (!BeyondDimensionsReflection
                .isAuthorizedNetwork(serverPlayer, networkId)) {
            serverPlayer.sendSystemMessage(Component.translatable(
                    "rsi.resonance.bd.access_denied"));
            return InteractionResultHolder.fail(stack);
        }

        // The data/view layer is ready for the generic backpack menu. Keep the
        // item interaction fail-closed until that menu is registered, so a
        // bound disk can never silently fall back to BD UnifiedStorage.
        serverPlayer.sendSystemMessage(Component.translatable(
                "rsi.resonance.bd.bound_status", networkId));
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        UUID diskId = BDResonanceDiskAccess.getDiskId(stack);
        int networkId = BDResonanceDiskAccess.getNetworkId(stack);

        if (!Screen.hasShiftDown()) {
            tooltip.add(DiskTooltipEffects.flow("rsi.resonance.bd.tooltip.intro",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MAIN, 0.0F)
                    .copy().withStyle(ChatFormatting.BOLD));
            tooltip.add(DiskTooltipEffects.flow("rsi.resonance.bd.tooltip.subtitle",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MAIN, 0.35F));
            tooltip.add(Component.empty());
            appendBindingStatus(tooltip, stack, diskId, networkId);
            tooltip.add(DiskTooltipEffects.flow("rsi.resonance.bd.tooltip.expand",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.HINT, 0.7F));
            return;
        }

        tooltip.add(DiskTooltipEffects.flow("rsi.resonance.bd.tooltip.title",
                DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.HINT, 0.0F)
                .copy().withStyle(ChatFormatting.BOLD));
        for (int line = 1; line <= 8; line++) {
            tooltip.add(DiskTooltipEffects.flow(Component.literal("• ").append(
                            Component.translatable("rsi.resonance.bd.tooltip.detail_" + line)),
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MUTED, line * 0.09F));
        }
        tooltip.add(Component.empty());
        tooltip.add(DiskTooltipEffects.tint("rsi.resonance.bd.tooltip.capacity",
                DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MAIN, CAPACITY));
        appendBindingStatus(tooltip, stack, diskId, networkId);
    }

    private static void appendBindingStatus(List<Component> tooltip, ItemStack stack,
                                            UUID diskId, int networkId) {
        if (!BDResonanceDiskAccess.isBound(stack)) {
            tooltip.add(DiskTooltipEffects.flow("rsi.resonance.bd.tooltip.unbound",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.WARNING, 0.0F));
        } else {
            tooltip.add(DiskTooltipEffects.tint("rsi.resonance.bd.tooltip.network",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.FLUID, networkId));
            tooltip.add(DiskTooltipEffects.tint("rsi.resonance.bd.tooltip.uuid",
                    DiskTooltipEffects.Theme.RESONANCE, DiskTooltipEffects.Tone.MUTED,
                    diskId.toString().substring(0, 8)));
        }
    }
}
