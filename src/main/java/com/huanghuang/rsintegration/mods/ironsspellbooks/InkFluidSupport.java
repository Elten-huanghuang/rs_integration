package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModItems;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** RS 原生流体与递归合成物品账本之间的边界，绝不把流体凭据当作 RS 物品存储。 */
public final class InkFluidSupport {
    public static final int BOTTLE_AMOUNT = 250;
    private InkFluidSupport() {}

    public static boolean isToken(ItemStack stack) { return stack.getItem() instanceof InkFluidItem; }

    /** 原版物品数量只传一个字节，流体数量通过 NBT 保存，避免 250 mB 被截断。 */
    public static void writePacket(FriendlyByteBuf buffer, ItemStack stack) {
        if (!isToken(stack)) { buffer.writeItem(stack); return; }
        ItemStack encoded = stack.copyWithCount(1);
        encoded.getOrCreateTag().putInt("RSIFluidAmount", stack.getCount());
        buffer.writeItem(encoded);
    }

    public static ItemStack readPacket(FriendlyByteBuf buffer) {
        ItemStack stack = buffer.readItem();
        if (isToken(stack) && stack.getTag() != null) {
            int amount = stack.getTag().contains("RSIFluidAmount")
                    ? stack.getTag().getInt("RSIFluidAmount") : stack.getCount();
            if (amount <= 0) throw new DecoderException("流体数量必须为正数");
            stack.setCount(amount);
            stack.getTag().remove("RSIFluidAmount");
        }
        return stack;
    }

    public static FluidStack fluid(ItemStack stack) {
        if (!isToken(stack) || stack.getTag() == null) return FluidStack.EMPTY;
        FluidStack result = FluidStack.loadFluidStackFromNBT(stack.getTag());
        result.setAmount(stack.getCount());
        return result;
    }

    public static ItemStack token(FluidStack fluid) {
        return token(ModItems.ALCHEMIST_INK_FLUID.get(), fluid);
    }

    public static ItemStack token(Item item, FluidStack fluid) {
        if (fluid.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(item, fluid.getAmount());
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        result.setTag(identity.writeToNBT(new CompoundTag()));
        return result;
    }

    public static boolean isInk(FluidStack fluid) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
        return id != null && id.getNamespace().equals("irons_spellbooks")
                && Set.of("common_ink", "uncommon_ink", "rare_ink", "epic_ink", "legendary_ink")
                .contains(id.getPath());
    }

    public static List<ItemStack> snapshot(INetwork network, Set<Item> itemTypes) {
        if (!ModItems.ALCHEMIST_INK_FLUID.isPresent()
                || (itemTypes != null && !itemTypes.contains(ModItems.ALCHEMIST_INK_FLUID.get()))) return List.of();
        var cache = network.getFluidStorageCache();
        if (cache == null || cache.getList() == null) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (var entry : cache.getList().getStacks()) {
            FluidStack fluid = entry.getStack();
            // 炼金机也使用这套流体账本，包括水、熔岩和模组流体。
            if (!fluid.isEmpty()) result.add(token(fluid));
        }
        return result;
    }

    public static ItemStack extract(INetwork network, ItemStack template, int amount, boolean simulate) {
        FluidStack requested = fluid(template);
        if (requested.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        FluidStack extracted = network.extractFluid(requested, amount, simulate ? Action.SIMULATE : Action.PERFORM);
        if (extracted == null) throw new IllegalStateException("RS 返回了无效的流体提取结果");
        return extracted.isEmpty() ? ItemStack.EMPTY : token(template.getItem(), extracted);
    }

    public static ItemStack insert(INetwork network, ItemStack stack, boolean simulate) {
        FluidStack requested = fluid(stack);
        if (requested.isEmpty()) return stack.copy();
        FluidStack remainder = network.insertFluid(requested, requested.getAmount(), simulate ? Action.SIMULATE : Action.PERFORM);
        if (remainder == null) throw new IllegalStateException("RS 返回了无效的流体存入结果");
        return remainder.isEmpty() ? ItemStack.EMPTY : token(stack.getItem(), remainder);
    }
}
