package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineHubInputHandler;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractContainerScreenAccessor;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mixin(value = GridScreen.class, remap = false)
public abstract class GridScreenMouseMixin {

    @Unique
    private boolean rsi$swipeActive;
    @Unique
    private final Set<UUID> rsi$swipedIds = new LinkedHashSet<>();
    @Unique
    private final List<ItemStack> rsi$swipedItems = new ArrayList<>();

    static {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH,
                GridScreenMouseMixin::onMouseDraggedPre);
    }

    @Unique
    private static void onMouseDraggedPre(ScreenEvent.MouseDragged.Pre event) {
        if (!(event.getScreen() instanceof GridScreen screen)) return;
        GridScreenMouseMixin self = (GridScreenMouseMixin) (Object) screen;
        if (!self.rsi$swipeActive) return;

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        double relX = event.getMouseX() - acc.getLeftPos();
        double relY = event.getMouseY() - acc.getTopPos();
        if (screen.isOverSlotArea(relX, relY)) {
            int slot = screen.getSlotNumber();
            List<IGridStack> stacks = screen.getView().getStacks();
            if (stacks != null && slot >= 0 && slot < stacks.size()) {
                IGridStack gs = stacks.get(slot);
                if (gs.getIngredient() instanceof ItemStack is && !is.isEmpty()
                        && self.rsi$swipedIds.add(gs.getId())) {
                    self.rsi$swipedItems.add(is.copy());
                    gs.setQuantity(gs.getQuantity() - 1);
                }
            }
        }
        event.setCanceled(true);
    }

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true)
    private void rsi$onMouseClicked(double mouseX, double mouseY, int button,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.mouseClicked(mouseX, mouseY, button);
            if (consumed) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (RSIntegrationConfig.ENABLE_RS_GRID_SWIPE_EXTRACT.get()
                && Screen.hasControlDown() && button == 0
                && MachineTabHandler.getHoveredTabIndex() < 0) {
            GridScreen screen = (GridScreen) (Object) this;
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
            double relX = mouseX - acc.getLeftPos();
            double relY = mouseY - acc.getTopPos();
            if (screen.isOverSlotArea(relX, relY)) {
                int slot = screen.getSlotNumber();
                List<IGridStack> stacks = screen.getView().getStacks();
                if (stacks != null && slot >= 0 && slot < stacks.size()) {
                    IGridStack gs = stacks.get(slot);
                    if (gs.getIngredient() instanceof ItemStack is && !is.isEmpty()) {
                        rsi$swipeActive = true;
                        rsi$swipedIds.clear();
                        rsi$swipedItems.clear();
                        rsi$swipedIds.add(gs.getId());
                        rsi$swipedItems.add(is.copy());
                        gs.setQuantity(gs.getQuantity() - 1);
                        cir.setReturnValue(true);
                        return;
                    }
                }
            }
        }

        if (button == 0) {
            int ht = MachineTabHandler.getHoveredTabIndex();
            List<BindingInfo> visibleTabs = MachineTabHandler.getVisibleTabs();
            List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();
            boolean shouldUse = MachineHub.shouldUseHub(allMachines.size());
            if (ht == 0 && visibleTabs.isEmpty() && shouldUse) {
                MachineHub.toggle(allMachines);
                cir.setReturnValue(true);
                return;
            }
        }

        int hovered = MachineTabHandler.getHoveredTabIndex();
        if (hovered < 0) return;

        List<BindingInfo> visibleTabs = MachineTabHandler.getVisibleTabs();
        if (!visibleTabs.isEmpty() && hovered < visibleTabs.size()) {
            BindingInfo info = visibleTabs.get(hovered);

            if (button == 1) {
                MachineTabHandler.onClick(info);
                cir.setReturnValue(true);
                return;
            }

            if (button == 0) {
                MachineInteractType type = MachineInteractType.fromBlockKey(info.blockKey());

                if (type == MachineInteractType.GUI) {
                    MachineTabHandler.onClick(info);
                    cir.setReturnValue(true);
                    return;
                }

                boolean shift = Minecraft.getInstance().screen != null
                        && Minecraft.getInstance().screen.hasShiftDown();
                Minecraft mc = Minecraft.getInstance();
                ItemStack carried = mc.player != null
                    ? mc.player.containerMenu.getCarried()
                    : ItemStack.EMPTY;
                MachineStatus status = MachineStatusCache.getInstance().get(info);

                if (carried.isEmpty() && status.state() == MachineState.HAS_OUTPUT) {
                    MachineTabHandler.onCollect(info, shift);
                } else if (!carried.isEmpty()) {
                    MachineSlotType slot = shift ? MachineSlotType.FUEL : MachineSlotType.INPUT;
                    MachineTabHandler.onInsert(info, slot);
                } else {
                    MachineTabHandler.onClick(info);
                }
                cir.setReturnValue(true);
                return;
            }
        }

    }

    @Inject(method = "m_6348_", at = @At("HEAD"), cancellable = true)
    private void rsi$onMouseReleased(double mouseX, double mouseY, int button,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.mouseReleased(mouseX, mouseY, button);
            if (consumed) {
                cir.setReturnValue(true);
            }
            return;
        }

        if (rsi$swipeActive) {
            rsi$swipeActive = false;
            int count = rsi$swipedItems.size();
            if (count > 0) {
                RSSidePanelNetworkHandler.sendDragDistribute(rsi$swipedItems);
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.translatable("rsi.swipe.extracted", count), true);
                }
            }
            rsi$swipedIds.clear();
            rsi$swipedItems.clear();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_7979_", at = @At("HEAD"), cancellable = true)
    private void rsi$onMouseDragged(double mouseX, double mouseY, int button,
                                     double dragX, double dragY,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            MachineHub.updateDrag((int) mouseX, (int) mouseY);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_6050_", at = @At("HEAD"), cancellable = true)
    private void rsi$onMouseScrolled(double mouseX, double mouseY, double delta,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.mouseScrolled(mouseX, mouseY, delta);
            if (consumed) {
                cir.setReturnValue(true);
            }
        }
    }
}
