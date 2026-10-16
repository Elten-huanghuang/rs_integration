package com.huanghuang.rsintegration.magic;

import com.wintercogs.beyonddimensions.api.ids.BDConstants;
import com.wintercogs.beyonddimensions.api.storage.key.IStackRender;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.LongStackKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/** RSI 注册到 BD 的 Goety 灵魂身份；数量由 KeyAmount 单独保存。 */
public final class GoetySoulStackKey extends LongStackKey<GoetySoulType> {
    public static final ResourceLocation ID = new ResourceLocation(BDConstants.MODID, "stack_type/goety_soul");
    public static final GoetySoulStackKey INSTANCE = new GoetySoulStackKey();

    private GoetySoulStackKey() { this.stack = new GoetySoulType(0); }

    @Override public @Nullable KeyAmount fromStackObject(Object stack) {
        return stack instanceof GoetySoulType soul ? new KeyAmount(INSTANCE, soul.getStackCount()) : null;
    }

    @Override public long getVanillaMaxStackSize() { return Integer.MAX_VALUE; }
    @Override public ResourceLocation getTypeID() { return ID; }
    @Override public String getModId() { return "goety"; }
    @Override public GoetySoulStackKey getEmpty() { return INSTANCE; }

    @Override public @Nullable GoetySoulStackKey fromSourceObject(Object key, CompoundTag ignored) {
        return key instanceof GoetySoulType || key instanceof Number ? INSTANCE : null;
    }

    @Override public @NotNull GoetySoulType getSource() { return stack; }
    @Override public GoetySoulType getEmptyStack() { return new GoetySoulType(0); }
    @Override public boolean hasTag(TagKey<?> tagKey) { return false; }
    @Override public Stream<? extends TagKey<?>> getTags() { return Stream.empty(); }
    @Override public void serialize(FriendlyByteBuf buf) {}
    @Override public @NotNull GoetySoulStackKey deserialize(FriendlyByteBuf buf) { return INSTANCE; }
    @Override public @NotNull CompoundTag serializeNBT() { return new CompoundTag(); }
    @Override public @NotNull GoetySoulStackKey deserializeNBT(CompoundTag nbt) { return INSTANCE; }
    @Override public @NotNull IStackRender getRender() { return GoetySoulStackKeyRender.INSTANCE; }
}
