package com.huanghuang.rsintegration.client.recipe;

import com.huanghuang.rsintegration.mods.immortalersdelight.ImmortalersDelightRSModule;


import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualRecipeHandler;
import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import com.huanghuang.rsintegration.feature.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.feature.resonance.disk.ResonanceDiskAbilities;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Manages JEI "+" button positions and click handlers for crafting recipes. */
@OnlyIn(Dist.CLIENT)
public final class AltarCraftButtons {

    private static final List<CraftButton> BUTTONS = new ArrayList<>();
    private static final List<ButtonBounds> TRANSFER_POSITIONS = new ArrayList<>();
    // Dedup only the same click burst. A long planning operation must remain
    // retryable, otherwise the button appears dead while the first request is
    // still being calculated.
    private static final long CLICK_DEDUP_MS = 250L;
    private static final Map<ResourceLocation, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();

    // Machine GUI button — parallel to "+" button, opens bound machine directly
    private static final List<MachineGuiButton> MACHINE_GUI_BUTTONS =
            new ArrayList<>();

    private AltarCraftButtons() {}

    public static void clear() {
        BUTTONS.clear();
        TRANSFER_POSITIONS.clear();
        MACHINE_GUI_BUTTONS.clear();
        LAST_REQUEST_MS.clear();
    }

    // ── Machine GUI button ──────────────────────────────────────────

    public static int getMachineGuiCount() {
        return MACHINE_GUI_BUTTONS.size();
    }

    public static ButtonBounds getMachineGuiBounds(int index) {
        return MACHINE_GUI_BUTTONS.get(index).bounds();
    }

    public static void updateMachineGuiBounds(
            int index, int x, int y, int width, int height) {
        if (index < 0 || index >= MACHINE_GUI_BUTTONS.size()) return;
        MachineGuiButton button = MACHINE_GUI_BUTTONS.get(index);
        MACHINE_GUI_BUTTONS.set(index, new MachineGuiButton(
                new ButtonBounds(x, y, width, height), button.handler()));
    }

    public static void addMachineGui(int x, int y, int w, int h, Runnable handler) {
        MACHINE_GUI_BUTTONS.add(new MachineGuiButton(
                new ButtonBounds(x, y, w, h), handler));
    }

    public static int hitTestMachineGui(double mouseX, double mouseY) {
        for (int index = MACHINE_GUI_BUTTONS.size() - 1;
             index >= 0; index--) {
            if (MACHINE_GUI_BUTTONS.get(index).bounds()
                    .contains(mouseX, mouseY)) return index;
        }
        return -1;
    }

    public static void triggerMachineGui(int index) {
        if (index >= 0 && index < MACHINE_GUI_BUTTONS.size()) {
            Runnable handler = MACHINE_GUI_BUTTONS.get(index).handler();
            if (handler != null) handler.run();
        }
    }

    public static void add(int x, int y, int w, int h, Runnable handler, String tooltip,
                           ResourceLocation recipeId, @Nullable ResourceLocation dim,
                           BlockPos machinePos, ModType modType) {
        BUTTONS.add(new CraftButton(
                new ButtonBounds(x, y, w, h), handler, tooltip,
                recipeId, dim, machinePos, modType));
    }

    public static int getButtonCount() { return BUTTONS.size(); }

    public static ButtonBounds getBounds(int index) {
        return BUTTONS.get(index).bounds();
    }

    public static void updateBounds(
            int index, int x, int y, int width, int height) {
        if (index < 0 || index >= BUTTONS.size()) return;
        BUTTONS.set(index, BUTTONS.get(index).withBounds(
                new ButtonBounds(x, y, width, height)));
    }

    public static void setRecipeId(int index, ResourceLocation recipeId) {
        if (index >= 0 && index < BUTTONS.size() && recipeId != null) {
            BUTTONS.set(index, BUTTONS.get(index).withRecipeId(recipeId));
        }
    }

    public static void addTransferPos(int x, int y, int w, int h) {
        TRANSFER_POSITIONS.add(new ButtonBounds(x, y, w, h));
    }

    public static void clearTransferPositions() {
        TRANSFER_POSITIONS.clear();
    }

    public static int hitTest(double mouseX, double mouseY) {
        for (int index = BUTTONS.size() - 1; index >= 0; index--) {
            if (isVisible(index)
                    && BUTTONS.get(index).bounds()
                    .contains(mouseX, mouseY)) return index;
        }
        return -1;
    }

    public static int hitTestTransfer(double mouseX, double mouseY) {
        for (int index = TRANSFER_POSITIONS.size() - 1;
             index >= 0; index--) {
            if (TRANSFER_POSITIONS.get(index)
                    .contains(mouseX, mouseY)) return index;
        }
        return -1;
    }

    public static void triggerClick(int index) {
        if (index >= 0 && index < BUTTONS.size()
                && isVisible(index)) {
            CraftButton button = BUTTONS.get(index);
            if (button.handler() == null) return;
            // Dedup only an accidental double click, while keeping retries
            // available when the first request is slow or rejected.
            if (button.recipeId() != null) {
                ResourceLocation rid = button.recipeId();
                long now = Util.getMillis();
                Long last = LAST_REQUEST_MS.get(rid);
                if (last != null && now - last < CLICK_DEDUP_MS) {
                    RSIntegrationMod.LOGGER.debug("[RSI-AltarBtn] Dedup: skipped {} ({}ms since last request)",
                            rid, now - last);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.translatable("rsi.plan.failure.request_pending"), true);
                    }
                    return;
                }
                LAST_REQUEST_MS.put(rid, now);
            }
            try {
                button.handler().run();
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-AltarBtn] Handler for index {} threw:", index, ex);
            }
        }
    }

    @Nullable
    public static CraftButtonData getButtonData(int index) {
        if (index < 0 || index >= BUTTONS.size()) return null;
        CraftButton button = BUTTONS.get(index);
        return new CraftButtonData(
                button.recipeId(), button.dim(), button.machinePos(),
                button.modType(), button.tooltip()
        );
    }

    public static boolean isVisible(int index) {
        if (index < 0 || index >= BUTTONS.size()) return false;
        CraftButton button = BUTTONS.get(index);
        return isVisible(button.recipeId(), button.modType());
    }

    public static boolean isVisible(ResourceLocation recipeId, @Nullable ModType type) {
        if (type == null) return true;
        if ("lychee_item_inside_virtual".equals(type.id())) {
            int required = LycheeVirtualRecipeHandler
                    .requiredCatalystMask(recipeId);
            return required != 0 && ClientDiskData
                    .hasLycheeCatalyst(required);
        }
        if (ImmortalersDelightRSModule.HOT_SPRING_TYPE_ID.equals(type.id())) {
            return ClientDiskData.hasCatalyst(
                    LycheeVirtualCatalysts
                            .HOT_SPRING_BUCKET);
        }
        if (MalumRSModule.VOID_FAVOR_TYPE_ID
                .equals(type.id())) {
            return ClientDiskData.hasAbility(
                    ResonanceDiskAbilities
                            .MALUM_VOID_FAVOR);
        }
        return true;
    }

    public record CraftButtonData(
            ResourceLocation recipeId,
            @Nullable ResourceLocation dim,
            BlockPos machinePos,
            ModType modType,
            String tooltip
    ) {}

    public record ButtonBounds(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + height;
        }
    }

    private record CraftButton(
            ButtonBounds bounds, Runnable handler, String tooltip,
            ResourceLocation recipeId, @Nullable ResourceLocation dim,
            BlockPos machinePos, ModType modType) {
        CraftButton withRecipeId(ResourceLocation recipeId) {
            return new CraftButton(
                    bounds, handler, tooltip, recipeId,
                    dim, machinePos, modType);
        }

        CraftButton withBounds(ButtonBounds bounds) {
            return new CraftButton(
                    bounds, handler, tooltip, recipeId,
                    dim, machinePos, modType);
        }
    }

    private record MachineGuiButton(ButtonBounds bounds, Runnable handler) {}
}
