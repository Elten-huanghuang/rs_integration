package com.huanghuang.rsintegration.client.config;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ConfigEditorModel;
import com.huanghuang.rsintegration.config.ConfigEditorModel.ConfigFile;
import com.huanghuang.rsintegration.config.ConfigEditorModel.Option;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.huanghuang.rsintegration.client.config.ConfigTheme.ACCENT;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.BORDER;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.EDITOR;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.EDITOR_BORDER;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.ERROR;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.MUTED;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.PINK;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.SURFACE;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.SURFACE_HOVER;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.TEXT;
import static com.huanghuang.rsintegration.client.config.ConfigTheme.WHITE;

public final class RSIntegrationConfigScreen extends Screen {
    private final Screen parent;
    private final ConfigEditorModel model = new ConfigEditorModel(ConfigEditorModel.defaultFiles());
    private ConfigFile selectedFile = model.files().get(0);
    private String selectedCategory = "";
    private String query = "";
    private Component status = Component.empty();
    private boolean failed;
    private boolean saving;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int sidebarWidth;
    private ConfigList configList;
    private CategoryList categoryList;
    private EditBox search;
    private ConfigTheme.ConfigButton apply;
    private ConfigTheme.ConfigButton defaults;
    private final List<ConfigTheme.ConfigButton> tabs = new ArrayList<>();

    public RSIntegrationConfigScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
    }

    static Component tr(String key, Object... args) {
        String translation = switch (key) {
            case "search" -> "config.rs_integration.search";
            case "enabled" -> "options.on";
            case "disabled" -> "options.off";
            default -> "config.rs_integration." + key;
        };
        return Component.translatable(translation, args);
    }

    private static Component name(Option entry) {
        return Component.literal(entry.displayName());
    }

    private Component categoryName(String category) {
        return Component.literal(selectedFile.categoryName(category));
    }

    private static String description(Option entry) {
        return entry.description();
    }

    private static String summary(Option entry) {
        String description = description(entry).replace('\n', ' ').strip();
        return description.isEmpty() ? "路径：" + entry.path() : description;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(580, width - 20);
        panelHeight = Math.min(386, height - 20);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        sidebarWidth = panelWidth < 420 ? 76 : 104;
        tabs.clear();
        int tabWidth = (panelWidth - 24) / 4;
        for (int index = 0; index < model.files().size(); index++) {
            ConfigFile file = model.files().get(index);
            ConfigTheme.ConfigButton tab = addRenderableWidget(new ConfigTheme.ConfigButton(
                    left + 12 + index * tabWidth, top + 31, tabWidth - 3, 20, tr("file." + file.id()),
                    button -> selectFile(file)));
            tab.setTooltip(Tooltip.create(Component.literal(file.fileName())));
            tabs.add(tab);
        }
        search = addRenderableWidget(new EditBox(font, left + sidebarWidth + 14, top + 61,
                panelWidth - sidebarWidth - 29, 18, tr("search")));
        search.setTextColor(TEXT);
        search.setTextColorUneditable(MUTED);
        search.setBordered(false);
        search.setMaxLength(128);
        search.setValue(query);
        search.setResponder(value -> { query = value; refreshEntries(); });
        categoryList = addRenderableWidget(new CategoryList());
        configList = addRenderableWidget(new ConfigList());
        defaults = addRenderableWidget(new ConfigTheme.ConfigButton(left + 12, top + panelHeight - 30,
                94, 20, tr("defaults"), button -> {
                    visibleEntries().forEach(Option::resetDefault);
                    refreshEntries();
                    status = Component.empty();
                }));
        apply = addRenderableWidget(new ConfigTheme.ConfigButton(left + panelWidth - 151, top + panelHeight - 30,
                66, 20, tr("apply"), button -> save()));
        addRenderableWidget(new ConfigTheme.ConfigButton(left + panelWidth - 77, top + panelHeight - 30,
                65, 20, Component.translatable("gui.done"), button -> onClose()));
        refreshCategories();
        refreshEntries();
        updateControls();
    }

    private void selectFile(ConfigFile file) {
        if (saving) return;
        selectedFile = file;
        selectedCategory = "";
        status = Component.empty();
        refreshCategories();
        refreshEntries();
    }

    private List<Option> visibleEntries() {
        String filter = query.trim().toLowerCase(Locale.ROOT);
        return model.entries().stream().filter(entry -> entry.file().equals(selectedFile))
                .filter(entry -> selectedCategory.isEmpty() || entry.category().equals(selectedCategory))
                .filter(entry -> filter.isEmpty() || (entry.path() + " " + name(entry).getString() + " "
                        + selectedFile.categoryName(entry.category()) + " " + description(entry))
                        .toLowerCase(Locale.ROOT).contains(filter)).toList();
    }

    private void refreshCategories() {
        categoryList.clear();
        categoryList.append("");
        model.entries().stream().filter(entry -> entry.file().equals(selectedFile))
                .map(Option::category).distinct().forEach(categoryList::append);
    }

    private void refreshEntries() {
        if (configList == null) return;
        configList.clear();
        visibleEntries().forEach(configList::append);
        configList.setScrollAmount(0);
        updateControls();
    }

    private void updateControls() {
        if (apply == null) return;
        apply.active = !saving && model.isDirty() && model.isValid();
        defaults.active = !saving && ConfigScreenSave.isEditable(selectedFile) && !visibleEntries().isEmpty();
        tabs.forEach(tab -> tab.active = !saving);
        search.setEditable(!saving);
    }

    private void save() {
        if (saving || !model.isValid() || !model.isDirty()) return;
        boolean restart = model.changes().stream().anyMatch(Option::requiresRestart);
        saving = true;
        failed = false;
        status = tr("saving");
        updateControls();
        ConfigScreenSave.save(model, failure -> {
            saving = false;
            failed = failure != null;
            status = tr(failed ? "save_failed" : restart ? "saved_restart" : "saved");
            if (failed) RSIntegrationMod.LOGGER.error("[RSI-Config] 配置界面保存失败", failure);
            refreshEntries();
        });
    }

    @Override
    public void tick() {
        search.tick();
        for (ConfigRow row : configList.children()) row.tick();
        updateControls();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        ConfigTheme.frame(graphics, left, top, panelWidth, panelHeight, WHITE);
        graphics.fill(left + 2, top + 2, left + panelWidth - 2, top + 27, PINK);
        graphics.hLine(left + 2, left + panelWidth - 3, top + 26, ACCENT);
        graphics.drawString(font, title, left + 12, top + 10, TEXT, false);
        graphics.fill(left + 2, top + 57, left + sidebarWidth + 4, top + panelHeight - 61, SURFACE);
        graphics.vLine(left + sidebarWidth + 4, top + 56, top + panelHeight - 62, BORDER);
        graphics.fill(search.getX() - 3, search.getY() - 3, search.getX() + search.getWidth() + 3,
                search.getY() + search.getHeight() + 3, SURFACE);
        int selectedIndex = model.files().indexOf(selectedFile);
        int tabWidth = (panelWidth - 24) / 4;
        super.render(graphics, mouseX, mouseY, partialTick);
        if (search.getValue().isEmpty() && !search.isFocused()) {
            graphics.drawString(font, tr("search"), search.getX() + 4, search.getY() + 5, MUTED, false);
        }
        graphics.hLine(search.getX(), search.getX() + search.getWidth(), search.getY() + search.getHeight(),
                search.isFocused() ? ACCENT : BORDER);
        graphics.hLine(left + 13 + selectedIndex * tabWidth, left + 9 + (selectedIndex + 1) * tabWidth,
                top + 50, ACCENT);
        Component footer = status;
        if (footer.getString().isEmpty()) {
            footer = !ConfigScreenSave.isEditable(selectedFile) ? tr(selectedFile.spec().isLoaded()
                    ? "read_only" : "not_loaded") : !model.isValid() ? tr("invalid") : model.isDirty()
                    ? tr("unsaved", model.changes().size()) : tr("file." + selectedFile.id() + ".description");
        }
        graphics.drawString(font, font.plainSubstrByWidth(footer.getString(), panelWidth - 24),
                left + 12, top + panelHeight - 49, failed || !model.isValid() ? ERROR : MUTED, false);
        configList.drawHoveredTooltip(graphics, mouseX, mouseY);
        if (configList.children().isEmpty()) {
            Component empty = tr("empty");
            graphics.drawString(font, empty.getVisualOrderText(),
                    left + sidebarWidth + (panelWidth - sidebarWidth - font.width(empty)) / 2,
                    top + 110, MUTED, false);
        }
    }

    @Override
    public void onClose() {
        if (saving) return;
        if (!model.isDirty()) {
            minecraft.setScreen(parent);
            return;
        }
        minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard ? parent : this),
                tr("discard.title"), tr("discard.message"), tr("discard.confirm"), Component.translatable("gui.cancel")));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private final class CategoryList extends ObjectSelectionList<CategoryRow> {
        private CategoryList() {
            super(Minecraft.getInstance(), sidebarWidth - 2, RSIntegrationConfigScreen.this.height,
                    top + 61, top + panelHeight - 62, 20);
            setLeftPos(left + 4);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);
        }

        void clear() { clearEntries(); }
        void append(String category) { addEntry(new CategoryRow(category)); }
        @Override public int getRowWidth() { return sidebarWidth - 12; }
        @Override protected int getScrollbarPosition() { return getRight() - 4; }
    }

    private final class CategoryRow extends ObjectSelectionList.Entry<CategoryRow> {
        private final String category;

        private CategoryRow(String category) { this.category = category; }
        @Override public Component getNarration() { return category.isEmpty() ? tr("all") : categoryName(category); }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            if (selectedCategory.equals(category)) {
                graphics.fill(x, y, x + rowWidth, y + rowHeight, PINK);
                graphics.fill(x, y, x + 2, y + rowHeight, ACCENT);
            }
            graphics.drawString(font, font.plainSubstrByWidth(getNarration().getString(), rowWidth - 11), x + 6, y + 5, TEXT, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || saving) return false;
            selectedCategory = category;
            refreshEntries();
            return true;
        }
    }

    private final class ConfigList extends ContainerObjectSelectionList<ConfigRow> {
        private ConfigList() {
            super(Minecraft.getInstance(), panelWidth - sidebarWidth - 16, RSIntegrationConfigScreen.this.height,
                    top + 86, top + panelHeight - 61, 62);
            setLeftPos(left + sidebarWidth + 9);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void clear() { clearEntries(); }
        void append(Option entry) { addEntry(new ConfigRow(entry)); }
        @Override public int getRowWidth() { return getWidth() - 12; }
        @Override protected int getScrollbarPosition() { return getRight() - 5; }

        void drawHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
            if (!isMouseOver(mouseX, mouseY)) return;
            ConfigRow hovered = getHovered();
            if (hovered == null || mouseX >= getRowRight() - 110) return;
            Option entry = hovered.entry;
            Component tooltip = name(entry).copy().append("\n" + entry.path());
            if (!description(entry).isBlank()) tooltip = tooltip.copy().append("\n" + description(entry));
            tooltip = tooltip.copy().append("\n").append(tr("default_value", entry.defaultText()));
            if (!entry.range().isEmpty()) tooltip = tooltip.copy().append("\n").append(tr("range", entry.range()));
            if (entry.requiresRestart()) tooltip = tooltip.copy().append("\n").append(tr("restart"));
            graphics.renderTooltip(font, font.split(tooltip, Math.min(330, width - 30)), mouseX, mouseY);
        }
    }

    private final class ConfigRow extends ContainerObjectSelectionList.Entry<ConfigRow> {
        private final Option entry;
        private final AbstractWidget editor;
        private final ConfigTheme.ConfigButton reset;

        private ConfigRow(Option entry) {
            this.entry = entry;
            if (entry.isBoolean()) {
                editor = new ConfigTheme.ToggleButton(0, 0, 99, 18, booleanLabel(), () -> Boolean.TRUE.equals(entry.parsed()), button -> {
                    entry.setText(String.valueOf(!Boolean.TRUE.equals(entry.parsed())));
                    button.setMessage(booleanLabel());
                    updateControls();
                });
            } else if (entry.isList()) {
                editor = new ConfigTheme.ConfigButton(0, 0, 99, 18, listLabel(),
                        button -> minecraft.setScreen(new ListEditorScreen(entry)));
            } else {
                ClippedEditBox input = new ClippedEditBox(font, 0, 0, 93, 16, name(entry));
                input.setMaxLength(1024);
                input.setBordered(false);
                input.setTextColor(TEXT);
                input.setTextColorUneditable(MUTED);
                input.setValue(entry.text());
                input.setResponder(value -> { entry.setText(value); updateControls(); });
                editor = input;
            }
            reset = new ConfigTheme.ConfigButton(0, 0, 18, 18, Component.literal("↺"), button -> {
                entry.resetDefault();
                if (editor instanceof EditBox input) input.setValue(entry.text());
                else editor.setMessage(entry.isBoolean() ? booleanLabel() : listLabel());
                updateControls();
            });
            reset.setTooltip(Tooltip.create(Component.translatable("controls.reset").append(" · ")
                    .append(tr("default_value", entry.defaultText()))));
        }

        private Component booleanLabel() {
            return tr(Boolean.TRUE.equals(entry.parsed()) ? "enabled" : "disabled");
        }

        private Component listLabel() {
            return tr("edit_list", entry.parsed() instanceof List<?> list ? list.size() : 0);
        }

        private void tick() {
            if (editor instanceof EditBox input) input.tick();
        }

        @Override public List<? extends GuiEventListener> children() { return List.of(editor, reset); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(editor, reset); }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            if (hovered) graphics.fill(x + 1, y + 1, x + rowWidth - 1, y + rowHeight - 1, SURFACE_HOVER);
            graphics.hLine(x, x + rowWidth, y + rowHeight - 1, 0xFFECE7EF);
            int labelColor = !entry.isValid() ? ERROR : entry.isDirty() ? ACCENT : TEXT;
            int textWidth = Math.max(30, rowWidth - 118);
            List<FormattedCharSequence> titleLines = font.split(name(entry), textWidth);
            List<FormattedCharSequence> summaryLines = font.split(Component.literal(summary(entry)), textWidth);
            int titleCount = Math.min(2, titleLines.size());
            for (int line = 0; line < titleCount; line++) {
                graphics.drawString(font, titleLines.get(line), x + 3, y + 4 + line * 10, labelColor, false);
            }
            int summaryY = y + 5 + titleCount * 10;
            int summaryCount = Math.min(2, summaryLines.size());
            for (int line = 0; line < summaryCount; line++) {
                graphics.drawString(font, summaryLines.get(line), x + 3, summaryY + line * 10, MUTED, false);
            }
            boolean editable = !saving && ConfigScreenSave.isEditable(entry.file());
            editor.active = editable;
            reset.active = editable;
            int controlY = y + (rowHeight - 18) / 2;
            reset.setX(x + rowWidth - 19);
            reset.setY(controlY);
            int editorX = x + rowWidth - 101;
            editor.setX(editorX + (editor instanceof EditBox ? 3 : 0));
            editor.setY(controlY + (editor instanceof EditBox ? 2 : 0));
            if (editor instanceof EditBox input) {
                input.setEditable(editable);
                ConfigTheme.frame(graphics, editorX, controlY, 99, 18, 0xFFF8F6FA);
                if (!entry.isValid()) graphics.hLine(editorX + 1, editorX + 97, controlY + 17, ERROR);
            }
            if (editor instanceof ClippedEditBox input) {
                graphics.enableScissor(editorX + 2, controlY + 1, editorX + 97, controlY + 17);
                input.render(graphics, mouseX, mouseY, partialTick);
                graphics.disableScissor();
            } else {
                editor.render(graphics, mouseX, mouseY, partialTick);
            }
            reset.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private final class ListEditorScreen extends Screen {
        private final Option entry;
        private MultiLineEditBox input;
        private ConfigTheme.ConfigButton done;
        private String draft;

        private ListEditorScreen(Option entry) {
            super(name(entry));
            this.entry = entry;
            draft = entry.parsed() instanceof List<?> list ? String.join("\n", list.stream().map(String::valueOf).toList()) : "";
        }

        @Override
        protected void init() {
            int boxWidth = Math.min(480, width - 30);
            int boxHeight = Math.min(290, height - 60);
            int x = (width - boxWidth) / 2;
            int y = (height - boxHeight) / 2;
            input = addRenderableWidget(new StyledMultiLineEditBox(font, x + 12, y + 46, boxWidth - 24,
                    boxHeight - 95, tr("list_hint"), title));
            input.setCharacterLimit(262144);
            input.setValue(draft);
            input.setValueListener(value -> { draft = value; validateDraft(); });
            done = addRenderableWidget(new ConfigTheme.ConfigButton(x + boxWidth - 150, y + boxHeight - 30,
                    64, 20, Component.translatable("gui.done"), button -> {
                        entry.setText(ConfigEditorModel.listInputToJson(draft));
                        minecraft.setScreen(RSIntegrationConfigScreen.this);
                    }));
            addRenderableWidget(new ConfigTheme.ConfigButton(x + boxWidth - 78, y + boxHeight - 30,
                    64, 20, Component.translatable("gui.cancel"), button -> onClose()));
            validateDraft();
            setInitialFocus(input);
        }

        private void validateDraft() {
            if (done != null) done.active = entry.accepts(ConfigEditorModel.listInputToJson(draft));
        }

        @Override public void tick() { input.tick(); }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics);
            int boxWidth = Math.min(480, width - 30);
            int boxHeight = Math.min(290, height - 60);
            int x = (width - boxWidth) / 2;
            int y = (height - boxHeight) / 2;
            ConfigTheme.frame(graphics, x, y, boxWidth, boxHeight, WHITE);
            graphics.fill(x + 2, y + 2, x + boxWidth - 2, y + 26, PINK);
            graphics.hLine(x + 2, x + boxWidth - 3, y + 26, ACCENT);
            graphics.drawString(font, font.plainSubstrByWidth(title.getString(), boxWidth - 24), x + 12, y + 10, TEXT, false);
            graphics.drawString(font, tr("list_editor_hint"), x + 12, y + 32, MUTED, false);
            super.render(graphics, mouseX, mouseY, partialTick);
            if (!done.active) graphics.drawString(font, tr("invalid_list"), x + 12, y + boxHeight - 46, ERROR, false);
        }

        @Override public void onClose() { minecraft.setScreen(RSIntegrationConfigScreen.this); }
        @Override public boolean isPauseScreen() { return false; }
    }

    private static final class StyledMultiLineEditBox extends MultiLineEditBox {
        private StyledMultiLineEditBox(Font font, int x, int y, int width, int height,
                                       Component placeholder, Component message) {
            super(font, x, y, width, height, placeholder, message);
        }

        @Override
        protected void renderBackground(GuiGraphics graphics) {
            int border = isFocused() ? ACCENT : EDITOR_BORDER;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, EDITOR);
        }
    }

    private static final class ClippedEditBox extends EditBox {
        private ClippedEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }
    }
}
