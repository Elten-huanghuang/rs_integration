package com.huanghuang.rsintegration.resonance.bd;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.TextBuilder;
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

import java.util.List;
import java.util.UUID;

/** BD-native resonance disk. Its contents never enter BD UnifiedStorage. */
public final class BDResonanceDiskItem extends Item {
    public static final int CAPACITY = BDResonanceDiskData.CAPACITY;

    public BDResonanceDiskItem() {
        super(new Item.Properties().stacksTo(1));
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

        if (!com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection
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
            tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.intro")
                    .colorFlow(1600L, 0.0F, RSIntegrationMod.RS_FLOW_COLORS)
                    .bold()
                    .build());
            tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.subtitle")
                    .colorFlow(1600L, 0.35F, RSIntegrationMod.RS_FLOW_COLORS)
                    .build());
            tooltip.add(Component.empty());
            appendBindingStatus(tooltip, stack, diskId, networkId);
            tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.expand")
                    .darkGray()
                    .build());
            return;
        }

        tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.title")
                .aqua()
                .bold()
                .build());
        for (int line = 1; line <= 8; line++) {
            tooltip.add(TextBuilder.of("• ").darkAqua()
                    .append(TextBuilder.translate("rsi.resonance.bd.tooltip.detail_" + line)
                            .gray())
                    .build());
        }
        tooltip.add(Component.empty());
        tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.capacity", CAPACITY)
                .gray()
                .build());
        appendBindingStatus(tooltip, stack, diskId, networkId);
    }

    private static void appendBindingStatus(List<Component> tooltip, ItemStack stack,
                                            UUID diskId, int networkId) {
        if (!BDResonanceDiskAccess.isBound(stack)) {
            tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.unbound")
                    .red()
                    .build());
        } else {
            tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.network", networkId)
                    .cornflowerBlue()
                    .build());
            tooltip.add(TextBuilder.translate("rsi.resonance.bd.tooltip.uuid",
                            diskId.toString().substring(0, 8))
                    .darkGray()
                    .build());
        }
    }
}
