package com.huanghuang.rsintegration.crafting.plan;

import com.google.gson.Gson;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.JeiSubtreeBuilder;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeLayout;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.fml.ModList;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 捕获生产 Screen/组件的真实绘制命令，供 Python 回放；不是重新手绘一套示意布局。 */
class PlanWorkspaceRenderTest extends BootstrapTest {
    @TempDir Path directory;

    @Test
    void productionWorkspaceRendersAndRoutesNavigationWithoutSendingCraftRequests() throws Exception {
        Font font = mock(Font.class);
        set(font, "lineHeight", 9);
        when(font.width(anyString())).thenAnswer(call -> measure(call.getArgument(0)));
        when(font.width(any(FormattedText.class))).thenAnswer(call -> measure(call.<FormattedText>getArgument(0).getString()));
        when(font.width(any(FormattedCharSequence.class))).thenAnswer(call -> measure(sequence(call.getArgument(0))));
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> trim(call.getArgument(0), call.getArgument(1)));
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean())).thenAnswer(call -> trim(call.getArgument(0), call.getArgument(1)));
        Minecraft game = mock(Minecraft.class);
        when(game.getNarrator()).thenReturn(mock(GameNarrator.class));
        set(game, "font", font);
        CraftingPlanScreen.RequestHandler requests = mock(CraftingPlanScreen.RequestHandler.class);
        Language previous = Language.getInstance();
        Language language = mock(Language.class);
        Map<String, String> strings;
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/assets/rs_integration/lang/zh_cn.json"))) {
            strings = new Gson().fromJson(reader, Map.class);
        }
        when(language.getOrDefault(anyString(), anyString())).thenAnswer(call -> strings.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(language.getOrDefault(anyString())).thenAnswer(call -> strings.getOrDefault(call.getArgument(0), call.getArgument(0)));
        when(language.getVisualOrder(any(FormattedText.class))).thenAnswer(call -> FormattedCharSequence.forward(
                call.<FormattedText>getArgument(0).getString(), Style.EMPTY));
        Language.inject(language);
        RSIntegrationConfig.SERVER_SPEC.setConfig(CommentedConfig.inMemory());
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        RSIntegrationConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory());
        RSIntegrationConfig.DIAGNOSTIC_VERBOSE_LOGGING.set(false);
        try (var games = mockStatic(Minecraft.class); var mods = mockStatic(ModList.class);
             var translations = mockStatic(I18n.class);
             var enrichment = mockStatic(JeiSubtreeBuilder.class); var paths = mockStatic(ClientPlanningPreferences.class)) {
            translations.when(() -> I18n.get(anyString(), any(Object[].class))).thenAnswer(call -> {
                Object[] raw = call.getRawArguments();
                return String.format(Locale.ROOT, strings.getOrDefault((String) raw[0], (String) raw[0]), (Object[]) raw[1]);
            });
            games.when(Minecraft::getInstance).thenReturn(game);
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            paths.when(ClientPlanningPreferences::preferencesPath).thenReturn(directory.resolve("preferences.json"));
            PlanStep rootStep = new PlanStep(id("pickaxe"), stack(Items.IRON_PICKAXE, "铁镐"), 1,
                    List.of(stack(Items.IRON_INGOT, "铁锭"), stack(Items.STICK, "木棍")), List.of(id("alternate")), null, 0, true);
            PlanStep stickStep = new PlanStep(id("sticks"), stack(Items.STICK, "木棍"), 4,
                    List.of(stack(Items.OAK_PLANKS, "橡木木板")), List.of(), null, 1, true);
            Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
            materials.put(IngredientKey.of(rootStep.inputs().get(0)), new PlanResponse.Availability(3, 1));
            materials.put(IngredientKey.of(rootStep.inputs().get(1)), new PlanResponse.Availability(2, 0, 0));
            materials.put(IngredientKey.of(stickStep.inputs().get(0)), new PlanResponse.Availability(1, 4));
            PlanResponse plan = new PlanResponse(false, "铁镐", rootStep.output(), List.of(rootStep, stickStep), materials,
                    List.of(), rootStep.recipeId().toString(), null, null, 0, 0, 0,
                    List.of(Component.literal("需要先解锁研究：金属加工")), 1,
                    null, null, null, 0, false, false, false, null, Set.of(),
                    Map.of(IngredientKey.of(new ItemStack(Items.DIAMOND)), 2L,
                            IngredientKey.of(new ItemStack(Items.EMERALD)), 3L,
                            IngredientKey.of(rootStep.output()), 1L), null, null);
            RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
            when(preview.candidateDetails(any(), any())).thenReturn(new RecipePreviewRenderer.CandidateDetails("工作台配方", "3 × 3"));
            when(preview.categoryTitle(any())).thenReturn(Optional.of(Component.literal("工作台")));
            when(preview.previewBounds(any())).thenReturn(new RecipePreviewRenderer.PreviewBounds(100, 45));
            when(preview.renderRecipeInArea(any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
                    .thenAnswer(call -> {
                        GuiGraphics g = call.getArgument(0);
                        int x = call.getArgument(3), y = call.getArgument(4);
                        g.fill(x, y, x + 100, y + 45, 0xFF101E17);
                        g.drawString(font, "JEI 配方预览区", x + 5, y + 16, 0xFFA6BAAE, false);
                        return ItemStack.EMPTY;
                    });
            for (int[] size : new int[][]{{960, 540}, {640, 336}, {427, 240}}) {
                CraftingPlanScreen screen = new CraftingPlanScreen(plan, 1, requests, () -> {});
                set(screen, "recipePreview", preview);
                screen.init(game, size[0], size[1]);
                Capture capture = new Capture();
                screen.render(capture.graphics, -10, -10, 0);
                // 通过生产工具栏切换材料页、缺料页；检查查看行为不会发起试算。
                write(capture, size, "01-tree");
                checkScaledBounds(screen, capture);
                PlanDetailsPanel panel = field(screen, "detailsPanel");
                panel.showIssues(); capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                write(capture, size, "02-shortages");
                panel.showConditions(); capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                write(capture, size, "03-conditions");
                panel.showSteps(); capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                write(capture, size, "04-steps");
                panel.open(((PlanTreeModel) field(screen, "treeModel")).root);
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                write(capture, size, "11-step-current-recipe");
                if (size[0] < 720) {
                    for (int index = 0; index < 10; index++) {
                        PlanWorkspaceLayout small = field(screen, "workspace");
                        panel.mouseScrolled(small.inspector().x() + 40, small.inspector().y() + 100, -1);
                        capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                    }
                    write(capture, size, "12-step-current-recipe-scrolled");
                }
                PlanWorkspaceLayout layout = field(screen, "workspace");
                if (size[0] >= 720) {
                    panel.mouseClicked(layout.inspector().right() - 40, layout.inspector().y() + 130, 0);
                    capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                    verify(preview, atLeastOnce()).renderRecipeInArea(any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
                    write(capture, size, "05-recipes");
                    PlanTreeNode material = ((PlanTreeModel) field(screen, "treeModel")).findByKey(
                            IngredientKey.of(stickStep.inputs().get(0)));
                    material.materialLockKey = "test:sticks#planks";
                    material.materialOptions = List.of(stickStep.inputs().get(0), stack(Items.BIRCH_PLANKS, "白桦木板"));
                    panel.open(material);
                    capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                    panel.mouseClicked(layout.inspector().x() + 40, layout.inspector().y() + 130, 0);
                    capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                    write(capture, size, "07-variants");
                }
                double oldPanX = field(screen, "treePanX"), oldPanY = field(screen, "treePanY"), oldZoom = field(screen, "treeZoom");
                screen.mouseClicked(layout.toolbar().x() + 90, layout.toolbar().y() + 9, 0);
                assertTrue((Boolean) field(screen, "materialView"));
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                write(capture, size, "06-materials");
                screen.mouseClicked(layout.toolbar().x() + 30, layout.toolbar().y() + 9, 0);
                assertFalse((Boolean) field(screen, "materialView"));
                assertEquals(oldPanX, (Double) field(screen, "treePanX"));
                assertEquals(oldPanY, (Double) field(screen, "treePanY"));
                assertEquals(oldZoom, (Double) field(screen, "treeZoom"));
                set(screen, "quantityMenuOpen", true);
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                write(capture, size, "08-quantity");
                assertTrue(screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                assertFalse((Boolean) field(screen, "quantityMenuOpen"));
                assertEquals(0, capture.scissors.size(), "所有裁剪均应成对恢复");
                assertFalse(((PlanResponseRequestState) field(screen, "responseRequests")).waitingForPreview());
                verifyNoInteractions(requests);
                checkSearchAndHeader(screen, capture, size, requests);
                panel.showConditions();
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                layout = field(screen, "workspace");
                screen.mouseClicked(layout.inspector().x() + 25, layout.inspector().bottom() - 12, 0);
                long request = screen.activeRequestId();
                verify(requests).request(eq(true), eq(request), anyMap(), anyMap(), any());
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                assertTrue(capture.hasText("正在重新运算"));
                write(capture, size, "09-recalculating");
                screen.mouseClicked(layout.inspector().x() + 25, layout.inspector().bottom() - 12, 0);
                verifyNoMoreInteractions(requests);
                PlanningProgressTracker.start(request, rootStep.recipeId());
                PlanningProgressTracker.update(new PlanningProgressSnapshot(request, 1, rootStep.recipeId(),
                        PlanningProgressSnapshot.State.FAILED, PlanningProgressSnapshot.Phase.SEARCH, 500,
                        Component.literal("示例：计算失败原因")));
                screen.tick();
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                assertTrue(capture.hasText("示例：计算失败"));
                assertFalse(((PlanResponseRequestState) field(screen, "responseRequests")).calculating());
                write(capture, size, "10-recalculation-failed");
                screen.mouseClicked(layout.inspector().x() + 25, layout.inspector().bottom() - 12, 0);
                assertNotEquals(request, screen.activeRequestId(), "失败后应允许点击重新检查再次运算");
                assertTrue(screen.acceptResponse(screen.activeRequestId(), plan));
                capture.clear(); screen.render(capture.graphics, -10, -10, 0);
                assertTrue(capture.hasText("重新运算完成"));
                clearInvocations(requests);
                PlanningProgressTracker.clear();
            }
            CraftingPlanScreen nativeScreen = new CraftingPlanScreen(plan, 1);
            set(nativeScreen, "recipePreview", preview);
            nativeScreen.init(game, 640, 336);
            Capture nativeCapture = new Capture();
            nativeScreen.render(nativeCapture.graphics, -10, -10, 0);
            checkScaledBounds(nativeScreen, nativeCapture);
            write(nativeCapture, new int[]{640, 336}, "16-scale-3x-footer");
            verifyNoInteractions(requests);
        } finally {
            Language.inject(previous);
            RSIntegrationConfig.SERVER_SPEC.setConfig(null);
            RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
            RSIntegrationConfig.COMMON_SPEC.setConfig(null);
        }
    }

    private static void checkScaledBounds(CraftingPlanScreen screen, Capture capture) throws Exception {
        PlanWorkspaceLayout layout = field(screen, "workspace");
        for (String name : List.of("confirmButton", "cancelButton")) {
            Button button = field(screen, name);
            PlanWorkspaceLayout.Rect target = name.equals("confirmButton") ? layout.confirm() : layout.cancel();
            assertEquals(target.height(), button.getHeight(), "按钮实际高度应随 GUI 布局缩小");
            assertEquals(target.bottom(), button.getY() + button.getHeight());
            assertTrue(button.getY() + button.getHeight() <= layout.frame().bottom());
        }
        for (Map<String, Object> command : capture.commands) {
            if (!"item".equals(command.get("type"))) continue;
            float[] point = (float[]) command.get("point");
            if (point[1] >= layout.summary().y() && point[1] < layout.summary().bottom()) {
                double bottom = point[1] + 16 * ((Number) command.get("scale")).doubleValue();
                assertTrue(bottom <= layout.summary().bottom(), "3× 布局的余产物图标不能溢出材料摘要");
            }
        }
        for (Object hit : (List<?>) field(screen, "costHits")) {
            int y = field(hit, "y"), h = field(hit, "h");
            assertTrue(y >= layout.summary().y() && y + h <= layout.summary().bottom(),
                    "材料/余产物命中区域应与实际摘要范围一致");
        }
        if (layout.tree().height() >= 122) {
            assertTrue(capture.commands.stream().anyMatch(command -> "整树导航".equals(command.get("text"))
                    && ((Number) command.get("z")).floatValue() > 150), "导航背景和文字必须盖住主树的物品层");
        }
    }

    private static void checkSearchAndHeader(CraftingPlanScreen screen, Capture capture, int[] size,
                                            CraftingPlanScreen.RequestHandler requests) throws Exception {
        PlanDetailsPanel panel = field(screen, "detailsPanel");
        panel.close();
        capture.clear(); screen.render(capture.graphics, -10, -10, 0);
        assertFalse(capture.hasText("查看缺料"));
        assertFalse(capture.hasText("查看条件提示"));
        String shortages = "缺少 " + panel.shortageCount() + " 种材料";
        String conditions = panel.conditionCount() + " 条条件提示";
        for (String label : List.of(shortages, conditions)) {
            float[] point = capture.textPoint(label);
            assertNotNull(point, "数量与查看入口应合并为可点击提示：" + label);
            screen.mouseClicked(point[0] + 2, point[1] + 2, 0);
            assertTrue(panel.isOpen());
            assertEquals(label.equals(conditions), (Boolean) field(panel, "conditions"));
            panel.close();
            capture.clear(); screen.render(capture.graphics, -10, -10, 0);
        }
        List<?> originalShortages = field(panel, "missingRows"), originalConditions = field(panel, "conditionRows");
        set(panel, "missingRows", Collections.nCopies(51, originalShortages.get(0)));
        set(panel, "conditionRows", Collections.nCopies(72, originalConditions.get(0)));
        capture.clear(); screen.render(capture.graphics, -10, -10, 0);
        assertNotNull(capture.textPoint("缺少 51 种材料"), "小窗口也不能截断数量提示");
        assertNotNull(capture.textPoint("72 条条件提示"));
        write(capture, size, "15-merged-alerts");
        set(panel, "missingRows", originalShortages);
        set(panel, "conditionRows", originalConditions);
        PlanTreeModel model = field(screen, "treeModel");
        PlanTreeNode wood = model.root.children.stream().filter(node -> node.displayStack.is(Items.STICK)).findFirst().orElseThrow();
        PlanTreeNode plank = wood.children.get(0);
        model.root.expanded = false; wood.expanded = false;
        PlanTreeLayout treeLayout = field(screen, "treeLayout");
        treeLayout.markDirty();
        set(screen, "treeZoom", 0.3);
        EditBox search = field(screen, "workspaceSearch");
        search.setFocused(true);
        search.setValue("木");
        double oldPanX = field(screen, "treePanX"), oldPanY = field(screen, "treePanY");
        capture.clear(); screen.render(capture.graphics, -10, -10, 0);
        assertEquals(oldPanX, (Double) field(screen, "treePanX"), "输入只显示候选，不移动相机");
        assertEquals(oldPanY, (Double) field(screen, "treePanY"));
        assertTrue(capture.hasText("2 个匹配"));
        write(capture, size, "13-search-results");
        screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertSame(plank, panel.selectedNode());
        assertTrue(model.root.expanded && wood.expanded, "搜索应展开折叠的祖先");
        double zoom = field(screen, "treeZoom");
        assertTrue(zoom > 0.3);
        assertTrue((Boolean) field(screen, "treeCameraInit"));
        PlanTreeLayout.Box box = treeLayout.boxFor(plank);
        double cx = ((Integer) field(screen, "treeViewLeft") + (Integer) field(screen, "treeViewRight")) / 2.0;
        double cy = ((Integer) field(screen, "treeContentTop") + (Integer) field(screen, "treeViewBottom")) / 2.0;
        assertEquals(cx, box.centerX() * zoom + (Double) field(screen, "treePanX"), 0.001);
        assertEquals(cy, (box.y() + (box.h() + 12) / 2.0) * zoom + (Double) field(screen, "treePanY"), 0.001);
        capture.clear(); screen.render(capture.graphics, -10, -10, 0);
        assertEquals(zoom, (Double) field(screen, "treeZoom"));
        assertSame(plank, field(field(screen, "treeRenderer"), "inspectedNode"), "关闭侧栏时搜索结果仍应高亮");
        assertTrue(box.bottom() * zoom + (Double) field(screen, "treePanY") + 12 * zoom
                <= (Integer) field(screen, "treeViewBottom"), "放大后仍要完整显示紧凑节点数量");
        write(capture, size, "14-search-located");
        search.setFocused(true); search.setValue("minecraft:stick");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertSame(wood, panel.selectedNode(), "物品 ID 必须使用注册名查找");
        search.setFocused(true); search.setValue("没有匹配");
        capture.clear(); screen.render(capture.graphics, -10, -10, 0);
        assertTrue(capture.hasText("没有匹配节点"));
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        assertFalse(((PlanTreeSearchPanel) field(screen, "treeSearch")).isOpen());
        search.setValue("");
        verifyNoInteractions(requests);
    }

    private static void write(Capture capture, int[] size, String name) throws Exception {
        Path output = Path.of("build/plan-ui-render", size[0] + "x" + size[1]);
        Files.createDirectories(output);
        Files.writeString(output.resolve(name + ".json"), new Gson().toJson(Map.of("width", size[0], "height", size[1], "commands", capture.commands)));
    }

    private static final class Capture {
        final PoseStack pose = new PoseStack();
        final Deque<int[]> scissors = new ArrayDeque<>();
        final List<Map<String, Object>> commands = new ArrayList<>();
        final GuiGraphics graphics = mock(GuiGraphics.class, call -> {
            String method = call.getMethod().getName();
            Object[] a = call.getArguments();
            if (method.equals("pose")) return pose;
            if (method.equals("drawManaged")) { ((Runnable) a[0]).run(); return null; }
            if (method.equals("enableScissor")) {
                int[] r = {(int) a[0], (int) a[1], (int) a[2], (int) a[3]};
                if (!scissors.isEmpty()) { int[] old = scissors.peek(); r = new int[]{Math.max(r[0], old[0]), Math.max(r[1], old[1]), Math.min(r[2], old[2]), Math.min(r[3], old[3])}; }
                scissors.push(r);
            } else if (method.equals("disableScissor")) scissors.pop();
            else if (method.equals("fill") && a.length == 5) add("fill", a, 0, 1, Map.of("right", point(((Number) a[2]).floatValue(), ((Number) a[3]).floatValue()), "color", a[4]));
            else if (method.equals("renderOutline")) {
                int x = (int) a[0], y = (int) a[1], w = (int) a[2], h = (int) a[3], color = (int) a[4];
                GuiGraphics g = (GuiGraphics) call.getMock();
                g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
                g.fill(x, y, x + 1, y + h, color); g.fill(x + w - 1, y, x + w, y + h, color);
            } else if (method.equals("drawString") && a.length >= 5) {
                Object text = a[1];
                String value = text instanceof String string ? string : text instanceof Component c ? c.getString()
                        : text instanceof FormattedCharSequence sequence ? sequence(sequence) : String.valueOf(text);
                add("text", a, 2, 3, Map.of("text", value, "color", a[4], "bold", text instanceof Component c && c.getStyle().isBold()));
            } else if (method.equals("renderItem") && a.length == 3 && !((ItemStack) a[0]).isEmpty()) {
                ItemStack stack = (ItemStack) a[0];
                add("item", a, 1, 2, Map.of("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        void clear() { commands.clear(); assertTrue(scissors.isEmpty()); }
        boolean hasText(String value) { return commands.stream().anyMatch(command ->
                command.getOrDefault("text", "").toString().contains(value)); }
        float[] textPoint(String value) { return commands.stream().filter(command -> value.equals(command.get("text")))
                .map(command -> (float[]) command.get("point")).findFirst().orElse(null); }
        float[] point(float x, float y) { Vector3f v = pose.last().pose().transformPosition(new Vector3f(x, y, 0)); return new float[]{v.x, v.y}; }
        void add(String type, Object[] a, int xi, int yi, Map<String, Object> extra) {
            Map<String, Object> command = new LinkedHashMap<>(extra);
            command.put("type", type); command.put("point", point(((Number) a[xi]).floatValue(), ((Number) a[yi]).floatValue()));
            command.put("scale", pose.last().pose().m00());
            command.put("z", pose.last().pose().m32());
            if (!scissors.isEmpty()) command.put("clip", scissors.peek().clone());
            commands.add(command);
        }
    }

    private static ResourceLocation id(String value) { return ResourceLocation.tryParse("test:" + value); }
    private static ItemStack stack(Item item, String name) { return new ItemStack(item).setHoverName(Component.literal(name)); }
    private static int measure(String value) { return value.codePoints().map(c -> c > 255 ? 9 : 6).sum(); }
    private static String trim(String value, int width) { int end = 0; while (end < value.length() && measure(value.substring(0, end + 1)) <= width) end++; return value.substring(0, end); }
    private static String sequence(FormattedCharSequence sequence) { StringBuilder s = new StringBuilder(); sequence.accept((i, style, codepoint) -> { s.appendCodePoint(codepoint); return true; }); return s.toString(); }
    private static void set(Object owner, String name, Object value) throws Exception { find(owner, name).set(owner, value); }
    @SuppressWarnings("unchecked") private static <T> T field(Object owner, String name) throws Exception { return (T) find(owner, name).get(owner); }
    private static Field find(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
