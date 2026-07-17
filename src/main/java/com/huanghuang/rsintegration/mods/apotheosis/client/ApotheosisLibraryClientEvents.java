package com.huanghuang.rsintegration.mods.apotheosis.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryService.LevelAction;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryImportResultPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryLevelPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryScanResponsePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Adds compact controls and an attached import panel to Apotheosis' library screen. */
public final class ApotheosisLibraryClientEvents {
    private static final String LIBRARY_SCREEN =
            "dev.shadowsoffire.apotheosis.ench.library.EnchLibraryScreen";
    private static final ResourceLocation ICON = new ResourceLocation(
            RSIntegrationMod.MOD_ID, "textures/gui/apotheosis_library_import.png");
    private static final int ROW_TOP = 31;
    private static final int ROW_HEIGHT = 20;
    private static final int BUTTON_SIZE = 9;
    private static final int BUTTON_X = 124;
    private static ApotheosisLibraryImportScreen panel;

    private ApotheosisLibraryClientEvents() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!isLibraryScreen(event.getScreen())
                || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || !RSIntegrationConfig.ENABLE_APOTHEOSIS.get()) return;

        if (!isCurrentLibraryBound(screen)) return;
        panel = new ApotheosisLibraryImportScreen(screen);
        panel.init(event);
        int importX = importButtonX(screen);
        int importY = Math.max(2, Math.min(screen.height - 20, screen.getGuiTop() + 4));
        event.addListener(new LibraryButton(importX, importY, 18, 18,
                Component.translatable("rsi.apotheosis.library.import"), true, "", panel::toggle));

    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (panel != null && event.getScreen() instanceof AbstractContainerScreen<?> screen
                && panel.owns(screen)) {
            panel.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (panel != null && event.getScreen() instanceof AbstractContainerScreen<?> screen
                && panel.owns(screen) && panel.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }
    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (panel != null && event.getScreen() instanceof AbstractContainerScreen<?> screen
                && panel.owns(screen) && panel.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClose(ScreenEvent.Closing event) {
        if (panel != null) panel.close();
        panel = null;
    }

    public static void acceptScan(ApotheosisLibraryScanResponsePacket packet) {
        if (panel != null) panel.acceptScan(packet);
    }

    public static void acceptImportResult(ApotheosisLibraryImportResultPacket packet) {
        if (panel != null) panel.acceptImportResult(packet);
    }

    private static LibraryButton levelButton(AbstractContainerScreen<?> screen, int row,
                                             int y, LevelAction action) {
        Component message = Component.translatable(action == LevelAction.PREVIOUS
                ? "rsi.apotheosis.library.level.previous"
                : "rsi.apotheosis.library.level.next");
        String glyph = action == LevelAction.PREVIOUS ? "−" : "+";
        return new LibraryButton(screen.getGuiLeft() + BUTTON_X, y, BUTTON_SIZE, BUTTON_SIZE,
                message, false, glyph, () -> sendLevelAction(screen, row, action));
    }

    private static void sendLevelAction(AbstractContainerScreen<?> screen, int row, LevelAction action) {
        Enchantment enchantment = visibleEnchantment(screen, row);
        ResourceLocation id = enchantment == null ? null : ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
        Minecraft minecraft = Minecraft.getInstance();
        if (id == null || minecraft.level == null) return;
        NetworkHandler.CHANNEL.sendToServer(new ApotheosisLibraryLevelPacket(
                minecraft.level.dimension().location(), menuPos(screen), id, action));
    }

    private static Enchantment visibleEnchantment(AbstractContainerScreen<?> screen, int row) {
        try {
            var hovered = screen.getClass().getMethod("getHoveredSlot", int.class, int.class);
            Object slot = hovered.invoke(screen, screen.getGuiLeft() + 30,
                    screen.getGuiTop() + ROW_TOP + row * ROW_HEIGHT + 5);
            if (slot == null) return null;
            var enchantment = slot.getClass().getDeclaredMethod("ench");
            enchantment.setAccessible(true);
            return (Enchantment) enchantment.invoke(slot);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static net.minecraft.core.BlockPos menuPos(AbstractContainerScreen<?> screen) {
        Class<?> type = screen.getMenu().getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField("pos");
                field.setAccessible(true);
                return (net.minecraft.core.BlockPos) field.get(screen.getMenu());
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return net.minecraft.core.BlockPos.ZERO;
    }

    private static boolean isCurrentLibraryBound(AbstractContainerScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.core.BlockPos pos = menuPos(screen);
        if (minecraft.level == null || pos.equals(net.minecraft.core.BlockPos.ZERO)) return false;
        ResourceLocation dimension = minecraft.level.dimension().location();
        return BindingCache.getInstance().getAll().stream().anyMatch(binding ->
                dimension.equals(binding.dim()) && pos.equals(binding.pos())
                        && ApotheosisLibraryBinding.isLibrary(binding.blockRegKey()));
    }

    static int importButtonX(int guiLeft, int guiWidth, int screenWidth) {
        int right = guiLeft + guiWidth + 1;
        if (right + 18 <= screenWidth - 2) return right;
        return Math.max(2, guiLeft - 19);
    }

    private static int importButtonX(AbstractContainerScreen<?> screen) {
        return importButtonX(screen.getGuiLeft(), screen.getXSize(), screen.width);
    }

    private static boolean isLibraryScreen(net.minecraft.client.gui.screens.Screen screen) {
        return LIBRARY_SCREEN.equals(screen.getClass().getName());
    }

    private static final class LibraryButton extends AbstractButton {
        private final Runnable action;
        private final boolean icon;
        private final String glyph;

        private LibraryButton(int x, int y, int width, int height, Component message,
                              boolean icon, String glyph, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
            this.icon = icon;
            this.glyph = glyph;
            setTooltip(Tooltip.create(message));
        }

        @Override public void onPress() { action.run(); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHoveredOrFocused() ? 0xFFE0B965 : 0xFF6D4327;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, border);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
                    active ? 0xFF321A12 : 0xFF241F1D);
            if (icon) {
                graphics.blit(ICON, getX() + 1, getY() + 1, 16, 16,
                        0, 0, 32, 32, 32, 32);
            } else {
                graphics.drawCenteredString(Minecraft.getInstance().font, glyph,
                        getX() + width / 2, getY(), active ? 0xFFE0B965 : 0xFF796B61);
            }
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
