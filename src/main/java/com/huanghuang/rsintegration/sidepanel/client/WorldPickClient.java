package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.sidepanel.RSSidePanelClickPacket;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldPickClient {
    private static boolean initialized;
    private static final Map<Long, ItemStack> pendingPicks = new ConcurrentHashMap<>();

    private WorldPickClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                WorldPickClient::onPickBlock);
    }

    private static void onPickBlock(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.isCanceled() || event.getKeyMapping() != minecraft.options.keyPickItem) return;
        if (minecraft.screen != null || minecraft.player == null || minecraft.level == null) return;
        if (minecraft.player.getAbilities().instabuild
                || !minecraft.player.getMainHandItem().isEmpty()) return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;

        var state = minecraft.level.getBlockState(hit.getBlockPos());
        ItemStack target = state.getCloneItemStack(hit, minecraft.level,
                hit.getBlockPos(), minecraft.player);
        if (target.isEmpty()) return;

        // Let vanilla select an existing matching stack before asking RS for one.
        if (minecraft.player.getInventory().findSlotMatchingItem(target) >= 0) return;

        event.setCanceled(true);
        long operationId = RSSidePanelNetworkHandler.sendClick(target,
                RSSidePanelClickPacket.ACTION_PICK_BLOCK, false, null);
        pendingPicks.put(operationId, target.copyWithCount(1));
    }

    public static void onOperationResult(long operationId, boolean success, int actualCount) {
        ItemStack target = pendingPicks.remove(operationId);
        if (target == null || success || actualCount > 0) return;
        // Always report a failed pick.  Previously the whole feedback path was
        // inside the JEI-loaded check, so a missing JEI runtime (or a JEI
        // startup timing issue) silently dropped both the bookmark attempt and
        // the user-facing explanation.
        boolean bookmarked = net.minecraftforge.fml.ModList.get().isLoaded("jei")
                && WorldPickJeiClient.bookmark(target);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    bookmarked ? "rsi.world_pick.bookmarked" : "rsi.world_pick.bookmark_unavailable",
                    target.getHoverName()), true);
        }
    }
}
