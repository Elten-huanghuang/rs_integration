package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.planning.PlanningProgressOverlay;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.client.recipe.JeiNetworkItemCache;
import com.huanghuang.rsintegration.storage.StorageReference;

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
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

/** 保留玩家关闭规划页后完成的后台结果，并提供聊天栏一键重新打开入口。 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlanBackgroundClient {
    static final long CACHE_TTL_MS = 10L * 60_000L;
    private static final int MAX_PENDING_REQUESTS = 32;
    private static final int MAX_CACHED_PLANS = 8;
    private static final String COMMAND = "rsi_plan";
    private static final String OPEN_COMMAND = COMMAND + " open";
    private static final Map<Long, Long> DETACHED_REQUESTS = new LinkedHashMap<>();
    private static final Map<Long, CachedPlan> CACHED_PLANS = new LinkedHashMap<>();
    private static final Map<Long, PreviewTarget> PREVIEW_TARGETS = new LinkedHashMap<>();
    private static final Map<Long, PendingRequote> REQUOTES = new LinkedHashMap<>();

    private PlanBackgroundClient() {}

    public static synchronized void attach(long requestId) {
        DETACHED_REQUESTS.remove(requestId);
    }

    /** 确认通常不会返回计划；仅登记可能到达的重报价，不启动规划进度。 */
    static synchronized void expectRequote(long requestId, long previewId) {
        if (previewId <= 0L || requestId <= previewId) return;
        long now = System.currentTimeMillis();
        prune(now);
        PlanningProgressTracker.markIssued(requestId);
        REQUOTES.put(requestId, new PendingRequote(previewId, now));
        while (REQUOTES.size() > MAX_PENDING_REQUESTS) {
            REQUOTES.remove(REQUOTES.keySet().iterator().next());
        }
    }

    static synchronized long takeRequote(long requestId) {
        prune(System.currentTimeMillis());
        PendingRequote pending = REQUOTES.remove(requestId);
        return pending == null ? 0L : pending.previewId();
    }

    public static synchronized void remember(long requestId, PlanResponse plan) {
        if (requestId <= 0L || plan == null) return;
        long now = System.currentTimeMillis();
        prune(now);
        PreviewTarget target = PREVIEW_TARGETS.remove(requestId);
        CachedPlan previous = CACHED_PLANS.get(requestId);
        if (target == null && previous != null) target = previous.previewTarget();
        // 服务端可能补全底材、产物等执行信息，复用判断仍须沿用浏览器首次点击的身份。
        // 根配方切换后则交给新方案的身份，避免把另一目标误认作原计划。
        if (target != null && !target.recipeId().toString().equals(plan.recipeId())) target = null;
        CACHED_PLANS.put(requestId, new CachedPlan(plan, now, target));
        while (CACHED_PLANS.size() > MAX_CACHED_PLANS) {
            CACHED_PLANS.remove(CACHED_PLANS.keySet().iterator().next());
        }
    }

    /** 同一编辑页的新版本替换旧条目；独立打开的其他计划不按配方名称去重。 */
    public static synchronized void replaceRevision(long previousRequest, long nextRequest) {
        if (previousRequest <= 0 || nextRequest <= 0 || previousRequest == nextRequest) return;
        CachedPlan previous = CACHED_PLANS.remove(previousRequest);
        PreviewTarget target = PREVIEW_TARGETS.remove(previousRequest);
        if (target == null && previous != null) target = previous.previewTarget();
        if (target != null) {
            StorageReference network = previous != null && previous.plan().storageReference() != null
                    ? previous.plan().storageReference() : target.network();
            PREVIEW_TARGETS.put(nextRequest, new PreviewTarget(target.recipeId(),
                    target.baseItem(), target.clickedOutput(), network));
        }
        DETACHED_REQUESTS.remove(previousRequest);
        PlanningProgressTracker.responseReceived(previousRequest);
    }

    public static synchronized Map<Long, PlanResponse> completedPlans() {
        prune(System.currentTimeMillis());
        Map<Long, PlanResponse> result = new LinkedHashMap<>();
        CACHED_PLANS.entrySet().stream().sorted(Map.Entry.<Long, CachedPlan>comparingByKey().reversed())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().plan()));
        return result;
    }

    /** 配方浏览器再次点击加号时，继续编辑已有计划，保留数量和路线选择。 */
    public static boolean openExisting(ResourceLocation recipeId, @Nullable ItemStack baseItem,
                                       @Nullable ItemStack clickedOutput) {
        StorageReference network = JeiNetworkItemCache.INSTANCE.reference();
        long requestId = findExisting(recipeId, baseItem, clickedOutput, network);
        if (requestId > 0L) return openRequest(requestId);
        requestId = findPending(recipeId, baseItem, clickedOutput, network);
        if (requestId <= 0L) return false;
        PlanningProgressTracker.select(requestId);
        if (Minecraft.getInstance().screen instanceof PlanScreenHost host) host.rsi$openPlans();
        else PlanningTasksScreen.open();
        return true;
    }

    /** 登记配方浏览器首次点击的身份，后续页面重算通过 replaceRevision 继承。 */
    public static synchronized void startPreview(long requestId, ResourceLocation recipeId,
                                                  @Nullable ItemStack baseItem, @Nullable ItemStack clickedOutput) {
        PREVIEW_TARGETS.put(requestId, new PreviewTarget(recipeId, copyVariant(baseItem), copyVariant(clickedOutput),
                JeiNetworkItemCache.INSTANCE.reference()));
        while (PREVIEW_TARGETS.size() > MAX_PENDING_REQUESTS) {
            PREVIEW_TARGETS.remove(PREVIEW_TARGETS.keySet().iterator().next());
        }
        PlanningProgressTracker.start(requestId, recipeId);
    }

    static synchronized long findPending(ResourceLocation recipeId, @Nullable ItemStack baseItem,
                                           @Nullable ItemStack clickedOutput, @Nullable StorageReference network) {
        PREVIEW_TARGETS.entrySet().removeIf(entry -> {
            var progress = PlanningProgressTracker.get(entry.getKey());
            return progress == null || !entry.getValue().recipeId().equals(progress.recipeId())
                    || progress.terminal()
                    && progress.state() != PlanningProgressSnapshot.State.SUCCEEDED;
        });
        return PREVIEW_TARGETS.entrySet().stream().filter(entry -> {
            PreviewTarget target = entry.getValue();
            return recipeId.equals(target.recipeId())
                    && (network == null || Objects.equals(network, target.network()))
                    && sameVariant(baseItem, target.baseItem())
                    && sameVariant(clickedOutput, target.clickedOutput());
        }).mapToLong(Map.Entry::getKey).max().orElse(0L);
    }

    static synchronized long findExisting(ResourceLocation recipeId, @Nullable ItemStack baseItem,
                             @Nullable ItemStack clickedOutput, @Nullable StorageReference network) {
        prune(System.currentTimeMillis());
        long latest = 0L;
        for (var entry : CACHED_PLANS.entrySet()) {
            CachedPlan cached = entry.getValue();
            PlanResponse plan = cached.plan();
            PreviewTarget target = cached.previewTarget();
            if (recipeId.toString().equals(plan.recipeId())
                    && (network == null || Objects.equals(network, plan.storageReference()))
                    && sameVariant(baseItem, target == null ? plan.baseItem() : target.baseItem())
                    && sameVariant(clickedOutput, target == null ? plan.clickedOutput() : target.clickedOutput())) {
                latest = Math.max(latest, entry.getKey());
            }
        }
        return latest;
    }

    private static boolean sameVariant(@Nullable ItemStack first, @Nullable ItemStack second) {
        boolean firstEmpty = first == null || first.isEmpty();
        boolean secondEmpty = second == null || second.isEmpty();
        return firstEmpty || secondEmpty ? firstEmpty == secondEmpty : ItemStack.isSameItemSameTags(first, second);
    }

    private static ItemStack copyVariant(@Nullable ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

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
        DETACHED_REQUESTS.remove(requestId);
        remember(requestId, plan);
        return true;
    }

    public static synchronized void clear() {
        DETACHED_REQUESTS.clear();
        CACHED_PLANS.clear();
        PREVIEW_TARGETS.clear();
        REQUOTES.clear();
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
        event.getDispatcher().register(Commands.literal(COMMAND)
                .then(Commands.literal("tasks").executes(context -> { PlanningTasksScreen.open(); return 1; }))
                .then(Commands.literal("cancel").executes(context -> PlanningProgressOverlay.cancelCurrent() ? 1 : 0))
                .then(Commands.literal("open")
                        .then(Commands.argument("request", LongArgumentType.longArg(1L))
                                .executes(PlanBackgroundClient::open))));
    }

    static String localCommand(Style style) {
        ClickEvent click = style == null ? null : style.getClickEvent();
        if (click == null || click.getAction() != ClickEvent.Action.RUN_COMMAND) return null;
        String prefix = "/" + COMMAND;
        String command = click.getValue();
        return command.equals(prefix) || command.startsWith(prefix + " ") ? command.substring(1) : null;
    }

    /** 聊天链接默认走服务端发送路径，必须在本地执行客户端计划命令。 */
    public static boolean handleClick(Style style) {
        String command = localCommand(style);
        if (command == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ClientCommandHandler.runCommand(command)) {
            minecraft.gui.getChat().addMessage(Component.translatable("rsi.plan.background.expired"));
        }
        return true;
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        PlanningProgressTracker.clear();
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
        return openRequest(requestId) ? 1 : 0;
    }

    public static boolean openRequest(long requestId) {
        return openRequest(requestId, null);
    }

    public static boolean openRequest(long requestId, Runnable returnToParent) {
        PlanResponse plan = completedPlans().get(requestId);
        Minecraft minecraft = Minecraft.getInstance();
        if (plan == null || minecraft.player == null) return false;
        minecraft.setScreen(new CraftingPlanScreen(plan, requestId, null, returnToParent));
        return true;
    }

    /** 服务端已接收的计划不再显示为待确认；迟到回执也能清理缓存。 */
    public static synchronized void accepted(long requestId, UUID craftId) {
        if (requestId <= 0L) return;
        CACHED_PLANS.remove(requestId);
        PREVIEW_TARGETS.remove(requestId);
        DETACHED_REQUESTS.remove(requestId);
        PlanningProgressTracker.responseReceived(requestId);
        if (Minecraft.getInstance().screen instanceof PlanScreenHost host) {
            host.rsi$planAccepted(requestId, craftId);
        }
    }

    /** 移除尚未确认的计算结果；不会向服务器发送取消，也不会影响已提交的合成。 */
    public static synchronized boolean dismiss(long requestId) {
        if (requestId <= 0L || CACHED_PLANS.remove(requestId) == null) return false;
        PREVIEW_TARGETS.remove(requestId);
        DETACHED_REQUESTS.remove(requestId);
        PlanningProgressTracker.responseReceived(requestId);
        return true;
    }

    public static synchronized boolean contains(long requestId) {
        prune(System.currentTimeMillis());
        return CACHED_PLANS.containsKey(requestId);
    }

    private static void prune(long now) {
        DETACHED_REQUESTS.entrySet().removeIf(entry ->
                now - entry.getValue() >= CACHE_TTL_MS);
        CACHED_PLANS.entrySet().removeIf(entry ->
                now - entry.getValue().cachedAt() >= CACHE_TTL_MS);
        REQUOTES.values().removeIf(pending -> now - pending.sentAt() >= CACHE_TTL_MS);
    }

    private record PendingRequote(long previewId, long sentAt) {}
    private record CachedPlan(PlanResponse plan, long cachedAt, @Nullable PreviewTarget previewTarget) {}
    private record PreviewTarget(ResourceLocation recipeId, ItemStack baseItem,
                                 ItemStack clickedOutput, @Nullable StorageReference network) {}
}
