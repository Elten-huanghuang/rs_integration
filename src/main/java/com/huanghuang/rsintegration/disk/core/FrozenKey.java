package com.huanghuang.rsintegration.disk.core;

import com.huanghuang.rsintegration.storage.StorageIdentityBytes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** 身份签名和可恢复载荷分离；索引不持有调用方的可变 Stack。 */
public final class FrozenKey {
    public enum Kind { ITEM, FLUID }
    /** 保留缺失资源的类型和注册名，供玩家提示使用，避免解析日志文本。 */
    public static final class MissingResourceException extends IOException {
        private final Kind kind;
        private final ResourceLocation resource;

        public MissingResourceException(Kind kind, ResourceLocation resource) {
            super("资源注册项不存在: " + resource);
            this.kind = kind;
            this.resource = resource;
        }

        public Kind kind() { return kind; }
        public ResourceLocation resource() { return resource; }
    }
    public static final int MAX_BYTES = 1048576;
    private final Kind kind;
    private final Object type;
    private final CompoundTag tag;
    private final CompoundTag caps;
    private final boolean malformed;
    private final byte[] payload;
    private final int hash;
    private final Object template;
    private final boolean plain;

    private FrozenKey(Kind kind, Object type, CompoundTag payload, Object template) {
        this(kind, type, payload, template, null);
    }

    private FrozenKey(Kind kind, Object type, CompoundTag payload, Object template, byte[] original) {
        this(kind, type, payload, template, original, null);
    }

    private FrozenKey(Kind kind, Object type, CompoundTag payload, Object template, byte[] original, FrozenKey query) {
        this.kind = kind;
        this.type = type;
        String tagField = kind == Kind.ITEM ? "tag" : "Tag";
        boolean hasCaps = payload.contains("ForgeCaps");
        // 不保留每项 id/Count 外层 Compound；能力模板独立冻结，普通 tag 与私有模板共享。
        if (!hasCaps && template instanceof ItemStack stack && stack.getTag() != null) payload.put(tagField, stack.getTag());
        if (template instanceof FluidStack stack && stack.getTag() != null) payload.put(tagField, stack.getTag());
        this.tag = payload.contains(tagField) ? hasCaps ? payload.getCompound(tagField).copy() : payload.getCompound(tagField) : null;
        this.caps = hasCaps ? payload.getCompound("ForgeCaps").copy() : null;
        if (query != null && query.kind == kind && query.type == type && sameContent(query)) {
            this.hash = query.hash;
            this.malformed = query.malformed;
        } else {
            NbtIdentity.Result result = NbtIdentity.inspect(payload);
            this.hash = 31 * (31 * kind.hashCode() + type.hashCode()) + result.hash();
            this.malformed = result.malformed();
        }
        this.payload = original == null ? encode(payload) : original.clone();
        if (this.payload.length > MAX_BYTES) throw new IllegalArgumentException("统一盘资源身份过大");
        this.template = template;
        this.plain = payload.size() == 2 && !payload.contains(kind == Kind.ITEM ? "tag" : "Tag")
                && !payload.contains("ForgeCaps");
    }

    /** 临时查询只冻结身份，不生成保存字节或返回模板；不能放入库存。 */
    private FrozenKey(Kind kind, Object type, CompoundTag tag, CompoundTag caps) {
        this.kind = kind;
        this.type = type;
        this.tag = tag == null ? null : tag.copy();
        this.caps = caps == null ? null : caps.copy();
        String id = (kind == Kind.ITEM ? ForgeRegistries.ITEMS.getKey((Item) type)
                : ForgeRegistries.FLUIDS.getKey((Fluid) type)).toString();
        NbtIdentity.Result result = NbtIdentity.inspectResource(kind == Kind.FLUID, id, this.tag, this.caps);
        this.hash = 31 * (31 * kind.hashCode() + type.hashCode()) + result.hash();
        this.malformed = result.malformed();
        this.payload = null;
        this.template = null;
        this.plain = false;
    }

    public static boolean plainItem(ItemStack source) {
        // Forge 的 null dispatcher 比较只检查是否存在可序列化能力，不缓存可变 Stack 的结论。
        return source.areCapsCompatible((CapabilityDispatcher) null) && source.getTag() == null;
    }

    public static FrozenKey queryItem(ItemStack source) {
        if (source.isEmpty()) throw new IllegalArgumentException("空物品不能作为资源身份");
        // 带能力物品沿用复制后归一化的路径，避免 count 相关能力在复制时改变身份。
        if (!source.areCapsCompatible((CapabilityDispatcher) null)) return item(source);
        return new FrozenKey(Kind.ITEM, source.getItem(), source.getTag(), null);
    }

    public static FrozenKey queryFluid(FluidStack source) {
        if (source.isEmpty()) throw new IllegalArgumentException("空流体不能作为资源身份");
        return new FrozenKey(Kind.FLUID, source.getFluid(), source.getTag(), null);
    }

    public static FrozenKey item(ItemStack source) {
        if (source.isEmpty()) throw new IllegalArgumentException("空物品不能作为资源身份");
        ItemStack copy = source.copy();
        copy.setCount(1);
        return new FrozenKey(Kind.ITEM, copy.getItem(), copy.save(new CompoundTag()), copy);
    }

    public static FrozenKey fluid(FluidStack source) {
        if (source.isEmpty()) throw new IllegalArgumentException("空流体不能作为资源身份");
        FluidStack copy = source.copy();
        copy.setAmount(1);
        return new FrozenKey(Kind.FLUID, copy.getFluid(), copy.writeToNBT(new CompoundTag()), copy);
    }

    /** 未命中的查询提升为完整模板；正常身份复用已完成的校验和哈希。 */
    public FrozenKey freezeItem(ItemStack source) {
        if (stored()) return this;
        ItemStack copy = source.copy(); copy.setCount(1);
        return new FrozenKey(Kind.ITEM, copy.getItem(), copy.save(new CompoundTag()), copy, null, this);
    }

    public FrozenKey freezeFluid(FluidStack source) {
        if (stored()) return this;
        FluidStack copy = source.copy(); copy.setAmount(1);
        return new FrozenKey(Kind.FLUID, copy.getFluid(), copy.writeToNBT(new CompoundTag()), copy, null, this);
    }

    public static FrozenKey load(Kind kind, byte[] bytes) throws IOException {
        CompoundTag tag = decode(bytes);
        String amountField = kind == Kind.ITEM ? "Count" : "Amount";
        if (tag.getInt(amountField) != 1) throw new IOException("模板数量必须为 1");
        String name = tag.getString(kind == Kind.ITEM ? "id" : "FluidName");
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) throw new IOException("资源注册名非法: " + name);
        if (kind == Kind.ITEM
                ? !ForgeRegistries.ITEMS.containsKey(id) || ForgeRegistries.ITEMS.getValue(id) == Items.AIR
                : !ForgeRegistries.FLUIDS.containsKey(id) || ForgeRegistries.FLUIDS.getValue(id) == Fluids.EMPTY) {
            throw new MissingResourceException(kind, id);
        }
        // 不把未知资源或无法稳定比较的载荷变为空库存。
        try {
            FrozenKey key = kind == Kind.ITEM ? item(ItemStack.of(tag)) : fluid(FluidStack.loadFluidStackFromNBT(tag));
            if (!Arrays.equals(StorageIdentityBytes.exact(key.identity()), StorageIdentityBytes.exact(tag))) {
                throw new IOException("资源载荷恢复后身份发生变化，拒绝开放写入");
            }
            return new FrozenKey(kind, key.type, tag, key.template, bytes);
        } catch (RuntimeException e) {
            throw new IOException("资源身份无法恢复: " + name, e);
        }
    }

    public Kind kind() { return kind; }
    public Object type() { return type; }
    boolean plain() { return plain; }
    boolean stored() { return payload != null; }
    public int payloadBytes() { return payload.length; }
    public byte[] payload() { return payload.clone(); }

    public ItemStack itemStack(int amount) {
        ItemStack result = ((ItemStack) template).copy();
        result.setCount(amount);
        return result;
    }

    public FluidStack fluidStack(int amount) {
        FluidStack result = ((FluidStack) template).copy();
        result.setAmount(amount);
        return result;
    }

    @Override public int hashCode() { return hash; }
    private boolean sameContent(FrozenKey key) {
        return NbtIdentity.equal(tag, key.tag) && NbtIdentity.equal(caps, key.caps);
    }
    private CompoundTag identity() {
        CompoundTag root = new CompoundTag();
        if (kind == Kind.ITEM) {
            root.putString("id", ForgeRegistries.ITEMS.getKey((Item) type).toString()); root.putByte("Count", (byte) 1);
        } else {
            root.putString("FluidName", ForgeRegistries.FLUIDS.getKey((Fluid) type).toString()); root.putInt("Amount", 1);
        }
        if (tag != null) root.put(kind == Kind.ITEM ? "tag" : "Tag", tag);
        if (caps != null) root.put("ForgeCaps", caps);
        return root;
    }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof FrozenKey key && hash == key.hash
                && kind == key.kind && type == key.type && (malformed || key.malformed
                ? Arrays.equals(StorageIdentityBytes.exact(identity()), StorageIdentityBytes.exact(key.identity()))
                : sameContent(key));
    }

    private static byte[] encode(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("资源 NBT 无法编码", e);
        }
    }

    private static CompoundTag decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("资源载荷过大");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(MAX_BYTES * 16L));
            if (input.available() != 0) throw new IOException("资源载荷包含尾随字节");
            return tag;
        }
    }
}
