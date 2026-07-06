package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeTab;

public class RSFeedingUpgradeTab extends FeedingUpgradeTab.Advanced {

    public RSFeedingUpgradeTab(FeedingUpgradeContainer container,
                               Position position, StorageScreenBase<?> screen, int slotsInRow) {
        super(container, position, screen, slotsInRow);
    }
}
