package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerType;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;

public final class RSMagnetUpgradeContainer extends MagnetUpgradeContainer {
    public RSMagnetUpgradeContainer(Player player, int id, MagnetUpgradeWrapper wrapper,
            UpgradeContainerType<MagnetUpgradeWrapper, MagnetUpgradeContainer> type) {
        super(player, id, wrapper, type);
    }

    public boolean shouldPickupFluids() {
        return upgradeWrapper instanceof RSMagnetUpgradeWrapper wrapper && wrapper.shouldPickupFluids();
    }

    public void setPickupFluids(boolean enabled) {
        if (!(upgradeWrapper instanceof RSMagnetUpgradeWrapper wrapper)) return;
        wrapper.setPickupFluids(enabled);
        sendBooleanToServer(RSMagnetUpgradeWrapper.PICKUP_FLUIDS, enabled);
    }

    @Override
    public void handleMessage(CompoundTag data) {
        if (data.contains(RSMagnetUpgradeWrapper.PICKUP_FLUIDS, Tag.TAG_BYTE)) {
            setPickupFluids(data.getBoolean(RSMagnetUpgradeWrapper.PICKUP_FLUIDS));
        }
        super.handleMessage(data);
    }
}
