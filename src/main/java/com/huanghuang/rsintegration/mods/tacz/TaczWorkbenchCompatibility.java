package com.huanghuang.rsintegration.mods.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.block.entity.GunSmithTableBlockEntity;
import com.tacz.guns.resource.filter.RecipeFilter;
import com.tacz.guns.resource.index.CommonBlockIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.function.BiPredicate;

/** Applies the same recipe filter used by TACZ's native gun-smith menu. */
public final class TaczWorkbenchCompatibility {

    private TaczWorkbenchCompatibility() {}

    @Nullable
    public static ResourceLocation blockId(ItemStack displayStack) {
        if (displayStack == null || displayStack.isEmpty()) return null;
        CompoundTag tag = displayStack.getTag();
        if (tag == null) return null;

        String value = null;
        if (tag.contains("BlockId", Tag.TAG_STRING)) {
            value = tag.getString("BlockId");
        } else if (tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
            CompoundTag blockEntityTag = tag.getCompound("BlockEntityTag");
            if (blockEntityTag.contains("BlockId", Tag.TAG_STRING)) {
                value = blockEntityTag.getString("BlockId");
            }
        }
        return value == null || value.isBlank() ? null : ResourceLocation.tryParse(value);
    }

    public static boolean accepts(ItemStack displayStack, ResourceLocation recipeId) {
        return accepts(displayStack, recipeId, TaczWorkbenchCompatibility::accepts);
    }

    static boolean accepts(ItemStack displayStack, ResourceLocation recipeId,
                           BiPredicate<ResourceLocation, ResourceLocation> filter) {
        ResourceLocation blockId = blockId(displayStack);
        return blockId != null && recipeId != null && filter.test(blockId, recipeId);
    }

    public static boolean accepts(ServerLevel level, BlockPos pos, ResourceLocation recipeId) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof GunSmithTableBlockEntity gunSmithTable)) return false;
        return accepts(gunSmithTable.getId(), recipeId);
    }

    public static boolean accepts(@Nullable ResourceLocation blockId, ResourceLocation recipeId) {
        if (blockId == null || recipeId == null) return false;
        CommonBlockIndex index = TimelessAPI.getCommonBlockIndex(blockId).orElse(null);
        if (index == null) return false;
        RecipeFilter filter = index.getFilter();
        return filter != null && filter.contains(recipeId);
    }
}
