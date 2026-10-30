package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家已吃食物的持久化记录。
 *
 * <p>旧附属使用独立的 {@code rsfoodorder_eaten} 存档；合并后改名并挂在
 * 主世界 DataStorage 上，避免和旧存档争用同一个键。</p>
 */
public final class FoodOrderEatenData extends SavedData {

    private static final String DATA_NAME = "rs_integration_food_order_eaten";
    private static final int MAX_ITEMS_PER_PLAYER = 8192;

    private final Map<UUID, Set<String>> players = new HashMap<>();

    private FoodOrderEatenData() {}

    public static FoodOrderEatenData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                FoodOrderEatenData::load, FoodOrderEatenData::new, DATA_NAME);
    }

    /**
     * 一次性导入旧附属 {@code rsfoodorder_eaten.dat} 中的已吃记录。
     *
     * <p>旧存档格式为 {@code players} 复合标签，每个键是玩家 UUID、值是以逗号分隔的物品 id。
     * 迁移只读旧文件，不删除也不改写它，便于玩家回退版本。</p>
     */
    public static void migrateLegacy(MinecraftServer server) {
        Path legacyPath = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("rsfoodorder_eaten.dat");
        if (!Files.isRegularFile(legacyPath)) {
            return;
        }
        CompoundTag root;
        try (InputStream input = Files.newInputStream(legacyPath)) {
            root = NbtIo.readCompressed(input);
        } catch (Throwable throwable) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-FoodOrder] 读取旧附属已吃记录失败，跳过迁移", throwable);
            return;
        }
        CompoundTag payload = root.contains("data", Tag.TAG_COMPOUND)
                ? root.getCompound("data") : root;
        CompoundTag legacyPlayers = payload.getCompound("players");
        if (legacyPlayers.isEmpty()) {
            return;
        }
        FoodOrderEatenData data = get(server);
        int migrated = 0;
        for (String playerKey : legacyPlayers.getAllKeys()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            for (String itemId : legacyPlayers.getString(playerKey).split(",")) {
                String trimmed = itemId.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Set<String> itemIds = data.players.computeIfAbsent(
                        playerId, ignored -> new HashSet<>());
                if (itemIds.size() < MAX_ITEMS_PER_PLAYER && itemIds.add(trimmed)) {
                    migrated++;
                }
            }
        }
        if (migrated > 0) {
            data.setDirty();
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FoodOrder] 已从旧附属存档迁移 {} 条已吃记录", migrated);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag encodedPlayers = new ListTag();
        for (Map.Entry<UUID, Set<String>> entry : players.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag encoded = new CompoundTag();
            encoded.putUUID("player", entry.getKey());
            ListTag items = new ListTag();
            for (String itemId : entry.getValue()) {
                items.add(StringTag.valueOf(itemId));
            }
            encoded.put("items", items);
            encodedPlayers.add(encoded);
        }
        tag.put("players", encodedPlayers);
        return tag;
    }

    private static FoodOrderEatenData load(CompoundTag tag) {
        FoodOrderEatenData data = new FoodOrderEatenData();
        ListTag encodedPlayers = tag.getList("players", Tag.TAG_COMPOUND);
        for (int playerIndex = 0; playerIndex < encodedPlayers.size(); playerIndex++) {
            CompoundTag encoded = encodedPlayers.getCompound(playerIndex);
            if (!encoded.hasUUID("player")) {
                continue;
            }
            UUID playerId = encoded.getUUID("player");
            ListTag items = encoded.getList("items", Tag.TAG_STRING);
            Set<String> itemIds = new HashSet<>();
            for (int index = 0; index < items.size()
                    && itemIds.size() < MAX_ITEMS_PER_PLAYER; index++) {
                String itemId = items.getString(index);
                if (!itemId.isEmpty()) {
                    itemIds.add(itemId);
                }
            }
            if (!itemIds.isEmpty()) {
                data.players.put(playerId, itemIds);
            }
        }
        return data;
    }

    public Set<String> snapshot(UUID playerId) {
        Set<String> itemIds = players.get(playerId);
        return itemIds == null ? Set.of() : Set.copyOf(itemIds);
    }

    public void mark(UUID playerId, String itemId) {
        if (itemId.isEmpty()) {
            return;
        }
        Set<String> itemIds = players.computeIfAbsent(playerId, ignored -> new HashSet<>());
        if (itemIds.size() >= MAX_ITEMS_PER_PLAYER || !itemIds.add(itemId)) {
            return;
        }
        setDirty();
    }
}
