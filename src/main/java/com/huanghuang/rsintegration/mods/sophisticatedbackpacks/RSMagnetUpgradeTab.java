package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.ButtonDefinition;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.TranslationHelper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterControl;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterType;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeTab;

public class RSMagnetUpgradeTab extends MagnetUpgradeTab {

    public RSMagnetUpgradeTab(MagnetUpgradeContainer container, Position position,
                               StorageScreenBase<?> screen, int slotIndex,
                               ButtonDefinition.Toggle<ContentsFilterType> filterTypeButton) {
        super(container, position, screen,
                TranslationHelper.INSTANCE.translUpgrade("rs_magnet"),
                TranslationHelper.INSTANCE.translUpgradeTooltip("rs_magnet"));

        filterLogicControl = addHideableChild(new ContentsFilterControl.Advanced(screen,
                new Position(x + 3, y + 44),
                container.getFilterLogicContainer(), slotIndex, filterTypeButton));
    }
}
