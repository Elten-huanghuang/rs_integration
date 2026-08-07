package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.ToggleButton;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogicControl;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeTab;
import net.minecraft.network.chat.Component;

public class RSFeedingUpgradeTab extends FeedingUpgradeTab {

    public RSFeedingUpgradeTab(FeedingUpgradeContainer container,
                               Position position, StorageScreenBase<?> screen, int slotsInRow) {
        super(container, position, screen,
                Component.translatable("upgrade.rs_integration.rs_feeding"),
                Component.translatable("upgrade.rs_integration.rs_feeding.tooltip"));
        addHideableChild(new ToggleButton(new Position(x + 3, y + 24), HUNGER_LEVEL,
                ignored -> getContainer().setFeedAtHungerLevel(getContainer().getFeedAtHungerLevel().next()),
                () -> getContainer().getFeedAtHungerLevel()));
        addHideableChild(new ToggleButton(new Position(x + 21, y + 24), FEED_IMMEDIATELY_WHEN_HURT,
                ignored -> getContainer().setFeedImmediatelyWhenHurt(!getContainer().shouldFeedImmediatelyWhenHurt()),
                () -> getContainer().shouldFeedImmediatelyWhenHurt()));
        filterLogicControl = addHideableChild(new FilterLogicControl.Advanced(screen,
                new Position(x + 3, y + 44), container.getFilterLogicContainer(), slotsInRow));
    }
}
