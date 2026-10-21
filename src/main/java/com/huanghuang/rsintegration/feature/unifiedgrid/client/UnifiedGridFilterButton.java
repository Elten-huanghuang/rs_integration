package com.huanghuang.rsintegration.feature.unifiedgrid.client;

import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.widget.sidebutton.SideButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 使用原版侧按钮布局，窗口缩放时随原版按钮重新排布。 */
public final class UnifiedGridFilterButton extends SideButton {
    private final GridScreen grid;

    /**
     * 按钮图标不取翻译文本，用固定符号。
     *
     * <p>原版侧按钮只有 16×16，取标签首字会随语言变化（英文档位会变成 A / I / F），
     * 更麻烦的是语言文件缺键时 {@code getString()} 返回的是原始 key 而不是空串，
     * 按钮上就会糊出一串「rs_integration…」。固定符号与语言无关，也不会被缺键影响。</p>
     */
    private static final String ICON_ALL = "全";
    private static final String ICON_ITEMS = "物";
    private static final String ICON_FLUIDS = "流";
    private static final int ICON_COLOR = 0xFFFFFF;

    public UnifiedGridFilterButton(GridScreen screen) { super(screen); grid = screen; }

    @Override public void onPress() {
        if (grid.getView() instanceof UnifiedGridView view) view.cycleFilter();
    }

    @Override protected void renderButtonIcon(GuiGraphics graphics, int x, int y) {
        if (!(grid.getView() instanceof UnifiedGridView view)) return;
        String icon = switch (view.filter()) {
            case UnifiedGridView.FILTER_ITEMS -> ICON_ITEMS;
            case UnifiedGridView.FILTER_FLUIDS_AND_MAGIC -> ICON_FLUIDS;
            default -> ICON_ALL;
        };
        graphics.drawCenteredString(Minecraft.getInstance().font, icon, x + 8, y + 4, ICON_COLOR);
    }

    @Override protected String getSideButtonTooltip() {
        // SideButton 的 tooltip 接口只收 String，这里靠 zh_cn 语言表就地解析：客户端渲染
        // 路径会正常走 LanguageMap，不是服务端预渲染，中文能出来。
        return grid.getView() instanceof UnifiedGridView view
                ? view.filterLabel().getString() + "\n"
                + Component.translatable("rs_integration.unified_grid.container_hint").getString()
                : "";
    }
}
