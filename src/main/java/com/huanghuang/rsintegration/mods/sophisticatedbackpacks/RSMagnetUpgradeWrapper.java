package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;

import java.util.function.Consumer;

public final class RSMagnetUpgradeWrapper extends MagnetUpgradeWrapper {
    public static final String PICKUP_FLUIDS = "rsiPickupFluids";
    private final RSMagnetFluidCollector collector = new RSMagnetFluidCollector();
    private long nextFluidTick;
    private ServerPlayer placedPlayer;
    private GameProfile owner;

    public RSMagnetUpgradeWrapper(IStorageWrapper storage, ItemStack upgrade, Consumer<ItemStack> saveHandler) {
        super(storage, upgrade, saveHandler);
    }

    public boolean shouldPickupFluids() {
        return upgrade.hasTag() && upgrade.getTag().getBoolean(PICKUP_FLUIDS);
    }

    public void setPickupFluids(boolean enabled) {
        upgrade.getOrCreateTag().putBoolean(PICKUP_FLUIDS, enabled);
        save();
    }

    @Override
    public void tick(Entity entity, Level level, BlockPos pos) {
        super.tick(entity, level, pos);
        if (level.isClientSide || !isEnabled() || !shouldPickupFluids()
                || level.getGameTime() < nextFluidTick) return;
        nextFluidTick = level.getGameTime() + 5;
        var reference = StorageBackpackUtils.readReference(upgrade.getTag());
        if (reference == null || !"refinedstorage".equals(reference.backendId().value())) return;
        ServerPlayer player = entity instanceof ServerPlayer serverPlayer ? serverPlayer : placedPlayer(level, pos);
        if (player == null) return;
        var session = StorageBackpackUtils.resolve(player, reference);
        if (session == null) return;
        try {
            collector.collect(level, pos, upgradeItem.getRadius(), player, session, upgrade, this::save,
                    fluid -> RSMagnetFluidFilter.matches(getFilterLogic(), fluid));
        } catch (RuntimeException failure) {
            setPickupFluids(false);
            LogUtils.getLogger().error("次元磁铁液体采集异常，已关闭液体采集开关", failure);
        }
    }

    private ServerPlayer placedPlayer(Level level, BlockPos pos) {
        if (level.getServer() == null) return null;
        if (owner == null) owner = BackpackOperationOwner.resolve(
                upgrade.getTag(), storageWrapper, level.getServer()).orElse(null);
        if (owner == null) return null;
        if (placedPlayer == null || placedPlayer.serverLevel() != level) {
            placedPlayer = BackpackOperationOwner.createOfflinePlayer(level, pos, owner);
        }
        if (placedPlayer != null) placedPlayer.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return placedPlayer;
    }
}
