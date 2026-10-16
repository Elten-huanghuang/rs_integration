package com.huanghuang.rsintegration.unifiedgrid.client;

import com.huanghuang.rsintegration.magic.MagicGridItemTransfer;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.huanghuang.rsintegration.unifiedgrid.GridResourceKind;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridActionPacket;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridUpdatePacket;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.api.network.grid.handler.IItemGridHandler;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.CraftingSettingsScreen;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.view.GridViewImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/** 只由客户端分发器及客户端 Mixin 调用。 */
public final class UnifiedGridClient {
    private UnifiedGridClient() { }

    public static void apply(UnifiedGridUpdatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof GridContainerMenu menu)
                || menu.containerId != packet.containerId()) return;
        if (menu.getScreenInfoProvider() instanceof GridScreen screen) apply(screen, menu, packet);
        else BaseScreen.executeLater(GridScreen.class, screen -> apply(screen, menu, packet));
    }

    private static void apply(GridScreen screen, GridContainerMenu menu, UnifiedGridUpdatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.containerMenu != menu || screen.getMenu() != menu) return;
        if (!packet.enabled()) {
            screen.getView().removed();
            screen.setView(new GridViewImpl(screen, GridScreen.getDefaultSorter(), GridScreen.getSorters()));
            screen.getView().forceSort();
            return;
        }
        UnifiedGridView view;
        if (screen.getView() instanceof UnifiedGridView mixed) view = mixed;
        else {
            if (!packet.begin()) return;
            screen.getView().removed();
            view = new UnifiedGridView(screen);
            screen.setView(view);
            RSGridSearchCache.onGridReset(screen, view);
        }
        view.apply(packet);
        addFilterButton(screen);
    }

    public static void tick(GridScreen screen) {
        if (screen.getView() instanceof UnifiedGridView view) {
            view.tick();
            addFilterButton(screen);
        }
        for (var button : screen.getSideButtons()) if (button instanceof UnifiedGridFilterButton)
            button.visible = button.active = screen.getView() instanceof UnifiedGridView;
    }

    private static void addFilterButton(GridScreen screen) {
        if (screen.getSideButtons().stream().noneMatch(UnifiedGridFilterButton.class::isInstance))
            screen.addSideButton(new UnifiedGridFilterButton(screen));
    }

    public static boolean click(GridScreen screen, double x, double y, int button) {
        if (!(screen.getView() instanceof UnifiedGridView view)
                || !screen.isOverSlotArea(x - screen.getGuiLeft(), y - screen.getGuiTop())) return false;
        if (!screen.getGrid().isGridActive() || button < 0 || button > 1) return true;
        IGridStack selected = selected(screen);
        UnifiedGridView.Row row = view.row(selected);
        ItemStack carried = screen.getMenu().getCarried();
        if (!carried.isEmpty()) {
            MagicResourceKind carriedMagic = MagicGridItemTransfer.kindOf(carried);
            if (carriedMagic != null && !carriedMagic.available()) carriedMagic = null;
            GridResourceKind itemKind = GridResourceKind.item();
            GridResourceKind fluidKind = GridResourceKind.fluid();
            // AE2：右键排空容器；左键流体装填；魔法资源容器按同样的左右键语义充放。
            if (button == 1 && view.canEmptyCarried(carried)) {
                view.request(fluidKind, 0, UnifiedGridActionPacket.Action.INSERT_FLUID, 0);
            } else if (button == 0 && row != null && row.kind() == fluidKind) {
                view.request(fluidKind, row.serial(), UnifiedGridActionPacket.Action.FILL_FLUID,
                        Screen.hasShiftDown() ? IItemGridHandler.EXTRACT_SHIFT : 0);
            } else if (carriedMagic != null) {
                // 容器：右键空白处倒出，左键同类资源条目装填，其他情况存入容器物品
                if (button == 1 && row == null) {
                    view.request(GridResourceKind.ofMagic(carriedMagic), 0,
                            UnifiedGridActionPacket.Action.INSERT_MAGIC, 0);
                } else if (button == 0 && row != null && row.kind().magicKind() == carriedMagic) {
                    view.request(row.kind(), row.serial(), UnifiedGridActionPacket.Action.FILL_MAGIC, 0);
                } else {
                    view.request(itemKind, 0, UnifiedGridActionPacket.Action.INSERT_ITEM,
                            button == 1 ? 1 : 0);
                }
            } else if (row == null || row.kind() == itemKind) {
                view.request(itemKind, 0, UnifiedGridActionPacket.Action.INSERT_ITEM,
                        button == 1 ? 1 : 0);
            }
        } else if (row != null) {
            if (view.canCraft() && (selected.isCraftable() || (row.crafting() != null
                    && Screen.hasShiftDown() && Screen.hasControlDown()))) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.setScreen(new CraftingSettingsScreen(screen, minecraft.player, row.crafting()));
            } else if (row.kind() == GridResourceKind.fluid()) {
                if (button == 0) view.request(GridResourceKind.fluid(), row.serial(),
                        UnifiedGridActionPacket.Action.FILL_FLUID,
                        Screen.hasShiftDown() ? IItemGridHandler.EXTRACT_SHIFT : 0);
            } else if (row.kind().isMagic()) {
                if (button == 0) view.request(row.kind(), row.serial(),
                        UnifiedGridActionPacket.Action.FILL_MAGIC,
                        Screen.hasShiftDown() ? IItemGridHandler.EXTRACT_SHIFT : 0);
            } else {
                int flags = (button == 1 && row.kind() == GridResourceKind.item() ? IItemGridHandler.EXTRACT_HALF : 0)
                        | (Screen.hasShiftDown() ? IItemGridHandler.EXTRACT_SHIFT : 0);
                view.request(row.kind(), row.serial(), UnifiedGridActionPacket.Action.EXTRACT, flags);
            }
        }
        screen.setFocused(null);
        return true;
    }

    public static boolean scroll(GridScreen screen, double x, double y, double delta) {
        if (!(screen.getView() instanceof UnifiedGridView view) || !(Screen.hasShiftDown() || Screen.hasControlDown())
                || !screen.isOverSlotArea(x - screen.getGuiLeft(), y - screen.getGuiTop())) return false;
        UnifiedGridView.Row row = view.row(selected(screen));
        if (row != null && row.kind() == GridResourceKind.item() && !row.primary().isCraftable())
            view.request(row.kind(), row.serial(), UnifiedGridActionPacket.Action.SCROLL,
                    (Screen.hasShiftDown() ? 1 : 0) | (delta > 0 ? 2 : 0));
        return true;
    }

    private static IGridStack selected(GridScreen screen) {
        int slot = screen.getSlotNumber();
        return slot >= 0 && slot < screen.getView().getStacks().size() ? screen.getView().getStacks().get(slot) : null;
    }
}
