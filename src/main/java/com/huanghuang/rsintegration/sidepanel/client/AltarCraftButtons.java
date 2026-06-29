package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
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

    private static final List<int[]> POSITIONS = new ArrayList<>();
    private static final List<int[]> TRANSFER_POSITIONS = new ArrayList<>();
    private static final List<Runnable> HANDLERS = new ArrayList<>();
    private static final List<ResourceLocation> RECIPE_IDS = new ArrayList<>();
    private static final List<ResourceLocation> DIMS = new ArrayList<>();
    private static final List<BlockPos> MACHINE_POSES = new ArrayList<>();
    private static final List<ModType> MOD_TYPES = new ArrayList<>();
    private static final List<String> TOOLTIPS = new ArrayList<>();
    // Dedup: prevent duplicate plan requests for the same recipe within 1.5s
    private static final Map<ResourceLocation, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();

    // Machine GUI button — parallel to "+" button, opens bound machine directly
    private static final List<int[]> MACHINE_GUI_POSITIONS = new ArrayList<>();
    private static final List<Runnable> MACHINE_GUI_HANDLERS = new ArrayList<>();

    private AltarCraftButtons() {}

    public static void clear() {
        POSITIONS.clear();
        TRANSFER_POSITIONS.clear();
        HANDLERS.clear();
        RECIPE_IDS.clear();
        DIMS.clear();
        MACHINE_POSES.clear();
        MOD_TYPES.clear();
        TOOLTIPS.clear();
        MACHINE_GUI_POSITIONS.clear();
        MACHINE_GUI_HANDLERS.clear();
        LAST_REQUEST_MS.clear();
    }

    // ── Machine GUI button ──────────────────────────────────────────

    public static List<int[]> getMachineGuiPositions() { return MACHINE_GUI_POSITIONS; }

    public static void addMachineGui(int x, int y, int w, int h, Runnable handler) {
        MACHINE_GUI_POSITIONS.add(new int[]{x, y, w, h});
        MACHINE_GUI_HANDLERS.add(handler);
    }

    public static int hitTestMachineGui(double mouseX, double mouseY) {
        for (int i = MACHINE_GUI_POSITIONS.size() - 1; i >= 0; i--) {
            int[] pos = MACHINE_GUI_POSITIONS.get(i);
            if (mouseX >= pos[0] && mouseX < pos[0] + pos[2]
                    && mouseY >= pos[1] && mouseY < pos[1] + pos[3]) {
                return i;
            }
        }
        return -1;
    }

    public static void triggerMachineGui(int index) {
        if (index >= 0 && index < MACHINE_GUI_HANDLERS.size() && MACHINE_GUI_HANDLERS.get(index) != null) {
            MACHINE_GUI_HANDLERS.get(index).run();
        }
    }

    public static void add(int x, int y, int w, int h, Runnable handler, String tooltip,
                           ResourceLocation recipeId, @Nullable ResourceLocation dim,
                           BlockPos machinePos, ModType modType) {
        POSITIONS.add(new int[]{x, y, w, h});
        HANDLERS.add(handler);
        RECIPE_IDS.add(recipeId);
        DIMS.add(dim);
        MACHINE_POSES.add(machinePos);
        MOD_TYPES.add(modType);
        TOOLTIPS.add(tooltip);
    }

    public static List<int[]> getPositions() { return POSITIONS; }

    public static void addTransferPos(int x, int y, int w, int h) {
        TRANSFER_POSITIONS.add(new int[]{x, y, w, h});
    }

    public static void clearTransferPositions() {
        TRANSFER_POSITIONS.clear();
    }

    public static int hitTest(double mouseX, double mouseY) {
        for (int i = POSITIONS.size() - 1; i >= 0; i--) {
            int[] pos = POSITIONS.get(i);
            if (mouseX >= pos[0] && mouseX < pos[0] + pos[2]
                    && mouseY >= pos[1] && mouseY < pos[1] + pos[3]) {
                return i;
            }
        }
        return -1;
    }

    public static int hitTestTransfer(double mouseX, double mouseY) {
        for (int i = TRANSFER_POSITIONS.size() - 1; i >= 0; i--) {
            int[] pos = TRANSFER_POSITIONS.get(i);
            if (mouseX >= pos[0] && mouseX < pos[0] + pos[2]
                    && mouseY >= pos[1] && mouseY < pos[1] + pos[3]) {
                return i;
            }
        }
        return -1;
    }

    public static void triggerClick(int index) {
        if (index >= 0 && index < HANDLERS.size() && HANDLERS.get(index) != null) {
            // Dedup: skip if same recipe was requested within 1.5s
            if (index < RECIPE_IDS.size()) {
                ResourceLocation rid = RECIPE_IDS.get(index);
                long now = Util.getMillis();
                Long last = LAST_REQUEST_MS.get(rid);
                if (last != null && now - last < 1500L) {
                    RSIntegrationMod.LOGGER.debug("[RSI-AltarBtn] Dedup: skipped {} ({}ms since last request)",
                            rid, now - last);
                    return;
                }
                LAST_REQUEST_MS.put(rid, now);
            }
            try {
                HANDLERS.get(index).run();
            } catch (Exception ex) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-AltarBtn] Handler for index {} threw:", index, ex);
            }
        }
    }

    @Nullable
    public static CraftButtonData getButtonData(int index) {
        if (index < 0 || index >= RECIPE_IDS.size()) return null;
        return new CraftButtonData(
                RECIPE_IDS.get(index),
                DIMS.get(index),
                MACHINE_POSES.get(index),
                MOD_TYPES.get(index),
                TOOLTIPS.get(index)
        );
    }

    public record CraftButtonData(
            ResourceLocation recipeId,
            @Nullable ResourceLocation dim,
            BlockPos machinePos,
            ModType modType,
            String tooltip
    ) {}
}
