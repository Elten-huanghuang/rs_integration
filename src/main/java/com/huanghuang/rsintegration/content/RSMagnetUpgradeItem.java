package com.huanghuang.rsintegration.content;

import com.huanghuang.rsintegration.util.TextBuilder;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.huanghuang.rsintegration.RSIntegrationMod;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeSlotChangeResult;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeCountLimitConfig;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class RSMagnetUpgradeItem extends MagnetUpgradeItem {

    private static final int[] RS_FLOW_COLORS = {0x3355FF, 0x7733FF, 0xCC33FF, 0x3355FF};

    public RSMagnetUpgradeItem(IntSupplier radius, IntSupplier filterSlotCount,
                                IUpgradeCountLimitConfig countLimitConfig) {
        super(radius, filterSlotCount, countLimitConfig);
    }

    @Override
    public int getFilterSlotCount() {
        return 16;
    }

    @Override
    public List<IUpgradeItem.UpgradeConflictDefinition> getUpgradeConflicts() {
        return List.of();
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("RSBlockPos") && tag.contains("RSBlockDimension");
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        appendRSInfo(stack, tooltip);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return bindToRS(context);
    }

    public static void appendRSInfo(ItemStack stack, List<Component> tooltip) {
        CompoundTag tag = stack.hasTag() ? stack.getTag() : null;
        if (tag != null) {
            String dimKey = tag.getString("RSBlockDimension");
            if (!dimKey.isEmpty() && tag.contains("RSBlockPos")) {
                BlockPos pos = BlockPos.of(tag.getLong("RSBlockPos"));
                tooltip.add(TextBuilder.translate("item.sophisticatedbackpacks.rs_network.tooltip")
                        .colorFlow(1500L, 0.0F, RS_FLOW_COLORS).build());
                tooltip.add(TextBuilder.of("  " + dimDisplayName(dimKey) + " " + pos.toShortString())
                        .cornflowerBlue().build());
            } else {
                tooltip.add(TextBuilder.translate("item.sophisticatedbackpacks.rs_network.unbound")
                        .red().build());
            }
        } else {
            tooltip.add(TextBuilder.translate("item.sophisticatedbackpacks.rs_network.unbound")
                    .red().build());
        }
        tooltip.add(Component.empty());
        if (tag != null && tag.contains("disabled")) {
            tooltip.add(TextBuilder.translate("item.sophisticatedbackpacks.rs_upgrade.disabled")
                    .red().build());
        } else {
            tooltip.add(TextBuilder.translate("item.sophisticatedbackpacks.rs_upgrade.enabled")
                    .spectrumGradient().build());
        }
    }

    private static String dimDisplayName(String dimKey) {
        return switch (dimKey) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "The Nether";
            case "minecraft:the_end" -> "The End";
            default -> dimKey;
        };
    }

    @Override
    public UpgradeSlotChangeResult canSwapUpgradeFor(ItemStack newStack, int slotIndex,
                                                      IStorageWrapper wrapper, boolean isClientSide) {
        RSIntegrationMod.LOGGER.debug("[RSI-SB] canSwapUpgradeFor ENTER slot={} item={}",
                slotIndex, newStack.getHoverName().getString());
        try {
            UpgradeSlotChangeResult result = super.canSwapUpgradeFor(newStack, slotIndex, wrapper, isClientSide);
            RSIntegrationMod.LOGGER.debug("[RSI-SB] canSwapUpgradeFor EXIT ok={} error={}",
                    result.isSuccessful(),
                    result.getErrorMessage().map(Component::getString).orElse("none"));
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-SB] canSwapUpgradeFor EXCEPTION:", e);
            return new UpgradeSlotChangeResult.Fail(Component.literal(e.toString()),
                    java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        }
    }

    @Override
    public UpgradeSlotChangeResult canAddUpgradeTo(IStorageWrapper wrapper, ItemStack stack,
                                                    boolean isFirstLevel, boolean isClientSide) {
        try {
            UpgradeSlotChangeResult result = super.canAddUpgradeTo(wrapper, stack, isFirstLevel, isClientSide);
            RSIntegrationMod.LOGGER.debug("[RSI-SB] canAddUpgradeTo item={} isFirst={} ok={}",
                    stack.getHoverName().getString(), isFirstLevel, result.isSuccessful());
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-SB] canAddUpgradeTo EXCEPTION:", e);
            return new UpgradeSlotChangeResult.Fail(Component.literal(e.toString()),
                    java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        }
    }

    public static InteractionResult bindToRS(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;

        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);

        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ControllerBlockEntity)) return InteractionResult.PASS;

        ItemStack stack = context.getItemInHand();
        CompoundTag tag = stack.getOrCreateTag();
        tag.putLong("RSBlockPos", pos.asLong());
        ResourceKey<Level> dim = level.dimension();
        tag.putString("RSBlockDimension", dim.location().toString());
        player.displayClientMessage(
                Component.translatable("item.sophisticatedbackpacks.rs_network.bound"), true);
        return InteractionResult.sidedSuccess(false);
    }
}
