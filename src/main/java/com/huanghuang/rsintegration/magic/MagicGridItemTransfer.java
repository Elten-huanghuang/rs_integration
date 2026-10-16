package com.huanghuang.rsintegration.magic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.util.Reflect;

import javax.annotation.Nullable;
import java.util.function.IntUnaryOperator;

/**
 * 魔法资源与“带量物品容器”（魔力石板、Source 罐等）之间的手动充放。
 *
 * <p>原生类全部按名称反射识别，所以这里不硬链接 Botania、Ars Nouveau 或 Goety，
 * 未安装对应模组时整条路径自然失效。三端共用同一份数量规则：容器记多少就转多少，
 * 不做跨资源换算。</p>
 */
public final class MagicGridItemTransfer {
    private MagicGridItemTransfer() {}

    private static final String MANA_ITEM = "vazkii.botania.api.mana.ManaItem";
    private static final ResourceLocation SOURCE_JAR_ID =
            new ResourceLocation("ars_nouveau", "source_jar");
    /** SourceJarTile 的固定容量；物品形态没有实例可查询容量。 */
    private static final int SOURCE_JAR_CAPACITY = 10_000;

    /** 容器余额读不出来时的返回值。 */
    private static final int UNKNOWN = -1;

    /** 手持容器当前能不能承载这种资源。 */
    public static boolean accepts(MagicResourceKind kind, @Nullable ItemStack stack) {
        return readable(kind) != UNKNOWN && kindOf(stack) == kind;
    }

    /**
     * 把容器里的资源充入库存，返回实际转移量。
     *
     * <p>先做一次模拟插入，只有库存确实收得下才扣除容器，避免容器被清空而能量丢失。</p>
     */
    public static int deposit(ServerPlayer player, MagicResourceKind kind, @Nullable ItemStack stack) {
        return transfer(player, kind, stack, true);
    }

    /** 从库存取资源填进容器，返回实际转移量。 */
    public static int withdraw(ServerPlayer player, MagicResourceKind kind, @Nullable ItemStack stack, int amount) {
        return transfer(player, kind, stack, false, amount);
    }

    private static int transfer(ServerPlayer player, MagicResourceKind kind,
                               @Nullable ItemStack stack, boolean deposit) {
        return transfer(player, kind, stack, deposit, Integer.MAX_VALUE);
    }

    /**
     * @param deposit 真表示容器→库存，假表示库存→容器
     * @param amount  取出上限，仅对库存→容器有意义
     */
    private static int transfer(ServerPlayer player, MagicResourceKind kind,
                               @Nullable ItemStack stack, boolean deposit, int amount) {
        if (player == null || stack == null || stack.isEmpty()) return 0;
        int balance = read(kind, stack);
        if (balance == UNKNOWN) return 0;
        int handled = deposit ? depositToStock(player, kind, stack, balance)
                : withdrawToContainer(player, kind, stack, amount);
        if (handled > 0) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        return handled;
    }

    private static int depositToStock(ServerPlayer player, MagicResourceKind kind, ItemStack stack, int balance) {
        if (balance <= 0) return 0;
        int accepted = MagicResourceEndpoint.insert(player.getServer(), kind, balance, false);
        if (accepted <= 0) return 0;
        int moved = write(kind, stack, balance - accepted);
        if (moved != accepted) return 0;
        MagicResourceEndpoint.insert(player.getServer(), kind, accepted, true);
        return accepted;
    }

    private static int withdrawToContainer(ServerPlayer player, MagicResourceKind kind,
                                          ItemStack stack, int amount) {
        long space = space(kind, stack);
        if (space <= 0) return 0;
        int requested = (int) Math.min(Math.min(space, Math.max(0, amount)), kind.capacity());
        if (requested <= 0) return 0;
        int extracted = MagicResourceEndpoint.extract(player.getServer(), kind, requested, true);
        if (extracted <= 0) return 0;
        int restored = write(kind, stack, read(kind, stack) + extracted);
        if (restored == extracted) return extracted;
        // 写回失败时把刚取出的部分退回库存，不能让资源凭空消失。
        MagicResourceEndpoint.insert(player.getServer(), kind, extracted, true);
        return 0;
    }

    private static int readable(MagicResourceKind kind) {
        return kind.available() ? kind.ordinal() : UNKNOWN;
    }

    /** 容器归属的资源种类；不属于任何魔法资源时返回 null。 */
    @Nullable
    public static MagicResourceKind kindOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (isManaItem(stack)) return MagicResourceKind.MANA;
        if (isSourceJar(stack)) return MagicResourceKind.SOURCE;
        if (GoetySoulTotem.isTotem(stack)) return MagicResourceKind.SOUL;
        return null;
    }

    /** 容器当前余额；读不出来返回 {@link #UNKNOWN}。 */
    public static int read(MagicResourceKind kind, @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || !kind.available()) return UNKNOWN;
        IntUnaryOperator getter = switch (kind) {
            // 灵魂图腾是自己实现的 NBT 读写，不经过反射。
            case SOUL -> item -> GoetySoulTotem.souls(stack);
            case MANA -> item -> isManaItem(stack)
                    ? intMethod(stack, MANA_ITEM, "getMana", UNKNOWN) : UNKNOWN;
            case SOURCE -> item -> isSourceJar(stack) ? sourceAmount(stack) : UNKNOWN;
        };
        return getter.applyAsInt(0);
    }

    /** 容器还能装多少；读不出来返回 0。 */
    public static long space(MagicResourceKind kind, @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || !kind.available()) return 0L;
        return switch (kind) {
            case SOUL -> GoetySoulTotem.space(stack);
            case MANA -> isManaItem(stack) ? Math.max(0, intMethod(stack, MANA_ITEM, "getMaxMana", 0)
                    - intMethod(stack, MANA_ITEM, "getMana", 0)) : 0L;
            case SOURCE -> {
                boolean jar = isSourceJar(stack);
                int current = sourceAmount(stack);
                yield jar && current != UNKNOWN
                        ? Math.max(0, SOURCE_JAR_CAPACITY - current) : 0L;
            }
        };
    }

    /**
     * 把容器余额写成 {@code amount}；成功返回改动量，失败返回 {@link #UNKNOWN}。
     *
     * <p>写回后重新读取一次做校验，防止原生实现自行截断导致实际数量不符。</p>
     */
    public static int write(MagicResourceKind kind, ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || !kind.available()) return UNKNOWN;
        int before = read(kind, stack);
        if (before == UNKNOWN) return UNKNOWN;
        boolean ok = switch (kind) {
            case SOUL -> {
                GoetySoulTotem.set(stack, amount);
                yield GoetySoulTotem.souls(stack) == amount;
            }
            case MANA -> {
                if (!isManaItem(stack)) yield false;
                int delta = amount - before;
                boolean applied = invokeVoid(stack, MANA_ITEM, "addMana", delta,
                        new Class<?>[]{int.class});
                yield applied && intMethod(stack, MANA_ITEM, "getMana", UNKNOWN) == amount;
            }
            case SOURCE -> {
                if (!isSourceJar(stack)) yield false;
                sourceTag(stack, true).putInt("source", amount);
                yield sourceAmount(stack) == amount;
            }
        };
        return ok ? amount - before : UNKNOWN;
    }

    private static boolean isManaItem(ItemStack stack) {
        return implementsType(stack.getItem().getClass(), MANA_ITEM);
    }

    private static boolean isSourceJar(ItemStack stack) {
        return SOURCE_JAR_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 名称匹配到的接口或父类都在允许范围；Goety 用类名白名单，保持等价语义。 */
    private static boolean implementsType(Class<?> type, String typeName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (typeName.equals(current.getName())) return true;
            for (Class<?> contract : current.getInterfaces()) {
                if (namedInterface(contract, typeName)) return true;
            }
        }
        return false;
    }

    private static boolean namedInterface(Class<?> type, String typeName) {
        if (typeName.equals(type.getName())) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (namedInterface(parent, typeName)) return true;
        }
        return false;
    }

    private static int intMethod(Object target, String typeName, String methodName, int fallback) {
        Object raw = invoke(target, typeName, methodName, new Class<?>[0]);
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean invokeVoid(Object target, String typeName, String methodName,
                                      Object argument, Class<?>[] parameterTypes) {
        return invoke(target, typeName, methodName, parameterTypes, argument) == null
                && resolved(target, typeName, methodName, parameterTypes) != null;
    }

    private static Object invoke(Object target, String typeName, String methodName, Class<?>[] parameterTypes,
                                 Object... arguments) {
        java.lang.reflect.Method method = resolved(target, typeName, methodName, parameterTypes);
        if (method == null) return null;
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method resolved(Object target, String typeName, String methodName,
                                                     Class<?>[] parameterTypes) {
        Class<?> proxy = primal(typeName);
        if (proxy == null || !proxy.isInstance(target)) return null;
        try {
            return proxy.getMethod(methodName, parameterTypes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Class<?> primal(String typeName) {
        return Reflect.forName(typeName).orElse(null);
    }

    /** Source 罐的数量通常位于方块实体数据的 source 字段。 */
    private static int sourceAmount(ItemStack stack) {
        CompoundTag tag = sourceTag(stack, false);
        return tag == null ? 0 : Math.max(0, tag.getInt("source"));
    }

    /** 返回 Source 数据所在的复合标签；不存在时可选择创建方块实体标签。 */
    @Nullable
    private static CompoundTag sourceTag(ItemStack stack, boolean create) {
        CompoundTag root = stack.getTag();
        if (root != null && root.contains("BlockEntityTag", CompoundTag.TAG_COMPOUND)) {
            return root.getCompound("BlockEntityTag");
        }
        if (root != null && root.contains("source")) return root;
        if (!create) return null;
        CompoundTag rootTag = stack.getOrCreateTag();
        CompoundTag blockEntityTag = new CompoundTag();
        rootTag.put("BlockEntityTag", blockEntityTag);
        return blockEntityTag;
    }
}
