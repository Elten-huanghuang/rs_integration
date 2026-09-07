package com.huanghuang.rsintegration.crafting.availability;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Objects;

/** A displayed recipe variant and its selected machine; stack tags are owned copies. */
public record RecipeAvailabilityKey(ResourceLocation recipeId, ResourceLocation dimension,
                                    BlockPos machinePos, CompoundTag base, CompoundTag output) {
    public RecipeAvailabilityKey {
        Objects.requireNonNull(recipeId);
        Objects.requireNonNull(dimension);
        machinePos = Objects.requireNonNull(machinePos).immutable();
        base = Objects.requireNonNull(base).copy();
        output = Objects.requireNonNull(output).copy();
    }

    public static RecipeAvailabilityKey of(ResourceLocation recipeId, ResourceLocation dimension,
                                            BlockPos pos, @Nullable ItemStack base,
                                            @Nullable ItemStack output) {
        return new RecipeAvailabilityKey(recipeId, dimension, pos, save(base), save(output));
    }

    private static CompoundTag save(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? new CompoundTag() : stack.save(new CompoundTag());
    }

    @Override public CompoundTag base() { return base.copy(); }
    @Override public CompoundTag output() { return output.copy(); }
    public ItemStack baseStack() { return ItemStack.of(base.copy()); }
    public ItemStack outputStack() { return ItemStack.of(output.copy()); }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(machinePos);
        buf.writeNbt(base);
        buf.writeNbt(output);
    }

    public static RecipeAvailabilityKey decode(FriendlyByteBuf buf) {
        return new RecipeAvailabilityKey(buf.readResourceLocation(), buf.readResourceLocation(),
                buf.readBlockPos(), Objects.requireNonNull(buf.readNbt()),
                Objects.requireNonNull(buf.readNbt()));
    }
}
