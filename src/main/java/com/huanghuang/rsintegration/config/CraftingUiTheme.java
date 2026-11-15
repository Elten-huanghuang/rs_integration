package com.huanghuang.rsintegration.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Objects;

/** 两类界面独立选择本地主题；预设不改写玩家的自定义色值。 */
public enum CraftingUiTheme {
    CUSTOM(null),
    FOREST(new Palette(
            0xFF111E17, 0xFF17251E, 0xFF1B2D22, 0xFF294332, 0xFF203D2B, 0xFF314939,
            0xFF8BE3AC, 0xFFE5EEE8, 0xFFA2B8AC, 0xFF6D8375, 0xFF245E42,
            0xFF86D6A2, 0xFFE9C875, 0xFFEB9B92, 0xFF91BCD8, 0xFFC3A0DC)),
    OCEAN(new Palette(
            0xFFDCE9F0, 0xFFECF3F7, 0xFFF7FCFF, 0xFFD6E5F0, 0xFFC8DBE8, 0xFF7694AA,
            0xFF285571, 0xFF243747, 0xFF526A7C, 0xFF8A9AA7, 0xFF285571,
            0xFF286043, 0xFF80551D, 0xFFA34242, 0xFF3E6290, 0xFF855880)),
    AMETHYST(new Palette(
            0xFFE9E0EE, 0xFFF5EFF8, 0xFFFDF9FF, 0xFFE8DBF0, 0xFFDDCCE7, 0xFFA48BAD,
            0xFF67416F, 0xFF392B41, 0xFF715E7A, 0xFFA38FAA, 0xFF67416F,
            0xFF386447, 0xFF80531D, 0xFFA43D5C, 0xFF4A6388, 0xFF8B4C8B)),
    WARM_PAPER(new Palette(
            0xFFE7DDCB, 0xFFF3EBDD, 0xFFFFF9EE, 0xFFECE0CB, 0xFFE4D1AD, 0xFFA18B6C,
            0xFF795330, 0xFF3D3328, 0xFF6F604E, 0xFF998A76, 0xFF795330,
            0xFF356947, 0xFF80591C, 0xFFA3443E, 0xFF3D6589, 0xFF79518F)),
    VANILLA(new Palette(
            0xFFA8A8A8, 0xFFC6C6C6, 0xFFDCDCDC, 0xFFCDCDCD, 0xFFB9C4AB, 0xFF373737,
            0xFF303B2B, 0xFF202020, 0xFF454545, 0xFF7A7A7A, 0xFF535353,
            0xFF255A2E, 0xFF674400, 0xFF8C2525, 0xFF2E4A6D, 0xFF65436D), true),
    BRASS(new Palette(
            0xFF21180F, 0xFF332617, 0xFF42301B, 0xFF5A4325, 0xFF5A4221, 0xFF927344,
            0xFFE3B65E, 0xFFF5E8CD, 0xFFC7AD7C, 0xFF8C7654, 0xFF715020,
            0xFFD8D3A1, 0xFFEFC372, 0xFFE6A087, 0xFFB6C2D3, 0xFFC4A7B9)),
    ARCANE(new Palette(
            0xFF1A111E, 0xFF2B192B, 0xFF3E2438, 0xFF573149, 0xFF523049, 0xFF8B697C,
            0xFFE6CCA1, 0xFFF5EDE5, 0xFFCEB3BD, 0xFF9C7D8E, 0xFF6A3E59,
            0xFFB9C7F3, 0xFFF2BE7D, 0xFFF0A6B9, 0xFFD5AADF, 0xFFD6B6C5)),
    QUARTZ(new Palette(
            0xFFE2E3EB, 0xFFF0F0F4, 0xFFDADBE4, 0xFFE2E2EB, 0xFFCECEDF, 0xFF8B8FA4,
            0xFF54536E, 0xFF30313F, 0xFF5C5F72, 0xFF9497A8, 0xFFD6D6E0,
            0xFF355F43, 0xFF77501A, 0xFF9C3E42, 0xFF415E87, 0xFF7E5095)),
    ROSE(new Palette(
            0xFFF0DFE2, 0xFFFAEFF0, 0xFFFFF9F9, 0xFFEED9DF, 0xFFE6C8D3, 0xFFB68B9A,
            0xFF7F3E56, 0xFF462C36, 0xFF7D606C, 0xFFAA8F9B, 0xFF7F3E56,
            0xFF3F694F, 0xFF80551D, 0xFFA03E49, 0xFF4B6787, 0xFF875B94)),
    EMBER(new Palette(
            0xFF1A1716, 0xFF544A47, 0xFF605552, 0xFF5F534F, 0xFF584C46, 0xFF837874,
            0xFFF2C796, 0xFFFAF2E9, 0xFFBFB5B1, 0xFF948884, 0xFF544A47,
            0xFFE5CEAA, 0xFFE0AF7B, 0xFFDD9A85, 0xFFD68A59, 0xFFBCABA5)),
    GRAPHITE(new Palette(
            0xFF171A1D, 0xFF22272B, 0xFF2B3237, 0xFF3A444B, 0xFF384950, 0xFF57636B,
            0xFF9AD4D3, 0xFFEDF2F3, 0xFFB1BEC4, 0xFF7F909B, 0xFF31585B,
            0xFFA6D2A0, 0xFFE5C886, 0xFFF0A4A4, 0xFFA9C6E7, 0xFFC3ADDE)),
    FROSTED_GLASS(new Palette(
            0x10DFE9EF, 0x9CF1F8FB, 0xB0F9FDFF, 0xC4E4F2F8, 0xD0C9E5EE, 0x48778D97,
            0xFF244957, 0xFF192F3B, 0xFF395B68, 0xFF718C98, 0xE8325869,
            0xFF21533F, 0xFF70501C, 0xFF8E3345, 0xFF365582, 0xFF705184)),
    BLACK_GLASS(new Palette(
            0x24090D12, 0x900D141C, 0xA6151F2A, 0xBA2B3A48, 0xC8405360, 0x38556777,
            0xFFBDEAF6, 0xFFF2F8FC, 0xFFBDCDD8, 0xFF8195A6, 0xD8294B59,
            0xFFACE3BE, 0xFFF0D397, 0xFFFFB3BD, 0xFFB8D6FA, 0xFFD9BFF5)),
    DEEP_SPACE(new Palette(
            0xFF080D1C, 0xFF101B32, 0xFF182640, 0xFF293657, 0xFF302F54, 0xFF4E5E85,
            0xFF9BCAF3, 0xFFEAF0FF, 0xFFA6B6D6, 0xFF697C9F, 0xFF344B73,
            0xFF9FDCDA, 0xFFEACF93, 0xFFF2A8BE, 0xFFC7B1F4, 0xFFD7B5E6));

    private static ForgeConfigSpec.EnumValue<CraftingUiTheme> config;
    private static ForgeConfigSpec.EnumValue<CraftingUiTheme> treeConfig;
    private static ForgeConfigSpec.IntValue themeVersion;
    public enum Scope { TREE, MONITOR }
    private final int[] treeColors;
    private final int[] craftingColors;

    CraftingUiTheme(Palette palette) {
        this(palette, false);
    }

    CraftingUiTheme(Palette palette, boolean vanillaEdges) {
        // 预先展开完整色板，绘制每个节点和材料格时只做数组查询。
        treeColors = new int[PlanUiConfig.Color.values().length];
        craftingColors = new int[CraftingUiColors.values().length];
        if (palette != null) {
            for (PlanUiConfig.Color color : PlanUiConfig.Color.values()) {
                treeColors[color.ordinal()] = palette.treeColor(color);
            }
            for (CraftingUiColors color : CraftingUiColors.values()) {
                craftingColors[color.ordinal()] = palette.craftingColor(color, vanillaEdges);
            }
        }
        // 玻璃的线条、状态和层叠底板保持透光；文字与主按钮仍有足够对比度。
        if (name().endsWith("GLASS")) {
            treeColors[PlanUiConfig.Color.SHADOW.ordinal()] = 0x08000000;
            treeColors[PlanUiConfig.Color.GRID.ordinal()] = withAlpha(palette.border, 0x24);
            treeColors[PlanUiConfig.Color.NODE_BORDER.ordinal()] = palette.border;
            craftingColors[CraftingUiColors.PANEL_SHADOW.ordinal()] = 0x08000000;
            craftingColors[CraftingUiColors.GRID_LINE.ordinal()] = withAlpha(palette.border, 0x24);
            craftingColors[CraftingUiColors.HIGHLIGHT.ordinal()] = palette.border;
        }
        // 石英终端使用亮白机壳、灰紫槽位和深色字，按钮随面板提亮。
        if (name().equals("QUARTZ")) {
            treeColors[PlanUiConfig.Color.FRAME.ordinal()] = 0xFFF6F6F8;
            treeColors[PlanUiConfig.Color.GRID.ordinal()] = 0xFFCFD0DA;
            treeColors[PlanUiConfig.Color.NODE_BORDER.ordinal()] = palette.border;
            treeColors[PlanUiConfig.Color.CONNECTOR.ordinal()] = 0xFF7C7F98;
            craftingColors[CraftingUiColors.BUTTON_TEXT.ordinal()] = palette.text;
            craftingColors[CraftingUiColors.HIGHLIGHT.ordinal()] = 0xFFFFFFFF;
            craftingColors[CraftingUiColors.PANEL_TOP_EDGE.ordinal()] = 0xFFFFFFFF;
            craftingColors[CraftingUiColors.PANEL_LEFT_EDGE.ordinal()] = 0xFFFFFFFF;
            craftingColors[CraftingUiColors.EDGE_SHADOW.ordinal()] = 0xFF9294A8;
            craftingColors[CraftingUiColors.GRID_LINE.ordinal()] = 0xFFCACBD7;
            craftingColors[CraftingUiColors.INPUT.ordinal()] = 0xFFD0D1DF;
            craftingColors[CraftingUiColors.PANEL_SHADOW.ordinal()] = 0x18000000;
        }
        // 余烬手册的暖灰石板与火线分开取色，降低红棕和荧橙的饱和度。
        if (name().equals("EMBER")) {
            treeColors[PlanUiConfig.Color.FRAME.ordinal()] = 0xFF6B615E;
            treeColors[PlanUiConfig.Color.GRID.ordinal()] = 0xFF24201F;
            treeColors[PlanUiConfig.Color.CONNECTOR.ordinal()] = palette.reference;
            treeColors[PlanUiConfig.Color.NODE_BORDER.ordinal()] = palette.border;
            treeColors[PlanUiConfig.Color.ACTIVE_BORDER.ordinal()] = 0xFFDFAB7B;
            craftingColors[CraftingUiColors.HIGHLIGHT.ordinal()] = 0xFF736865;
            craftingColors[CraftingUiColors.PANEL_TOP_EDGE.ordinal()] = palette.border;
            craftingColors[CraftingUiColors.PANEL_LEFT_EDGE.ordinal()] = palette.border;
            craftingColors[CraftingUiColors.GRID_LINE.ordinal()] = 0xFF282321;
        }
    }

    static void define(ForgeConfigSpec.Builder builder) {
        config = builder.comment("监控器主题",
                        "只切换合成监控器和计划列表，无需重启；递归树使用独立的 treeTheme。",
                        "CUSTOM：使用下方颜色和 plan_ui.toml 的自定义配色（保留已有设置）。",
                        "FOREST：森林绿；OCEAN：冰川蓝；AMETHYST：丁香紫；WARM_PAPER：暖纸色。",
                        "VANILLA：原版灰；BRASS：黄铜工坊；ARCANE：绯月秘法；QUARTZ：石英终端。",
                        "ROSE：樱霞；EMBER：余烬；GRAPHITE：石墨。",
                        "FROSTED_GLASS：高透磨砂玻璃；BLACK_GLASS：高透黑玻璃。",
                        "DEEP_SPACE：宇宙深空。",
                        "预设模式下暂不使用自定义颜色；切回 CUSTOM 即可恢复，布局不受影响。")
                .defineEnum("theme", CUSTOM);
        treeConfig = builder.comment("递归树主题", "只影响递归树、材料表及树内控件，不改变监控器配色。")
                .defineEnum("treeTheme", CUSTOM);
        themeVersion = builder.comment("独立主题配置迁移版本；由程序维护。")
                .defineInRange("themeSchema", 0, 0, 1);
    }

    public static CraftingUiTheme current() {
        return current(Scope.MONITOR);
    }

    public static CraftingUiTheme current(Scope scope) {
        if (config == null || !RSIntegrationConfig.CLIENT_SPEC.isLoaded()) return CUSTOM;
        return scope == Scope.TREE ? treeConfig.get() : config.get();
    }

    /** 首次升级沿用旧的共用主题，之后不再联动；返回是否需要保存配置。 */
    public static boolean migrateIndependentThemes() {
        if (config == null || !RSIntegrationConfig.CLIENT_SPEC.isLoaded() || themeVersion.get() >= 1) return false;
        if (treeConfig.get() == CUSTOM) treeConfig.set(config.get());
        themeVersion.set(1);
        return true;
    }

    public boolean isGlass() { return this == FROSTED_GLASS || this == BLACK_GLASS; }

    public int swatch() { return this == CUSTOM ? 0xFFE7C88E : treeColors[PlanUiConfig.Color.SURFACE.ordinal()] | 0xFF000000; }
    public int swatchAccent() { return this == CUSTOM ? 0xFF8861BA : treeColors[PlanUiConfig.Color.ACCENT.ordinal()]; }

    public CraftingUiTheme next() {
        CraftingUiTheme[] themes = values();
        return themes[(ordinal() + 1) % themes.length];
    }

    public CraftingUiTheme previous() {
        CraftingUiTheme[] themes = values();
        return themes[(ordinal() + themes.length - 1) % themes.length];
    }

    /** 界面快捷切换立即保存；失败时恢复原主题，不修改任何自定义色值。 */
    public static boolean select(CraftingUiTheme theme) {
        return select(Scope.MONITOR, theme);
    }

    public static boolean select(Scope scope, CraftingUiTheme theme) {
        Objects.requireNonNull(scope, "界面不能为空");
        Objects.requireNonNull(theme, "主题不能为空");
        if (config == null || !RSIntegrationConfig.CLIENT_SPEC.isLoaded()) return false;
        CraftingUiTheme previous = current(scope);
        if (previous == theme && themeVersion.get() >= 1) return true;
        CraftingUiTheme oldMonitor = config.get(), oldTree = treeConfig.get();
        int oldVersion = themeVersion.get();
        migrateIndependentThemes();
        (scope == Scope.TREE ? treeConfig : config).set(theme);
        try {
            RSIntegrationConfig.saveClientConfig();
        } catch (RuntimeException failure) {
            config.set(oldMonitor);
            treeConfig.set(oldTree);
            themeVersion.set(oldVersion);
            throw failure;
        }
        return true;
    }

    int treeColor(PlanUiConfig.Color color) { return treeColors[color.ordinal()]; }
    int craftingColor(CraftingUiColors color) { return craftingColors[color.ordinal()]; }

    private record Palette(int background, int panel, int surface, int hover, int selected, int border,
                           int accent, int text, int muted, int disabled, int primary,
                           int success, int warning, int danger, int reference, int stopping) {
        int treeColor(PlanUiConfig.Color color) {
            return switch (color) {
                case BACKGROUND, FRAME -> background;
                case PANEL, HEADER -> panel;
                case SURFACE, ROOT, STEP, BUTTON -> surface;
                case LEAF -> blend(background, surface, 75);
                case HOVER -> hover;
                case SELECTED -> selected;
                case BORDER -> border;
                case ACCENT, PRIMARY_BORDER -> accent;
                case TEXT -> text;
                case MUTED -> muted;
                case DISABLED -> disabled;
                case READY -> success;
                case WARNING -> warning;
                case ERROR -> danger;
                case ERROR_SURFACE -> blend(surface, danger, 18);
                case CONNECTOR -> blend(accent, success, 30);
                case CONNECTOR_PENDING -> blend(background, muted, 55);
                case REFERENCE -> reference;
                case REFERENCE_OVERLAY -> withAlpha(background, 0x33);
                case SHADOW -> 0x50000000;
                case TEXT_BACKDROP -> withAlpha(background, 0xE0);
                case HOVER_OVERLAY -> withAlpha(accent, 0x30);
                case NODE_BORDER -> blend(border, accent, 30);
                case ACTIVE_BORDER -> blend(border, accent, 60);
                case WARNING_SURFACE -> blend(surface, warning, 16);
                case WARNING_BORDER -> blend(border, warning, 50);
                case PRIMARY -> blend(surface, accent, 20);
                case GRID -> blend(background, border, 35);
            };
        }

        int craftingColor(CraftingUiColors color, boolean vanillaEdges) {
            return switch (color) {
                case PANEL -> panel;
                case CANVAS, MATERIAL_STORED -> background;
                case BORDER, SCROLLBAR_DISABLED_BORDER -> border;
                case TEXT -> text;
                case MUTED, HINT -> muted;
                case ACCENT -> accent;
                case SUCCESS -> success;
                case WARNING -> warning;
                case DANGER -> danger;
                case HIGHLIGHT, PANEL_TOP_EDGE, PANEL_LEFT_EDGE -> vanillaEdges
                        ? 0xFFFFFFFF : blend(surface, accent, 18);
                case GRID_LINE -> blend(background, border, 50);
                case PANEL_SHADOW -> 0x50000000;
                case EDGE_SHADOW -> background;
                case ICON_PAPER, MONITOR_ICON_PAPER, ROW, INPUT -> surface;
                case BUTTON -> primary;
                case BUTTON_HOVER, BUTTON_PRIMARY_HOVER -> blend(primary, 0xFFFFFFFF, 12);
                case BUTTON_DISABLED -> blend(panel, border, 35);
                case BUTTON_TEXT -> 0xFFF7F7FA;
                case SIDE_BUTTON_HOVER, ROW_HOVER -> hover;
                case SELECTED_ROW -> selected;
                case SCROLLBAR -> blend(border, accent, 35);
                case SCROLLBAR_HOVER -> blend(border, accent, 65);
                case SCROLLBAR_DISABLED -> blend(panel, border, 35);
                case SCROLLBAR_GRIP -> text;
                case SCROLLBAR_DISABLED_GRIP -> disabled;
                case MATERIAL_RUNNING -> blend(background, reference, 30);
                case MATERIAL_SCHEDULED -> blend(background, warning, 30);
                case MATERIAL_BLOCKED -> blend(background, danger, 30);
                case MATERIAL_STOPPING -> blend(background, stopping, 30);
                case MATERIAL_COMPLETED -> blend(background, success, 30);
            };
        }
    }

    private static int withAlpha(int color, int alpha) { return color & 0x00FFFFFF | alpha << 24; }

    private static int blend(int base, int tint, int percent) {
        int inverse = 100 - percent;
        int red = ((base >>> 16 & 255) * inverse + (tint >>> 16 & 255) * percent) / 100;
        int green = ((base >>> 8 & 255) * inverse + (tint >>> 8 & 255) * percent) / 100;
        int blue = ((base & 255) * inverse + (tint & 255) * percent) / 100;
        int alpha = ((base >>> 24) * inverse + (tint >>> 24) * percent) / 100;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
