package com.huanghuang.rsintegration.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Objects;

/** 合成界面共用的客户端配色；不加载客户端渲染类，服务端也能安全注册配置。 */
public enum CraftingUiColors {
    PANEL("panel", "#D0CFD6", "面板背景"),
    CANVAS("canvas", "#A1A5BB", "内容区域背景"),
    BORDER("border", "#666B82", "边框"),
    TEXT("text", "#292832", "正文文字"),
    MUTED("muted", "#464550", "次要文字"),
    HINT("hint", "#83869B", "提示文字"),
    ACCENT("accent", "#706E88", "强调色与主按钮"),
    SUCCESS("success", "#29795D", "成功文字"),
    WARNING("warning", "#9A6725", "警告文字"),
    DANGER("danger", "#A44D53", "错误与取消文字"),
    HIGHLIGHT("highlight", "#F0F0F5", "高亮边缘"),
    GRID_LINE("gridLine", "#CFD0DC", "网格分隔线"),
    PANEL_SHADOW("panelShadow", "#50000000", "面板投影"),
    PANEL_TOP_EDGE("panelTopEdge", "#F1F1F5", "面板顶部亮边"),
    PANEL_LEFT_EDGE("panelLeftEdge", "#E5E6ED", "面板左侧亮边"),
    EDGE_SHADOW("edgeShadow", "#9294A5", "按钮底部与右侧暗边"),
    ICON_PAPER("iconPaper", "#F6FAFC", "计划图标底色"),
    MONITOR_ICON_PAPER("monitorIconPaper", "#ECEAF1", "监控器计划图标底色"),
    BUTTON("button", "#A6ABC0", "普通按钮背景"),
    BUTTON_HOVER("buttonHover", "#B8BDCF", "普通按钮悬停背景"),
    BUTTON_PRIMARY_HOVER("buttonPrimaryHover", "#777E9C", "主按钮悬停背景"),
    BUTTON_DISABLED("buttonDisabled", "#B9BCCB", "禁用按钮背景"),
    BUTTON_TEXT("buttonText", "#F7F7FA", "按钮文字"),
    SIDE_BUTTON_HOVER("sideButtonHover", "#E4E2EB", "侧栏按钮悬停背景"),
    ROW("row", "#FFFFFF", "列表卡片与输入框背景"),
    ROW_HOVER("rowHover", "#E6F3F8", "计划卡片悬停背景"),
    SELECTED_ROW("selectedRow", "#B8DCF2", "监控器选中任务背景"),
    INPUT("input", "#DFE1EA", "监控器搜索框背景"),
    SCROLLBAR("scrollbar", "#9CA1BA", "滚动条滑块"),
    SCROLLBAR_HOVER("scrollbarHover", "#BBC0D5", "滚动条滑块悬停"),
    SCROLLBAR_DISABLED("scrollbarDisabled", "#B3B5C4", "无滚动内容时的滑块"),
    SCROLLBAR_DISABLED_BORDER("scrollbarDisabledBorder", "#858798", "禁用滑块边框"),
    SCROLLBAR_GRIP("scrollbarGrip", "#5E6279", "滑块握柄"),
    SCROLLBAR_DISABLED_GRIP("scrollbarDisabledGrip", "#8D90A2", "禁用滑块握柄"),
    MATERIAL_STORED("materialStored", "#A1A5BB", "材料状态：已有库存"),
    MATERIAL_RUNNING("materialRunning", "#88B0AD", "材料状态：正在合成"),
    MATERIAL_SCHEDULED("materialScheduled", "#C3B887", "材料状态：等待合成"),
    MATERIAL_BLOCKED("materialBlocked", "#C6929B", "材料状态：缺料或阻塞"),
    MATERIAL_STOPPING("materialStopping", "#B3A0C4", "材料状态：正在停止"),
    MATERIAL_COMPLETED("materialCompleted", "#A4B89A", "材料状态：已完成");

    private final String key;
    private final String defaultValue;
    private final String description;
    private final int defaultArgb;
    private ForgeConfigSpec.ConfigValue<String> config;
    private volatile Resolved resolved;

    CraftingUiColors(String key, String defaultValue, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.description = description;
        this.defaultArgb = parse(defaultValue);
        this.resolved = new Resolved(defaultValue, defaultArgb);
    }

    static void define(ForgeConfigSpec.Builder builder) {
        builder.comment("合成界面配色",
                        "计划列表、计划材料格、合成监控器和共用控件的本地配色。",
                        "使用引号包围的 #RRGGBB 或 #AARRGGBB；AA 为透明度，FF 不透明，00 全透明。",
                        "配置重新加载后生效；可在游戏内客户端配置界面修改并应用。")
                .push("craftingUi");
        for (CraftingUiColors color : values()) {
            color.config = builder.comment(color.description,
                            "格式：#RRGGBB 或 #AARRGGBB。默认：" + color.defaultValue)
                    .define(color.key, color.defaultValue, CraftingUiColors::isValid);
        }
        builder.pop();
    }

    public int argb() {
        if (config == null || !RSIntegrationConfig.CLIENT_SPEC.isLoaded()) return defaultArgb;
        String value = config.get();
        Resolved current = resolved;
        if (Objects.equals(current.value(), value)) return current.argb();
        int argb;
        try {
            argb = parse(value);
        } catch (IllegalArgumentException invalid) {
            argb = defaultArgb;
        }
        // 仅在配置值改变时解析，逐格绘制不重复解析字符串。
        resolved = new Resolved(value, argb);
        return argb;
    }

    static boolean isValid(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static int parse(String value) {
        if (value == null) throw new IllegalArgumentException("颜色不能为空");
        String text = value.trim();
        if (!text.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            throw new IllegalArgumentException("颜色必须使用 #RRGGBB 或 #AARRGGBB");
        }
        int color = (int) Long.parseLong(text.substring(1), 16);
        return text.length() == 7 ? 0xFF000000 | color : color;
    }

    private record Resolved(String value, int argb) {}
}
