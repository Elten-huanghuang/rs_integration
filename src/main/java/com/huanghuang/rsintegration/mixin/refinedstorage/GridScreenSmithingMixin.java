package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.craftingstation.CraftingStationAvailability;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.CraftingStationModePacket;
import com.huanghuang.rsintegration.craftingstation.CraftingStationState;
import com.huanghuang.rsintegration.craftingstation.AnvilTerminalState;
import com.huanghuang.rsintegration.craftingstation.AnvilNamePacket;
import com.huanghuang.rsintegration.craftingstation.SmithingInputSlot;
import com.huanghuang.rsintegration.craftingstation.SmithingTerminalAccess;
import com.huanghuang.rsintegration.craftingstation.StonecutterRecipeSelectPacket;
import com.huanghuang.rsintegration.craftingstation.StonecutterScreenAccess;
import com.huanghuang.rsintegration.craftingstation.StonecutterTerminalState;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CyclingSlotBackground;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SmithingTemplateItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** RS 终端底部锻造台区域：使用原版贴图的局部裁切。 */
@Mixin(value = GridScreen.class, remap = false)
public abstract class GridScreenSmithingMixin implements StonecutterScreenAccess {
    @Unique
    private static final ResourceLocation RSI_SMITHING_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/smithing.png");
    @Unique
    private static final ResourceLocation RSI_STONECUTTER_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/stonecutter.png");
    @Unique
    private static final ResourceLocation RSI_ANVIL_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/anvil.png");
    @Unique
    private static final ResourceLocation RSI_RS_CRAFTING_TEXTURE =
            new ResourceLocation("refinedstorage", "textures/gui/crafting_grid.png");
    @Unique
    private static final int RSI_BUTTON_SIZE = 18;
    @Unique
    private static final int RSI_BUTTON_GAP = 2;
    @Unique
    // 与 RS 原生滚动条（x=174，宽度=12）的右边缘对齐。
    private static final int RSI_BUTTON_X = 174;
    @Unique
    private static final int RSI_BUTTON_TOP_OFFSET = 4;
    @Unique
    // 工作站区域右上角的 RS 清空按钮（区域右边界约为 158，图标宽 7）。
    private static final int RSI_CLEAR_X = 151;
    @Unique
    private static final int RSI_CLEAR_Y = 4;
    @Unique
    private static final int RSI_ANVIL_NAME_X = 59;
    @Unique
    private static final int RSI_ANVIL_NAME_Y = 20;
    @Unique
    private static final int RSI_ANVIL_NAME_WIDTH = 110;
    @Unique
    private static final int RSI_ANVIL_NAME_HEIGHT = 16;
    @Unique
    // 切石机配方面板在原版贴图中的位置和尺寸。
    private static final int RSI_STONECUTTER_PANEL_U = 51;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_V = 14;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_WIDTH = 81;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_HEIGHT = 54;
    @Unique
    // 配方面板整体向左上微调，图标、滚动条和交互区域使用同一组坐标。
    private static final int RSI_STONECUTTER_PANEL_X = 49;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_Y = 11;
    @Unique
    private static final int RSI_STONECUTTER_RECIPE_X = RSI_STONECUTTER_PANEL_X + 1;
    @Unique
    private static final int RSI_STONECUTTER_RECIPE_Y = RSI_STONECUTTER_PANEL_Y + 1;
    @Unique
    private static final int RSI_STONECUTTER_TRACK_X = RSI_STONECUTTER_PANEL_X + 68;
    @Unique
    private static final int RSI_STONECUTTER_TRACK_Y = RSI_STONECUTTER_PANEL_Y + 1;
    @Unique
    private static final int RSI_STONECUTTER_INPUT_X = 19;
    @Unique
    private static final int RSI_STONECUTTER_INPUT_Y = 18;
    @Unique
    // 原版切石机结果槽框位于贴图 x=137；面板裁切起点在目标 x=49。
    private static final int RSI_STONECUTTER_RESULT_X = RSI_STONECUTTER_PANEL_X + 92;
    @Unique
    private static final int RSI_STONECUTTER_RESULT_Y = RSI_STONECUTTER_PANEL_Y + 18;
    @Unique
    private static final int RSI_STONECUTTER_RESULT_U = 137;
    @Unique
    private static final int RSI_STONECUTTER_RESULT_V = 28;
    // smithing.png 的 u=0..3 是整张 GUI 的黑色左外框，不能带入终端合成区。
    @Unique
    private static final int RSI_CROP_U = 4;
    @Unique
    private static final int RSI_CROP_WIDTH = 136;
    @Unique
    private static final List<ResourceLocation> RSI_TEMPLATE_ICONS = List.of(
            new ResourceLocation("item/empty_slot_smithing_template_armor_trim"),
            new ResourceLocation("item/empty_slot_smithing_template_netherite_upgrade"));
    @Unique
    private CyclingSlotBackground[] rsi$slotBackgrounds;
    @Unique
    private int rsi$stonecutterStartIndex;
    @Unique
    private boolean rsi$anvilNameFocused;

    @Override
    public int rsi$getStonecutterStartIndex() {
        return rsi$stonecutterStartIndex;
    }


    @Inject(method = "renderBackground", at = @At("TAIL"), remap = false)
    private void rsi$renderSmithingBackground(GuiGraphics graphics, int guiLeft, int guiTop,
                                               int mouseX, int mouseY, CallbackInfo ci) {
        GridScreen screen = (GridScreen) (Object) this;
        GridContainerMenu menu = screen.getMenu();
        if (menu.getGrid().getGridType() != GridType.CRAFTING) return;
        CraftingStationMode mode = CraftingStationAccess.access(menu).rsi$getCraftingStationMode();
        if (mode == CraftingStationMode.CRAFTING) return;
        int y = guiTop + screen.getTopHeight() + screen.getVisibleRows() * 18;
        if (mode == CraftingStationMode.STONECUTTER) {
            rsi$renderStonecutterBackground(graphics, guiLeft, y, mouseX - guiLeft, mouseY - y,
                    CraftingStationAccess.access(menu).rsi$getCraftingStationState());
            graphics.blit(RSI_RS_CRAFTING_TEXTURE, guiLeft + RSI_CLEAR_X, y + RSI_CLEAR_Y,
                    82, 77, 7, 7, 256, 256);
            return;
        }
        if (mode == CraftingStationMode.ANVIL) {
            rsi$renderAnvilBackground(graphics, guiLeft, y);
            graphics.blit(RSI_RS_CRAFTING_TEXTURE, guiLeft + RSI_CLEAR_X, y + RSI_CLEAR_Y,
                    82, 77, 7, 7, 256, 256);
            return;
        }
        // 先用锻造台底色完整覆盖 RS 原 3x3 区域，避免旧槽框残留。
        graphics.fill(guiLeft + 18, y, guiLeft + 158, y + 60, 0xFFC6C6C6);
        // 将原版锻造台的操作条裁切到 3x3 区域的中间一排。
        // 源槽位 y=48，目标槽位 y=基准+22，正好对齐原 3x3 中排。
        graphics.blit(RSI_SMITHING_TEXTURE, guiLeft + 22, y + 14,
                RSI_CROP_U, 40, RSI_CROP_WIDTH, 36, 256, 256);
        // 保留 RS 自带的清空图标及资源包贴图，坐标与 RS 原生 isOverClear 一致。
        graphics.blit(RSI_RS_CRAFTING_TEXTURE, guiLeft + RSI_CLEAR_X, y + RSI_CLEAR_Y,
                82, 77, 7, 7, 256, 256);
        rsi$ensureSlotBackgrounds(menu);
        if (rsi$slotBackgrounds != null) {
            for (CyclingSlotBackground background : rsi$slotBackgrounds) {
                background.render(menu, graphics, Minecraft.getInstance().getFrameTime(), guiLeft, guiTop);
            }
        }
        if (rsi$hasRecipeError(menu)) {
            graphics.blit(RSI_SMITHING_TEXTURE, guiLeft + 83, y + 20,
                    176, 0, 28, 21, 256, 256);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void rsi$tickSmithingSlots(int guiLeft, int guiTop, CallbackInfo ci) {
        GridContainerMenu menu = ((GridScreen) (Object) this).getMenu();
        if (CraftingStationAccess.access(menu).rsi$getCraftingStationMode() != CraftingStationMode.SMITHING) return;
        rsi$ensureSlotBackgrounds(menu);
        if (rsi$slotBackgrounds == null) return;
        rsi$slotBackgrounds[0].tick(RSI_TEMPLATE_ICONS);
        Slot templateSlot = rsi$findSlot(menu, 0);
        if (templateSlot == null) return;
        ItemStack template = templateSlot.getItem();
        Item item = template.getItem();
        if (item instanceof SmithingTemplateItem smithingTemplate) {
            rsi$slotBackgrounds[1].tick(smithingTemplate.getBaseSlotEmptyIcons());
            rsi$slotBackgrounds[2].tick(smithingTemplate.getAdditionalSlotEmptyIcons());
        } else {
            rsi$slotBackgrounds[1].tick(List.of());
            rsi$slotBackgrounds[2].tick(List.of());
        }
    }

    @Inject(method = "renderForeground", at = @At("TAIL"), remap = false)
    private void rsi$renderSmithingButton(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        GridScreen screen = (GridScreen) (Object) this;
        if (screen.getGrid().getGridType() != GridType.CRAFTING) return;
        CraftingStationMode mode = CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationMode();
        if (mode == CraftingStationMode.ANVIL
                && CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationState()
                instanceof AnvilTerminalState anvil) {
            int y = screen.getTopHeight() + screen.getVisibleRows() * 18;
            String itemName = Minecraft.getInstance().font.plainSubstrByWidth(
                    anvil.itemName(), RSI_ANVIL_NAME_WIDTH - 8);
            graphics.drawString(Minecraft.getInstance().font, itemName,
                    RSI_ANVIL_NAME_X + 4, y + RSI_ANVIL_NAME_Y + 3, 0xFFFFFFFF, false);
            if (!anvil.result().isEmpty()) {
                int color = screen.getMenu().getPlayer().experienceLevel >= anvil.cost()
                        ? 0x80FF20 : 0xFF3030;
                graphics.drawString(Minecraft.getInstance().font,
                        Component.translatable("container.repair.cost", anvil.cost()),
                        110, y + 64, color, false);
            }
            if (rsi$anvilNameFocused) {
                int cursorX = Math.min(RSI_ANVIL_NAME_X + RSI_ANVIL_NAME_WIDTH - 4,
                        RSI_ANVIL_NAME_X + 3 + Minecraft.getInstance().font.width(anvil.itemName()));
                graphics.fill(cursorX, y + RSI_ANVIL_NAME_Y + 2,
                        cursorX + 1, y + RSI_ANVIL_NAME_Y + RSI_ANVIL_NAME_HEIGHT - 2,
                        0xFF404040);
            }
        }
        int top = screen.getTopHeight() + screen.getVisibleRows() * 18 + RSI_BUTTON_TOP_OFFSET;
        int step = RSI_BUTTON_SIZE + RSI_BUTTON_GAP;
        int buttonIndex = 0;
        buttonIndex = rsi$drawAvailableModeButton(graphics, mouseX, mouseY, top, buttonIndex,
                mode, CraftingStationMode.CRAFTING, new ItemStack(Items.CRAFTING_TABLE));
        buttonIndex = rsi$drawAvailableModeButton(graphics, mouseX, mouseY, top, buttonIndex,
                mode, CraftingStationMode.STONECUTTER, new ItemStack(Items.STONECUTTER));
        buttonIndex = rsi$drawAvailableModeButton(graphics, mouseX, mouseY, top, buttonIndex,
                mode, CraftingStationMode.SMITHING, new ItemStack(Items.SMITHING_TABLE));
        rsi$drawAvailableModeButton(graphics, mouseX, mouseY, top, buttonIndex,
                mode, CraftingStationMode.ANVIL, new ItemStack(Items.ANVIL));
    }

    @Unique
    private static int rsi$drawAvailableModeButton(GuiGraphics graphics, double mouseX, double mouseY,
                                                   int top, int buttonIndex, CraftingStationMode selectedMode,
                                                   CraftingStationMode mode, ItemStack icon) {
        if (!CraftingStationAvailability.isAvailable(mode)) return buttonIndex;
        int y = top + buttonIndex * (RSI_BUTTON_SIZE + RSI_BUTTON_GAP);
        rsi$drawModeButton(graphics, RSI_BUTTON_X, y, icon,
                selectedMode == mode, rsi$inside(mouseX, mouseY, RSI_BUTTON_X, y));
        return buttonIndex + 1;
    }

    @Unique
    private static void rsi$drawModeButton(GuiGraphics graphics, int x, int y,
                                           ItemStack icon, boolean selected, boolean hovered) {
        // 直接复用 RS 侧边按钮背景，避免 RSI 自己绘制一块灰色底板。
        // RS 的普通态和高亮态分别位于 icons.png 的 v=16 和 v=35。
        int backgroundV = selected || hovered ? 35 : 16;
        graphics.blit(BaseScreen.ICONS_TEXTURE, x, y, 238, backgroundV,
                RSI_BUTTON_SIZE, RSI_BUTTON_SIZE, 256, 256);
        graphics.renderItem(icon, x + 1, y + 1);
    }

    @Unique
    private static boolean rsi$inside(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + RSI_BUTTON_SIZE
                && mouseY >= y && mouseY < y + RSI_BUTTON_SIZE;
    }

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true)
    private void rsi$clickSmithingButton(double mouseX, double mouseY, int button,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) return;
        GridScreen screen = (GridScreen) (Object) this;
        GridContainerMenu menu = screen.getMenu();
        CraftingStationAccess access = CraftingStationAccess.access(menu);
        if (access.rsi$getCraftingStationMode() == CraftingStationMode.ANVIL) {
            int stationY = screen.getTopHeight() + screen.getVisibleRows() * 18;
            double localX = mouseX - screen.getGuiLeft();
            double localY = mouseY - screen.getGuiTop();
            if (rsi$inside(localX, localY, RSI_ANVIL_NAME_X, stationY + RSI_ANVIL_NAME_Y,
                    RSI_ANVIL_NAME_WIDTH, RSI_ANVIL_NAME_HEIGHT)) {
                rsi$anvilNameFocused = true;
                cir.setReturnValue(true);
                return;
            }
        }
        rsi$anvilNameFocused = false;
        if (access.rsi$getCraftingStationMode() == CraftingStationMode.STONECUTTER
                && access.rsi$getCraftingStationState() instanceof StonecutterTerminalState stonecutter) {
            int stationTop = screen.getTopHeight() + screen.getVisibleRows() * 18;
            double stationX = mouseX - screen.getGuiLeft();
            double stationY = mouseY - screen.getGuiTop();
            for (int index = rsi$stonecutterStartIndex;
                 index < Math.min(stonecutter.recipes().size(), rsi$stonecutterStartIndex + 12); index++) {
                int relative = index - rsi$stonecutterStartIndex;
                int recipeX = RSI_STONECUTTER_RECIPE_X + relative % 4 * 16;
                int recipeY = stationTop + RSI_STONECUTTER_RECIPE_Y + relative / 4 * 18;
                if (rsi$inside(stationX, stationY, recipeX, recipeY, 16, 18)) {
                    stonecutter.selectRecipe(index);
                    NetworkHandler.CHANNEL.sendToServer(new StonecutterRecipeSelectPacket(index));
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
        if (access.rsi$getCraftingStationMode() != CraftingStationMode.CRAFTING) {
            int clearX = screen.getGuiLeft() + RSI_CLEAR_X;
            int clearY = screen.getGuiTop() + screen.getTopHeight()
                    + screen.getVisibleRows() * 18 + RSI_CLEAR_Y;
            if (mouseX >= clearX && mouseX < clearX + 7
                    && mouseY >= clearY && mouseY < clearY + 7) {
                NetworkHandler.CHANNEL.sendToServer(CraftingStationModePacket.clear());
                cir.setReturnValue(true);
                return;
            }
        }
        int y = screen.getTopHeight() + screen.getVisibleRows() * 18 + RSI_BUTTON_TOP_OFFSET;
        double relX = mouseX - screen.getGuiLeft();
        double relY = mouseY - screen.getGuiTop();
        int step = RSI_BUTTON_SIZE + RSI_BUTTON_GAP;
        CraftingStationMode mode = null;
        int buttonIndex = 0;
        CraftingStationMode[] modes = {
                CraftingStationMode.CRAFTING,
                CraftingStationMode.STONECUTTER,
                CraftingStationMode.SMITHING,
                CraftingStationMode.ANVIL
        };
        for (CraftingStationMode candidate : modes) {
            if (!CraftingStationAvailability.isAvailable(candidate)) continue;
            if (rsi$inside(relX, relY, RSI_BUTTON_X, y + buttonIndex * step)) {
                mode = candidate;
                break;
            }
            buttonIndex++;
        }
        if (mode == null) return;
        if (access.rsi$getCraftingStationMode() == mode) {
            cir.setReturnValue(true);
            return;
        }
        // 服务端包负责权威状态；客户端先同步本地容器，避免等待槽位包时界面仍显示旧合成区。
        access.rsi$setCraftingStationMode(mode);
        access.rsi$refreshCraftingStationSlots();
        rsi$slotBackgrounds = null;
        rsi$stonecutterStartIndex = 0;
        NetworkHandler.CHANNEL.sendToServer(new CraftingStationModePacket(mode));
        cir.setReturnValue(true);
    }

    @Inject(method = "m_7933_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$handleAnvilNameKey(int keyCode, int scanCode, int modifiers,
                                        CallbackInfoReturnable<Boolean> cir) {
        GridScreen screen = (GridScreen) (Object) this;
        if (!rsi$anvilNameFocused
                || CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationMode()
                != CraftingStationMode.ANVIL) return;
        if (keyCode == 259) {
            CraftingStationState state = CraftingStationAccess.access(screen.getMenu())
                    .rsi$getCraftingStationState();
            if (state instanceof AnvilTerminalState anvil) {
                String name = anvil.itemName();
                if (!name.isEmpty()) rsi$setAnvilName(anvil, name.substring(0, name.length() - 1));
            }
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_5534_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$handleAnvilNameCharacter(char character, int modifiers,
                                              CallbackInfoReturnable<Boolean> cir) {
        GridScreen screen = (GridScreen) (Object) this;
        if (!rsi$anvilNameFocused
                || CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationMode()
                != CraftingStationMode.ANVIL || Character.isISOControl(character)) return;
        CraftingStationState state = CraftingStationAccess.access(screen.getMenu())
                .rsi$getCraftingStationState();
        if (state instanceof AnvilTerminalState anvil && anvil.itemName().length() < 50) {
            rsi$setAnvilName(anvil, anvil.itemName() + character);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void rsi$setAnvilName(AnvilTerminalState state, String name) {
        state.setItemName(name);
        NetworkHandler.CHANNEL.sendToServer(new AnvilNamePacket(name));
    }

    @Inject(method = "m_6050_", at = @At("HEAD"), cancellable = true)
    private void rsi$scrollStonecutterRecipes(double mouseX, double mouseY, double scroll,
                                              CallbackInfoReturnable<Boolean> cir) {
        GridScreen screen = (GridScreen) (Object) this;
        GridContainerMenu menu = screen.getMenu();
        if (CraftingStationAccess.access(menu).rsi$getCraftingStationMode() != CraftingStationMode.STONECUTTER
                || !(CraftingStationAccess.access(menu).rsi$getCraftingStationState()
                instanceof StonecutterTerminalState stonecutter)) return;
        int top = screen.getTopHeight() + screen.getVisibleRows() * 18;
        double relativeX = mouseX - screen.getGuiLeft();
        double relativeY = mouseY - screen.getGuiTop();
        if (!rsi$inside(relativeX, relativeY, RSI_STONECUTTER_RECIPE_X,
                top + RSI_STONECUTTER_RECIPE_Y, 79, 54)
                || stonecutter.recipes().size() <= 12) return;
        int rows = (stonecutter.recipes().size() + 3) / 4;
        int maxStart = Math.max(0, rows - 3) * 4;
        rsi$stonecutterStartIndex = Math.max(0, Math.min(maxStart,
                rsi$stonecutterStartIndex - (scroll > 0 ? 4 : -4)));
        cir.setReturnValue(true);
    }

    @Unique
    private void rsi$renderStonecutterBackground(GuiGraphics graphics, int guiLeft, int y,
                                                 int mouseX, int mouseY, Object state) {
        graphics.fill(guiLeft + 18, y, guiLeft + 158, y + 60, 0xFFC6C6C6);
        // 保留原版切石机的棕色配方面板，只裁出配方区，避免带入右侧无关内容。
        graphics.blit(RSI_STONECUTTER_TEXTURE, guiLeft + RSI_STONECUTTER_PANEL_X,
                y + RSI_STONECUTTER_PANEL_Y, RSI_STONECUTTER_PANEL_U, RSI_STONECUTTER_PANEL_V,
                RSI_STONECUTTER_PANEL_WIDTH, RSI_STONECUTTER_PANEL_HEIGHT, 256, 256);
        // 输入/输出槽框直接裁自原版贴图，槽位本身由容器槽负责渲染物品。
        graphics.blit(RSI_STONECUTTER_TEXTURE,
                guiLeft + RSI_STONECUTTER_INPUT_X, y + RSI_STONECUTTER_INPUT_Y,
                18, 32, 18, 18, 256, 256);
        graphics.blit(RSI_STONECUTTER_TEXTURE,
                guiLeft + RSI_STONECUTTER_RESULT_X, y + RSI_STONECUTTER_RESULT_Y,
                RSI_STONECUTTER_RESULT_U, RSI_STONECUTTER_RESULT_V,
                18, 18, 256, 256);
        if (!(state instanceof StonecutterTerminalState stonecutter)) return;
        rsi$clampStonecutterStartIndex(stonecutter);
        // 轨道和滚动块都始终绘制；配方不足一页时使用原版禁用态滚动块。
        graphics.blit(RSI_STONECUTTER_TEXTURE, guiLeft + RSI_STONECUTTER_TRACK_X,
                y + RSI_STONECUTTER_TRACK_Y,
                119, 15, 12, 54, 256, 256);
        if (stonecutter.recipes().size() > 12) {
            int rows = (stonecutter.recipes().size() + 3) / 4;
            int maxStart = Math.max(0, (rows - 3) * 4);
            int offsetRows = maxStart == 0 ? 0 : rsi$stonecutterStartIndex / 4;
            int maxOffsetRows = Math.max(1, rows - 3);
            int thumbOffset = Math.round(41.0f * offsetRows / maxOffsetRows);
            graphics.blit(RSI_STONECUTTER_TEXTURE, guiLeft + RSI_STONECUTTER_TRACK_X,
                    y + RSI_STONECUTTER_TRACK_Y + 1 + thumbOffset,
                    176, 0, 12, 15, 256, 256);
        } else {
            graphics.blit(RSI_STONECUTTER_TEXTURE, guiLeft + RSI_STONECUTTER_TRACK_X,
                    y + RSI_STONECUTTER_TRACK_Y + 1,
                    188, 0, 12, 15, 256, 256);
        }
        int end = Math.min(stonecutter.recipes().size(), rsi$stonecutterStartIndex + 12);
        for (int index = rsi$stonecutterStartIndex; index < end; index++) {
            int relative = index - rsi$stonecutterStartIndex;
            int x = guiLeft + RSI_STONECUTTER_RECIPE_X + relative % 4 * 16;
            int row = relative / 4;
            int buttonY = y + RSI_STONECUTTER_RECIPE_Y + row * 18;
            int sourceY = index == stonecutter.selectedRecipeIndex() ? 184
                    : rsi$inside(mouseX, mouseY, x - guiLeft, buttonY - y, 16, 18) ? 202 : 166;
            graphics.blit(RSI_STONECUTTER_TEXTURE, x, buttonY - 1, 0, sourceY, 16, 18, 256, 256);
            graphics.renderItem(stonecutter.recipes().get(index).getResultItem(
                    Minecraft.getInstance().level.registryAccess()), x, buttonY + 2);
        }
    }

    @Unique
    private void rsi$renderAnvilBackground(GuiGraphics graphics, int guiLeft, int y) {
        // 直接裁切原版铁砧操作区，保留锤子、名称栏、加号、箭头和所有槽框。
        // 原版槽框位于贴图 y=47，因此整块裁切后容器槽位也使用同一坐标。
        graphics.blit(RSI_ANVIL_TEXTURE, guiLeft, y,
                0, 0, 176, 64, 256, 256);
        // 原版贴图的名称栏是占位色，覆盖为原版铁砧的深色输入框，并避开 RS 的 X。
        int nameX = guiLeft + RSI_ANVIL_NAME_X;
        int nameY = y + RSI_ANVIL_NAME_Y;
        int nameBackground = rsi$anvilNameFocused ? 0xFFB0A184 : 0xFF4B4639;
        graphics.fill(nameX - 1, nameY - 1,
                nameX + RSI_ANVIL_NAME_WIDTH + 1, nameY + RSI_ANVIL_NAME_HEIGHT + 1, 0xFF37332B);
        graphics.fill(nameX, nameY, nameX + RSI_ANVIL_NAME_WIDTH,
                nameY + RSI_ANVIL_NAME_HEIGHT, nameBackground);
        graphics.fill(nameX + 1, nameY + 1, nameX + RSI_ANVIL_NAME_WIDTH - 1,
                nameY + 2, rsi$anvilNameFocused ? 0xFFC5B99D : 0xFF625B49);
        graphics.fill(nameX + RSI_ANVIL_NAME_WIDTH - 1, nameY + 1,
                nameX + RSI_ANVIL_NAME_WIDTH, nameY + RSI_ANVIL_NAME_HEIGHT - 1,
                rsi$anvilNameFocused ? 0xFFE7DEC9 : 0xFF777062);
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.translatable("container.repair"), guiLeft + 88, y + 6, 0x404040);
    }

    @Unique
    private void rsi$clampStonecutterStartIndex(StonecutterTerminalState stonecutter) {
        int rows = (stonecutter.recipes().size() + 3) / 4;
        int maxStart = Math.max(0, (rows - 3) * 4);
        rsi$stonecutterStartIndex = Math.max(0, Math.min(maxStart,
                rsi$stonecutterStartIndex - rsi$stonecutterStartIndex % 4));
    }

    @Unique
    private static boolean rsi$inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Unique
    private void rsi$ensureSlotBackgrounds(GridContainerMenu menu) {
        if (rsi$slotBackgrounds != null) return;
        Slot template = rsi$findSlot(menu, 0);
        Slot base = rsi$findSlot(menu, 1);
        Slot addition = rsi$findSlot(menu, 2);
        if (template == null || base == null || addition == null) return;
        rsi$slotBackgrounds = new CyclingSlotBackground[]{
                new CyclingSlotBackground(template.index),
                new CyclingSlotBackground(base.index),
                new CyclingSlotBackground(addition.index)};
    }

    @Unique
    private static Slot rsi$findSlot(GridContainerMenu menu, int smithingIndex) {
        for (Slot slot : menu.slots) {
            if (slot instanceof SmithingInputSlot input && input.isActive()
                    && input.getSlotIndex() == smithingIndex) return input;
        }
        return null;
    }

    @Unique
    private static boolean rsi$hasRecipeError(GridContainerMenu menu) {
        for (int index = 0; index < 3; index++) {
            Slot slot = rsi$findSlot(menu, index);
            if (slot == null || !slot.hasItem()) return false;
        }
        return SmithingTerminalAccess.access(menu).rsi$getSmithingState().result().isEmpty();
    }
}
