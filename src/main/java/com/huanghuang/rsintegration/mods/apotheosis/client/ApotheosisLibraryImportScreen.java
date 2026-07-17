package com.huanghuang.rsintegration.mods.apotheosis.client;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.EnchantmentInfo;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.Entry;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryModels.EntryStatus;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryImportRequestPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryImportResultPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryScanRequestPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryScanResponsePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Controller for the import panel attached to an Apotheosis library screen. */
public final class ApotheosisLibraryImportScreen {
    private static final int PANEL_W = 168;
    private static final int PANEL_H = 218;
    private static final int ROW_H = 26;
    private static final int VISIBLE_ROWS = 5;

    private final AbstractContainerScreen<?> owner;
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> displayEntries = new ArrayList<>();
    private final Set<Integer> selectedIds = new HashSet<>();
    private EditBox search;
    private Button importFiltered;
    private Button importAll;
    private int panelLeft;
    private int panelTop;
    private int scrollOffset;
    private boolean open;
    private String errorKey = "";
    private ScanContext scanContext;

    public ApotheosisLibraryImportScreen(AbstractContainerScreen<?> owner) {
        this.owner = owner;
        updateBounds();
    }

    public void init(ScreenEvent.Init.Post event) {
        updateBounds();
        Font font = Minecraft.getInstance().font;
        search = new EditBox(font, panelLeft + 8, panelTop + 25, PANEL_W - 16, 18,
                Component.translatable("rsi.apotheosis.library.search"));
        search.setMaxLength(64);
        search.setResponder(ignored -> refreshDisplay());
        event.addListener(search);

        importFiltered = Button.builder(Component.translatable("rsi.apotheosis.library.import_filtered"),
                        button -> doImport(selectedIdsFor(displayEntries)))
                .pos(panelLeft + 8, panelTop + PANEL_H - 26).size(82, 20).build();
        event.addListener(importFiltered);
        importAll = Button.builder(Component.translatable("rsi.apotheosis.library.import_all"),
                        button -> doImport(importableIds(displayEntries)))
                .pos(panelLeft + 94, panelTop + PANEL_H - 26).size(66, 20).build();
        event.addListener(importAll);
        updateWidgetState();
    }

    public void toggle() {
        open = !open;
        if (open && scanContext == null) requestScan();
        updateWidgetState();
    }

    public void close() {
        open = false;
        updateWidgetState();
    }

    public boolean owns(AbstractContainerScreen<?> screen) {
        return owner == screen && Minecraft.getInstance().screen == owner;
    }

    public void acceptScan(ApotheosisLibraryScanResponsePacket packet) {
        if (!matches(packet.dimension(), packet.pos())) return;
        pending.set(false);
        errorKey = packet.errorKey() == null ? "" : packet.errorKey();
        scanContext = new ScanContext(packet.dimension(), packet.pos(), packet.snapshotId());
        allEntries.clear();
        allEntries.addAll(packet.entries());
        selectedIds.clear();
        refreshDisplay();
        updateWidgetState();
    }

    public void acceptImportResult(ApotheosisLibraryImportResultPacket packet) {
        if (!matches(packet.dimension(), packet.pos())) return;
        pending.set(false);
        errorKey = packet.errorKey() == null ? "" : packet.errorKey();
        if (!errorKey.isEmpty()) {
            updateWidgetState();
            return;
        }
        ApotheosisLibraryScanResponsePacket refreshed = packet.scan();
        scanContext = new ScanContext(refreshed.dimension(), refreshed.pos(), refreshed.snapshotId());
        allEntries.clear();
        allEntries.addAll(refreshed.entries());
        selectedIds.clear();
        refreshDisplay();
        updateWidgetState();
        if (Minecraft.getInstance().player != null) {
            var stats = packet.stats();
            Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                    "rsi.apotheosis.library.import.done", stats.imported(), stats.skipped(),
                    stats.refunded(), stats.dropped()), true);
        }
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!open || !owns(owner)) return;
        updateBounds();
        int right = panelLeft + PANEL_W;
        int bottom = panelTop + PANEL_H;
        graphics.fill(panelLeft, panelTop, right, bottom, 0xFF1A1008);
        graphics.fill(panelLeft + 2, panelTop + 2, right - 2, bottom - 2, 0xFF2E1A0C);
        graphics.fill(panelLeft + 2, panelTop + 2, right - 2, panelTop + 22, 0xFF4A2E14);
        graphics.drawString(Minecraft.getInstance().font,
                Component.translatable("rsi.apotheosis.library.import.title"),
                panelLeft + 8, panelTop + 7, 0xFFE0B965, false);

        int listTop = panelTop + 54;
        if (!errorKey.isEmpty()) {
            graphics.drawString(Minecraft.getInstance().font, Component.translatable(errorKey),
                    panelLeft + 8, listTop, 0xFFFF7777, false);
            listTop += 12;
        } else if (pending.get()) {
            graphics.drawString(Minecraft.getInstance().font,
                    Component.translatable("rsi.apotheosis.library.scanning"),
                    panelLeft + 8, listTop, 0xFFAAAAAA, false);
            listTop += 12;
        }

        graphics.enableScissor(panelLeft + 4, listTop, right - 5,
                Math.min(bottom - 30, listTop + VISIBLE_ROWS * ROW_H));
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= displayEntries.size()) break;
            drawEntry(graphics, displayEntries.get(index), panelLeft + 5, listTop + row * ROW_H);
        }
        graphics.disableScissor();
        renderEntryTooltip(graphics, mouseX, mouseY, listTop);
    }

    private void drawEntry(GuiGraphics graphics, Entry entry, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int right = panelLeft + PANEL_W - 6;
        graphics.fill(x, y, right, y + ROW_H - 2,
                selectedIds.contains(entry.id()) ? 0xFF6A3D12
                        : entry.status() == EntryStatus.IMPORTABLE ? 0xFF3A2010 : 0xFF28201C);
        graphics.renderItem(entry.stack(), x + 4, y + 4);

        Component title = entryTitle(entry);
        String count = "x" + entry.count();
        int countWidth = font.width(count);
        int textX = x + 25;
        int available = Math.max(20, right - textX - countWidth - 6);
        String name = ellipsize(font, title.getString(), available);
        graphics.drawString(font, name, textX, y + 3,
                entry.status() == EntryStatus.IMPORTABLE ? 0xFFE2C287 : 0xFF99918B, false);
        graphics.drawString(font, count, right - countWidth - 3, y + 3, 0xFFB8B0A8, false);

        Component detail = entry.status() == EntryStatus.IMPORTABLE
                ? Component.translatable("rsi.apotheosis.library.entry.ready")
                : Component.translatable("rsi.apotheosis.library.status."
                + entry.status().name().toLowerCase(Locale.ROOT));
        graphics.drawString(font, ellipsize(font, detail.getString(), right - textX - 4),
                textX, y + 14, entry.status() == EntryStatus.IMPORTABLE ? 0xFF78B978 : 0xFFD18A62, false);
    }

    private void renderEntryTooltip(GuiGraphics graphics, int mouseX, int mouseY, int listTop) {
        if (mouseX < panelLeft + 5 || mouseX >= panelLeft + PANEL_W - 6
                || mouseY < listTop || mouseY >= listTop + VISIBLE_ROWS * ROW_H) return;
        int index = scrollOffset + (mouseY - listTop) / ROW_H;
        if (index < 0 || index >= displayEntries.size()) return;
        Entry entry = displayEntries.get(index);
        List<Component> tooltip = new ArrayList<>();
        for (EnchantmentInfo info : entry.enchantments()) tooltip.add(enchantmentName(info));
        tooltip.add(Component.translatable("rsi.apotheosis.library.entry.count", entry.count())
                .withStyle(ChatFormatting.GRAY));
        if (entry.status() != EntryStatus.IMPORTABLE) {
            tooltip.add(Component.translatable("rsi.apotheosis.library.status."
                    + entry.status().name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.YELLOW));
        }
        graphics.renderTooltip(Minecraft.getInstance().font, tooltip, Optional.empty(), mouseX, mouseY);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open || button != 0 || mouseX < panelLeft + 5 || mouseX >= panelLeft + PANEL_W - 6) return false;
        int listTop = panelTop + 54 + (!errorKey.isEmpty() || pending.get() ? 12 : 0);
        if (mouseY < listTop || mouseY >= listTop + VISIBLE_ROWS * ROW_H) return false;
        int index = scrollOffset + (int) ((mouseY - listTop) / ROW_H);
        if (index < 0 || index >= displayEntries.size()) return true;
        Entry entry = displayEntries.get(index);
        if (entry.status() == EntryStatus.IMPORTABLE) {
            if (!selectedIds.add(entry.id())) selectedIds.remove(entry.id());
        }
        updateWidgetState();
        return true;
    }
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!open || mouseX < panelLeft || mouseX >= panelLeft + PANEL_W
                || mouseY < panelTop || mouseY >= panelTop + PANEL_H) return false;
        int max = Math.max(0, displayEntries.size() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) Math.signum(delta)));
        return true;
    }

    private void requestScan() {
        if (!pending.compareAndSet(false, true)) return;
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = menuPos(owner);
        if (minecraft.level == null || pos == null) {
            pending.set(false);
            errorKey = "rsi.apotheosis.library.context_changed";
            return;
        }
        scanContext = new ScanContext(minecraft.level.dimension().location(), pos, 0L);
        NetworkHandler.CHANNEL.sendToServer(new ApotheosisLibraryScanRequestPacket(
                scanContext.dimension(), scanContext.pos()));
        updateWidgetState();
    }

    private void doImport(Set<Integer> ids) {
        if (pending.get() || scanContext == null || scanContext.snapshotId() <= 0 || ids.isEmpty()) return;
        pending.set(true);
        NetworkHandler.CHANNEL.sendToServer(new ApotheosisLibraryImportRequestPacket(
                scanContext.dimension(), scanContext.pos(), scanContext.snapshotId(), new HashSet<>(ids)));
        updateWidgetState();
    }

    private void refreshDisplay() {
        if (search == null) return;
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        displayEntries.clear();
        for (Entry entry : allEntries) {
            if (query.isEmpty() || searchableText(entry).contains(query)) displayEntries.add(entry);
        }
        scrollOffset = 0;
        updateWidgetState();
    }

    static String searchableText(Entry entry) {
        StringBuilder text = new StringBuilder();
        for (EnchantmentInfo info : entry.enchantments()) {
            String localized = Component.translatable(info.translationKey()).getString().toLowerCase(Locale.ROOT);
            text.append(' ').append(localized)
                    .append(' ').append(PinyinUtil.toPinyin(localized))
                    .append(' ').append(PinyinUtil.toPinyinInitials(localized))
                    .append(' ').append(info.id())
                    .append(' ').append(info.id().getPath())
                    .append(' ').append(info.translationKey())
                    .append(' ').append(info.level())
                    .append(' ').append(roman(info.level()).toLowerCase(Locale.ROOT));
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static Component entryTitle(Entry entry) {
        if (entry.enchantments().isEmpty()) return entry.stack().getHoverName();
        Component first = enchantmentName(entry.enchantments().get(0));
        return entry.enchantments().size() == 1 ? first
                : Component.translatable("rsi.apotheosis.library.entry.multiple",
                first, entry.enchantments().size() - 1);
    }

    private static Component enchantmentName(EnchantmentInfo info) {
        return Component.translatable("rsi.apotheosis.library.entry.enchantment",
                Component.translatable(info.translationKey()), roman(info.level()));
    }

    static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }

    private static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        String suffix = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(suffix))) + suffix;
    }

    private void updateBounds() {
        int right = owner.getGuiLeft() + owner.getXSize() + 3;
        panelLeft = right + PANEL_W <= owner.width - 3
                ? right : Math.max(3, owner.getGuiLeft() - PANEL_W - 3);
        panelTop = Math.max(3, Math.min(owner.height - PANEL_H - 3, owner.getGuiTop()));
        if (search != null) search.setPosition(panelLeft + 8, panelTop + 25);
        if (importFiltered != null) importFiltered.setPosition(panelLeft + 8, panelTop + PANEL_H - 26);
        if (importAll != null) importAll.setPosition(panelLeft + 94, panelTop + PANEL_H - 26);
    }

    private void updateWidgetState() {
        boolean visible = open;
        boolean ready = visible && !pending.get() && scanContext != null && scanContext.snapshotId() > 0;
        if (search != null) search.visible = visible;
        if (importFiltered != null) {
            importFiltered.visible = visible;
            importFiltered.active = ready && !selectedIdsFor(displayEntries).isEmpty();
        }
        if (importAll != null) {
            importAll.visible = visible;
            importAll.active = ready && !importableIds(allEntries).isEmpty();
        }
    }

    private boolean matches(ResourceLocation dimension, BlockPos pos) {
        return open && owns(owner) && scanContext != null
                && scanContext.dimension().equals(dimension) && scanContext.pos().equals(pos);
    }

    private Set<Integer> selectedIdsFor(List<Entry> entries) {
        return entries.stream().map(Entry::id).filter(selectedIds::contains).collect(Collectors.toSet());
    }

    private static Set<Integer> importableIds(List<Entry> entries) {
        return entries.stream().filter(entry -> entry.status() == EntryStatus.IMPORTABLE)
                .map(Entry::id).collect(Collectors.toSet());
    }

    private static BlockPos menuPos(AbstractContainerScreen<?> screen) {
        Class<?> type = screen.getMenu().getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField("pos");
                field.setAccessible(true);
                return (BlockPos) field.get(screen.getMenu());
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private record ScanContext(ResourceLocation dimension, BlockPos pos, long snapshotId) {}
}
