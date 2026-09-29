package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.CraftingStationResultSlot;
import com.huanghuang.rsintegration.craftingstation.AnvilTerminalState;
import com.huanghuang.rsintegration.craftingstation.CraftingStationState;
import com.huanghuang.rsintegration.craftingstation.SmithingInputSlot;
import com.huanghuang.rsintegration.craftingstation.SmithingResultSlot;
import com.huanghuang.rsintegration.craftingstation.SmithingTerminalAccess;
import com.huanghuang.rsintegration.craftingstation.SmithingTerminalState;
import com.huanghuang.rsintegration.craftingstation.StonecutterInputSlot;
import com.huanghuang.rsintegration.craftingstation.StonecutterTerminalState;
import com.huanghuang.rsintegration.craftingstation.CraftingStationInputSlot;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.container.slot.grid.CraftingGridSlot;
import com.refinedmods.refinedstorage.container.slot.grid.ResultCraftingGridSlot;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在不改 RS 源码的前提下，将合成终端的槽位代理到 RSI 工作站状态。 */
@Mixin(value = GridContainerMenu.class, remap = false)
public abstract class GridContainerSmithingMixin implements SmithingTerminalAccess {
    @Unique
    private static final int RSI_STONECUTTER_INPUT_X = 20;
    @Unique
    private static final int RSI_STONECUTTER_INPUT_Y_OFFSET = 14;
    @Unique
    // RS 默认结果槽 x=134，原版切石机结果槽 x=143。
    private static final int RSI_STONECUTTER_RESULT_X_OFFSET = 9;
    @Unique
    private static final int RSI_STONECUTTER_RESULT_Y_OFFSET = 8;

    @Shadow
    private ResultCraftingGridSlot craftingResultSlot;

    @Unique
    private CraftingStationMode rsi$stationMode = CraftingStationMode.CRAFTING;
    @Unique
    private SmithingTerminalState rsi$smithingState;
    @Unique
    private StonecutterTerminalState rsi$stonecutterState;
    @Unique
    private AnvilTerminalState rsi$anvilState;

    @Inject(method = "initSlots", at = @At("HEAD"), remap = false)
    private void rsi$prepareSmithingState(CallbackInfo ci) {
        if (rsi$smithingState == null) {
            rsi$smithingState = new SmithingTerminalState((GridContainerMenu) (Object) this);
        }
        if (rsi$stonecutterState == null) {
            rsi$stonecutterState = new StonecutterTerminalState((GridContainerMenu) (Object) this);
        }
        if (rsi$anvilState == null) {
            rsi$anvilState = new AnvilTerminalState((GridContainerMenu) (Object) this);
        }
    }

    /**
     * 保留 RS 的槽位数量和同步编号，九格中仅前三个对应锻造输入。
     */
    @Inject(method = "initSlots", at = @At("TAIL"), remap = false)
    private void rsi$replaceCraftingSlotsAfterInit(CallbackInfo ci) {
        if (rsi$stationMode == CraftingStationMode.CRAFTING) return;
        rsi$refreshCraftingStationSlots();
    }

    @Override
    public void rsi$refreshCraftingStationSlots() {
        GridContainerMenu menu = (GridContainerMenu) (Object) this;
        for (int slotNumber = 0; slotNumber < menu.slots.size(); slotNumber++) {
            Slot old = menu.slots.get(slotNumber);
            if (old instanceof CraftingGridSlot && old.getSlotIndex() < 9) {
                int index = old.getSlotIndex();
                int top = menu.getScreenInfoProvider().getTopHeight()
                        + menu.getScreenInfoProvider().getVisibleRows() * 18;
                Slot replacement = rsi$stationMode == CraftingStationMode.CRAFTING
                        ? new CraftingGridSlot(menu.getGrid().getCraftingMatrix(), index,
                        26 + index % 3 * 18, top + 4 + index / 3 * 18)
                        : rsi$stationMode == CraftingStationMode.SMITHING
                        ? new SmithingInputSlot(menu, index,
                        index < 3 ? 26 + index * 18 : -1000,
                        index < 3 ? top + 22 : -1000)
                        : rsi$stationMode == CraftingStationMode.STONECUTTER
                        ? new StonecutterInputSlot(menu, index,
                        index == 0 ? RSI_STONECUTTER_INPUT_X : -1000,
                        index == 0 ? top + 4 + RSI_STONECUTTER_INPUT_Y_OFFSET : -1000)
                        : new CraftingStationInputSlot(rsi$getAnvilState(), index,
                        index < 2 ? 27 + index * 49 : -1000,
                        index < 2 ? top + 47 : -1000, index < 2);
                replacement.index = old.index;
                menu.slots.set(slotNumber, replacement);
            } else if (old instanceof ResultCraftingGridSlot) {
                int top = menu.getScreenInfoProvider().getTopHeight()
                        + menu.getScreenInfoProvider().getVisibleRows() * 18;
                ResultCraftingGridSlot replacement = rsi$stationMode == CraftingStationMode.CRAFTING
                        ? new ResultCraftingGridSlot(menu.getPlayer(), menu.getGrid(),
                        old.getSlotIndex(), 134, top + 22)
                        : rsi$stationMode == CraftingStationMode.SMITHING
                        ? new SmithingResultSlot(menu, menu.getPlayer(), menu.getGrid(),
                        old.getSlotIndex(), 116, top + 22)
                        : new CraftingStationResultSlot(menu, menu.getPlayer(), menu.getGrid(),
                        old.getSlotIndex(),
                        rsi$stationMode == CraftingStationMode.STONECUTTER
                                ? 143 : 134,
                        rsi$stationMode == CraftingStationMode.STONECUTTER
                                ? top + 29 : rsi$stationMode == CraftingStationMode.ANVIL
                                ? top + 47 : top + 22);
                replacement.index = old.index;
                menu.slots.set(slotNumber, replacement);
                craftingResultSlot = replacement;
            }
        }
    }

    @Redirect(method = "addCraftingSlots", at = @At(value = "NEW",
            target = "com/refinedmods/refinedstorage/container/slot/grid/CraftingGridSlot"), remap = false)
    private CraftingGridSlot rsi$redirectInputSlot(Container container,
                                                    int index, int x, int y) {
        if (rsi$stationMode == CraftingStationMode.SMITHING) {
            return new SmithingInputSlot((GridContainerMenu) (Object) this, index,
                    index < 3 ? 26 + index * 18 : -1000,
                    index < 3 ? y + 18 : -1000);
        }
        if (rsi$stationMode == CraftingStationMode.STONECUTTER) {
            return new StonecutterInputSlot((GridContainerMenu) (Object) this, index,
                    index == 0 ? RSI_STONECUTTER_INPUT_X : -1000,
                    index == 0 ? y + RSI_STONECUTTER_INPUT_Y_OFFSET : -1000);
        }
        if (rsi$stationMode == CraftingStationMode.ANVIL) {
            return new CraftingStationInputSlot(rsi$getAnvilState(), index,
                    index < 2 ? 27 + index * 49 : -1000,
                    index < 2 ? y + 43 : -1000, index < 2);
        }
        return new CraftingGridSlot(container, index, x, y);
    }

    @Redirect(method = "addCraftingSlots", at = @At(value = "NEW",
            target = "com/refinedmods/refinedstorage/container/slot/grid/ResultCraftingGridSlot"), remap = false)
    private ResultCraftingGridSlot rsi$redirectResultSlot(Player player, IGrid grid,
                                                            int index, int x, int y) {
        if (rsi$stationMode == CraftingStationMode.SMITHING) {
            return new SmithingResultSlot((GridContainerMenu) (Object) this, player, grid,
                    index, x - 18, y);
        }
        if (rsi$stationMode == CraftingStationMode.STONECUTTER) {
            return new CraftingStationResultSlot((GridContainerMenu) (Object) this, player, grid,
                    index, x + RSI_STONECUTTER_RESULT_X_OFFSET,
                    y + RSI_STONECUTTER_RESULT_Y_OFFSET);
        }
        if (rsi$stationMode == CraftingStationMode.ANVIL) {
            return new CraftingStationResultSlot((GridContainerMenu) (Object) this,
                    // RS 原版结果槽 y 已经是输入槽下方 18 像素，只需补足到铁砧贴图的 y=47。
                    player, grid, index, x, y + 25);
        }
        return new ResultCraftingGridSlot(player, grid, index, x, y);
    }

    @Inject(method = "m_6877_", at = @At("HEAD"), remap = false)
    private void rsi$returnSmithingInputs(Player player, CallbackInfo ci) {
        if (!player.level().isClientSide && rsi$stationMode != CraftingStationMode.CRAFTING) {
            rsi$getCraftingStationState().returnInputs(player);
        }
    }

    @Override
    public boolean rsi$isSmithingMode() {
        return rsi$stationMode == CraftingStationMode.SMITHING;
    }

    @Override
    public void rsi$setSmithingMode(boolean enabled) {
        rsi$setCraftingStationMode(enabled ? CraftingStationMode.SMITHING : CraftingStationMode.CRAFTING);
    }

    @Override
    public CraftingStationMode rsi$getCraftingStationMode() {
        return rsi$stationMode;
    }

    @Override
    public void rsi$setCraftingStationMode(CraftingStationMode mode) {
        GridContainerMenu menu = (GridContainerMenu) (Object) this;
        if (mode == null) mode = CraftingStationMode.CRAFTING;
        if (mode != rsi$stationMode) {
            if (menu.getPlayer().level().isClientSide) {
                if (rsi$stationMode != CraftingStationMode.CRAFTING) {
                    rsi$getCraftingStationState().clearInputs();
                }
                if (rsi$stationMode == CraftingStationMode.CRAFTING
                        && mode != CraftingStationMode.CRAFTING) {
                    rsi$clearCraftingMatrixClient(menu);
                }
            } else {
                if (rsi$stationMode != CraftingStationMode.CRAFTING) {
                    rsi$getCraftingStationState().returnInputs(menu.getPlayer());
                }
                if (mode != CraftingStationMode.CRAFTING) {
                    rsi$getCraftingStationState(mode).returnCraftingMatrix(menu.getPlayer());
                }
            }
        }
        rsi$stationMode = mode;
        if (mode != CraftingStationMode.CRAFTING) rsi$getCraftingStationState().recompute();
    }

    @Unique
    private static void rsi$clearCraftingMatrixClient(GridContainerMenu menu) {
        if (menu.getGrid() == null || menu.getGrid().getCraftingMatrix() == null) return;
        for (int i = 0; i < menu.getGrid().getCraftingMatrix().getContainerSize(); i++) {
            menu.getGrid().getCraftingMatrix().removeItemNoUpdate(i);
        }
    }

    @Override
    public SmithingTerminalState rsi$getSmithingState() {
        if (rsi$smithingState == null) {
            rsi$smithingState = new SmithingTerminalState((GridContainerMenu) (Object) this);
        }
        return rsi$smithingState;
    }

    @Unique
    private AnvilTerminalState rsi$getAnvilState() {
        if (rsi$anvilState == null) {
            rsi$anvilState = new AnvilTerminalState((GridContainerMenu) (Object) this);
        }
        return rsi$anvilState;
    }

    @Override
    public CraftingStationState rsi$getCraftingStationState() {
        return rsi$getCraftingStationState(rsi$stationMode);
    }

    @Unique
    private CraftingStationState rsi$getCraftingStationState(CraftingStationMode mode) {
        if (mode == CraftingStationMode.STONECUTTER) {
            if (rsi$stonecutterState == null) {
                rsi$stonecutterState = new StonecutterTerminalState((GridContainerMenu) (Object) this);
            }
            return rsi$stonecutterState;
        }
        if (mode == CraftingStationMode.ANVIL) return rsi$getAnvilState();
        return rsi$getSmithingState();
    }
}
