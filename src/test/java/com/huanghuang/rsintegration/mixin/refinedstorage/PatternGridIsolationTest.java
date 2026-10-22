package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PatternGridIsolationTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(strings = {"rsi$clickSmithingButton", "rsi$handleAnvilNameKey",
            "rsi$handleAnvilNameCharacter", "rsi$scrollStonecutterRecipes", "rsi$tickSmithingSlots"})
    void screenHooksReturnBeforeTouchingNonCraftingGridState(String method) throws Exception {
        assertCraftingOnlyGuard(GridScreenSmithingMixin.class, method);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rsi$prepareSmithingState", "rsi$refreshCraftingStationSlots",
            "rsi$setCraftingStationMode"})
    void menuHooksCannotReplacePatternSlotsOrSwitchTheirMode(String method) throws Exception {
        assertCraftingOnlyGuard(GridContainerSmithingMixin.class, method);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeJeiTransferIsNotCancelledForPatternGrid(boolean doTransfer) throws Exception {
        GridContainerMenu menu = mock(GridContainerMenu.class);
        IGrid grid = mock(IGrid.class);
        when(menu.getGrid()).thenReturn(grid);
        when(grid.getGridType()).thenReturn(GridType.PATTERN);
        IRecipeSlotsView slots = mock(IRecipeSlotsView.class);
        Player player = mock(Player.class);
        GridRecipeTransferSmithingMixin mixin = new GridRecipeTransferSmithingMixin() {};
        Method handler = GridRecipeTransferSmithingMixin.class.getDeclaredMethod(
                "rsi$handleSmithingRecipe", GridContainerMenu.class, Object.class,
                IRecipeSlotsView.class, Player.class, boolean.class, boolean.class,
                CallbackInfoReturnable.class);
        handler.setAccessible(true);

        for (Object recipe : new Object[]{mock(SmithingRecipe.class),
                mock(StonecutterRecipe.class), mock(IJeiAnvilRecipe.class)}) {
            CallbackInfoReturnable<IRecipeTransferError> callback =
                    new CallbackInfoReturnable<>("transferRecipe", true);

            handler.invoke(mixin, menu, recipe, slots, player, false, doTransfer, callback);

            assertFalse(callback.isCancelled());
            verifyNoInteractions(slots, player);
        }
    }

    /** 普通单测不运行 Mixin 转换，只验证实际注入方法的前置分支契约。 */
    private static void assertCraftingOnlyGuard(Class<?> type, String methodName) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        MethodNode method = node.methods.stream().filter(candidate -> candidate.name.equals(methodName))
                .findFirst().orElseThrow();
        for (AbstractInsnNode instruction : method.instructions) {
            // 在读取、修改工作站状态前，必须排除所有非合成终端。
            assertFalse(instruction instanceof FieldInsnNode field && field.name.startsWith("rsi$"));
            if (!(instruction instanceof MethodInsnNode call)) continue;
            assertFalse(call.owner.endsWith("/CraftingStationAccess"));
            if (!call.name.equals("getGridType")) continue;
            AbstractInsnNode constant = nextInstruction(instruction);
            assertTrue(constant instanceof FieldInsnNode field
                    && field.owner.equals("com/refinedmods/refinedstorage/api/network/grid/GridType")
                    && field.name.equals("CRAFTING"));
            AbstractInsnNode branch = nextInstruction(constant);
            assertTrue(branch instanceof JumpInsnNode);
            assertEquals(Opcodes.IF_ACMPEQ, branch.getOpcode());
            assertEquals(Opcodes.RETURN, nextInstruction(branch).getOpcode());
            return;
        }
        throw new AssertionError(methodName + " 缺少合成终端类型检查");
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        assertNotNull(next);
        return next;
    }
}
