package com.huanghuang.rsintegration.feature.goety.integration;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.api.storage.StorageLink;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.brewing.NativeCauldronActions;
import com.huanghuang.rsintegration.mods.goety.magic.GoetySoulCraftingTask;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** 炼制启动后由机器持有供能任务，关闭界面不会中断已启动的原生炼制。 */
public final class CauldronSoulAutomation {
    private static final Map<BrewCauldronBlockEntity, ActiveBrew> ACTIVE = new IdentityHashMap<>();

    private CauldronSoulAutomation() {}

    public static void retain(BrewCauldronBlockEntity cauldron, ServerPlayer player, GoetySoulCraftingTask task) {
        var previous = ACTIVE.put(cauldron, new ActiveBrew(player.getUUID(), task));
        if (previous != null) previous.task().close();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var cauldron = entry.getKey();
            var level = cauldron.getLevel();
            boolean valid = RSIntegrationConfig.isGoetyCauldronEnabled() && !cauldron.isRemoved()
                    && level != null && level.hasChunkAt(cauldron.getBlockPos())
                    && level.getBlockEntity(cauldron.getBlockPos()) == cauldron;
            if (valid && cauldron.isBrewing) continue;
            if (!valid && cauldron.isBrewing) {
                cauldron.isBrewing = false;
                cauldron.setChanged();
            }
            if (valid && !cauldron.isBrewing && cauldron.mode == BrewCauldronBlockEntity.Mode.COMPLETED) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getValue().owner());
                if (player != null) autoBottle(player, cauldron);
            }
            entry.getValue().task().close();
            iterator.remove();
        }
    }

    /** 炼制完成后自动装出所有可用瓶数；没有瓶子时保留完成状态等待玩家处理。 */
    static void autoBottle(ServerPlayer player, BrewCauldronBlockEntity cauldron) {
        var link = RsiStorageLink.resolve(player).orElse(null);
        var endpoint = RsiStorageLink.endpoint(player, link);
        while (cauldron.mode == BrewCauldronBlockEntity.Mode.COMPLETED
                && cauldron.getBlockState().getValue(BrewCauldronBlock.LEVEL) > 0) {
            ItemStack bottle = takeBottle(player, link);
            if (bottle.isEmpty()) return;
            ItemStack output = NativeCauldronActions.bottle(player, cauldron);
            if (output.isEmpty()) {
                RsiStorageLink.give(player, bottle);
                return;
            }
            PlayerUtils.safeGiveToStorage(player, output, endpoint);
        }
    }

    private static ItemStack takeBottle(ServerPlayer player, StorageLink link) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.GLASS_BOTTLE)) return stack.split(1);
        }
        if (link == null) return ItemStack.EMPTY;
        return RsiStorageLink.extract(player, link, new ItemStack(Items.GLASS_BOTTLE), 1)
                .stream().findFirst().map(stack -> stack.split(1)).orElse(ItemStack.EMPTY);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        var iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().owner().equals(event.getEntity().getUUID())) continue;
            entry.getKey().isBrewing = false;
            entry.getKey().markUpdated();
            entry.getValue().task().close();
            iterator.remove();
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        // 虚拟烛台与预留仅存于内存，存盘前暂停炼制，避免重启后无供能继续运行。
        ACTIVE.keySet().forEach(cauldron -> {
            cauldron.isBrewing = false;
            cauldron.setChanged();
        });
        ACTIVE.values().forEach(active -> active.task().close());
        ACTIVE.clear();
    }

    private record ActiveBrew(UUID owner, GoetySoulCraftingTask task) {}
}
