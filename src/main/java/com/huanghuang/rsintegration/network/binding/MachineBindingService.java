package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.util.CuriosAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 连接器 NBT 与服务端权威机器绑定索引之间的兼容门面。
 *
 * <p>主线程写入索引；服务端异步线程只读取已经加载的不可变快照。客户端在
 * 独立服务器环境下通过安装的只读视图访问同步缓存，不再自行解析绑定列表。</p>
 */
public final class MachineBindingService {
    public static final String KEY_BOOK_ID = "rsi_binding_book_id";

    private MachineBindingService() {}

    static List<BindingStorage.BindingEntry> getBindings(MinecraftServer server, ItemStack stack) {
        UUID id = bookId(stack);
        MachineBindingSavedData data = id == null || server == null ? null : MachineBindingSavedData.peek(server);
        return data == null ? List.of() : data.bindings(id);
    }

    static boolean addBinding(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos,
                              String blockKey, @Nullable String blockRegKey,
                              @Nullable ItemStack displayStack, @Nullable UUID ownerId) {
        if (server == null || !server.isSameThread()) return false;

        UUID bookId = ensureBook(stack, server, ownerId);
        return MachineBindingSavedData.get(server).put(
                bookId, ownerId, MachineEndpointId.create(),
                new BindingStorage.BindingEntry(
                        dim, pos.immutable(), blockKey, blockRegKey,
                        copyDisplay(displayStack)));
    }

    static boolean removeBinding(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos) {
        if (server == null || !server.isSameThread()) return false;
        UUID bookId = bookId(stack);
        return bookId != null && MachineBindingSavedData.get(server)
                .remove(bookId, dim, pos);
    }

    public static int removeEverywhere(MinecraftServer server,
                                       ResourceLocation dim, BlockPos pos) {
        if (server == null || !server.isSameThread()) return 0;
        return MachineBindingSavedData.get(server).removeEverywhere(dim, pos);
    }

    static boolean replaceBindingBlockKey(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos,
                                          String expectedBlockKey, String replacementBlockKey) {
        if (server == null || !server.isSameThread()) return false;
        UUID bookId = bookId(stack);
        if (bookId == null) return false;

        MachineBindingSavedData data = MachineBindingSavedData.get(server);
        for (BindingStorage.BindingEntry entry : data.bindings(bookId)) {
            if (!entry.dim().equals(dim) || !entry.pos().equals(pos)
                    || !expectedBlockKey.equals(entry.blockKey())) continue;
            BindingStorage.BindingEntry replacement = new BindingStorage.BindingEntry(
                    entry.dim(), entry.pos(), replacementBlockKey,
                    entry.blockRegKey(), entry.displayStack(), entry.customName());
            return data.put(bookId, MachineEndpointId.create(), replacement);
        }
        return false;
    }

    /** 改名只能作用于玩家当前携带的绑定，不能凭客户端提供的 UUID 修改其它终端。 */
    public static boolean rename(ServerPlayer player, UUID bookId,
                                 MachineEndpointId endpointId, String name) {
        for (PlayerEndpoint endpoint : collectPlayerEndpoints(player)) {
            if (endpoint.bookId().equals(bookId) && endpoint.endpointId().equals(endpointId)) {
                return MachineBindingSavedData.get(player.server).rename(bookId, endpointId, name);
            }
        }
        return false;
    }

    /**
     * 只扫描终端物品中的 book UUID，再从索引取每个书的不可变端点快照。
     * 不复制绑定条目，也不把列表重新写回物品 NBT。
     */
    public static List<PlayerEndpoint> collectPlayerEndpoints(ServerPlayer player) {
        if (player == null || player.server == null || !player.server.isSameThread()) {
            return List.of();
        }

        MinecraftServer server = player.server;
        MachineBindingSavedData data = MachineBindingSavedData.peek(server);
        if (data == null) return List.of();
        Map<UUID, ResourceLocation> books = new LinkedHashMap<>();
        collectBooks(player.getInventory().items, books);
        collectBooks(player.getInventory().offhand, books);
        collectBooks(player.getInventory().armor, books);
        for (ItemStack curio : CuriosAccess.stacks(player)) {
            collectBook(curio, books);
        }

        if (books.isEmpty()) return List.of();
        List<PlayerEndpoint> result = new ArrayList<>();
        for (Map.Entry<UUID, ResourceLocation> book : books.entrySet()) {
            for (MachineBindingSavedData.IndexedEndpoint endpoint :
                    data.endpoints(book.getKey())) {
                result.add(new PlayerEndpoint(
                        book.getKey(), book.getValue(), endpoint.endpointId(),
                        endpoint.entry(), endpoint.revision()));
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    public static UUID bookId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(KEY_BOOK_ID) ? tag.getUUID(KEY_BOOK_ID) : null;
    }

    @Nullable
    public static MachineEndpointId endpointId(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos) {
        UUID bookId = bookId(stack);
        if (server == null || bookId == null) return null;
        MachineBindingSavedData data = MachineBindingSavedData.peek(server);
        return data == null ? null : data.endpointId(bookId, dim, pos);
    }

    private static void collectBooks(List<ItemStack> stacks,
                                     Map<UUID, ResourceLocation> books) {
        for (ItemStack stack : stacks) {
            collectBook(stack, books);
        }
    }

    private static void collectBook(ItemStack stack,
                                    Map<UUID, ResourceLocation> books) {
        if (stack == null || stack.isEmpty()) return;
        UUID bookId = bookId(stack);
        if (bookId == null) return;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) books.putIfAbsent(bookId, itemId);
    }

    public static void prepareHeldBooks(ServerPlayer player) {
        if (player.server == null || !player.server.isSameThread()) return;
        AltarBindingRegistry.forEachInventoryGroup(player, stacks -> {
            for (ItemStack stack : stacks) prepareHeldBook(player, stack);
        });
    }

    public static void prepareHeldBook(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()
                || player.server == null || !player.server.isSameThread()) return;
        CompoundTag tag = stack.getTag();
        if (tag.contains(BindingStorage.KEY_BINDINGS) || tag.contains("aec_bound_x")) {
            if (!BindingStorage.readLegacyBindings(stack).isEmpty()) ensureBook(stack, player.server, player.getUUID());
        } else if (bookId(stack) != null) {
            MachineBindingSavedData.get(player.server).claimOwner(bookId(stack), player.getUUID());
        }
    }

    private static UUID ensureBook(ItemStack stack, MinecraftServer server,
                                   @Nullable UUID ownerId) {
        UUID existing = bookId(stack);
        MachineBindingSavedData data = MachineBindingSavedData.get(server);
        List<BindingStorage.BindingEntry> legacy = BindingStorage.readLegacyBindings(stack);

        UUID bookId = existing == null ? UUID.randomUUID() : existing;
        for (BindingStorage.BindingEntry entry : legacy) {
            if (data.endpointId(bookId, entry.dim(), entry.pos()) == null) {
                data.put(bookId, ownerId, MachineEndpointId.create(), entry);
            }
        }
        if (ownerId != null) data.claimOwner(bookId, ownerId);

        if (existing == null || !legacy.isEmpty()) {
            CompoundTag oldTag = stack.getTag();
            CompoundTag tag = oldTag == null ? new CompoundTag() : oldTag.copy();
            tag.putUUID(KEY_BOOK_ID, bookId);
            clearLegacyTags(tag);
            stack.setTag(tag);
        }
        return bookId;
    }

    private static void clearLegacyTags(CompoundTag tag) {
        tag.remove(BindingStorage.KEY_BINDINGS);
        tag.remove("aec_bound_dim");
        tag.remove("aec_bound_x");
        tag.remove("aec_bound_y");
        tag.remove("aec_bound_z");
        tag.remove("aec_bound_block");
    }

    @Nullable
    private static ItemStack copyDisplay(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.copy();
    }

    public record PlayerEndpoint(
            UUID bookId,
            @Nullable ResourceLocation itemKey,
            MachineEndpointId endpointId,
            BindingStorage.BindingEntry entry,
            long revision
    ) {}
}
