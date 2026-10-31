package com.huanghuang.rsintegration.feature.voidupgrade.client;

import com.huanghuang.rsintegration.util.PinyinUtil;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.feature.voidupgrade.VoidUpgradeConfig;
import com.huanghuang.rsintegration.feature.voidupgrade.VoidUpgradeRule;
import com.huanghuang.rsintegration.feature.voidupgrade.network.SaveVoidUpgradeConfigPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class VoidUpgradeScreen extends Screen {
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 268;
    private static final int ROW_HEIGHT = 20;
    private static final int TEXT = 0x414151;
    private static final int MUTED = 0x777586;
    private static final int ACCENT = 0x786294;
    private static final int CYAN = 0x468581;
    private static final ResourceLocation NEBULA = new ResourceLocation("rs_integration",
            "textures/gui/disk_tooltip/resonance_nebula.png");
    private static final ExecutorService PINYIN_INDEXER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RSI-Void-Upgrade-Pinyin");
        thread.setDaemon(true);
        return thread;
    });

    private final SaveVoidUpgradeConfigPacket.Target target;
    private final int targetSlot;
    private final Screen parent;
    private final List<VoidUpgradeRule> rules;
    private final List<Candidate> catalog = new ArrayList<>();
    private final List<Candidate> visible = new ArrayList<>();
    private List<Component> hoveredTooltip = List.of();
    private VoidUpgradeRule.Type selectedType = VoidUpgradeRule.Type.ITEM;
    private boolean matchNbt;
    private boolean matchDamage;
    private boolean matchEnchantments;
    private EditBox search;
    private Button nameAddButton;
    private final EnumMap<VoidUpgradeRule.Type, VoidButton> typeButtons =
            new EnumMap<>(VoidUpgradeRule.Type.class);
    private int candidateScroll;
    private int ruleScroll;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private boolean configSaved;
    private final AtomicInteger catalogGeneration = new AtomicInteger();

    public VoidUpgradeScreen(SaveVoidUpgradeConfigPacket.Target target, int targetSlot,
                             VoidUpgradeConfig config, Screen parent) {
        super(Component.translatable("screen.rs_integration.void_upgrade"));
        this.target = target;
        this.targetSlot = targetSlot;
        this.parent = parent;
        this.matchNbt = config.matchNbt();
        this.matchDamage = config.matchDamage();
        this.matchEnchantments = config.matchEnchantments();
        this.rules = new ArrayList<>(config.rules());
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_WIDTH, width - 16);
        panelHeight = Math.min(PANEL_HEIGHT, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        typeButtons.clear();

        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, left + 49, top + 43, panelWidth - 68, 10, text("search"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setHint(text("search"));
        search.setMaxLength(128);
        search.setValue(query);
        search.setResponder(unused -> {
            filterCatalog();
            updateTypeControls();
        });
        addRenderableWidget(search);

        int tabY = top + 64;
        int tabWidth = (panelWidth - 24) / 4;
        addTypeButton(VoidUpgradeRule.Type.ITEM, left + 12, tabY, tabWidth);
        addTypeButton(VoidUpgradeRule.Type.TAG, left + 12 + tabWidth, tabY, tabWidth);
        addTypeButton(VoidUpgradeRule.Type.MOD, left + 12 + tabWidth * 2, tabY, tabWidth);
        addTypeButton(VoidUpgradeRule.Type.NAME, left + 12 + tabWidth * 3, tabY,
                panelWidth - 24 - tabWidth * 3);

        nameAddButton = addRenderableWidget(new VoidButton(left + panelWidth - 34, top + 36, 22, 22,
                text("name.add"), button -> addNameRule(), true));
        nameAddButton.setTooltip(Tooltip.create(text("name.add")));

        int optionWidth = (panelWidth - 108) / 3;
        addRenderableWidget(new MatchCheckbox(left + 12, optionWidth, "nbt",
                matchNbt, value -> matchNbt = value));
        addRenderableWidget(new MatchCheckbox(left + 12 + optionWidth, optionWidth, "damage",
                matchDamage, value -> matchDamage = value));
        addRenderableWidget(new MatchCheckbox(left + 12 + optionWidth * 2, optionWidth, "enchantments",
                matchEnchantments, value -> matchEnchantments = value));
        var done = new VoidButton(left + panelWidth - 76, top + panelHeight - 28, 64, 20,
                Component.translatable("gui.done"), button -> save(), false);
        done.selected = true;
        addRenderableWidget(done);
        addRenderableWidget(new VoidButton(left + panelWidth - 30, top + 5, 18, 18,
                Component.translatable("gui.done"), button -> save(), true))
                .setTooltip(Tooltip.create(Component.translatable("gui.done")));
        updateTypeControls();
        rebuildCatalog();
        ruleScroll = clampScroll(ruleScroll, rules.size());
    }

    private void addTypeButton(VoidUpgradeRule.Type type, int x, int y, int width) {
        var tab = new VoidButton(x, y, width, 22, typeLabel(type), button -> {
            selectedType = type;
            candidateScroll = 0;
            updateTypeControls();
            rebuildCatalog();
        }, false);
        tab.setTooltip(Tooltip.create(typeLabel(type)));
        typeButtons.put(type, addRenderableWidget(tab));
    }

    private void rebuildCatalog() {
        catalog.clear();
        switch (selectedType) {
            case ITEM -> buildItemCatalog();
            case TAG -> BuiltInRegistries.ITEM.getTagNames()
                    .map(tag -> tag.location())
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(id -> catalog.add(new Candidate(Component.literal("#" + id),
                            id.toString(), VoidUpgradeRule.tag(id), ItemStack.EMPTY)));
            case MOD -> ModList.get().getMods().stream()
                    .sorted(Comparator.comparing(info -> info.getDisplayName().toLowerCase(Locale.ROOT)))
                    .forEach(info -> catalog.add(new Candidate(Component.literal(info.getDisplayName()),
                            info.getModId(), VoidUpgradeRule.mod(info.getModId()), ItemStack.EMPTY)));
            case NAME, EQUIPMENT -> { }
        }
        filterCatalog();
        indexPinyinAsync();
    }

    private void buildItemCatalog() {
        Set<String> seen = new HashSet<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            for (ItemStack stack : minecraft.player.getInventory().items) addItemCandidate(stack, seen);
            addItemCandidate(minecraft.player.getOffhandItem(), seen);
        }
        BuiltInRegistries.ITEM.stream()
                .filter(item -> item != Items.AIR)
                .sorted(Comparator.comparing(item -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    return id == null ? "" : id.toString();
                }))
                .forEach(item -> addItemCandidate(item.getDefaultInstance(), seen));
    }

    private void addItemCandidate(ItemStack stack, Set<String> seen) {
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || id.getNamespace().equals("rs_integration")
                && id.getPath().equals("rs_void_upgrade")) return;
        String key = id + "|" + (stack.hasTag() ? stack.getTag().toString() : "");
        if (!seen.add(key)) return;
        ItemStack icon = stack.copyWithCount(1);
        catalog.add(new Candidate(icon.getHoverName(), id.toString(),
                VoidUpgradeRule.item(icon), icon));
    }

    private void updateTypeControls() {
        if (search == null || nameAddButton == null) return;
        boolean nameMode = selectedType == VoidUpgradeRule.Type.NAME;
        nameAddButton.visible = nameMode;
        nameAddButton.active = nameMode && !search.getValue().isBlank()
                && rules.size() < VoidUpgradeConfig.MAX_RULES
                && !rules.contains(VoidUpgradeRule.name(search.getValue().strip()));
        search.setWidth(panelWidth - (nameMode ? 96 : 68));
        search.setHint(Component.translatable(nameMode
                ? "screen.rs_integration.void_upgrade.name.hint"
                : "screen.rs_integration.void_upgrade.search"));
        typeButtons.forEach((type, button) -> button.selected = type == selectedType);
    }

    private void addNameRule() {
        if (search == null) return;
        String value = search.getValue().strip();
        if (value.isEmpty()) return;
        addRule(VoidUpgradeRule.name(value));
        search.setValue("");
    }

    private void filterCatalog() {
        visible.clear();
        if (search == null) return;
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        if (selectedType == VoidUpgradeRule.Type.NAME) {
            if (!query.isEmpty()) {
                String value = search.getValue().strip();
                visible.add(new Candidate(Component.literal(value), value,
                        VoidUpgradeRule.name(value), ItemStack.EMPTY));
            }
            candidateScroll = 0;
            return;
        }
        for (Candidate candidate : catalog) {
            if (query.isEmpty() || candidate.matches(query)) {
                visible.add(candidate);
            }
        }
        candidateScroll = clampScroll(candidateScroll, visible.size());
    }

    public Rect2i getGhostIngredientArea() {
        return new Rect2i(left + 12, top + 36, 22, 22);
    }

    public int getPanelLeft() {
        return left;
    }

    public int getPanelTop() {
        return top;
    }

    public int getPanelWidth() {
        return panelWidth;
    }

    public int getPanelHeight() {
        return panelHeight;
    }

    public void acceptGhostIngredient(ItemStack stack) {
        if (selectedType == VoidUpgradeRule.Type.TAG) {
            buildTagCatalogFor(stack);
            return;
        }
        addRule(VoidUpgradeRule.item(stack));
        selectedType = VoidUpgradeRule.Type.ITEM;
        updateTypeControls();
        rebuildCatalog();
    }

    private void buildTagCatalogFor(ItemStack stack) {
        catalog.clear();
        search.setValue("");
        ItemStack icon = stack.copyWithCount(1);
        stack.getTags().map(tag -> tag.location())
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(id -> catalog.add(new Candidate(Component.literal("#" + id),
                        icon.getHoverName().getString(), VoidUpgradeRule.tag(id), icon)));
        candidateScroll = 0;
        filterCatalog();
        indexPinyinAsync();
    }

    private void addRule(VoidUpgradeRule rule) {
        if (rule != null && rules.size() < VoidUpgradeConfig.MAX_RULES && !rules.contains(rule)) {
            rules.add(rule);
            ruleScroll = clampScroll(Integer.MAX_VALUE, rules.size());
            updateTypeControls();
        }
    }

    private void save() {
        onClose();
    }

    private void saveConfigOnce() {
        if (configSaved) return;
        configSaved = true;
        VoidUpgradeConfig config = new VoidUpgradeConfig(matchNbt, matchDamage,
                matchEnchantments, rules);
        NetworkHandler.CHANNEL.sendToServer(target == SaveVoidUpgradeConfigPacket.Target.MENU_SLOT
                ? SaveVoidUpgradeConfigPacket.menuSlot(targetSlot, config)
                : SaveVoidUpgradeConfigPacket.hand(target == SaveVoidUpgradeConfigPacket.Target.MAIN_HAND
                        ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, config));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoveredTooltip = List.of();
        renderBackground(graphics);
        graphics.fill(left + 3, top + 4, left + panelWidth + 3, top + panelHeight + 4, 0x60000000);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF8D859A);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFE4E2E9);
        // 星空只使用图集首帧，作为静态标题底纹。
        graphics.blit(NEBULA, left, top, panelWidth, 28, 0, 0, 512, 256, 2048, 1536);
        graphics.fill(left, top, left + panelWidth, top + 28, 0x80252330);
        graphics.fill(left + 1, top, left + panelWidth - 1, top + 1, 0xFFB6A3CB);
        graphics.fill(left, top + 27, left + panelWidth, top + 28, 0xFFB5A3CA);
        graphics.renderItem(new ItemStack(Items.ENDER_PEARL), left + 12, top + 6);
        graphics.drawString(font, trim(title, panelWidth - 72), left + 35, top + 10, 0xF2EDF8, false);

        Rect2i ghost = getGhostIngredientArea();
        graphics.fill(ghost.getX(), ghost.getY(), ghost.getX() + 22, ghost.getY() + 22, 0xFFCFC9DB);
        graphics.fill(ghost.getX() + 1, ghost.getY() + 1, ghost.getX() + 21, ghost.getY() + 21, 0xFFF2EFF6);
        drawPlus(graphics, ghost.getX() + 7, ghost.getY() + 7, ghostContains(mouseX, mouseY) ? ACCENT : MUTED);
        int searchX = search.getX() - 7;
        int searchY = search.getY() - 7;
        graphics.fill(searchX, searchY, searchX + search.getWidth() + 14, searchY + 22,
                search.isFocused() ? 0xFF786294 : 0xFFA9A2B5);
        graphics.fill(searchX + 1, searchY + 1, searchX + search.getWidth() + 13, searchY + 21, 0xFFF7F6FA);

        int split = left + panelWidth / 2;
        graphics.fill(split, top + 94, split + 1, top + panelHeight - 37, 0xFFC2BDCE);
        graphics.drawString(font, typeLabel(selectedType), left + 12, top + 94, MUTED, false);
        graphics.drawString(font, trim(text("rules_title", rules.size(), VoidUpgradeConfig.MAX_RULES),
                panelWidth / 2 - 20), split + 9, top + 94, MUTED, false);
        int rowsTop = top + 106;
        int rows = visibleRows();
        int rowWidth = panelWidth / 2 - 22;
        for (int row = 0; row < rows; row++) {
            int index = candidateScroll + row;
            if (index >= visible.size()) break;
            renderCandidate(graphics, visible.get(index), left + 12, rowsTop + row * ROW_HEIGHT,
                    rowWidth, mouseX, mouseY);
        }
        for (int row = 0; row < rows; row++) {
            int index = ruleScroll + row;
            if (index >= rules.size()) break;
            renderRule(graphics, rules.get(index), split + 9, rowsTop + row * ROW_HEIGHT,
                    panelWidth - panelWidth / 2 - 21, mouseX, mouseY);
        }
        if (visible.isEmpty()) {
            Component empty = text(selectedType == VoidUpgradeRule.Type.NAME ? "name.empty" : "empty_candidates");
            graphics.drawString(font, trim(empty, rowWidth - 8), left + 16, rowsTop + 12, MUTED, false);
        }
        if (rules.isEmpty()) {
            graphics.drawString(font, trim(text("empty_rules"), rowWidth - 8),
                    split + 13, rowsTop + 12, MUTED, false);
        }
        scrollbar(graphics, split - 6, rowsTop, candidateScroll, visible.size());
        scrollbar(graphics, left + panelWidth - 6, rowsTop, ruleScroll, rules.size());
        int footerY = top + panelHeight - 33;
        graphics.fill(left, footerY, left + panelWidth, top + panelHeight, 0xFFD6D2DF);
        graphics.fill(left + 12, footerY, left + panelWidth - 12, footerY + 1, 0xFFBEB6CA);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hoveredTooltip.isEmpty()) {
            graphics.renderTooltip(font, hoveredTooltip.stream().map(Component::getVisualOrderText).toList(),
                    mouseX, mouseY);
        }
        if (ghostContains(mouseX, mouseY)) {
            graphics.renderTooltip(font, List.of(
                    Component.translatable("screen.rs_integration.void_upgrade.jei_drop"),
                    Component.translatable("screen.rs_integration.void_upgrade.jei_container_warning")
                            .withStyle(ChatFormatting.GOLD)
            ).stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private void renderCandidate(GuiGraphics graphics, Candidate candidate, int x, int y, int width,
                                 int mouseX, int mouseY) {
        boolean hovered = contains(x, y, width, 18, mouseX, mouseY);
        boolean added = rules.contains(candidate.rule);
        graphics.fill(x, y, x + width, y + 18, hovered ? 0xFFD5E5E2 : 0xFFF0EEF4);
        if (hovered) graphics.fill(x, y + 2, x + 2, y + 16, 0xFF468581);
        int textX = x + 4;
        if (!candidate.icon.isEmpty()) {
            graphics.renderItem(candidate.icon, x + 1, y + 1);
            textX = x + 20;
        }
        graphics.drawString(font, trim(candidate.name, width - (textX - x) - 18), textX, y + 5, TEXT, false);
        if (added) {
            int checkX = x + width - 11;
            graphics.fill(checkX, y + 9, checkX + 3, y + 10, 0xFF468581);
            graphics.fill(checkX + 2, y + 8, checkX + 5, y + 9, 0xFF468581);
            graphics.fill(checkX + 4, y + 6, checkX + 6, y + 8, 0xFF468581);
        } else {
            drawPlus(graphics, x + width - 12, y + 5, hovered ? CYAN : 0xA6A2B0);
        }
        if (hovered) hoveredTooltip = candidateTooltip(candidate);
    }

    private void renderRule(GuiGraphics graphics, VoidUpgradeRule rule, int x, int y, int width,
                            int mouseX, int mouseY) {
        boolean hovered = contains(x, y, width, 18, mouseX, mouseY);
        boolean removeHovered = contains(x + width - 17, y, 17, 18, mouseX, mouseY);
        graphics.fill(x, y, x + width, y + 18, hovered ? 0xFFE3DDEC : 0xFFF0EEF4);
        ItemStack icon = iconFor(rule);
        int textX = x + 4;
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x + 1, y + 1);
            textX = x + 20;
        }
        graphics.drawString(font, trim(ruleName(rule), width - (textX - x) - 20), textX, y + 5, TEXT, false);
        if (removeHovered) graphics.fill(x + width - 17, y, x + width, y + 18, 0xFFE6C9CD);
        drawCross(graphics, x + width - 12, y + 6, removeHovered ? 0xA54050 : 0x9D819C);
        if (hovered) {
            List<Component> lines = new ArrayList<>();
            lines.add(ruleName(rule));
            lines.add(typeLabel(rule.type()).copy().withStyle(ChatFormatting.GRAY));
            if (removeHovered) lines.add(text("remove").copy().withStyle(ChatFormatting.RED));
            hoveredTooltip = lines;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && contains(search.getX() - 7, search.getY() - 7,
                search.getWidth() + 14, 22, mouseX, mouseY)) {
            setFocused(search);
            return search.mouseClicked(Math.max(search.getX(), Math.min(mouseX,
                    search.getX() + search.getWidth() - 1)), search.getY() + 2, button);
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int rowsTop = top + 106;
        if (mouseY < rowsTop || mouseY >= rowsTop + visibleRows() * ROW_HEIGHT) return false;
        int row = (int) ((mouseY - rowsTop) / ROW_HEIGHT);
        if ((mouseY - rowsTop) % ROW_HEIGHT >= 18) return false;
        int split = left + panelWidth / 2;
        if (mouseX >= left + 12 && mouseX < split - 10) {
            int index = candidateScroll + row;
            if (index < visible.size()) addRule(visible.get(index).rule);
            return true;
        }
        if (mouseX >= left + panelWidth - 29 && mouseX < left + panelWidth - 12) {
            int index = ruleScroll + row;
            if (index < rules.size()) {
                rules.remove(index);
                ruleScroll = clampScroll(ruleScroll, rules.size());
                updateTypeControls();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selectedType == VoidUpgradeRule.Type.NAME && search != null && search.isFocused()
                && (keyCode == 257 || keyCode == 335)) {
            addNameRule();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!contains(left + 12, top + 106, panelWidth - 24,
                visibleRows() * ROW_HEIGHT, mouseX, mouseY) || delta == 0) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        int split = left + panelWidth / 2;
        int direction = delta > 0 ? -1 : 1;
        if (mouseX < split) candidateScroll = clampScroll(candidateScroll + direction, visible.size());
        else ruleScroll = clampScroll(ruleScroll + direction, rules.size());
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        saveConfigOnce();
        Minecraft.getInstance().setScreen(parent);
    }

    private int visibleRows() {
        return Math.max(1, (panelHeight - 143) / ROW_HEIGHT);
    }

    private int clampScroll(int value, int size) {
        return Math.max(0, Math.min(value, Math.max(0, size - visibleRows())));
    }

    private boolean ghostContains(double x, double y) {
        Rect2i area = getGhostIngredientArea();
        return contains(area.getX(), area.getY(), area.getWidth(), area.getHeight(), x, y);
    }

    private static boolean contains(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private Component optionLabel(String option, boolean enabled) {
        return Component.translatable("screen.rs_integration.void_upgrade.option." + option,
                Component.translatable("screen.rs_integration.void_upgrade.option."
                        + (enabled ? "match" : "ignore")));
    }

    private static Component typeLabel(VoidUpgradeRule.Type type) {
        return Component.translatable("screen.rs_integration.void_upgrade.type."
                + type.name().toLowerCase(Locale.ROOT));
    }

    private Component ruleName(VoidUpgradeRule rule) {
        if (rule.type() == VoidUpgradeRule.Type.ITEM) {
            ItemStack icon = iconFor(rule);
            return icon.isEmpty() ? Component.literal(rule.value()) : icon.getHoverName();
        }
        if (rule.type() == VoidUpgradeRule.Type.TAG) return Component.literal("#" + rule.value());
        if (rule.type() == VoidUpgradeRule.Type.MOD) {
            return ModList.get().getModContainerById(rule.value())
                    .map(container -> Component.literal(container.getModInfo().getDisplayName()))
                    .orElse(Component.literal(rule.value()));
        }
        if (rule.type() == VoidUpgradeRule.Type.NAME) return Component.literal(rule.value());
        return Component.translatable("screen.rs_integration.void_upgrade.equipment." + rule.value());
    }

    private static ItemStack iconFor(VoidUpgradeRule rule) {
        if (rule.type() != VoidUpgradeRule.Type.ITEM) return ItemStack.EMPTY;
        ResourceLocation id = ResourceLocation.tryParse(rule.value());
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = item.getDefaultInstance();
        if (rule.itemNbt() != null) stack.setTag(rule.itemNbt().copy());
        return stack;
    }

    private List<Component> candidateTooltip(Candidate candidate) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(candidate.name);
        tooltip.add(Component.literal(candidate.detail).withStyle(ChatFormatting.DARK_GRAY));
        if (candidate.rule.itemNbt() != null) {
            tooltip.add(Component.translatable("screen.rs_integration.void_upgrade.has_nbt")
                    .withStyle(ChatFormatting.GOLD));
        }
        return tooltip;
    }

    private Component trim(Component text, int width) {
        String value = text.getString();
        if (font.width(value) > width) {
            value = font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
        }
        return Component.literal(value);
    }

    private void scrollbar(GuiGraphics graphics, int x, int y, int scroll, int total) {
        int rows = visibleRows();
        if (total <= rows) return;
        int height = rows * ROW_HEIGHT - 2;
        int thumbHeight = Math.max(12, height * rows / total);
        int thumbY = y + (height - thumbHeight) * scroll / (total - rows);
        graphics.fill(x, y, x + 2, y + height, 0xFFCDC6D8);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFF8C78A5);
    }

    private static void drawPlus(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y + 3, x + 7, y + 4, 0xFF000000 | color);
        graphics.fill(x + 3, y, x + 4, y + 7, 0xFF000000 | color);
    }

    private static void drawCross(GuiGraphics graphics, int x, int y, int color) {
        for (int i = 0; i < 7; i++) {
            graphics.fill(x + i, y + i, x + i + 1, y + i + 1, 0xFF000000 | color);
            graphics.fill(x + 6 - i, y + i, x + 7 - i, y + i + 1, 0xFF000000 | color);
        }
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("screen.rs_integration.void_upgrade." + key, arguments);
    }

    private final class MatchCheckbox extends Checkbox {
        private final String option;
        private final Consumer<Boolean> setter;

        private MatchCheckbox(int x, int width, String option, boolean selected, Consumer<Boolean> setter) {
            super(x, top + panelHeight - 28, width, 20, text("option." + option + "_label"), selected);
            this.option = option;
            this.setter = setter;
            setTooltip(Tooltip.create(optionLabel(option, selected)));
        }

        @Override
        public void onPress() {
            super.onPress();
            setter.accept(selected());
            setTooltip(Tooltip.create(optionLabel(option, selected())));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY() + 4;
            int color = selected() || isHoveredOrFocused() ? ACCENT : 0xA49AAD;
            graphics.fill(x, y, x + 12, y + 12, 0xFF000000 | color);
            graphics.fill(x + 1, y + 1, x + 11, y + 11, selected() ? 0xFF786294 : 0xFFF1EEF5);
            if (selected()) {
                graphics.fill(x + 3, y + 6, x + 6, y + 8, 0xFFF5EFFB);
                graphics.fill(x + 5, y + 4, x + 8, y + 7, 0xFFF5EFFB);
            }
            graphics.drawString(font, trim(getMessage(), getWidth() - 18), x + 17, getY() + 6, TEXT, false);
        }
    }

    private final class VoidButton extends Button {
        private final boolean iconOnly;
        private boolean selected;

        private VoidButton(int x, int y, int width, int height, Component label,
                           OnPress press, boolean iconOnly) {
            super(x, y, width, height, label, press, DEFAULT_NARRATION);
            this.iconOnly = iconOnly;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean header = getY() == top + 5;
            boolean hovered = active && isHoveredOrFocused();
            int x = getX();
            int y = getY();
            int ink = !active ? 0xA29BAA : header || selected ? 0xF5EEFC : TEXT;
            if (!header || hovered) {
                int edge = selected || hovered ? ACCENT : 0xB3A9BF;
                int fill = !active ? 0xD8D2DF : selected ? ACCENT : hovered ? 0xFAF7FF : 0xEEEAF3;
                graphics.fill(x, y, x + getWidth(), y + getHeight(), 0xFF000000 | edge);
                graphics.fill(x + 1, y + 1, x + getWidth() - 1, y + getHeight() - 1, 0xFF000000 | fill);
                if (selected) graphics.fill(x + 3, y + getHeight() - 3,
                        x + getWidth() - 3, y + getHeight() - 2, 0xFFCCC1E3);
            }
            if (iconOnly) {
                if (header) drawCross(graphics, x + 6, y + 6, ink);
                else drawPlus(graphics, x + 8, y + 8, ink);
            } else {
                Component label = trim(getMessage(), getWidth() - 12);
                graphics.drawString(font, label, x + (getWidth() - font.width(label)) / 2,
                        y + (getHeight() - 8) / 2, ink, false);
            }
            if (isFocused()) graphics.fill(x + 2, y + getHeight() - 2,
                    x + getWidth() - 2, y + getHeight() - 1, 0xFF000000 | ink);
        }
    }

    private void indexPinyinAsync() {
        int generation = catalogGeneration.incrementAndGet();
        List<Candidate> snapshot = List.copyOf(catalog);
        CompletableFuture.runAsync(() -> snapshot.forEach(Candidate::indexPinyin), PINYIN_INDEXER)
                .thenRun(() -> Minecraft.getInstance().execute(() -> {
                    if (catalogGeneration.get() == generation
                            && Minecraft.getInstance().screen == this) filterCatalog();
                }));
    }

    private static final class Candidate {
        private final Component name;
        private final String detail;
        private final VoidUpgradeRule rule;
        private final ItemStack icon;
        private final String displayName;
        private volatile String searchText;

        private Candidate(Component name, String detail, VoidUpgradeRule rule, ItemStack icon) {
            this.name = name;
            this.detail = detail;
            this.rule = rule;
            this.icon = icon;
            this.displayName = name.getString();
            this.searchText = (displayName + "\n" + detail).toLowerCase(Locale.ROOT);
        }

        private void indexPinyin() {
            searchText = searchText + "\n" + PinyinUtil.toPinyin(displayName)
                    + "\n" + PinyinUtil.toPinyinInitials(displayName);
        }

        private boolean matches(String query) {
            return searchText.contains(query);
        }
    }
}
