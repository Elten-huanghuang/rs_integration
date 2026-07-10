package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineHubInputHandler;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractContainerScreenAccessor;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
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
                && !MachineTabHandler.isMachineCenterHovered()) {
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

        // Machine Center side button click → toggle Hub overlay
        if (button == 0 && MachineTabHandler.isMachineCenterHovered()) {
            MachineTabHandler.toggleMachineCenter();
            cir.setReturnValue(true);
            return;
        }

        // Resonance Backpack side button click → open backpack GUI
        if (button == 0 && MachineTabHandler.isResonanceBackpackHovered()) {
            MachineTabHandler.toggleResonanceBackpack();
            cir.setReturnValue(true);
            return;
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
