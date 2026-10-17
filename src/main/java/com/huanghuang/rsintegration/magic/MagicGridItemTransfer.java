package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.IntUnaryOperator;

/**
 * 魔法资源与“带量物品容器”（魔力石板、Source 罐等）之间的手动充放。
 *
 * <p>原生类全部按名称反射识别，所以这里不硬链接 Botania、Ars Nouveau 或 Goety，
 * 未安装对应模组时整条路径自然失效。三端共用同一份数量规则：容器记多少就转多少，
 * 不做跨资源换算。</p>
 *
 * <p>Botania 的魔力容器走 Forge 能力（{@code BotaniaForgeCapabilities.MANA_ITEM}）。
 * 石板、魔力指环这些物品本身没有实现 {@code ManaItem}，能力是在
 * {@code AttachCapabilitiesEvent} 里挂到 {@code ItemStack} 上的，所以只能问物品堆要，
 * 不能看物品类的继承关系。</p>
 */
public final class MagicGridItemTransfer {
    private MagicGridItemTransfer() {}

    private static final String MANA_ITEM = "vazkii.botania.api.mana.ManaItem";
    /**
     * 加魔力的方法名。物品模型的内部实现类不会被重映射（成品包里只有原版方法带
     * {@code m_xxxxx_} 名），所以沿用接口声明的方法名即可。
     */
    private static final String MANA_ADD = "addMana";
    /** 能力常量所在类；只有找到 Botania 后才尝试读取字段。 */
    private static final String BOTANIA_CAPABILITIES = "vazkii.botania.api.BotaniaForgeCapabilities";
    private static final String MANA_ITEM_CAPABILITY = "MANA_ITEM";
    private static final ResourceLocation SOURCE_JAR_ID =
            new ResourceLocation("ars_nouveau", "source_jar");
    /** SourceJarTile 的固定容量；物品形态没有实例可查询容量。 */
    private static final int SOURCE_JAR_CAPACITY = 10_000;

    /** 容器余额读不出来时的返回值。 */
    private static final int UNKNOWN = -1;

    /** 单测用的能力常量替身；真机上始终为 null，不会走到。 */
    private static Capability<?> manaItemCapabilityOverride;

    /** 物品堆上的原生容器句柄；{@code nativeType} 为空表示这个堆不是那种容器。 */
    private record Binding(Object nativeType, int balance, int max) {}

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

    /**
     * 容器→库存。
     *
     * <p>先按库存实际收得下的量把容器改成「余额 - 收下量」，再把差额真正落进库存。
     * 顺序反过来会写坏容器：{@code write} 对 MANA 走的是 {@code addMana(delta)}，
     * 如果先写入库存、再按「写回失败」回滚，就会在已经收下能量的库存上再插一次，
     * 凭空多出一份。</p>
     */
    private static int depositToStock(ServerPlayer player, MagicResourceKind kind, ItemStack stack, int balance) {
        if (balance <= 0) return 0;
        int accepted = MagicResourceEndpoint.insert(player.getServer(), kind, balance, false);
        // Botania 按单件保存余额，堆叠物品的总余额只能按堆叠数变化。
        if (kind == MagicResourceKind.MANA) {
            int stackCount = Math.max(1, stack.getCount());
            accepted -= accepted % stackCount;
        }
        if (accepted <= 0) {
            RSIntegrationMod.debug("[RSI-Magic] 充入失败：{} 库存未收下任何量（没有已挂载的盘或容量已满）", kind.id());
            return 0;
        }
        // write 返回的是「本次改动量」：充入时是负数，所以容器实扣量要取相反数。
        int delta = write(kind, stack, balance - accepted);
        int removed = delta == UNKNOWN ? -1 : -delta;
        if (removed < 0) {
            // 原生实现可能只吃下一部分就报不一致，按重新读取的差额兜底，
            // 否则这部分能量会从容器里消失却没进库存。
            int after = read(kind, stack);
            removed = after == UNKNOWN ? 0 : Math.max(0, balance - after);
        }
        if (removed <= 0) {
            RSIntegrationMod.debug("[RSI-Magic] 充入失败：{} 容器未扣减（余额 {}）", kind.id(), balance);
            return 0;
        }
        if (removed != accepted) {
            // 原生实现是「读改写」，多容器同堆时可能扣多或扣少：多扣的还给容器，
            // 少扣的按实际收下的量认账，两边都不能凭空多出或少掉能量。
            int targetBalance = balance - accepted;
            int corrected = write(kind, stack, targetBalance);
            int afterCorrection = read(kind, stack);
            if (corrected != UNKNOWN && afterCorrection == targetBalance) {
                removed = accepted;
            } else {
                if (afterCorrection != UNKNOWN) {
                    removed = Math.max(0, balance - afterCorrection);
                } else if (corrected != UNKNOWN) {
                    removed = Math.max(0, removed - corrected);
                }
                accepted = Math.min(accepted, removed);
                RSIntegrationMod.debug("[RSI-Magic] 充入对账失败：{} 库存可收 {}，容器实扣 {}，按实扣量入账",
                        kind.id(), accepted, removed);
            }
        }
        if (accepted <= 0) return 0;
        MagicResourceEndpoint.insert(player.getServer(), kind, accepted, true);
        if (removed != accepted)
            RSIntegrationMod.debug("[RSI-Magic] 充入部分完成：{} 库存收下 {}，容器实扣 {}", kind.id(), accepted, removed);
        return accepted;
    }

    /**
     * 库存→容器。
     *
     * <p>{@code write} 对 MANA 同样按差值处理，所以只要容器写进了部分就按实际写进量返回。</p>
     */
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
        // restored 是本次改动量：写进容器多少就留多少，剩下的退回库存，不能让资源凭空消失。
        int kept = restored == UNKNOWN ? 0 : Math.max(0, restored);
        int refund = extracted - kept;
        if (refund > 0) {
            int back = MagicResourceEndpoint.insert(player.getServer(), kind, refund, true);
            if (back != refund)
                RSIntegrationMod.debug("[RSI-Magic] 取出回滚不全：{} 退回 {}，库存只收下 {}", kind.id(), refund, back);
        }
        RSIntegrationMod.debug("[RSI-Magic] 取出部分完成：{} 已写回容器 {}，退回库存 {}", kind.id(), kept, refund);
        return kept;
    }

    private static int readable(MagicResourceKind kind) {
        return kind.available() ? kind.ordinal() : UNKNOWN;
    }

    /** 容器归属的资源种类；不属于任何魔法资源时返回 null。 */
    @Nullable
    public static MagicResourceKind kindOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (manaBinding(stack) != null) return MagicResourceKind.MANA;
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
            case MANA -> item -> {
                Binding binding = manaBinding(stack);
                return binding == null ? UNKNOWN : binding.balance();
            };
            case SOURCE -> item -> isSourceJar(stack) ? sourceAmount(stack) : UNKNOWN;
        };
        return getter.applyAsInt(0);
    }

    /** 容器还能装多少；读不出来返回 0。 */
    public static long space(MagicResourceKind kind, @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || !kind.available()) return 0L;
        return switch (kind) {
            case SOUL -> GoetySoulTotem.space(stack);
            case MANA -> {
                Binding binding = manaBinding(stack);
                yield binding == null ? 0L : Math.max(0, binding.max() - binding.balance());
            }
            case SOURCE -> {
                boolean jar = isSourceJar(stack);
                int current = sourceAmount(stack);
                yield jar && current != UNKNOWN
                        ? Math.max(0, SOURCE_JAR_CAPACITY - current) : 0L;
            }
        };
    }

    /**
     * 尝试把容器余额写成 {@code amount}；返回实际改变量，无法读取时返回 {@link #UNKNOWN}。
     *
     * <p>原生实现可能按堆叠数取整，因此必须按写回后的真实余额结算，而不能假设改变量
     * 一定等于目标差值。</p>
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
                Binding binding = manaBinding(stack);
                if (binding == null) yield false;
                int delta = amount - binding.balance();
                // 目标就是当前余额时不能调 addMana(0)：原生实现会先算
                // (余额 + 0) / 堆叠数，堆叠数大于 1 时这一步自己就把余额改掉。
                if (delta == 0) yield true;
                yield manaAdd(binding.nativeType(), delta) && read(kind, stack) == amount;
            }
            case SOURCE -> {
                if (!isSourceJar(stack)) yield false;
                sourceTag(stack, true).putInt("source", amount);
                yield sourceAmount(stack) == amount;
            }
        };
        int after = read(kind, stack);
        if (after == UNKNOWN || (!ok && after == before)) return UNKNOWN;
        return after - before;
    }

    /** 物品堆上挂着的 Botania 魔力容器；不是魔力容器时返回 null。 */
    @Nullable
    private static Binding manaBinding(ItemStack stack) {
        Object capability = manaItemCapability(stack);
        if (capability == null) return null;
        int balance = intMethod(capability, MANA_ITEM, "getMana", UNKNOWN);
        if (balance == UNKNOWN) return null;
        int max = intMethod(capability, MANA_ITEM, "getMaxMana", 0);
        return new Binding(capability, Math.max(0, balance), Math.max(0, max));
    }

    /**
     * 取 Botania 挂到物品堆上的 {@code ManaItem} 能力对象。
     *
     * <p>能力常量按名称从 {@code BotaniaForgeCapabilities} 上读：读静态字段不会触发
     * 该类的静态初始化，未安装 Botania 时只是拿不到字段。物品本身不实现
     * {@code ManaItem}，能力是挂在 {@code ItemStack} 上的，所以必须走能力这条路径。</p>
     */
    @Nullable
    private static Object manaItemCapability(ItemStack stack) {
        Capability<?> capability = manaItemCapabilityType();
        if (capability == null) return null;
        LazyOptional<?> optional = stack.getCapability(capability, (Direction) null);
        return optional == null ? null : optional.resolve().orElse(null);
    }

    /** 按名称取 {@code BotaniaForgeCapabilities.MANA_ITEM}；Botania 缺席时返回 null。 */
    @Nullable
    private static Capability<?> manaItemCapabilityType() {
        if (manaItemCapabilityOverride != null) return manaItemCapabilityOverride;
        Class<?> holder = primal(BOTANIA_CAPABILITIES);
        if (holder == null) return null;
        try {
            Field field = holder.getField(MANA_ITEM_CAPABILITY);
            // 读取静态能力字段会初始化 Botania API 类；初始化失败时按不支持处理。
            return (Capability<?>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * 单测入口：注入 {@code vazkii.botania.api.mana.ManaItem} 的能力常量替身。
     *
     * <p>仅供同包测试使用；传 null 即恢复按名称查找。</p>
     */
    static void setManaItemCapabilityForTest(@Nullable Capability<?> capability) {
        manaItemCapabilityOverride = capability;
    }

    private static boolean isSourceJar(ItemStack stack) {
        return SOURCE_JAR_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 取整数方法，读不到用 {@code fallback}。 */
    private static int intMethod(Object target, String typeName, String methodName, int fallback) {
        for (String name : methodNames(methodName)) {
            Method method = resolve(target, typeName, name, new Class<?>[0]);
            if (method == null || method.getReturnType() != int.class) continue;
            try {
                return (Integer) method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 调原生实现把魔力写进容器，写进去就返回 true。
     *
     * <p>{@code addMana} 只加不减，所以充入时传负数；原生实现加了负数会走自己的
     * 扣减分支，写回后由调用方重新读数校验。</p>
     */
    private static boolean manaAdd(Object target, int delta) {
        for (String name : methodNames(MANA_ADD)) {
            Method method = resolve(target, MANA_ITEM, name, new Class<?>[]{int.class});
            if (method == null) continue;
            try {
                method.invoke(target, delta);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * 一个原生方法名在两种映射下可能不一样，声明的名字优先。
     *
     * <p>核对过 Botania 的成品包：{@code ManaItem} 是模组自己的接口，它的方法名不会
     * 被统一映射改写（包里 {@code ManaTabletItem$ManaItemImpl} 仍叫 {@code addMana}），
     * 所以官方映射名就能直接调到；{@code m_xxxxx_} 只是兜底，防止以后映射策略变化。</p>
     */
    private static String[] methodNames(String declared) {
        return switch (declared) {
            case "getMana" -> new String[]{"getMana", "m_7173_"};
            case "addMana" -> new String[]{"addMana", "m_7174_"};
            default -> new String[]{declared};
        };
    }

    /**
     * 在 {@code typeName} 上找方法，并要求该对象确实是这个接口的实例。
     *
     * <p>只用到接口类，不碰实现类：能力实体是 Botania 的内部类，接口这一侧的方法表
     * 会正常解析到实现上。</p>
     */
    @Nullable
    private static Method resolve(Object target, String typeName, String methodName,
                                  Class<?>[] parameterTypes) {
        Class<?> contract = primal(typeName);
        if (contract == null || !contract.isInstance(target)) return null;
        try {
            return contract.getMethod(methodName, parameterTypes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * 按名称载入可选模组的类。
     *
     * <p>刻意不用 {@code util.Reflect}：那个类的静态初始化会连到模组主类，而本类属于
     * 共享路径，只想在缺模组时安静地返回 null。</p>
     */
    @Nullable
    private static Class<?> primal(String typeName) {
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
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
