package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.batch.BatchQuantityScreen;
import com.huanghuang.rsintegration.ModType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
            HANDLERS.get(index).run();
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

    public static void openBatchScreen(int index) {
        CraftButtonData data = getButtonData(index);
        if (data == null) return;
        Minecraft.getInstance().setScreen(new BatchQuantityScreen(
                data.recipeId, data.dim, data.machinePos, data.modType, data.tooltip));
    }

    public record CraftButtonData(
            ResourceLocation recipeId,
            @Nullable ResourceLocation dim,
            BlockPos machinePos,
            ModType modType,
            String tooltip
    ) {}
}
