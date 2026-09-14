package com.huanghuang.rsintegration.voidupgrade.client;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeConfig;
import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeRule;
import com.huanghuang.rsintegration.voidupgrade.network.SaveVoidUpgradeConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class VoidUpgradeScreen extends Screen {
    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 246;
    private static final int ROW_HEIGHT = 20;
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
    private VoidUpgradeRule.Type selectedType = VoidUpgradeRule.Type.ITEM;
    private boolean matchNbt;
    private boolean matchDamage;
    private boolean matchEnchantments;
    private EditBox search;
    private Button nbtButton;
    private Button damageButton;
    private Button enchantmentsButton;
    private Button nameAddButton;
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

        search = new EditBox(font, left + 40, top + 25, panelWidth - 50, 20,
                Component.translatable("screen.rs_integration.void_upgrade.search"));
        search.setHint(Component.translatable("screen.rs_integration.void_upgrade.search"));
        search.setResponder(unused -> filterCatalog());
        addRenderableWidget(search);

        int tabY = top + 51;
        int tabWidth = Math.max(52, (panelWidth - 20) / 4);
        addTypeButton(VoidUpgradeRule.Type.ITEM, left + 10, tabY, tabWidth);
        addTypeButton(VoidUpgradeRule.Type.TAG, left + 10 + tabWidth, tabY, tabWidth);
        addTypeButton(VoidUpgradeRule.Type.MOD, left + 10 + tabWidth * 2, tabY, tabWidth);
        addTypeButton(VoidUpgradeRule.Type.NAME, left + 10 + tabWidth * 3, tabY,
                panelWidth - 20 - tabWidth * 3);

        nameAddButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.rs_integration.void_upgrade.name.add"),
                button -> addNameRule())
                .bounds(left + panelWidth - 60, top + 25, 50, 20).build());

        int optionWidth = Math.max(62, (panelWidth - 112) / 3);
        int optionGap = 4;
        nbtButton = addRenderableWidget(Button.builder(optionLabel("nbt", matchNbt), button -> {
            matchNbt = !matchNbt;
            button.setMessage(optionLabel("nbt", matchNbt));
        }).bounds(left + 10, top + panelHeight - 28, optionWidth, 20).build());
        damageButton = addRenderableWidget(Button.builder(optionLabel("damage", matchDamage), button -> {
            matchDamage = !matchDamage;
            button.setMessage(optionLabel("damage", matchDamage));
        }).bounds(left + 10 + optionWidth + optionGap, top + panelHeight - 28,
                optionWidth, 20).build());
        enchantmentsButton = addRenderableWidget(Button.builder(
                optionLabel("enchantments", matchEnchantments), button -> {
                    matchEnchantments = !matchEnchantments;
                    button.setMessage(optionLabel("enchantments", matchEnchantments));
                }).bounds(left + 10 + (optionWidth + optionGap) * 2,
                        top + panelHeight - 28, optionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> save())
                .bounds(left + panelWidth - 80, top + panelHeight - 28, 70, 20).build());
        updateTypeControls();
        rebuildCatalog();
    }

    private void addTypeButton(VoidUpgradeRule.Type type, int x, int y, int width) {
        addRenderableWidget(Button.builder(typeLabel(type), button -> {
            selectedType = type;
            candidateScroll = 0;
            updateTypeControls();
            rebuildCatalog();
        }).bounds(x, y, width, 20).build());
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
        nameAddButton.active = nameMode;
        search.setWidth(panelWidth - (nameMode ? 105 : 50));
        search.setHint(Component.translatable(nameMode
                ? "screen.rs_integration.void_upgrade.name.hint"
                : "screen.rs_integration.void_upgrade.search"));
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
        for (Candidate candidate : catalog) {
            if (query.isEmpty() || candidate.matches(query)) {
                visible.add(candidate);
            }
        }
        candidateScroll = clampScroll(candidateScroll, visible.size());
    }

    public Rect2i getGhostIngredientArea() {
        return new Rect2i(left + 10, top + 25, 22, 20);
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
        renderBackground(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF014171A);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFFE0B52E);
        graphics.drawCenteredString(font, title, left + panelWidth / 2, top + 8, 0xFFFFFFFF);
        Rect2i ghost = getGhostIngredientArea();
        graphics.fill(ghost.getX(), ghost.getY(), ghost.getX() + ghost.getWidth(),
                ghost.getY() + ghost.getHeight(), 0xFF30363B);
        graphics.renderItem(new ItemStack(Items.BARRIER), ghost.getX() + 2, ghost.getY() + 2);

        int listTop = top + 78;
        int split = left + panelWidth / 2;
        int listBottom = top + panelHeight - 34;
        graphics.fill(split, listTop, split + 1, listBottom, 0xFF4A4F54);
        graphics.drawString(font, typeLabel(selectedType), left + 12, listTop + 2, 0xFFB8BDC2, false);
        graphics.drawString(font, Component.translatable("screen.rs_integration.void_upgrade.rules_title",
                rules.size(), VoidUpgradeConfig.MAX_RULES), split + 8, listTop + 2, 0xFFB8BDC2, false);
        int rowsTop = listTop + 14;
        int rows = visibleRows();
        for (int row = 0; row < rows; row++) {
            int index = candidateScroll + row;
            if (index >= visible.size()) break;
            renderCandidate(graphics, visible.get(index), left + 8, rowsTop + row * ROW_HEIGHT,
                    panelWidth / 2 - 12, mouseX, mouseY);
        }
        for (int row = 0; row < rows; row++) {
            int index = ruleScroll + row;
            if (index >= rules.size()) break;
            renderRule(graphics, rules.get(index), split + 5, rowsTop + row * ROW_HEIGHT,
                    panelWidth - (split - left) - 12, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (ghostContains(mouseX, mouseY)) {
            graphics.renderTooltip(font, List.of(
                    Component.translatable("screen.rs_integration.void_upgrade.jei_drop"),
                    Component.translatable("screen.rs_integration.void_upgrade.jei_container_warning")
                            .withStyle(net.minecraft.ChatFormatting.GOLD)
            ).stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private void renderCandidate(GuiGraphics graphics, Candidate candidate, int x, int y, int width,
                                 int mouseX, int mouseY) {
        boolean hovered = contains(x, y, width, 18, mouseX, mouseY);
        graphics.fill(x, y, x + width, y + 18, hovered ? 0xFF39434A : 0xFF252A2E);
        int textX = x + 4;
        if (!candidate.icon.isEmpty()) {
            graphics.renderItem(candidate.icon, x + 1, y + 1);
            textX = x + 20;
        }
        graphics.drawString(font, trim(candidate.name, width - (textX - x) - 3), textX, y + 5,
                0xFFE8E8E8, false);
        if (hovered) graphics.renderTooltip(font, candidateTooltip(candidate).stream()
                .map(Component::getVisualOrderText).toList(), mouseX, mouseY);
    }

    private void renderRule(GuiGraphics graphics, VoidUpgradeRule rule, int x, int y, int width,
                            int mouseX, int mouseY) {
        boolean hovered = contains(x, y, width, 18, mouseX, mouseY);
        graphics.fill(x, y, x + width, y + 18, hovered ? 0xFF4B3030 : 0xFF252A2E);
        ItemStack icon = iconFor(rule);
        int textX = x + 4;
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x + 1, y + 1);
            textX = x + 20;
        }
        graphics.drawString(font, trim(ruleName(rule), width - (textX - x) - 12), textX, y + 5,
                0xFFE8E8E8, false);
        graphics.drawString(font, "x", x + width - 9, y + 5, 0xFFFF6B6B, false);
        if (hovered) graphics.renderTooltip(font,
                Component.translatable("screen.rs_integration.void_upgrade.remove"), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int rowsTop = top + 92;
        int listBottom = top + panelHeight - 34;
        if (mouseY < rowsTop || mouseY >= listBottom) return false;
        int row = (int) ((mouseY - rowsTop) / ROW_HEIGHT);
        int split = left + panelWidth / 2;
        if (mouseX >= left + 8 && mouseX < split - 4) {
            int index = candidateScroll + row;
            if (index < visible.size()) addRule(visible.get(index).rule);
            return true;
        }
        if (mouseX >= split + 5 && mouseX < left + panelWidth - 7) {
            int index = ruleScroll + row;
            if (index < rules.size()) {
                rules.remove(index);
                ruleScroll = clampScroll(ruleScroll, rules.size());
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
        return Math.max(1, (panelHeight - 126) / ROW_HEIGHT);
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
        tooltip.add(Component.literal(candidate.detail).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        if (candidate.rule.itemNbt() != null) {
            tooltip.add(Component.translatable("screen.rs_integration.void_upgrade.has_nbt")
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        }
        return tooltip;
    }

    private Component trim(Component text, int width) {
        return Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(1, width)));
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
