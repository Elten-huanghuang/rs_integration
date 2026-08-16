package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoriteKey;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;
import com.huanghuang.rsintegration.sidepanel.network.MachineFavoriteTogglePacket;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class MachineFavoritesClient {
    private static final int BUTTON_SIZE = 24;
    private static final int GAP = 2;
    private static final int GUI_X_OFFSET = -BUTTON_SIZE;
    private static final int GUI_Y_GAP = 4;
    private static final int SCREEN_MARGIN = 4;
    private static final int BORDER = 0xFF373737;
    private static final int BACKGROUND = 0xFFC6C6C6;
    private static final int HOVER_BACKGROUND = 0xFFE2E2E2;

    private static List<MachineFavoriteKey> favorites = List.of();
    private static boolean initialized;

    private MachineFavoritesClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                MachineFavoritesClient::onRender);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH,
                MachineFavoritesClient::onMousePressed);
        MinecraftForge.EVENT_BUS.addListener(MachineFavoritesClient::onLogout);
    }

    public static void update(List<MachineFavoriteKey> synchronizedFavorites) {
        favorites = List.copyOf(synchronizedFavorites);
    }

    public static boolean isFavorite(BindingInfo info) {
        MachineFavoriteKey candidate = MachineFavoriteKey.from(info);
        return favorites.contains(candidate);
    }

    public static void requestToggle(BindingInfo info) {
        MachineFavoriteKey key = MachineFavoriteKey.from(info);
        List<MachineFavoriteKey> optimistic = new ArrayList<>(favorites);
        if (!optimistic.remove(key) && optimistic.size() < MachineFavoritesSavedData.MAX_FAVORITES) {
            optimistic.add(key);
        }
        favorites = List.copyOf(optimistic);
        RSSidePanelNetworkHandler.CHANNEL.sendToServer(new MachineFavoriteTogglePacket(key));
    }

    public static List<Rect2i> getJeiExtraAreas(GridScreen screen) {
        if (MachineHub.isVisible()) return List.of();
        DockLayout layout = layout(screen);
        if (layout.slots().isEmpty()) return List.of();
        int rows = (layout.slots().size() + layout.columns() - 1) / layout.columns();
        int width = layout.columns() * BUTTON_SIZE + (layout.columns() - 1) * GAP;
        int height = rows * BUTTON_SIZE + (rows - 1) * GAP;
        return List.of(new Rect2i(layout.x(), layout.y(), width, height));
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof GridScreen screen) || MachineHub.isVisible()) return;
        DockLayout layout = layout(screen);
        if (layout.slots().isEmpty()) return;

        GuiGraphics graphics = event.getGuiGraphics();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 450);
        Slot hovered = null;
        try {
            for (Slot slot : layout.slots()) {
                boolean isHovered = slot.contains(event.getMouseX(), event.getMouseY());
                graphics.fill(slot.x(), slot.y(), slot.x() + BUTTON_SIZE,
                        slot.y() + BUTTON_SIZE, BORDER);
                graphics.fill(slot.x() + 1, slot.y() + 1,
                        slot.x() + BUTTON_SIZE - 1, slot.y() + BUTTON_SIZE - 1,
                        isHovered ? HOVER_BACKGROUND : BACKGROUND);
                ItemStack icon = MachineTabRenderer.resolveIcon(slot.info());
                if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
                graphics.renderItem(icon, slot.x() + 4, slot.y() + 4);
                if (isHovered) hovered = slot;
            }

            if (hovered != null) renderTooltip(graphics, hovered.info(),
                    event.getMouseX(), event.getMouseY());
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof GridScreen screen) || MachineHub.isVisible()) return;
        DockLayout layout = layout(screen);
        for (Slot slot : layout.slots()) {
            if (!slot.contains(event.getMouseX(), event.getMouseY())) continue;
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                MachineTabHandler.onClick(slot.info());
            }
            event.setCanceled(true);
            return;
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        favorites = List.of();
    }

    private static DockLayout layout(GridScreen screen) {
        if (!machineTabsEnabled()) return DockLayout.EMPTY;
        List<BindingInfo> bindings = favoriteBindings();
        if (bindings.isEmpty()) return DockLayout.EMPTY;

        int x = screen.getGuiLeft() + screen.getXSize() + GUI_X_OFFSET;
        int y = screen.getGuiTop() + screen.getTopHeight()
                + screen.getVisibleRows() * 18 + GUI_Y_GAP;
        int availableWidth = screen.width - x - SCREEN_MARGIN;
        int columns = availableWidth >= BUTTON_SIZE * 2 + GAP ? 2
                : availableWidth >= BUTTON_SIZE ? 1 : 0;
        if (columns == 0) return DockLayout.EMPTY;
        int availableRows = Math.max(0,
                (screen.height - y - SCREEN_MARGIN + GAP) / (BUTTON_SIZE + GAP));
        int capacity = Math.min(MachineFavoritesSavedData.MAX_FAVORITES,
                availableRows * columns);
        if (capacity == 0) return DockLayout.EMPTY;

        List<Slot> slots = new ArrayList<>(Math.min(capacity, bindings.size()));
        for (int index = 0; index < bindings.size() && index < capacity; index++) {
            int col = index % columns;
            int row = index / columns;
            slots.add(new Slot(x + col * (BUTTON_SIZE + GAP),
                    y + row * (BUTTON_SIZE + GAP), bindings.get(index)));
        }
        return new DockLayout(x, y, columns, List.copyOf(slots));
    }

    private static List<BindingInfo> favoriteBindings() {
        List<BindingInfo> result = new ArrayList<>(favorites.size());
        List<BindingInfo> available = MachineTabHandler.getAllMachines();
        for (MachineFavoriteKey favorite : favorites) {
            for (BindingInfo info : available) {
                if (favorite.matches(info)) {
                    result.add(info);
                    break;
                }
            }
        }
        return result;
    }

    private static boolean machineTabsEnabled() {
        return ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.ENABLE_MACHINE_GUI_TABS
                : RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get();
    }

    private static void renderTooltip(GuiGraphics graphics, BindingInfo info, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        ItemStack displayStack = info.displayStack();
        if (displayStack != null && !displayStack.isEmpty()) {
            lines.add(BindingEventHandler.resolveBlockName(
                    info.blockKey(), info.blockRegKey(), displayStack));
        } else {
            lines.add(Component.translatable(info.displayName()));
        }
        lines.add(Component.literal(info.dim() + " " + info.pos().toShortString())
                .withStyle(ChatFormatting.DARK_GRAY));
        RSSidePanelClient.isRenderingOurTooltip = true;
        try {
            graphics.renderTooltip(Minecraft.getInstance().font, lines,
                    java.util.Optional.empty(), mouseX, mouseY);
        } finally {
            RSSidePanelClient.isRenderingOurTooltip = false;
        }
    }

    private record DockLayout(int x, int y, int columns, List<Slot> slots) {
        private static final DockLayout EMPTY = new DockLayout(0, 0, 0, List.of());
    }

    private record Slot(int x, int y, BindingInfo info) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + BUTTON_SIZE
                    && mouseY >= y && mouseY < y + BUTTON_SIZE;
        }
    }
}
