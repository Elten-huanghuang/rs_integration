package com.huanghuang.rsintegration.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Locale;

/** 递归合成界面的本地主题，不参与服务端配置同步。 */
public final class PlanUiConfig {
    public enum Color {
        BACKGROUND("background", "树画布背景", 0xFF111E17),
        PANEL("panel", "侧栏背景", 0xFF17251E),
        SURFACE("surface", "卡片和输入框底色", 0xFF1B2D22),
        HOVER("hover", "鼠标悬停底色", 0xFF294332),
        SELECTED("selected", "选中卡片底色", 0xFF203D2B),
        BORDER("border", "普通边框", 0xFF314939),
        ACCENT("accent", "强调色和选中边框", 0xFF8BE3AC),
        TEXT("text", "主要文字", 0xFFE5EEE8),
        MUTED("muted", "次要文字", 0xFFA2B8AC),
        DISABLED("disabled", "禁用文字", 0xFF6D8375),
        READY("ready", "材料齐全和可执行", 0xFF86D6A2),
        WARNING("warning", "材料不足和提示", 0xFFE9C875),
        ERROR("error", "缺料和阻断条件", 0xFFEB9B92),
        ERROR_SURFACE("errorSurface", "阻断提示底色", 0xFF412C2D),
        CONNECTOR("connector", "树连线", 0xFF84C99B),
        CONNECTOR_PENDING("connectorPending", "等待重算时的连线", 0xFF567661),
        ROOT("root", "目标节点底色", 0xFF1B2D22),
        STEP("step", "制作步骤节点底色", 0xFF1B2D22),
        LEAF("leaf", "原材料节点底色", 0xFF1A2C21),
        REFERENCE("reference", "共享产出引用标记", 0xFF91BCD8),
        REFERENCE_OVERLAY("referenceOverlay", "引用节点遮罩", 0x33102018),
        SHADOW("shadow", "面板和节点阴影", 0xA0000000),
        TEXT_BACKDROP("textBackdrop", "浮动文字衬底", 0xB0102018),
        HOVER_OVERLAY("hoverOverlay", "物品槽悬停遮罩", 0x30FFFFFF),
        FRAME("frame", "整体窗体背景", 0xFF101B18),
        HEADER("header", "标题区背景", 0xFF18251F),
        BUTTON("button", "普通按钮底色", 0xFF1A2722),
        NODE_BORDER("nodeBorder", "节点边框", 0xFF4C6B55),
        ACTIVE_BORDER("activeBorder", "活动页签边框", 0xFF5B9C75),
        WARNING_SURFACE("warningSurface", "缺料卡片底色", 0xFF302B1E),
        WARNING_BORDER("warningBorder", "缺料卡片边框", 0xFF74643E),
        PRIMARY("primary", "主要操作按钮底色", 0xFF245E42),
        PRIMARY_BORDER("primaryBorder", "主要操作按钮边框", 0xFF8BE6B1),
        GRID("grid", "画布点阵", 0xFF25382B);

        private final String key;
        private final String label;
        private final int defaultColor;
        private ForgeConfigSpec.ConfigValue<String> config;
        private String cachedText;
        private int cachedColor;

        Color(String key, String label, int defaultColor) {
            this.key = key;
            this.label = label;
            this.defaultColor = defaultColor;
        }

        public int value() {
            String text = SPEC.isLoaded() ? config.get() : config.getDefault();
            if (!text.equals(cachedText)) {
                cachedColor = parseColor(text, defaultColor);
                cachedText = text;
            }
            return cachedColor;
        }
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue PANEL_WIDTH;
    public static final ForgeConfigSpec.IntValue LIST_ROW_HEIGHT;
    public static final ForgeConfigSpec.IntValue CONNECTOR_WIDTH;
    public static final ForgeConfigSpec.BooleanValue SHOW_NODE_NAMES;
    public static final ForgeConfigSpec.BooleanValue SHOW_LEAF_NAMES;
    public static final ForgeConfigSpec.IntValue NODE_LABEL_WIDTH;
    public static final ForgeConfigSpec.IntValue MINIMAP_WIDTH;
    public static final ForgeConfigSpec.IntValue MINIMAP_HEIGHT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("递归合成树界面布局；尺寸使用 Minecraft GUI 像素。",
                "仅影响本机显示。修改并保存后可在重新打开界面时查看效果。")
                .push("layout");
        PANEL_WIDTH = builder.comment("右侧详情面板期望宽度；小屏幕自动收缩并为树保留空间。")
                .defineInRange("panelWidth", 270, 180, 480);
        LIST_ROW_HEIGHT = builder.comment("材料候选、材料清单和步骤列表的行高。")
                .defineInRange("listRowHeight", 28, 22, 40);
        CONNECTOR_WIDTH = builder.comment("树连线粗细。")
                .defineInRange("connectorWidth", 2, 1, 4);
        SHOW_NODE_NAMES = builder.comment("在树节点内显示物品名称和数量；关闭可恢复紧凑图标布局。")
                .define("showNodeNames", true);
        SHOW_LEAF_NAMES = builder.comment("在原材料叶子上也显示名称；默认以紧凑物品槽显示，减小大树横向宽度。")
                .define("showLeafNames", false);
        NODE_LABEL_WIDTH = builder.comment("树节点文字区域宽度。")
                .defineInRange("nodeLabelWidth", 78, 60, 180);
        MINIMAP_WIDTH = builder.comment("右下缩略导航期望宽度；小窗口会自动缩小。")
                .defineInRange("minimapWidth", 160, 100, 300);
        MINIMAP_HEIGHT = builder.comment("右下缩略导航期望高度。")
                .defineInRange("minimapHeight", 100, 70, 200);
        builder.pop();
        builder.comment("颜色支持 #RRGGBB（不透明）或 #AARRGGBB（含透明度）。",
                "AA 为透明度：00 完全透明，FF 完全不透明。")
                .push("colors");
        for (Color color : Color.values()) {
            color.config = builder.comment(color.label).define(color.key,
                    String.format(Locale.ROOT, "#%08X", color.defaultColor),
                    value -> value instanceof String text && isColor(text));
        }
        builder.pop();
        SPEC = builder.build();
    }

    private PlanUiConfig() {}

    public static int panelWidth() { return SPEC.isLoaded() ? PANEL_WIDTH.get() : PANEL_WIDTH.getDefault(); }
    public static int rowHeight() { return SPEC.isLoaded() ? LIST_ROW_HEIGHT.get() : LIST_ROW_HEIGHT.getDefault(); }
    public static int connectorWidth() { return SPEC.isLoaded() ? CONNECTOR_WIDTH.get() : CONNECTOR_WIDTH.getDefault(); }
    public static int minimapWidth() { return SPEC.isLoaded() ? MINIMAP_WIDTH.get() : MINIMAP_WIDTH.getDefault(); }
    public static int minimapHeight() { return SPEC.isLoaded() ? MINIMAP_HEIGHT.get() : MINIMAP_HEIGHT.getDefault(); }
    public static boolean showNodeNames() { return SPEC.isLoaded() ? SHOW_NODE_NAMES.get() : SHOW_NODE_NAMES.getDefault(); }
    public static boolean showLeafNames() { return SPEC.isLoaded() ? SHOW_LEAF_NAMES.get() : SHOW_LEAF_NAMES.getDefault(); }
    public static int nodeLabelWidth() { return showNodeNames() ? SPEC.isLoaded() ? NODE_LABEL_WIDTH.get() : NODE_LABEL_WIDTH.getDefault() : 0; }

    public static boolean isColor(String text) {
        return text != null && text.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    public static int parseColor(String text, int fallback) {
        if (!isColor(text)) return fallback;
        long value = Long.parseLong(text.substring(1), 16);
        return (int) (text.length() == 7 ? value | 0xFF000000L : value);
    }
}
