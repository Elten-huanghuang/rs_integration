package com.huanghuang.rsintegration.machine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * 0.8 收藏条排布：BD 内部行数可扩展，窗口会变高变宽，收藏条必须据此换行换列。
 */
class BeyondDimensionsFavoriteLayoutTest {
    /** 宽度 23 + 间隔 4。 */
    private static final int STEP_X = 27;
    /** 高度 26 + 间隔 4。 */
    private static final int STEP_Y = 30;
    private static final int SLOT_WIDTH = 23;
    private static final int SLOT_HEIGHT = 26;

    @Test
    void favoritesFillAColumnBeforeStartingANewOne() {
        List<BeyondDimensionsMachineHubClient.FavoritePos> positions =
                BeyondDimensionsMachineHubClient.layoutFavoritePositions(screen(1280, 720), 20);
        assertEquals(20, positions.size());
        BeyondDimensionsMachineHubClient.FavoritePos first = positions.get(0);
        BeyondDimensionsMachineHubClient.FavoritePos lastOfFirstColumn = positions.get(14);
        assertEquals(first.x(), lastOfFirstColumn.x(), "同一列的 x 必须相同");
        assertEquals(lastOfFirstColumn.y(), first.y() + 14 * STEP_Y, "同一列按 30px 递进");
        BeyondDimensionsMachineHubClient.FavoritePos firstOfSecondColumn = positions.get(15);
        assertEquals(first.x() + STEP_X, firstOfSecondColumn.x(), "排满一列后向右扩列");
        assertEquals(first.y(), firstOfSecondColumn.y(), "新列从顶部重新开始");
    }

    @Test
    void tallerWindowKeepsFavoritesInOneColumn() {
        List<BeyondDimensionsMachineHubClient.FavoritePos> shortScreen =
                BeyondDimensionsMachineHubClient.layoutFavoritePositions(screen(1280, 720), 16);
        List<BeyondDimensionsMachineHubClient.FavoritePos> tallScreen =
                BeyondDimensionsMachineHubClient.layoutFavoritePositions(screen(1280, 1080), 16);
        int shortColumns = (int) shortScreen.stream().map(BeyondDimensionsMachineHubClient.FavoritePos::x)
                .distinct().count();
        int tallColumns = (int) tallScreen.stream().map(BeyondDimensionsMachineHubClient.FavoritePos::x)
                .distinct().count();
        assertEquals(2, shortColumns, "矮窗口放不下 16 个，需要两列");
        assertEquals(1, tallColumns, "高窗口一列就能放下");
    }

    @Test
    void stripAlwaysStaysInsideTheScreen() {
        for (int[] size : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}}) {
            Screen screen = screen(size[0], size[1]);
            List<BeyondDimensionsMachineHubClient.FavoritePos> positions =
                    BeyondDimensionsMachineHubClient.layoutFavoritePositions(screen, 12);
            for (BeyondDimensionsMachineHubClient.FavoritePos position : positions) {
                assertTrue(position.x() >= 0, "x 不能为负：" + position);
                assertTrue(position.y() >= 0, "y 不能为负：" + position);
                assertTrue(position.x() + SLOT_WIDTH <= screen.width,
                        "格子不能超出屏幕右边缘：" + position + " 屏幕宽 " + screen.width);
                assertTrue(position.y() + SLOT_HEIGHT <= screen.height,
                        "格子不能超出屏幕下边缘：" + position + " 屏幕高 " + screen.height);
            }
        }
    }

    @Test
    void emptyFavoritesProduceNoSlots() {
        assertTrue(BeyondDimensionsMachineHubClient.layoutFavoritePositions(screen(1280, 720), 0).isEmpty());
    }

    private static Screen screen(int width, int height) {
        Screen screen = new Screen(Component.literal("test")) {};
        screen.width = width;
        screen.height = height;
        return screen;
    }
}
