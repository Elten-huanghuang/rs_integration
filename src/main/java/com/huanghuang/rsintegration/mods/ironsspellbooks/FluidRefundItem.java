package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.List;

/** 玩家持有的流体退款物品：一个物品保存完整的流体 NBT 和 mB 数量。 */
public final class FluidRefundItem extends Item {
    private static final String FLUID = "Fluid";
    private static final String NETWORK = "Network";

    public FluidRefundItem() { super(new Properties().stacksTo(1)); }

    public static ItemStack create(CompoundTag fluid, @Nullable String networkId) {
        return create(ModItems.FLUID_REFUND.get(), fluid, networkId);
    }

    static ItemStack create(Item item, CompoundTag fluid, @Nullable String networkId) {
        if (!fluid.contains("FluidName", Tag.TAG_STRING) || fluid.getInt("Amount") <= 0) {
            throw new IllegalArgumentException("流体退款缺少有效的身份或数量");
        }
        ItemStack result = new ItemStack(item);
        CompoundTag tag = result.getOrCreateTag();
        tag.put(FLUID, fluid.copy());
        if (networkId != null) tag.putString(NETWORK, networkId);
        return result;
    }

    @Override
    public Component getName(ItemStack stack) {
        FluidStack fluid = fluid(stack);
        return fluid.isEmpty() ? super.getName(stack)
                : Component.translatable("item.rs_integration.fluid_refund.named", fluid.getDisplayName());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        CompoundTag raw = rawFluid(stack);
        if (raw.getInt("Amount") > 0) {
            tooltip.add(Component.translatable("rsi.fluid_recovery.item_amount", raw.getInt("Amount")));
        }
        tooltip.add(Component.translatable("rsi.fluid_recovery.item_hint"));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
        CraftStorageEndpoint endpoint = selectedBdEndpoint(serverPlayer);
        if (endpoint != null) {
            RestoreResult result = restoreEndpoint(serverPlayer, held, endpoint);
            showResult(serverPlayer, result);
            return result == RestoreResult.UNAVAILABLE || result == RestoreResult.INVALID
                    ? InteractionResultHolder.fail(held) : InteractionResultHolder.consume(held);
        }
        INetwork network = networkAtLookedBlock(serverPlayer);
        if (network == null) network = RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(serverPlayer);
        RestoreResult result = restore(serverPlayer, held, network);
        showResult(serverPlayer, result);
        return result == RestoreResult.UNAVAILABLE || result == RestoreResult.INVALID
                ? InteractionResultHolder.fail(held) : InteractionResultHolder.consume(held);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        INetwork network = RSIntegrationNetwork.resolveNetworkStrict(
                player.server, player.level().dimension(), context.getClickedPos());
        if (network == null) return InteractionResult.PASS;
        RestoreResult result = restore(player, context.getItemInHand(), network);
        showResult(player, result);
        return InteractionResult.CONSUME;
    }

    enum RestoreResult { STORED, PARTIAL, NO_SPACE, NO_PERMISSION, INVALID, UNAVAILABLE }

    @Nullable
    private static CraftStorageEndpoint selectedBdEndpoint(ServerPlayer player) {
        try {
            CraftStorageEndpoint endpoint = StorageRestockSupport.resolve(player).orElse(null);
            return endpoint != null && "beyonddimensions".equals(endpoint.session().reference().backendId().value())
                    ? endpoint : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static RestoreResult restoreEndpoint(ServerPlayer player, ItemStack voucher, CraftStorageEndpoint endpoint) {
        if (!(voucher.getItem() instanceof FluidRefundItem)) return RestoreResult.INVALID;
        CompoundTag raw = rawFluid(voucher);
        FluidStack fluid = FluidStack.loadFluidStackFromNBT(raw);
        if (fluid.isEmpty() || raw.getInt("Amount") <= 0) return RestoreResult.INVALID;
        if (endpoint == null) return RestoreResult.UNAVAILABLE;
        try {
            if (!endpoint.session().hasPermission(player, StoragePermission.INSERT)) return RestoreResult.NO_PERMISSION;
            ItemStack token = InkFluidSupport.token(fluid);
            var result = endpoint.insert(player, token, false);
            ItemStack remainder = result.remainder().orElse(null);
            if (remainder == null || remainder.getCount() > token.getCount()
                    || !remainder.isEmpty() && !InkFluidSupport.fluid(remainder).isFluidEqual(fluid)) {
                return RestoreResult.UNAVAILABLE;
            }
            if (remainder.getCount() == token.getCount()) return RestoreResult.NO_SPACE;
            if (remainder.isEmpty()) voucher.setCount(0);
            else raw.putInt("Amount", remainder.getCount());
            MaterialSources.invalidateFor(player);
            player.getInventory().setChanged();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
            return remainder.isEmpty() ? RestoreResult.STORED : RestoreResult.PARTIAL;
        } catch (RuntimeException | LinkageError unavailable) {
            return RestoreResult.UNAVAILABLE;
        }
    }

    static RestoreResult restore(ServerPlayer player, ItemStack voucher, @Nullable INetwork network) {
        if (!(voucher.getItem() instanceof FluidRefundItem)) return RestoreResult.INVALID;
        CompoundTag raw = rawFluid(voucher);
        FluidStack fluid = FluidStack.loadFluidStackFromNBT(raw);
        if (fluid.isEmpty() || raw.getInt("Amount") <= 0) return RestoreResult.INVALID;
        if (network == null) return RestoreResult.UNAVAILABLE;
        try {
            if (!network.canRun()) return RestoreResult.UNAVAILABLE;
            var security = network.getSecurityManager();
            if (security != null && !security.hasPermission(Permission.INSERT, player)) {
                return RestoreResult.NO_PERMISSION;
            }
            FluidStack remainder = network.insertFluid(fluid, fluid.getAmount(), Action.PERFORM);
            if (remainder == null || (!remainder.isEmpty() && (!remainder.isFluidEqual(fluid)
                    || remainder.getAmount() < 0 || remainder.getAmount() > fluid.getAmount()))) {
                return RestoreResult.UNAVAILABLE;
            }
            int accepted = fluid.getAmount() - remainder.getAmount();
            if (accepted == 0) return RestoreResult.NO_SPACE;
            if (remainder.isEmpty()) voucher.setCount(0);
            else raw.putInt("Amount", remainder.getAmount());
            var tracker = network.getFluidStorageTracker();
            if (tracker != null) {
                FluidStack stored = fluid.copy();
                stored.setAmount(accepted);
                tracker.changed(player, stored);
            }
            MaterialSources.invalidateFor(player);
            player.getInventory().setChanged();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
            return remainder.isEmpty() ? RestoreResult.STORED : RestoreResult.PARTIAL;
        } catch (RuntimeException | LinkageError unavailable) {
            return RestoreResult.UNAVAILABLE;
        }
    }

    private static CompoundTag rawFluid(ItemStack stack) {
        return stack.getTag() == null ? new CompoundTag() : stack.getTag().getCompound(FLUID);
    }

    private static FluidStack fluid(ItemStack stack) {
        return FluidStack.loadFluidStackFromNBT(rawFluid(stack));
    }

    @Nullable
    private static INetwork networkAtLookedBlock(ServerPlayer player) {
        HitResult hit = player.pick(5.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult block)) return null;
        return RSIntegrationNetwork.resolveNetworkStrict(
                player.server, player.level().dimension(), block.getBlockPos());
    }

    private static void showResult(ServerPlayer player, RestoreResult result) {
        String key = switch (result) {
            case STORED -> "rsi.fluid_recovery.item_stored";
            case PARTIAL -> "rsi.fluid_recovery.item_partial";
            case NO_SPACE -> "rsi.fluid_recovery.item_no_space";
            case NO_PERMISSION -> "rsi.fluid_recovery.item_no_permission";
            case INVALID -> "rsi.fluid_recovery.item_invalid";
            case UNAVAILABLE -> "rsi.fluid_recovery.item_unavailable";
        };
        player.displayClientMessage(Component.translatable(key), true);
    }

    static void restoreFromBlockClick(ServerPlayer player, ItemStack voucher, INetwork network) {
        showResult(player, restore(player, voucher, network));
    }
}
