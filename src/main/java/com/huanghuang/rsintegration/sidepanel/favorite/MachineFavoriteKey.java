package com.huanghuang.rsintegration.sidepanel.favorite;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record MachineFavoriteKey(ResourceLocation dimension, BlockPos pos, String blockKey) {
    public static final int MAX_BLOCK_KEY_LENGTH = 256;

    public MachineFavoriteKey {
        if (dimension == null || pos == null || blockKey == null || blockKey.isBlank()
                || blockKey.length() > MAX_BLOCK_KEY_LENGTH) {
            throw new IllegalArgumentException("Invalid machine favorite key");
        }
    }

    public static MachineFavoriteKey from(BindingInfo info) {
        return new MachineFavoriteKey(info.dim(), info.pos(), info.blockKey());
    }

    public boolean matches(BindingInfo info) {
        return matches(info.dim(), info.pos(), info.blockKey());
    }

    public boolean matches(ResourceLocation candidateDimension, BlockPos candidatePos,
                           String candidateBlockKey) {
        return dimension.equals(candidateDimension) && pos.equals(candidatePos)
                && blockKey.equals(candidateBlockKey);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeUtf(blockKey, MAX_BLOCK_KEY_LENGTH);
    }

    public static MachineFavoriteKey decode(FriendlyByteBuf buf) {
        return new MachineFavoriteKey(buf.readResourceLocation(), buf.readBlockPos(),
                buf.readUtf(MAX_BLOCK_KEY_LENGTH));
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimension.toString());
        tag.putLong("pos", pos.asLong());
        tag.putString("blockKey", blockKey);
        return tag;
    }

    @Nullable
    static MachineFavoriteKey load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        String blockKey = tag.getString("blockKey");
        if (dimension == null || blockKey.isBlank() || blockKey.length() > MAX_BLOCK_KEY_LENGTH) {
            return null;
        }
        try {
            return new MachineFavoriteKey(dimension, BlockPos.of(tag.getLong("pos")), blockKey);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
