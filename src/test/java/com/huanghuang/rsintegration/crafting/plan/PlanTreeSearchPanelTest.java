package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanTreeSearchPanelTest extends BootstrapTest {
    private final Font font = mock(Font.class);
    private final GuiGraphics graphics = mock(GuiGraphics.class);
    private final AtomicReference<PlanTreeNode> located = new AtomicReference<>();

    private PlanTreeSearchPanel panel(PlanTreeNode root) {
        when(graphics.pose()).thenReturn(new PoseStack());
        when(font.width(anyString())).thenAnswer(call -> call.<String>getArgument(0).length() * 6);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        PlanTreeSearchPanel panel = new PlanTreeSearchPanel(font, located::set);
        panel.update(root);
        return panel;
    }

    @Test
    void duplicateItemsRemainSeparateAndKeyboardCanChooseCollapsedDescendants() {
        PlanTreeNode root = node(Items.STICK, "木棍"), first = node(Items.OAK_PLANKS, "橡木木板"),
                second = node(Items.OAK_PLANKS, "橡木木板");
        root.children.add(first);
        first.children.add(second);
        first.expanded = false;
        PlanTreeSearchPanel panel = panel(root);
        panel.search("minecraft:oak_planks");
        render(panel, 960, 540);
        assertTrue(panel.isOpen());
        assertNull(located.get(), "输入查询不能直接移动树");
        panel.keyPressed(GLFW.GLFW_KEY_DOWN);
        panel.keyPressed(GLFW.GLFW_KEY_ENTER);
        assertSame(second, located.get(), "同种材料在不同分支位置必须能分别定位");
        assertFalse(panel.isOpen());
        panel.search("xmmb");
        render(panel, 960, 540);
        panel.keyPressed(GLFW.GLFW_KEY_ENTER);
        assertSame(first, located.get(), "拼音查询仍支持定位");
    }

    @Test
    void dropdownScrollAndMouseSelectionStayInsideEverySupportedFrame() throws Exception {
        for (int[] size : new int[][]{{320, 240}, {427, 240}, {960, 540}}) {
            PlanTreeNode root = node(Items.STICK, "木棍");
            for (int i = 0; i < 20; i++) root.children.add(node(Items.OAK_PLANKS, "木板 " + i));
            PlanTreeSearchPanel panel = panel(root);
            panel.search("oak_planks");
            render(panel, size[0], size[1]);
            Rect bounds = bounds(panel), frame = PlanWorkspaceLayout.create(size[0], size[1], true, 290).frame();
            assertTrue(bounds.x() >= frame.x() && bounds.right() <= frame.right());
            assertTrue(bounds.y() >= frame.y() && bounds.bottom() <= frame.bottom());
            assertTrue(panel.scroll(bounds.x() + 20, bounds.y() + 30, -1));
            assertTrue(panel.click(bounds.x() + 20, bounds.y() + 30, 0));
            assertSame(root.children.get(1), located.get());
            panel.show();
            render(panel, size[0], size[1]);
            assertFalse(panel.click(0, 0, 0));
            panel.close();
            assertFalse(panel.click(bounds.x() + 20, bounds.y() + 30, 0), "关闭后旧点击区失效");
        }
    }

    @Test
    void emptyQueryNoMatchAndNewPlanCannotChooseAnOldNode() throws Exception {
        PlanTreeSearchPanel panel = panel(node(Items.OAK_PLANKS, "木板"));
        panel.search("oak_planks");
        render(panel, 960, 540);
        Rect old = bounds(panel);
        PlanTreeNode replacement = node(Items.BIRCH_PLANKS, "白桦木板");
        panel.update(replacement);
        assertFalse(panel.click(old.x() + 20, old.y() + 30, 0));
        panel.keyPressed(GLFW.GLFW_KEY_ENTER);
        assertNull(located.get());
        panel.search("does-not-exist");
        panel.keyPressed(GLFW.GLFW_KEY_ENTER);
        assertNull(located.get());
        panel.search("");
        assertFalse(panel.isOpen());
        panel.search("minecraft:birch_planks");
        panel.close();
        panel.keyPressed(GLFW.GLFW_KEY_ENTER);
        assertSame(replacement, located.get());
    }

    private void render(PlanTreeSearchPanel panel, int width, int height) {
        PlanWorkspaceLayout layout = PlanWorkspaceLayout.create(width, height, true, 290);
        panel.render(graphics, new Rect(layout.toolbar().x() + 156, layout.toolbar().y(), 60, 20),
                layout.frame(), -1, -1);
    }

    private static Rect bounds(PlanTreeSearchPanel panel) throws Exception {
        Field field = PlanTreeSearchPanel.class.getDeclaredField("bounds");
        field.setAccessible(true);
        return (Rect) field.get(panel);
    }

    private static PlanTreeNode node(Item item, String name) {
        ItemStack stack = new ItemStack(item).setHoverName(Component.literal(name));
        return new PlanTreeNode(IngredientKey.of(stack), stack, 1, 0, null);
    }
}
