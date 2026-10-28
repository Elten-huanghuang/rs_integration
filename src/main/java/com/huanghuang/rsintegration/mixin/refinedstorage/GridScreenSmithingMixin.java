package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationAvailability;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.network.craftingstation.CraftingStationModePacket;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationInputSlot;
import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationResultSlot;
import com.huanghuang.rsintegration.feature.craftingstation.AnvilTerminalState;
import com.huanghuang.rsintegration.network.craftingstation.AnvilNamePacket;
import com.huanghuang.rsintegration.feature.craftingstation.SmithingTerminalState;
import com.huanghuang.rsintegration.network.craftingstation.StonecutterRecipeSelectPacket;
import com.huanghuang.rsintegration.feature.craftingstation.StonecutterScreenAccess;
import com.huanghuang.rsintegration.feature.craftingstation.StonecutterTerminalState;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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
    private static final ResourceLocation RSI_RS_ICONS_TEXTURE =
            new ResourceLocation("refinedstorage", "textures/icons.png");
    @Unique
    private static final int RSI_PANEL_WIDTH = 140;
    @Unique
    private static final int RSI_PANEL_HEIGHT = 60;
    @Unique
    // RS 工作台图集中的纯底色、单个九宫格槽框和合成箭头。
    private static final int RSI_PANEL_BACKGROUND_U = 100;
    @Unique
    private static final int RSI_PANEL_BACKGROUND_V = 137;
    @Unique
    private static final int RSI_CRAFTING_SLOT_U = 24;
    @Unique
    private static final int RSI_CRAFTING_SLOT_V = 77;
    @Unique
    private static final int RSI_CRAFTING_ARROW_U = 95;
    @Unique
    private static final int RSI_CRAFTING_ARROW_V = 96;
    @Unique
    private static final int RSI_CRAFTING_ARROW_WIDTH = 22;
    @Unique
    private static final int RSI_CRAFTING_ARROW_HEIGHT = 15;
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
    // 切石机原版配方选择面板的局部裁切。
    private static final int RSI_STONECUTTER_PANEL_U = 51;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_V = 14;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_WIDTH = 81;
    @Unique
    private static final int RSI_STONECUTTER_PANEL_HEIGHT = 54;
    @Unique
    // 配方列表整体向左上微调，图标、滚动条和交互区域使用同一组坐标。
    private static final int RSI_STONECUTTER_LIST_X = 49;
    @Unique
    private static final int RSI_STONECUTTER_LIST_Y = 11;
    @Unique
    private static final int RSI_STONECUTTER_RECIPE_X = RSI_STONECUTTER_LIST_X + 1;
    @Unique
    private static final int RSI_STONECUTTER_RECIPE_Y = RSI_STONECUTTER_LIST_Y + 1;
    @Unique
    private static final int RSI_STONECUTTER_TRACK_X = RSI_STONECUTTER_LIST_X + 68;
    @Unique
    private static final int RSI_STONECUTTER_TRACK_Y = RSI_STONECUTTER_LIST_Y + 1;
    @Unique
    // RS 主物品区右侧的完整滚动槽：外框 14x54，内部 12x52。
    private static final int RSI_RS_SCROLLBAR_TRACK_U = 173;
    @Unique
    private static final int RSI_RS_SCROLLBAR_TRACK_V = 19;
    @Unique
    private static final int RSI_RS_SCROLLBAR_TRACK_WIDTH = 12;
    @Unique
    private static final int RSI_RS_SCROLLBAR_TRACK_HEIGHT = 52;
    @Unique
    private static final int RSI_RS_SCROLLBAR_THUMB_ENABLED_U = 232;
    @Unique
    private static final int RSI_RS_SCROLLBAR_THUMB_DISABLED_U = 244;
    @Unique
    private static final int RSI_RS_SCROLLBAR_THUMB_HEIGHT = 15;
    @Unique
    private static final List<ResourceLocation> RSI_TEMPLATE_ICONS = List.of(
            new ResourceLocation("item/empty_slot_smithing_template_armor_trim"),
            new ResourceLocation("item/empty_slot_smithing_template_netherite_upgrade"));
    @Unique
    private CyclingSlotBackground[] rsi$slotBackgrounds;
    @Unique
    private int rsi$stonecutterStartIndex;
    @Unique
    // 铁砧名称输入框：用原版 EditBox，才能复用输入法提交、剪贴板粘贴和光标编辑。
    private EditBox rsi$anvilNameBox;
    @Unique
    // EditBox 记录的顶部偏移，工作区行数变化时据此重建输入框。
    private int rsi$anvilNameBoxTop = Integer.MIN_VALUE;
    @Unique
    // 最近一次同步到服务端的名称，用于逐帧检测 EditBox 内容变化。
    private String rsi$anvilNameSent = "";

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
            rsi$renderStonecutterBackground(graphics, guiLeft, guiTop, y, mouseX - guiLeft, mouseY - y,
                    CraftingStationAccess.access(menu).rsi$getCraftingStationState(), menu);
            graphics.blit(RSI_RS_CRAFTING_TEXTURE, guiLeft + RSI_CLEAR_X, y + RSI_CLEAR_Y,
                    82, 77, 7, 7, 256, 256);
            return;
        }
        if (mode == CraftingStationMode.ANVIL) {
            rsi$renderAnvilBackground(graphics, guiLeft, guiTop, y, menu);
            graphics.blit(RSI_RS_CRAFTING_TEXTURE, guiLeft + RSI_CLEAR_X, y + RSI_CLEAR_Y,
                    82, 77, 7, 7, 256, 256);
            return;
        }
        rsi$renderCraftingStationPanel(graphics, guiLeft, y);
        rsi$renderCraftingStationSlotFrames(graphics, guiLeft, guiTop, menu);
        rsi$renderCraftingArrow(graphics, guiLeft + 87, y + 22);
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
        if (menu.getGrid().getGridType() != GridType.CRAFTING) return;
        // EditBox 不在 RS 的控件列表里，需要手动推进光标闪烁。
        if (rsi$anvilNameBox != null) rsi$anvilNameBox.tick();
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
        if (mode != CraftingStationMode.ANVIL) rsi$blurAnvilName();
        if (mode == CraftingStationMode.ANVIL
                && CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationState()
                instanceof AnvilTerminalState anvil) {
            int y = screen.getTopHeight() + screen.getVisibleRows() * 18;
            rsi$renderAnvilNameBox(anvil, graphics, y, mouseX, mouseY);
            if (!anvil.result().isEmpty()) {
                int color = screen.getMenu().getPlayer().experienceLevel >= anvil.cost()
                        ? 0x80FF20 : 0xFF3030;
                graphics.drawString(Minecraft.getInstance().font,
                        Component.translatable("container.repair.cost", anvil.cost()),
                        110, y + 64, color, false);
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
        // 样板终端的写入按钮、输出槽与工作站按钮重叠，不能留下不可见的点击区域。
        if (screen.getGrid().getGridType() != GridType.CRAFTING) return;
        GridContainerMenu menu = screen.getMenu();
        CraftingStationAccess access = CraftingStationAccess.access(menu);
        if (access.rsi$getCraftingStationMode() == CraftingStationMode.ANVIL) {
            int stationY = screen.getTopHeight() + screen.getVisibleRows() * 18;
            double localX = mouseX - screen.getGuiLeft();
            double localY = mouseY - screen.getGuiTop();
            if (rsi$inside(localX, localY, RSI_ANVIL_NAME_X, stationY + RSI_ANVIL_NAME_Y,
                    RSI_ANVIL_NAME_WIDTH, RSI_ANVIL_NAME_HEIGHT)) {
                rsi$focusAnvilName(access, stationY, localX, localY - stationY);
                cir.setReturnValue(true);
                return;
            }
        }
        rsi$blurAnvilName();
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
        if (screen.getGrid().getGridType() != GridType.CRAFTING) return;
        if (!rsi$isAnvilNameFocused()
                || CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationMode()
                != CraftingStationMode.ANVIL) return;
        // 退格、Delete、方向键、Home/End 以及 Ctrl+A/C/V/X 全部交给 EditBox。
        if (rsi$anvilNameBox.keyPressed(keyCode, scanCode, modifiers)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_5534_", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$handleAnvilNameCharacter(char character, int modifiers,
                                              CallbackInfoReturnable<Boolean> cir) {
        GridScreen screen = (GridScreen) (Object) this;
        if (screen.getGrid().getGridType() != GridType.CRAFTING) return;
        if (!rsi$isAnvilNameFocused()
                || CraftingStationAccess.access(screen.getMenu()).rsi$getCraftingStationMode()
                != CraftingStationMode.ANVIL || Character.isISOControl(character)) return;
        // 中文等非 ASCII 字符同样经 EditBox 写入，长度按字符而不是字节截断。
        if (rsi$anvilNameBox.charTyped(character, modifiers)) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private void rsi$setAnvilName(AnvilTerminalState state, String name) {
        String clamped = AnvilNamePacket.clampName(name);
        state.setItemName(clamped);
        NetworkHandler.CHANNEL.sendToServer(new AnvilNamePacket(clamped));
    }

    @Unique
    private boolean rsi$isAnvilNameFocused() {
        return rsi$anvilNameBox != null && rsi$anvilNameBox.isFocused();
    }

    @Unique
    private void rsi$blurAnvilName() {
        if (rsi$anvilNameBox != null) rsi$anvilNameBox.setFocused(false);
    }

    @Unique
    private void rsi$focusAnvilName(CraftingStationAccess access, int stationY,
                                    double localX, double localY) {
        EditBox box = rsi$anvilNameBox(stationY);
        if (!box.isFocused()) {
            if (access.rsi$getCraftingStationState() instanceof AnvilTerminalState anvil) {
                rsi$syncAnvilName(box, anvil.itemName());
            }
            rsi$anvilNameSent = box.getValue();
            box.setFocused(true);
        }
        // 把点击位置换算成控件内坐标，用于把光标放到点中的位置。
        box.mouseClicked(localX, localY, 0);
    }

    @Unique
    private EditBox rsi$anvilNameBox(int stationY) {
        if (rsi$anvilNameBox != null && rsi$anvilNameBoxTop == stationY) return rsi$anvilNameBox;
        String previous = rsi$anvilNameBox == null ? "" : rsi$anvilNameBox.getValue();
        boolean wasFocused = rsi$isAnvilNameFocused();
        EditBox box = new EditBox(Minecraft.getInstance().font,
                RSI_ANVIL_NAME_X + 4, stationY + RSI_ANVIL_NAME_Y + 3,
                RSI_ANVIL_NAME_WIDTH - 8, 9, Component.empty());
        box.setBordered(false);
        box.setMaxLength(50);
        box.setTextColor(0xFFFFFFFF);
        box.setTextColorUneditable(0xFFFFFFFF);
        if (!previous.isEmpty()) box.setValue(previous);
        box.setFocused(wasFocused);
        rsi$anvilNameBox = box;
        rsi$anvilNameBoxTop = stationY;
        return box;
    }

    @Unique
    private void rsi$renderAnvilNameBox(AnvilTerminalState anvil, GuiGraphics graphics,
                                        int stationY, int mouseX, int mouseY) {
        EditBox box = rsi$anvilNameBox(stationY);
        if (box.isFocused()) {
            // 聚焦时以输入框内容为准，内容变化即同步给服务端（每帧检测，避免逐字符发包）。
            if (!box.getValue().equals(rsi$anvilNameSent)) {
                rsi$anvilNameSent = box.getValue();
                rsi$setAnvilName(anvil, box.getValue());
            }
        } else {
            // 未聚焦时跟随菜单状态，物品变化后能显示新的默认名称。
            rsi$syncAnvilName(box, anvil.itemName());
            rsi$anvilNameSent = box.getValue();
        }
        box.render(graphics, mouseX, mouseY, 0.0F);
    }

    @Unique
    private static void rsi$syncAnvilName(EditBox box, String name) {
        String clamped = AnvilNamePacket.clampName(name);
        if (!box.getValue().equals(clamped)) box.setValue(clamped);
    }

    @Inject(method = "m_6050_", at = @At("HEAD"), cancellable = true)
    private void rsi$scrollStonecutterRecipes(double mouseX, double mouseY, double scroll,
                                              CallbackInfoReturnable<Boolean> cir) {
        GridScreen screen = (GridScreen) (Object) this;
        if (screen.getGrid().getGridType() != GridType.CRAFTING) return;
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
    private void rsi$renderStonecutterBackground(GuiGraphics graphics, int guiLeft, int guiTop, int y,
                                                 int mouseX, int mouseY, Object state,
                                                 GridContainerMenu menu) {
        rsi$renderCraftingStationPanel(graphics, guiLeft, y);
        graphics.blit(RSI_STONECUTTER_TEXTURE, guiLeft + RSI_STONECUTTER_LIST_X,
                y + RSI_STONECUTTER_LIST_Y, RSI_STONECUTTER_PANEL_U, RSI_STONECUTTER_PANEL_V,
                RSI_STONECUTTER_PANEL_WIDTH, RSI_STONECUTTER_PANEL_HEIGHT, 256, 256);
        rsi$renderCraftingStationSlotFrames(graphics, guiLeft, guiTop, menu);
        if (!(state instanceof StonecutterTerminalState stonecutter)) return;
        rsi$clampStonecutterStartIndex(stonecutter);
        boolean canScroll = stonecutter.recipes().size() > 12;
        int thumbOffset = 0;
        if (canScroll) {
            int rows = (stonecutter.recipes().size() + 3) / 4;
            int maxOffsetRows = Math.max(1, rows - 3);
            int offsetRows = rsi$stonecutterStartIndex / 4;
            thumbOffset = Math.round((RSI_RS_SCROLLBAR_TRACK_HEIGHT - RSI_RS_SCROLLBAR_THUMB_HEIGHT)
                    * (float) offsetRows / maxOffsetRows);
        }
        graphics.blit(RSI_RS_CRAFTING_TEXTURE, guiLeft + RSI_STONECUTTER_TRACK_X - 1,
                y + RSI_STONECUTTER_TRACK_Y - 1, RSI_RS_SCROLLBAR_TRACK_U,
                RSI_RS_SCROLLBAR_TRACK_V, RSI_RS_SCROLLBAR_TRACK_WIDTH + 2,
                RSI_RS_SCROLLBAR_TRACK_HEIGHT + 2, 256, 256);
        graphics.blit(RSI_RS_ICONS_TEXTURE, guiLeft + RSI_STONECUTTER_TRACK_X,
                y + RSI_STONECUTTER_TRACK_Y + thumbOffset,
                canScroll ? RSI_RS_SCROLLBAR_THUMB_ENABLED_U : RSI_RS_SCROLLBAR_THUMB_DISABLED_U,
                0, RSI_RS_SCROLLBAR_TRACK_WIDTH, RSI_RS_SCROLLBAR_THUMB_HEIGHT, 256, 256);
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
                    Minecraft.getInstance().level.registryAccess()), x, buttonY);
        }
    }

    @Unique
    private void rsi$renderAnvilBackground(GuiGraphics graphics, int guiLeft, int guiTop, int y,
                                           GridContainerMenu menu) {
        rsi$renderCraftingStationPanel(graphics, guiLeft, y);
        rsi$renderCraftingStationSlotFrames(graphics, guiLeft, guiTop, menu);
        graphics.drawCenteredString(Minecraft.getInstance().font, "+", guiLeft + 61, y + 50, 0x404040);
        rsi$renderCraftingArrow(graphics, guiLeft + 103, y + 48);
        // 名称框与 RS 清空按钮错开，输入状态使用更亮的边框提示。
        int nameX = guiLeft + RSI_ANVIL_NAME_X;
        int nameY = y + RSI_ANVIL_NAME_Y;
        boolean editing = rsi$isAnvilNameFocused();
        int nameBackground = editing ? 0xFFB0A184 : 0xFF4B4639;
        graphics.fill(nameX - 1, nameY - 1,
                nameX + RSI_ANVIL_NAME_WIDTH + 1, nameY + RSI_ANVIL_NAME_HEIGHT + 1, 0xFF37332B);
        graphics.fill(nameX, nameY, nameX + RSI_ANVIL_NAME_WIDTH,
                nameY + RSI_ANVIL_NAME_HEIGHT, nameBackground);
        graphics.fill(nameX + 1, nameY + 1, nameX + RSI_ANVIL_NAME_WIDTH - 1,
                nameY + 2, editing ? 0xFFC5B99D : 0xFF625B49);
        graphics.fill(nameX + RSI_ANVIL_NAME_WIDTH - 1, nameY + 1,
                nameX + RSI_ANVIL_NAME_WIDTH, nameY + RSI_ANVIL_NAME_HEIGHT - 1,
                editing ? 0xFFE7DEC9 : 0xFF777062);
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.translatable("container.repair"), guiLeft + 88, y + 6, 0x404040);
    }

    @Unique
    private static void rsi$renderCraftingStationPanel(GuiGraphics graphics, int guiLeft, int y) {
        graphics.blit(RSI_RS_CRAFTING_TEXTURE,
                guiLeft + 18, y, RSI_PANEL_WIDTH, RSI_PANEL_HEIGHT,
                RSI_PANEL_BACKGROUND_U, RSI_PANEL_BACKGROUND_V, 1, 1, 256, 256);
    }

    @Unique
    private static void rsi$renderCraftingStationSlotFrames(GuiGraphics graphics,
                                                             int guiLeft, int guiTop,
                                                             GridContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if ((slot instanceof CraftingStationInputSlot input && input.isActive())
                    || slot instanceof CraftingStationResultSlot) {
                graphics.blit(RSI_RS_CRAFTING_TEXTURE,
                        guiLeft + slot.x - 1, guiTop + slot.y - 1, 18, 18,
                        RSI_CRAFTING_SLOT_U, RSI_CRAFTING_SLOT_V, 18, 18, 256, 256);
            }
        }
    }

    @Unique
    private static void rsi$renderCraftingArrow(GuiGraphics graphics, int x, int y) {
        graphics.blit(RSI_RS_CRAFTING_TEXTURE, x, y,
                RSI_CRAFTING_ARROW_U, RSI_CRAFTING_ARROW_V,
                RSI_CRAFTING_ARROW_WIDTH, RSI_CRAFTING_ARROW_HEIGHT, 256, 256);
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
            if (slot instanceof CraftingStationInputSlot input
                    && input.stationState() instanceof SmithingTerminalState
                    && input.isActive()
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
        return CraftingStationAccess.access(menu).rsi$getCraftingStationState()
                .result().isEmpty();
    }
}
