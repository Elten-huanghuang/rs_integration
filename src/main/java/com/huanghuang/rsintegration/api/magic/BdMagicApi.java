package com.huanghuang.rsintegration.api.magic;

import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeExtractHandler;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeInsertHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSI 的 Beyond Dimensions 魔法资源公共入口。
 *
 * <p>该 API 只操作指定 BD 网络中的 {@link IStackKey}，不会读取 RSI 归墟盘。
 * 第三方模组可以注册别名，把语义相同的 Key 规范到同一个 BD 库存行。</p>
 */
public final class BdMagicApi {
    public static final ResourceLocation MANA_KEY =
            new ResourceLocation("beyonddimensions", "stack_type/mana");
    public static final ResourceLocation SOURCE_KEY =
            new ResourceLocation("beyonddimensions", "stack_type/source");
    public static final ResourceLocation SOUL_KEY =
            new ResourceLocation("beyonddimensions", "stack_type/goety_soul");

    private static final Map<ResourceLocation, ResourceLocation> ALIASES =
            new ConcurrentHashMap<>();
    private static volatile boolean handlersInstalled;
    private static final Logger LOGGER = LoggerFactory.getLogger("RSI-BD");

    private BdMagicApi() {}

    /** 注册别名；所有别名 Key 的插入和提取都会改用 canonicalKey。 */
    public static void registerAlias(ResourceLocation alias, ResourceLocation canonicalKey) {
        if (alias == null || canonicalKey == null || alias.equals(canonicalKey)) return;
        ALIASES.put(alias, canonicalKey);
    }

    /**
     * 安装 BD 全局 Key 规范化 handler。
     *
     * <p>由 RSI 启动流程调用一次；公开方法便于其它兼容模组提前触发，内部幂等。</p>
     */
    public static void installHandlers() {
        if (handlersInstalled) return;
        synchronized (BdMagicApi.class) {
            if (handlersInstalled) return;
            handlersInstalled = true;
            if (!legacyHandlersPresent()) {
                // 0.8 起 BD 删掉了这两个全局 BeforeInsert/BeforeExtract 回调，改用 UnifiedStorageRules 规则表。
                // 直接链接旧类会 NoClassDefFoundError，这里先跳过：别名仍对 RSI 自己的存取生效，
                // 只是不再参与 BD 全局的插入/提取规范化。
                LOGGER.info("[RSI-BD] 当前 BD 版本没有旧的 Key 别名 handler API，跳过安装");
                return;
            }
            UnifiedStorageBeforeInsertHandler.addHandler(
                    (incoming, current, network) -> {
                        IStackKey<?> key = canonicalKey(incoming.key());
                        KeyAmount normalized = key == incoming.key()
                                ? incoming : new KeyAmount(key, incoming.amount());
                        return new UnifiedStorageBeforeInsertHandler
                                .BeforeInsertHandlerReturnInfo(
                                normalized, false);
                    });
            UnifiedStorageBeforeExtractHandler.addHandler(
                    (requested, current, network) -> {
                        IStackKey<?> key = canonicalKey(requested.key());
                        KeyAmount normalized = key == requested.key()
                                ? requested : new KeyAmount(key, requested.amount());
                        return new UnifiedStorageBeforeExtractHandler
                                .BeforeExtractHandlerReturnInfo(
                                normalized, false);
                    });
        }
    }

    public static Session network(ServerPlayer player, int networkId) {
        return new Session(player, networkId);
    }

    /** 0.7 才有的全局 handler 类是否还在（0.8 换成 UnifiedStorageRules 后已删除）。 */
    private static boolean legacyHandlersPresent() {
        try {
            Class.forName(
                    "com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeInsertHandler",
                    false, BdMagicApi.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** 把已注册别名解析为规范 Key；未登记的 Key 原样返回。 */
    public static IStackKey<?> canonicalKey(IStackKey<?> requested) {
        if (requested == null) return null;
        ResourceLocation canonicalId = ALIASES.get(requested.getTypeId());
        if (canonicalId == null) return requested;
        IStackKey<?> canonical = resolveKey(canonicalId);
        return canonical == null ? requested : canonical;
    }

    @Nullable
    public static IStackKey<?> resolveKey(ResourceLocation keyId) {
        if (keyId == null) return null;
        try {
            return StackKeyRegistry.getType(keyId);
        } catch (IllegalArgumentException | LinkageError ignored) {
            return null;
        }
    }

    public record Session(ServerPlayer player, int networkId) {
        public long amount(ResourceLocation keyId) {
            return amount(resolveKey(keyId));
        }

        public long amount(@Nullable IStackKey<?> key) {
            UnifiedStorage storage = storage();
            IStackKey<?> canonical = canonicalKey(key);
            if (storage == null || canonical == null) return 0;
            KeyAmount current = storage.getStackByKey(canonical);
            return current == null ? 0 : Math.max(0, current.amount());
        }

        public long insert(ResourceLocation keyId, long amount, boolean simulate) {
            return insert(resolveKey(keyId), amount, simulate);
        }

        public long insert(@Nullable IStackKey<?> key, long amount, boolean simulate) {
            UnifiedStorage storage = storage();
            IStackKey<?> canonical = canonicalKey(key);
            if (storage == null || canonical == null || amount <= 0) return 0;
            KeyAmount remainder = storage.insert(canonical, amount, simulate);
            long left = remainder == null ? 0 : Math.max(0, remainder.amount());
            return Math.max(0, amount - left);
        }

        public long extract(ResourceLocation keyId, long amount, boolean simulate) {
            return extract(resolveKey(keyId), amount, simulate);
        }

        public long extract(@Nullable IStackKey<?> key, long amount, boolean simulate) {
            UnifiedStorage storage = storage();
            IStackKey<?> canonical = canonicalKey(key);
            if (storage == null || canonical == null || amount <= 0) return 0;
            KeyAmount extracted = storage.extract(canonical, amount, simulate, false);
            return extracted == null ? 0 : Math.max(0, extracted.amount());
        }

        @Nullable
        private UnifiedStorage storage() {
            if (player == null || networkId < 0
                    || !BeyondDimensionsReflection.isAuthorizedNetwork(player, networkId)) {
                return null;
            }
            DimensionsNet network = DimensionsNet.getNetFromId(networkId);
            return network == null ? null : network.getUnifiedStorage();
        }
    }
}
