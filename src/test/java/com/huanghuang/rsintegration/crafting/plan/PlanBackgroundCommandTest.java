package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.mixin.minecraft.CraftFailureReportClickMixin;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanBackgroundCommandTest extends BootstrapTest {
    @AfterEach
    void clear() {
        PlanBackgroundClient.clear();
    }

    @Test
    void readyLinkRunsClientDispatcherAndOpensTheExactCachedRequest() throws Exception {
        PlanBackgroundClient.remember(45, mock(PlanResponse.class));
        PlanResponse selected = mock(PlanResponse.class);
        PlanBackgroundClient.remember(46, selected);
        Style link = PlanBackgroundClient.readyMessage(Component.literal("破灭魔王剑"), 46)
                .getSiblings().stream().map(Component::getStyle)
                .filter(style -> style.getClickEvent() != null).findFirst().orElseThrow();
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = mock(CommandSourceStack.class);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.player = mock(LocalPlayer.class);

        try (var preferences = mockStatic(ClientPlanningPreferences.class)) {
            preferences.when(ClientPlanningPreferences::preferencesPath)
                    .thenReturn(Path.of("build", "test-plan-command.json"));
            try (var game = mockStatic(Minecraft.class);
                 var commands = mockStatic(ClientCommandHandler.class);
                 var keys = mockStatic(Screen.class);
                 var screens = mockConstruction(CraftingPlanScreen.class, (screen, context) -> {
                     assertSame(selected, context.arguments().get(0));
                     assertEquals(46L, context.arguments().get(1));
                 })) {
                game.when(Minecraft::getInstance).thenReturn(minecraft);
                commands.when(() -> ClientCommandHandler.runCommand("rsi_plan open 46"))
                        .thenAnswer(call -> dispatcher.execute(call.getArgument(0, String.class), source) == 1);

                CallbackInfoReturnable<Boolean> callback = click(link);
                assertTrue(callback.isCancelled(), "本地命令不能继续走原版服务端发送路径");
                assertEquals(Boolean.TRUE, callback.getReturnValue());
                commands.verify(() -> ClientCommandHandler.runCommand("rsi_plan open 46"));
                assertEquals(1, screens.constructed().size());
                verify(minecraft).setScreen(screens.constructed().get(0));
                verifyNoInteractions(source, minecraft.player);

                keys.when(Screen::hasShiftDown).thenReturn(true);
                assertFalse(click(link).isCancelled(), "保留原版 Shift 点击行为");
                assertEquals(1, screens.constructed().size());
            }
        }
    }

    @Test
    void removedPlanReportsExpiryWithoutTryingToOpenAnotherPlan() throws Exception {
        PlanBackgroundClient.remember(45, mock(PlanResponse.class));
        PlanBackgroundClient.remember(46, mock(PlanResponse.class));
        PlanBackgroundClient.dismiss(46);
        CommandSourceStack source = mock(CommandSourceStack.class);

        assertEquals(0, dispatcher().execute("rsi_plan open 46", source));
        verify(source).sendFailure(Component.translatable("rsi.plan.background.expired"));
        assertTrue(PlanBackgroundClient.contains(45));
    }

    @Test
    void unhandledLocalCommandIsConsumedAndReportsExpiry() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.player = mock(LocalPlayer.class);
        Gui gui = mock(Gui.class);
        Field guiField = Minecraft.class.getDeclaredField("gui");
        guiField.setAccessible(true);
        guiField.set(minecraft, gui);
        ChatComponent chat = mock(ChatComponent.class);
        when(gui.getChat()).thenReturn(chat);
        try (var game = mockStatic(Minecraft.class);
             var commands = mockStatic(ClientCommandHandler.class)) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(PlanBackgroundClient.handleClick(runCommand("/rsi_plan open 46")));
            verify(chat).addMessage(Component.translatable("rsi.plan.background.expired"));
            verifyNoInteractions(minecraft.player);
        }
    }

    @Test
    void bridgeOnlyMatchesPlanRunCommandsWithAnExactCommandName() {
        assertEquals("rsi_plan open 46", PlanBackgroundClient.localCommand(runCommand("/rsi_plan open 46")));
        assertEquals("rsi_plan tasks", PlanBackgroundClient.localCommand(runCommand("/rsi_plan tasks")));
        assertEquals("rsi_plan", PlanBackgroundClient.localCommand(runCommand("/rsi_plan")));
        assertNull(PlanBackgroundClient.localCommand(null));
        assertNull(PlanBackgroundClient.localCommand(Style.EMPTY));
        assertNull(PlanBackgroundClient.localCommand(runCommand("/rsi_plan_other open 46")));
        assertNull(PlanBackgroundClient.localCommand(runCommand("/other_mod open 46")));
        assertNull(PlanBackgroundClient.localCommand(Style.EMPTY.withClickEvent(
                new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/rsi_plan open 46"))));
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PlanBackgroundClient.registerClientCommand(new RegisterClientCommandsEvent(
                dispatcher, mock(CommandBuildContext.class)));
        return dispatcher;
    }

    private static Style runCommand(String command) {
        return Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
    }

    private static CallbackInfoReturnable<Boolean> click(Style style) throws Exception {
        Method handler = CraftFailureReportClickMixin.class.getDeclaredMethod(
                "rsIntegration$openLocalFailureReport", Style.class, CallbackInfoReturnable.class);
        handler.setAccessible(true);
        CallbackInfoReturnable<Boolean> callback = new CallbackInfoReturnable<>("handleComponentClicked", true);
        handler.invoke(new CraftFailureReportClickMixin() {}, style, callback);
        return callback;
    }
}
