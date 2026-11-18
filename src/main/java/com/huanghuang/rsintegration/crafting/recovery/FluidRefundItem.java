package com.huanghuang.rsintegration.crafting.recovery;

import com.huanghuang.rsintegration.crafting.recovery.FluidVoucherRecovery.RestoreResult;
import com.huanghuang.rsintegration.ModItems;
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
        RestoreResult result = FluidVoucherRecovery.use(serverPlayer, held);
        showResult(serverPlayer, result);
        return result == RestoreResult.UNAVAILABLE || result == RestoreResult.INVALID
                ? InteractionResultHolder.fail(held) : InteractionResultHolder.consume(held);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        RestoreResult result = FluidVoucherRecovery.atBlock(player, context.getItemInHand(), context.getClickedPos());
        if (result == null) return InteractionResult.PASS;
        showResult(player, result);
        return InteractionResult.CONSUME;
    }

    static CompoundTag rawFluid(ItemStack stack) {
        return stack.getTag() == null ? new CompoundTag() : stack.getTag().getCompound(FLUID);
    }

    private static FluidStack fluid(ItemStack stack) {
        return FluidStack.loadFluidStackFromNBT(rawFluid(stack));
    }

    static void showResult(ServerPlayer player, RestoreResult result) {
        String key = switch (result) {
            case STORED -> "rsi.fluid_recovery.item_stored";
            case PARTIAL -> "rsi.fluid_recovery.item_partial";
            case NO_SPACE -> "rsi.fluid_recovery.item_no_space";
            case NO_PERMISSION -> "rsi.fluid_recovery.item_no_permission";
            case UNKNOWN -> "rsi.fluid_recovery.item_unknown";
            case INVALID -> "rsi.fluid_recovery.item_invalid";
            case UNAVAILABLE -> "rsi.fluid_recovery.item_unavailable";
        };
        player.displayClientMessage(Component.translatable(key), true);
    }

}
