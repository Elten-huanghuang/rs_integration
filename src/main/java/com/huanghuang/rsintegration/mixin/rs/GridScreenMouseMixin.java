package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.machine.MachineHubInputHandler;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.sidepanel.RSItemLockPacket;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Intercepts mouse clicks and scroll on the RS GridScreen to handle machine tab / Hub clicks
 * and Ctrl+Left-Click item locking.
 * Target: com.refinedmods.refinedstorage.screen.grid.GridScreen
 */
@Mixin(value = com.refinedmods.refinedstorage.screen.grid.GridScreen.class, remap = false)
public abstract class GridScreenMouseMixin {

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$onMouseClicked(double mouseX, double mouseY, int button,
                                     CallbackInfoReturnable<Boolean> cir) {
        // Ctrl+Left Click on grid item → toggle item lock
        if (button == 0 && Screen.hasControlDown()) {
            if (rsi$tryLockGridItem(mouseX, mouseY)) {
                cir.setReturnValue(true);
                return;
            }
        }

        // Hub overlay steals input first
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.mouseClicked(mouseX, mouseY, button);
            if (consumed) {
                cir.setReturnValue(true);
                return;
            }
        }

        // Hub button click — must be before individual tab hover check,
        // since hovered == -1 when hub button is showing (tabs collapsed).
        // hoveredTabIndex is set to 0 by renderForeground when mouse is over the hub.
        if (button == 0 && MachineTabHandler.getHoveredTabIndex() == 0) {
            List<BindingInfo> visibleTabs = MachineTabHandler.getVisibleTabs();
            if (visibleTabs.isEmpty()) {
                List<BindingInfo> allMachines = MachineTabHandler.getAllMachines();
                if (MachineHub.shouldUseHub(allMachines.size())) {
                    MachineHub.toggle(allMachines);
                    cir.setReturnValue(true);
                    return;
                }
            }
        }

        int hovered = MachineTabHandler.getHoveredTabIndex();
        if (hovered < 0) return;

        List<BindingInfo> visibleTabs = MachineTabHandler.getVisibleTabs();
        if (!visibleTabs.isEmpty() && hovered < visibleTabs.size()) {
            BindingInfo info = visibleTabs.get(hovered);

            // Right-click always opens GUI
            if (button == 1) {
                MachineTabHandler.onClick(info);
                cir.setReturnValue(true);
                return;
            }

            if (button == 0) {
                MachineInteractType type = MachineInteractType.fromBlockKey(info.blockKey());

                // GUI type: always open GUI
                if (type == MachineInteractType.GUI) {
                    MachineTabHandler.onClick(info);
                    cir.setReturnValue(true);
                    return;
                }

                // Quick type: differentiate by cursor + state
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
                    // Fallback: open GUI
                    MachineTabHandler.onClick(info);
                }
                cir.setReturnValue(true);
                return;
            }
        }

    }

    @Inject(method = "m_6348_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$onMouseReleased(double mouseX, double mouseY, int button,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.mouseReleased(mouseX, mouseY, button);
            if (consumed) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "m_6050_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$onMouseScrolled(double mouseX, double mouseY, double delta,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (MachineHubInputHandler.isConsumingInput()) {
            boolean consumed = MachineHubInputHandler.mouseScrolled(mouseX, mouseY, delta);
            if (consumed) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * Attempts to toggle item lock for the grid item under the mouse cursor.
     * @return true if a lock toggle was performed and the click should be consumed.
     */
    private boolean rsi$tryLockGridItem(double mouseX, double mouseY) {
        com.refinedmods.refinedstorage.screen.grid.GridScreen screen =
            (com.refinedmods.refinedstorage.screen.grid.GridScreen) (Object) this;

        if (!screen.isOverSlotArea(mouseX, mouseY)) {
            return false;
        }

        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) this;
        int leftPos = acc.getLeftPos();
        int topPos = acc.getTopPos();

        int col = (int) (mouseX - leftPos - 7) / 18;
        int row = (int) (mouseY - topPos - screen.getTopHeight()) / 18;

        if (col < 0 || col >= 9 || row < 0 || row >= screen.getVisibleRows()) {
            return false;
        }

        int index = screen.getCurrentOffset() + row * 9 + col;
        List<IGridStack> stacks = screen.getView().getStacks();
        if (stacks == null || index < 0 || index >= stacks.size()) {
            return false;
        }

        IGridStack gridStack = stacks.get(index);
        if (gridStack == null) return false;

        Object ingredient = gridStack.getIngredient();
        if (!(ingredient instanceof ItemStack itemStack) || itemStack.isEmpty()) {
            return false;
        }

        ResourceLocation rl = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
        if (rl == null) return false;

        RSSidePanelNetworkHandler.CHANNEL.sendToServer(new RSItemLockPacket(rl));
        RSSidePanelClient.toggleClientLock(rl);
        return true;
    }
}
