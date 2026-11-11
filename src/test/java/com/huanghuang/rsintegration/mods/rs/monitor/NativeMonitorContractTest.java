package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 对真实 RS 依赖检查注入点与强转契约，避免只用自建桩验证兼容性。 */
class NativeMonitorContractTest extends BootstrapTest {
    @Test void screenInjectionPointsExistInTheActualRsJar() throws IOException {
        ClassNode screen = read("com/refinedmods/refinedstorage/screen/CraftingMonitorScreen");
        assertMethod(screen, "setTasks", "(Ljava/util/List;)V");
        assertMethod(screen, "onPostInit", "(II)V");
        assertMethod(screen, "tick", "(II)V");
        assertMethod(screen, "lambda$onPostInit$3", "(Lnet/minecraft/client/gui/components/Button;)V");
        assertMethod(screen, "getCurrentTab", "()Lcom/refinedmods/refinedstorage/api/network/grid/IGridTab;");
        assertMethod(screen, "renderBackground", "(Lnet/minecraft/client/gui/GuiGraphics;IIII)V");
        assertMethod(screen, "renderForeground", "(Lnet/minecraft/client/gui/GuiGraphics;II)V");
        assertTrue(screen.fields.stream().anyMatch(f -> f.name.equals("tasks") && f.desc.equals("Ljava/util/List;")));
        var task = read("com/refinedmods/refinedstorage/screen/CraftingMonitorScreen$Task");
        assertTrue(task.fields.stream().anyMatch(f -> f.name.equals("id") && f.desc.equals("Ljava/util/UUID;")));
        var tabs = read("com/refinedmods/refinedstorage/screen/widget/TabListWidget");
        assertMethod(tabs, "init", "(I)V");
        assertMethod(tabs, "update", "()V");
        assertMethod(tabs, "mouseClicked", "()Z");
        assertMethod(tabs, "drawTooltip", "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;II)V");
    }

    @Test void nativeElementDrawRedirectHasExactlyOneMatchingCallSite() throws IOException {
        var screen = read("com/refinedmods/refinedstorage/screen/CraftingMonitorScreen");
        int matches = 0;
        for (var method : screen.methods) if (method.name.equals("renderForeground")) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/refinedmods/refinedstorage/api/autocrafting/craftingmonitor/ICraftingMonitorElement")
                    && call.name.equals("draw")
                    && call.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;IILcom/refinedmods/refinedstorage/api/render/IElementDrawers;)V")) matches++;
        }
        assertEquals(1, matches);
    }

    @Test void nativeMaterialAccessorsMatchBothItemAndFluidFields() throws IOException {
        for (String type : List.of("Item", "Fluid")) {
            var node = read("com/refinedmods/refinedstorage/apiimpl/autocrafting/craftingmonitor/" + type + "CraftingMonitorElement");
            for (String amount : List.of("stored", "missing", "processing", "scheduled", "crafting")) {
                assertTrue(node.fields.stream().anyMatch(field -> field.name.equals(amount) && field.desc.equals("I")), type + ":" + amount);
            }
            assertTrue(node.fields.stream().anyMatch(field -> field.name.equals("stack") && field.desc.equals(type.equals("Item")
                    ? "Lnet/minecraft/world/item/ItemStack;" : "Lnet/minecraftforge/fluids/FluidStack;")));
        }
        var error = read("com/refinedmods/refinedstorage/apiimpl/autocrafting/craftingmonitor/ErrorCraftingMonitorElement");
        assertTrue(error.fields.stream().anyMatch(field -> field.name.equals("base")
                && field.desc.equals("Lcom/refinedmods/refinedstorage/api/autocrafting/craftingmonitor/ICraftingMonitorElement;")));
    }

    @Test void adapterAndMenuMatchNativeTypeAndLifecycleContracts() throws IOException {
        String task = "com/refinedmods/refinedstorage/screen/CraftingMonitorScreen$Task";
        assertEquals(task, read("com/huanghuang/rsintegration/mods/rs/monitor/RsiMonitorTask").superName);
        assertEquals(0, read(task).access & Opcodes.ACC_FINAL);
        ClassNode menu = read("com/refinedmods/refinedstorage/container/CraftingMonitorContainerMenu");
        assertTrue(menu.methods.stream().anyMatch(m ->
                (m.name.equals("broadcastChanges") || m.name.equals("m_38946_")) && m.desc.equals("()V")));
        ClassNode manager = read("com/refinedmods/refinedstorage/apiimpl/autocrafting/CraftingManager");
        assertTrue(manager.fields.stream().anyMatch(f -> f.name.equals("network")
                && f.desc.equals("Lcom/refinedmods/refinedstorage/api/network/INetwork;")));
        assertEquals(menu.name, read("com/huanghuang/rsintegration/mods/rs/monitor/NetworkCraftingMonitorMenu").superName);
        var monitor = read("com/huanghuang/rsintegration/mods/rs/monitor/NetworkCraftingMonitor");
        assertTrue(monitor.interfaces.contains("com/refinedmods/refinedstorage/blockentity/craftingmonitor/ICraftingMonitor"));
        // 普通桥接接口不能放进 Mixin 包，否则启动时会触发 IllegalClassLoadError。
        assertFalse(MonitorNavigationAccess.class.getPackageName().contains(".mixin."));
    }

    @Test void monitorMaterialRetainsExactLongQuantityAndItsActualMeaning() {
        long quantity = 9_000_000_000L;
        var row = new CraftMaterialView.Row(MaterialKey.of(new ItemStack(Items.DIAMOND)), quantity, 7, 8, 9, 10, 100, false);
        var elements = RsiMonitorElement.elements(new CraftMaterialView(List.of(row), true));
        assertEquals(1, elements.size());
        var text = (TranslatableContents) elements.get(0).getTooltip().get(2).getContents();
        assertEquals("rsi.monitor.material.available", text.getKey());
        assertArrayEquals(new Object[]{"9000000000"}, text.getArgs());
        assertEquals(MonitorGridStyle.RUNNING, MonitorGridStyle.nativeElement(elements.get(0)));
    }

    @Test void returnButtonPassesTheScreenNavigationSeparatelyFromTheNativeMenu() throws IOException {
        var button = read("com/huanghuang/rsintegration/mods/rs/monitor/RsMonitorReturnButton");
        var press = button.methods.stream().filter(method -> method.name.equals("onPress")).findFirst().orElseThrow();
        int matches = 0;
        for (var instruction : press.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/huanghuang/rsintegration/mods/rs/monitor/RsMonitorNavigationClient")
                    && call.name.equals("returnToTerminal")) {
                // 返回位置来自屏幕接口，不能再错误地把菜单强转为该接口。
                assertEquals("(Lcom/refinedmods/refinedstorage/container/CraftingMonitorContainerMenu;"
                        + "Lcom/huanghuang/rsintegration/mods/rs/monitor/MonitorNavigationAccess;)V", call.desc);
                matches++;
            }
        }
        assertEquals(1, matches);
    }

    private static void assertMethod(ClassNode node, String name, String descriptor) {
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)),
                node.name + "." + name + descriptor);
    }

    private static ClassNode read(String name) throws IOException {
        try (var stream = NativeMonitorContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }
}
