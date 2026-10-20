package com.huanghuang.rsintegration.magic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.refinedmods.refinedstorage.api.network.INetwork;

import javax.annotation.Nullable;

/**
 * 魔法资源的手动充放：库存 ↔ 原生容器。
 *
 * <p>所有原生访问都按名称处理（灵魂图腾直接读 NBT，魔力石板与 Source 罐走反射），
 * 所以未安装对应模组时这里不会链接 Botania、Ars Nouveau 或 Goety 的类型。</p>
 */
public final class MagicResourceInteraction {
    private MagicResourceInteraction() {}

    /** 右键空白处是倒出容器、左键资源条目是吸取，和流体一致。 */
    public static boolean depositsFromHeldItem(int button) {
        return button == 1;
    }

    /** 把容器里的资源充入当前已挂载的归墟盘。 */
    public static long depositToNetwork(ServerPlayer player, MagicResourceKind kind,
                                        @Nullable ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return 0;
        if (!kind.available() || MagicGridItemTransfer.kindOf(stack) != kind) return 0;
        return MagicGridItemTransfer.deposit(player, kind, stack);
    }

    /** 把容器中的资源存入终端当前连接的 RS 网络。 */
    public static long depositToNetwork(ServerPlayer player, INetwork network,
                                        MagicResourceKind kind, @Nullable ItemStack stack) {
        if (player == null || network == null || stack == null || stack.isEmpty()) return 0;
        if (!kind.available() || MagicGridItemTransfer.kindOf(stack) != kind) return 0;
        return MagicGridItemTransfer.deposit(player, network, kind, stack);
    }

    /** 从当前已挂载的归墟盘吸取资源填进容器。 */
    public static int withdrawFromNetwork(ServerPlayer player, MagicResourceKind kind,
                                          @Nullable ItemStack stack, int amount) {
        if (player == null || stack == null || stack.isEmpty()) return 0;
        if (!kind.available() || MagicGridItemTransfer.kindOf(stack) != kind) return 0;
        return MagicGridItemTransfer.withdraw(player, kind, stack, amount);
    }

    /** 从终端当前连接的 RS 网络取资源填进容器。 */
    public static int withdrawFromNetwork(ServerPlayer player, INetwork network,
                                          MagicResourceKind kind, @Nullable ItemStack stack,
                                          int amount) {
        if (player == null || network == null || stack == null || stack.isEmpty()) return 0;
        if (!kind.available() || MagicGridItemTransfer.kindOf(stack) != kind) return 0;
        return MagicGridItemTransfer.withdraw(player, network, kind, stack, amount);
    }
}
