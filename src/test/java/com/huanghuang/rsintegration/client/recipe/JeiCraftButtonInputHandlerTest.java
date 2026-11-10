package com.huanghuang.rsintegration.client.recipe;

import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JeiCraftButtonInputHandlerTest {
    private final AtomicInteger crafts = new AtomicInteger();
    private final AtomicInteger machineOpens = new AtomicInteger();
    private final Screen recipesGui = mock(RecipesGui.class);

    @BeforeEach
    @AfterEach
    void clearButtons() {
        AltarCraftButtons.clear();
    }

    @Test
    void leftClickStartsCraftAndConsumesTheEventOnce() {
        addCraftButton();
        var event = click(recipesGui, 15, 25, 0);

        JeiCraftButtonInputHandler.onMousePressed(event);
        JeiCraftButtonInputHandler.onMousePressed(event);

        assertTrue(event.isCanceled());
        assertEquals(1, crafts.get());
    }

    @Test
    void machineButtonKeepsPriorityWhenBoundsOverlap() {
        addCraftButton();
        AltarCraftButtons.addMachineGui(10, 20, 10, 10, machineOpens::incrementAndGet);
        var event = click(recipesGui, 15, 25, 0);

        JeiCraftButtonInputHandler.onMousePressed(event);

        assertTrue(event.isCanceled());
        assertEquals(1, machineOpens.get());
        assertEquals(0, crafts.get());
    }

    @Test
    void otherScreensAndMouseButtonsKeepTheirInput() {
        addCraftButton();
        AltarCraftButtons.addMachineGui(10, 20, 10, 10, machineOpens::incrementAndGet);
        var otherScreen = click(mock(Screen.class), 15, 25, 0);
        var rightClick = click(recipesGui, 15, 25, 1);
        var outside = click(recipesGui, 20, 25, 0);

        for (var event : new ScreenEvent.MouseButtonPressed.Pre[]{otherScreen, rightClick, outside}) {
            JeiCraftButtonInputHandler.onMousePressed(event);
            assertFalse(event.isCanceled());
        }
        assertEquals(0, crafts.get());
        assertEquals(0, machineOpens.get());
    }

    @Test
    void clicksUseCurrentLayoutBounds() {
        addCraftButton();
        AltarCraftButtons.updateBounds(0, 40, 50, 10, 10);
        var oldLocation = click(recipesGui, 15, 25, 0);
        var newLocation = click(recipesGui, 45, 55, 0);

        JeiCraftButtonInputHandler.onMousePressed(oldLocation);
        JeiCraftButtonInputHandler.onMousePressed(newLocation);

        assertFalse(oldLocation.isCanceled());
        assertTrue(newLocation.isCanceled());
        assertEquals(1, crafts.get());
    }

    @Test
    void forgeRegistersTheListenerBeforeJeiConsumesMouseInput() throws Exception {
        // 读取字节码，避免反射注解时初始化仅在完整游戏内可用的 FML 事件总线。
        ClassNode node = new ClassNode();
        try (var input = JeiCraftButtonInputHandler.class.getResourceAsStream(
                "JeiCraftButtonInputHandler.class")) {
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
        }
        var subscriber = node.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.equals(
                        "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;"))
                .findFirst().orElseThrow();
        var distributions = (List<?>) annotationValue(subscriber, "value");
        assertEquals(1, distributions.size());
        assertEquals("CLIENT", ((String[]) distributions.get(0))[1]);
        Object bus = annotationValue(subscriber, "bus");
        assertTrue(bus == null || "FORGE".equals(((String[]) bus)[1]));
        assertEquals("rs_integration", annotationValue(subscriber, "modid"));
        var listener = JeiCraftButtonInputHandler.class
                .getMethod("onMousePressed", ScreenEvent.MouseButtonPressed.Pre.class)
                .getAnnotation(SubscribeEvent.class);
        assertEquals(EventPriority.HIGHEST, listener.priority());
        assertFalse(listener.receiveCanceled());
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }

    private void addCraftButton() {
        AltarCraftButtons.add(10, 20, 10, 10, crafts::incrementAndGet,
                "递归合成", null, null, BlockPos.ZERO, null);
    }

    private static ScreenEvent.MouseButtonPressed.Pre click(Screen screen, double x, double y, int button) {
        return new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, button) {
            // 单元测试不经过 Forge 的字节码转换器。
            @Override
            public boolean isCancelable() {
                return true;
            }
        };
    }
}
