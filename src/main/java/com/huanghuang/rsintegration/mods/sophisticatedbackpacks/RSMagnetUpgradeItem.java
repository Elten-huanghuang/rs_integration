package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        return isBoundToRS(stack);
    }

    public static boolean isBoundToRS(ItemStack stack) {
        return StorageBackpackUtils.readReference(stack.getTag()) != null;
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
            StorageReference reference = StorageBackpackUtils.readReference(tag);
            if (reference != null) {
                String dimKey = tag.getString("RSBlockDimension");
                BlockPos pos = tag.contains("RSBlockPos") ? BlockPos.of(tag.getLong("RSBlockPos")) : null;
                tooltip.add(TextBuilder.translate("item.sophisticatedbackpacks.rs_network.tooltip")
                        .colorFlow(1500L, 0.0F, RSIntegrationMod.RS_FLOW_COLORS).build());
                String target = reference.backendId().value().equals("refinedstorage") && pos != null
                        ? dimDisplayName(dimKey) + " " + pos.toShortString()
                        : reference.backendId().value() + " #" + reference.networkId();
                tooltip.add(TextBuilder.of("  " + target)
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
        ResourceLocation rl = ResourceLocation.tryParse(dimKey);
        if (rl == null) return dimKey;
        String translationKey = "dimension." + rl.getNamespace() + "." + rl.getPath();
        String translated = Component.translatable(translationKey).getString();
        if (!translationKey.equals(translated)) return translated;
        String path = rl.getPath();
        // Convert snake_case to Title Case when a modded dimension has no translation.
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    @Override
    public UpgradeSlotChangeResult canSwapUpgradeFor(ItemStack newStack, int slotIndex,
                                                      IStorageWrapper wrapper, boolean isClientSide) {
        try {
            return super.canSwapUpgradeFor(newStack, slotIndex, wrapper, isClientSide);
        } catch (Exception e) {
            return new UpgradeSlotChangeResult.Fail(Component.literal(e.toString()),
                    java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        }
    }

    @Override
    public UpgradeSlotChangeResult canAddUpgradeTo(IStorageWrapper wrapper, ItemStack stack,
                                                    boolean isFirstLevel, boolean isClientSide) {
        try {
            return super.canAddUpgradeTo(wrapper, stack, isFirstLevel, isClientSide);
        } catch (Exception e) {
            return new UpgradeSlotChangeResult.Fail(Component.literal(e.toString()),
                    java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        }
    }

    public static InteractionResult bindToRS(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;

        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);

        ItemStack stack = context.getItemInHand();
        CompoundTag tag = stack.getOrCreateTag();
        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        StorageReference reference = null;
        if (isRsController(be)) {
            ResourceKey<Level> dim = level.dimension();
            reference = new StorageReference(new StorageBackendId("refinedstorage"),
                    "v1|" + dim.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            tag.putLong("RSBlockPos", pos.asLong());
            tag.putString("RSBlockDimension", dim.location().toString());
        } else {
            // BD has no controller block. Hold its bound terminal in the
            // offhand while binding the upgrade in the main hand.
            ItemStack terminal = player.getOffhandItem();
            var hook = com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.findHook(terminal);
            if (hook.isPresent()) {
                var binding = hook.get().createBinding(terminal).orElse(null);
                if (binding != null && com.huanghuang.rsintegration.network.binding.AltarBinding.BD_NETWORK.equals(binding.type())) {
                    reference = new StorageReference(new StorageBackendId("beyonddimensions"),
                            Integer.toString(binding.data().getInt("networkId")));
                }
            }
            if (reference == null && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                reference = RSIntegrationMod.STORAGE_BACKENDS.registry()
                        .resolveDefaultSessionsForPlayer(serverPlayer).stream()
                        .map(StorageSession::reference)
                        .filter(r -> "beyonddimensions".equals(r.backendId().value()))
                        .findFirst().orElse(null);
            }
            if (reference == null) return InteractionResult.PASS;
        }
        if (!"refinedstorage".equals(reference.backendId().value())) {
            tag.remove("RSBlockPos");
            tag.remove("RSBlockDimension");
        }
        StorageBackpackUtils.writeReference(tag, reference);
        player.displayClientMessage(
                Component.translatable("item.sophisticatedbackpacks.rs_network.bound"), true);
        return InteractionResult.sidedSuccess(false);
    }

    private static boolean isRsController(BlockEntity blockEntity) {
        return blockEntity != null
                && net.minecraftforge.fml.ModList.get().isLoaded("refinedstorage")
                && "com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity"
                .equals(blockEntity.getClass().getName());
    }
}
