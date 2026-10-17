package com.huanghuang.rsintegration.mods.farmandcharm.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Let's Do 原生配方书下方的 RSI 计划按钮布局与状态同步。 */
public final class NativeRecipeBookButton {
    private static final int BUTTON_WIDTH = 112;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_LEFT_SHIFT = 6;

    private NativeRecipeBookButton() {}

    public static Button create(Screen screen, Runnable action) {
        Button button = Button.builder(
                        Component.translatable("gui.rs_integration.native_recipe_book_craft"),
                        ignored -> action.run())
                .bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        update(screen, button);
        return button;
    }

    /** 按钮跟随配方书开关；配方书隐藏时按钮和鼠标命中区域一起隐藏。 */
    public static void update(Screen screen, Button button) {
        Object recipeBook = readField(screen, "recipeBook");
        boolean open = recipeBook != null && invokeBoolean(recipeBook, "isOpen");
        button.visible = open;
        button.active = open;
        if (!open) return;

        int imageHeight = intField(screen, "f_97738_", intField(screen, "imageHeight", 166));
        int guiTop = intField(screen, "f_97736_", intField(screen, "topPos",
                (screen.height - imageHeight) / 2));
        int recipeBookLeft = invokeInt(recipeBook, "findLeftEdge", screen.width, screen.height);
        Layout layout = layout(screen.width, screen.height, guiTop, imageHeight, recipeBookLeft);
        button.setX(layout.x());
        button.setY(layout.y());
    }

    static Layout layout(int screenWidth, int screenHeight, int guiTop,
                         int imageHeight, int recipeBookLeft) {
        int preferredX = recipeBookLeft + (147 - BUTTON_WIDTH) / 2 - BUTTON_LEFT_SHIFT;
        int x = Math.max(2, Math.min(screenWidth - BUTTON_WIDTH - 2, preferredX));
        int y = guiTop + imageHeight + BUTTON_GAP;
        return new Layout(x, y);
    }

    public static Object recipeBook(Screen screen) {
        return readField(screen, "recipeBook");
    }

    public static void addToScreen(Screen screen, Button button) {
        for (Method method : Screen.class.getDeclaredMethods()) {
            if (method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isAssignableFrom(button.getClass())) continue;
            String name = method.getName();
            if (!"addRenderableWidget".equals(name) && !"m_142416_".equals(name)) continue;
            try {
                method.setAccessible(true);
                method.invoke(screen, button);
            } catch (ReflectiveOperationException ignored) {
                // 可选按钮不应因映射变化破坏原生界面。
            }
            return;
        }
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                // 继续查找父类或映射字段。
            }
        }
        return null;
    }

    private static int intField(Object target, String name, int fallback) {
        Object value = readField(target, name);
        return value instanceof Integer integer ? integer : fallback;
    }

    private static boolean invokeBoolean(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static int invokeInt(Object target, String name, int width, int height) {
        try {
            Method method = target.getClass().getMethod(name, int.class, int.class);
            Object value = method.invoke(target, width, height);
            return value instanceof Integer integer ? integer : width / 2 - 90;
        } catch (ReflectiveOperationException ignored) {
            return width / 2 - 90;
        }
    }

    record Layout(int x, int y) {}
}
