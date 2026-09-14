package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;

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

    private static final List<int[]> POSITIONS = new ArrayList<>();
    private static final List<int[]> TRANSFER_POSITIONS = new ArrayList<>();
    private static final List<Runnable> HANDLERS = new ArrayList<>();
    private static final List<ResourceLocation> RECIPE_IDS = new ArrayList<>();
    private static final List<ResourceLocation> DIMS = new ArrayList<>();
    private static final List<BlockPos> MACHINE_POSES = new ArrayList<>();
    private static final List<ModType> MOD_TYPES = new ArrayList<>();
    private static final List<String> TOOLTIPS = new ArrayList<>();
    // Dedup only the same click burst. A long planning operation must remain
    // retryable, otherwise the button appears dead while the first request is
    // still being calculated.
    private static final long CLICK_DEDUP_MS = 250L;
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
            if (!isVisible(i)) continue;
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
        if (index >= 0 && index < HANDLERS.size() && HANDLERS.get(index) != null
                && isVisible(index)) {
            // Dedup only an accidental double click, while keeping retries
            // available when the first request is slow or rejected.
            if (index < RECIPE_IDS.size()) {
                ResourceLocation rid = RECIPE_IDS.get(index);
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

    public static boolean isVisible(int index) {
        if (index < 0 || index >= MOD_TYPES.size()) return false;
        return isVisible(RECIPE_IDS.get(index), MOD_TYPES.get(index));
    }

    public static boolean isVisible(ResourceLocation recipeId, @Nullable ModType type) {
        if (type == null) return true;
        if ("lychee_item_inside_virtual".equals(type.id())) {
            int required = com.huanghuang.rsintegration.mods.lychee.LycheeVirtualRecipeHandler
                    .requiredCatalystMask(recipeId);
            return required != 0 && com.huanghuang.rsintegration.resonance.bridge.ClientDiskData
                    .hasLycheeCatalyst(required);
        }
        if (com.huanghuang.rsintegration.mods.immortalersdelight
                .ImmortalersDelightRSModule.HOT_SPRING_TYPE_ID.equals(type.id())) {
            return com.huanghuang.rsintegration.resonance.bridge.ClientDiskData.hasCatalyst(
                    com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts
                            .HOT_SPRING_BUCKET);
        }
        if (com.huanghuang.rsintegration.mods.malum.MalumRSModule.VOID_FAVOR_TYPE_ID
                .equals(type.id())) {
            return com.huanghuang.rsintegration.resonance.bridge.ClientDiskData.hasAbility(
                    com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilities
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
}
