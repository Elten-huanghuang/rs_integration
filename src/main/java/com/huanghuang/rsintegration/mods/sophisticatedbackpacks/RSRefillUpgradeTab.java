package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;

import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeTab;

public class RSRefillUpgradeTab extends RefillUpgradeTab.Basic {

    public RSRefillUpgradeTab(RefillUpgradeContainer container,
                              Position position, StorageScreenBase<?> screen, int slotsInRow) {
        super(container, position, screen, slotsInRow);
    }
}
