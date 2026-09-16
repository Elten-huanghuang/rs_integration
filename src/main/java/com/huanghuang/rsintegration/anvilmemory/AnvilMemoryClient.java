package com.huanghuang.rsintegration.anvilmemory;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.client.RecipeBrowserBridge;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class AnvilMemoryClient {
    private static final ResourceLocation RESTOCK = texture("memory_restock.png");
    private static final ResourceLocation RESTOCK_HOVER = texture("memory_restock_hover.png");
    private static final ResourceLocation RESTOCK_DISABLED = texture("memory_restock_disabled.png");
    private static boolean initialized;
    private static String activeAdapter;
    private static List<ItemStack> memories = List.of();
    private static List<Boolean> lockedMemories = List.of();
    private static AnvilMemorySyncPacket lastResult;
    private static long resultUntil;
    private static int ipnRestockTicks = -1;
    private static int ipnContainerId = -1;
    private static String ipnAdapterId;

    private AnvilMemoryClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(AnvilMemoryClient.class);
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        AnvilMemoryClientAdapter adapter = AnvilMemoryClientAdapters.find(screen);
        if (adapter == null) return;
        activeAdapter = null;
        memories = List.of();
        lockedMemories = List.of();
        NetworkHandler.CHANNEL.sendToServer(new AnvilMemoryRequestPacket(
                AnvilMemoryRequestPacket.Action.SYNC, adapter.id(), 0));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClose(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> screen
                && AnvilMemoryClientAdapters.find(screen) != null) {
            activeAdapter = null; memories = List.of(); lockedMemories = List.of(); lastResult = null;
            clearIpnRestock();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        AnvilMemoryClientAdapter adapter = AnvilMemoryClientAdapters.find(screen);
        if (adapter == null || !adapter.id().equals(activeAdapter)) return;
        var panel = adapter.memoryPanel(screen);
        if (event.getButton() == 1) {
            for (int i = 0; i < memories.size(); i++) {
                var row = new AnvilMemoryClientAdapter.Bounds(panel.x() + 2, panel.y() + 2 + i * 20, 18, 18);
                if (row.contains(event.getMouseX(), event.getMouseY())) {
                    send(AnvilMemoryRequestPacket.Action.TOGGLE_LOCK, adapter.id(), i);
                    event.setCanceled(true); return;
                }
            }
            return;
        }
        if (event.getButton() != 0) return;
        if (adapter.swapButton(screen).contains(event.getMouseX(), event.getMouseY())) {
            send(AnvilMemoryRequestPacket.Action.SWAP, adapter.id(), 0);
            event.setCanceled(true); return;
        }
        if (ipnFastRenameEnabled() && adapter.resultSlot(screen).contains(
                event.getMouseX(), event.getMouseY())) {
            scheduleIpnRestock(screen, adapter);
        }
        if (adapter.resultSlot(screen).contains(event.getMouseX(), event.getMouseY())) {
            send(AnvilMemoryRequestPacket.Action.REMEMBER_RESULT, adapter.id(), 0);
        }
        for (int i = 0; i < memories.size(); i++) {
            var row = new AnvilMemoryClientAdapter.Bounds(panel.x() + 2, panel.y() + 2 + i * 20, 18, 18);
            if (row.contains(event.getMouseX(), event.getMouseY())) {
                send(AnvilMemoryRequestPacket.Action.RESTOCK, adapter.id(), i);
                event.setCanceled(true); return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        AnvilMemoryClientAdapter adapter = AnvilMemoryClientAdapters.find(screen);
        if (adapter == null || !adapter.id().equals(activeAdapter)) return;
        GuiGraphics graphics = event.getGuiGraphics();
        renderSwap(graphics, adapter.swapButton(screen), event.getMouseX(), event.getMouseY());
        renderMemories(graphics, adapter.memoryPanel(screen), event.getMouseX(), event.getMouseY());
        renderResult(graphics, screen);
    }

    public static void accept(AnvilMemorySyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return;
        AnvilMemoryClientAdapter adapter = AnvilMemoryClientAdapters.find(screen);
        if (adapter == null || !adapter.id().equals(packet.adapterId())) return;
        if (packet.status() == AnvilMemorySyncPacket.Status.INVALID) {
            activeAdapter = null; memories = List.of(); lockedMemories = List.of(); return;
        }
        activeAdapter = packet.adapterId();
        memories = packet.memories();
        lockedMemories = packet.locked();
        if (packet.status() != AnvilMemorySyncPacket.Status.SYNC) {
            lastResult = packet; resultUntil = System.currentTimeMillis() + 4500;
            if (packet.missingCount() > 0 && !packet.missingStack().isEmpty()) bookmark(packet.missingStack());
            if (minecraft.player != null) minecraft.player.playSound(
                    packet.status() == AnvilMemorySyncPacket.Status.COMPLETE
                            || packet.status() == AnvilMemorySyncPacket.Status.SWAPPED
                            ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.NOTE_BLOCK_BASS.value(),
                    0.8F, 1.0F);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ipnRestockTicks < 0) return;
        if (ipnRestockTicks-- > 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId == ipnContainerId) {
            AnvilMemoryClientAdapter adapter = AnvilMemoryClientAdapters.find(screen);
            if (adapter != null && adapter.id().equals(ipnAdapterId)) {
                send(AnvilMemoryRequestPacket.Action.IPN_RESTOCK, adapter.id(), 0);
            }
        }
        clearIpnRestock();
    }

    private static void scheduleIpnRestock(AbstractContainerScreen<?> screen,
                                           AnvilMemoryClientAdapter adapter) {
        if (memories.isEmpty()) return;
        ipnRestockTicks = 3;
        ipnContainerId = screen.getMenu().containerId;
        ipnAdapterId = adapter.id();
    }

    private static void clearIpnRestock() {
        ipnRestockTicks = -1;
        ipnContainerId = -1;
        ipnAdapterId = null;
    }

    private static boolean ipnFastRenameEnabled() {
        if (!ModList.get().isLoaded("inventoryprofilesnext")) {
            return false;
        }
        try {
            Class<?> settingsClass = Class.forName("org.anti_ad.mc.ipnext.config.GuiSettings");
            Object settings = settingsClass.getField("INSTANCE").get(null);
            Method getter = settingsClass.getMethod("getFAST_RENAME_SAVED_VALUE");
            Object option = getter.invoke(settings);
            Method valueGetter = option.getClass().getMethod("getBooleanValue");
            return (boolean) valueGetter.invoke(option);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static void renderSwap(GuiGraphics graphics, AnvilMemoryClientAdapter.Bounds bounds,
                                   double mouseX, double mouseY) {
        boolean hover = bounds.contains(mouseX, mouseY);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(),
                hover ? 0xE044555D : 0xC020282C);
        graphics.renderOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? 0xFF8BE9DD : 0xFF66757A);
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal("\u21c4"),
                bounds.x() + bounds.width() / 2, bounds.y() + 4, 0xFFFFFFFF);
        if (hover) graphics.renderTooltip(Minecraft.getInstance().font,
                Component.translatable("rsi.anvil_memory.swap"), (int) mouseX, (int) mouseY);
    }

    private static void renderMemories(GuiGraphics graphics, AnvilMemoryClientAdapter.Bounds panel,
                                       double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        graphics.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + panel.height(), 0xB0101518);
        graphics.renderOutline(panel.x(), panel.y(), panel.width(), panel.height(), 0xFF526166);
        for (int i = 0; i < AnvilMemoryData.LIMIT; i++) {
            int x = panel.x() + 2, y = panel.y() + 2 + i * 20;
            boolean hover = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
            graphics.fill(x, y, x + 18, y + 18, hover ? 0xD044555D : 0xB020282C);
            ResourceLocation icon = i >= memories.size() ? RESTOCK_DISABLED : hover ? RESTOCK_HOVER : RESTOCK;
            graphics.blit(icon, x + 1, y + 1, 0, 0, 16, 16, 16, 16);
            if (i < memories.size()) {
                ItemStack stack = memories.get(i);
                boolean locked = i < lockedMemories.size() && lockedMemories.get(i);
                graphics.renderItem(stack, x + 1, y + 1);
                if (locked) renderLock(graphics, x, y);
                if (hover) {
                    List<Component> tooltip = new ArrayList<>();
                    tooltip.add(stack.getHoverName());
                    tooltip.add(Component.translatable("rsi.anvil_memory.restock"));
                    tooltip.add(Component.translatable(locked
                            ? "rsi.anvil_memory.unlock" : "rsi.anvil_memory.lock"));
                    graphics.renderTooltip(minecraft.font, tooltip, stack.getTooltipImage(),
                            (int) mouseX, (int) mouseY);
                }
            }
        }
    }

    private static void renderLock(GuiGraphics graphics, int x, int y) {
        int lockX = x + 11, lockY = y + 1;
        graphics.fill(lockX + 1, lockY, lockX + 5, lockY + 4, 0xD0000000);
        graphics.fill(lockX, lockY + 3, lockX + 6, lockY + 9, 0xE0D7B34A);
        graphics.fill(lockX + 2, lockY + 5, lockX + 4, lockY + 7, 0xFF3A2B0A);
    }

    private static void renderResult(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        if (lastResult == null || System.currentTimeMillis() >= resultUntil) return;
        Component message = message(lastResult);
        int width = Math.max(180, Minecraft.getInstance().font.width(message) + 24);
        int x = (screen.width - width) / 2, y = 8;
        boolean ok = lastResult.status() == AnvilMemorySyncPacket.Status.COMPLETE
                || lastResult.status() == AnvilMemorySyncPacket.Status.SWAPPED;
        graphics.pose().pushPose(); graphics.pose().translate(0, 0, 1000);
        RenderSystem.disableDepthTest();
        graphics.fill(x - 1, y - 1, x + width + 1, y + 29, 0xFF000000);
        graphics.fill(x, y, x + width, y + 28, ok ? 0xE0185A35 : 0xE08A541F);
        graphics.fill(x, y, x + 4, y + 28, ok ? 0xFF55FF88 : 0xFFFFB04A);
        graphics.drawString(Minecraft.getInstance().font, message, x + 12, y + 10, 0xFFFFFFFF, true);
        RenderSystem.enableDepthTest(); graphics.pose().popPose();
    }

    private static Component message(AnvilMemorySyncPacket result) {
        return switch (result.status()) {
            case COMPLETE -> Component.translatable("rsi.anvil_memory.complete", result.inventoryCount(), result.rsCount());
            case PARTIAL -> Component.translatable("rsi.anvil_memory.partial", result.missingCount());
            case NO_NETWORK -> Component.translatable("rsi.anvil_memory.no_network", result.missingCount());
            case NO_PERMISSION -> Component.translatable("rsi.anvil_memory.no_permission", result.missingCount());
            case OCCUPIED -> Component.translatable("rsi.anvil_memory.occupied");
            case REJECTED -> Component.translatable("rsi.anvil_memory.rejected");
            case SWAPPED -> Component.translatable("rsi.anvil_memory.swapped");
            default -> Component.translatable("rsi.anvil_memory.invalid");
        };
    }

    private static void send(AnvilMemoryRequestPacket.Action action, String id, int index) {
        NetworkHandler.CHANNEL.sendToServer(new AnvilMemoryRequestPacket(action, id, index));
    }

    private static void bookmark(ItemStack stack) {
        RecipeBrowserBridge.addFavorite(stack);
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation(RSIntegrationMod.MOD_ID, "textures/gui/anvil_memory/" + name);
    }
}
