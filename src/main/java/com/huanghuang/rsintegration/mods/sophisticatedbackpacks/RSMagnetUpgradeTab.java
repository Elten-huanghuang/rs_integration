package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.ButtonDefinition;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.ButtonDefinitions;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.ToggleButton;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Dimension;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.TextureBlitData;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.UV;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.TranslationHelper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterControl;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterType;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeTab;

public class RSMagnetUpgradeTab extends MagnetUpgradeTab {
    private static final ButtonDefinition.Toggle<Boolean> PICKUP_FLUIDS =
            ButtonDefinitions.createToggleButtonDefinition(ButtonDefinitions.getBooleanStateData(
                    fluidState("water_bucket", "gui.rs_integration.magnet.fluid_on"),
                    fluidState("bucket", "gui.rs_integration.magnet.fluid_off")));

    private static ToggleButton.StateData fluidState(String icon, String tooltip) {
        return new ToggleButton.StateData(new TextureBlitData(
                new ResourceLocation("minecraft", "textures/item/" + icon + ".png"),
                new Position(1, 1), Dimension.SQUARE_16, new UV(0, 0), Dimension.SQUARE_16),
                Component.translatable(tooltip),
                Component.translatable("gui.rs_integration.magnet.fluid_hint_source"),
                Component.translatable("gui.rs_integration.magnet.fluid_hint_storage"),
                Component.translatable("gui.rs_integration.magnet.fluid_hint_filter"));
    }

    public RSMagnetUpgradeTab(MagnetUpgradeContainer container, Position position,
                               StorageScreenBase<?> screen, int slotIndex,
                               ButtonDefinition.Toggle<ContentsFilterType> filterTypeButton) {
        super(container, position, screen,
                TranslationHelper.INSTANCE.translUpgrade("rs_magnet"),
                TranslationHelper.INSTANCE.translUpgradeTooltip("rs_magnet"));

        filterLogicControl = addHideableChild(new ContentsFilterControl.Advanced(screen,
                new Position(x + 3, y + 44),
                container.getFilterLogicContainer(), slotIndex, filterTypeButton));
        if (container instanceof RSMagnetUpgradeContainer rsContainer) {
            addHideableChild(new ToggleButton<>(new Position(x + 39, y + 24), PICKUP_FLUIDS,
                    button -> rsContainer.setPickupFluids(!rsContainer.shouldPickupFluids()),
                    rsContainer::shouldPickupFluids));
        }
    }
}
