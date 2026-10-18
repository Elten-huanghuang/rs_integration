package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 保留玩家关闭规划页后完成的后台结果，并提供聊天栏一键重新打开入口。 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlanBackgroundClient {
    static final long CACHE_TTL_MS = 10L * 60_000L;
    private static final int MAX_PENDING_REQUESTS = 32;
    private static final int MAX_CACHED_PLANS = 8;
    private static final String OPEN_COMMAND = "rsi_plan open";
    private static final Map<Long, Long> DETACHED_REQUESTS = new LinkedHashMap<>();
    private static final Map<Long, CachedPlan> CACHED_PLANS = new LinkedHashMap<>();

    private PlanBackgroundClient() {}

    /** 规划页关闭时调用；响应到达后不会突然切换界面，而是留给聊天栏入口。 */
    public static synchronized void detach(long requestId) {
        if (requestId <= 0L) return;
        long now = System.currentTimeMillis();
        prune(now);
        DETACHED_REQUESTS.put(requestId, now);
        while (DETACHED_REQUESTS.size() > MAX_PENDING_REQUESTS) {
            Iterator<Long> iterator = DETACHED_REQUESTS.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 仅接收已经转后台的成功响应。普通规划响应仍由页面直接打开，避免改变现有入口行为。
     */
    public static synchronized boolean capture(long requestId, PlanResponse plan) {
        if (requestId <= 0L || plan == null || !DETACHED_REQUESTS.containsKey(requestId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        DETACHED_REQUESTS.remove(requestId);
        prune(now);
        CACHED_PLANS.put(requestId, new CachedPlan(plan, now));
        while (CACHED_PLANS.size() > MAX_CACHED_PLANS) {
            Iterator<Long> iterator = CACHED_PLANS.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    public static synchronized void clear() {
        DETACHED_REQUESTS.clear();
        CACHED_PLANS.clear();
    }

    static Component readyMessage(Component targetName, long requestId) {
        MutableComponent open = Component.translatable("rsi.plan.background.open")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                "/" + OPEN_COMMAND + " " + requestId))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("rsi.plan.background.open_hover"))));
        return Component.translatable("rsi.plan.background.ready", targetName)
                .append(Component.literal(" "))
                .append(open);
    }

    @SubscribeEvent
    public static void registerClientCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rsi_plan")
                .then(Commands.literal("open")
                        .then(Commands.argument("request", LongArgumentType.longArg(1L))
                                .executes(PlanBackgroundClient::open))));
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static int open(CommandContext<CommandSourceStack> context) {
        long requestId = LongArgumentType.getLong(context, "request");
        CachedPlan cached;
        synchronized (PlanBackgroundClient.class) {
            prune(System.currentTimeMillis());
            cached = CACHED_PLANS.get(requestId);
        }
        if (cached == null) {
            context.getSource().sendFailure(
                    Component.translatable("rsi.plan.background.expired"));
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return 0;
        minecraft.setScreen(new CraftingPlanScreen(cached.plan(), requestId));
        return 1;
    }

    private static void prune(long now) {
        DETACHED_REQUESTS.entrySet().removeIf(entry ->
                now - entry.getValue() >= CACHE_TTL_MS);
        CACHED_PLANS.entrySet().removeIf(entry ->
                now - entry.getValue().cachedAt() >= CACHE_TTL_MS);
    }

    private record CachedPlan(PlanResponse plan, long cachedAt) {}
}
