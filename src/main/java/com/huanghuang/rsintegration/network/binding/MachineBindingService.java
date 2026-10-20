package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.CuriosAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

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

    @FunctionalInterface
    public interface ClientBindingView {
        List<BindingStorage.BindingEntry> get(ItemStack stack);
    }

    @Nullable
    private static volatile ClientBindingView clientBindingView;

    private MachineBindingService() {}

    public static void installClientBindingView(@Nullable ClientBindingView view) {
        clientBindingView = view;
    }

    static List<BindingStorage.BindingEntry> getBindings(ItemStack stack) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            UUID bookId = bookId(stack);
            MachineBindingSavedData data = MachineBindingSavedData.peek(server);
            if (bookId != null && data != null) {
                return data.bindings(bookId);
            }

            if (server.isSameThread()) {
                data = MachineBindingSavedData.get(server);
                if (bookId != null) {
                    if (!BindingStorage.readLegacyBindings(stack).isEmpty()) {
                        bookId = ensureBook(stack, server, null);
                    }
                    return data.bindings(bookId);
                }
                if (!BindingStorage.readLegacyBindings(stack).isEmpty()) {
                    UUID migratedBookId = ensureBook(stack, server, null);
                    return data.bindings(migratedBookId);
                }
            }
        }

        ClientBindingView view = clientBindingView;
        return view != null ? view.get(stack) : BindingStorage.readLegacyBindings(stack);
    }

    static boolean addBinding(ItemStack stack, ResourceLocation dim, BlockPos pos,
                              String blockKey, @Nullable String blockRegKey,
                              @Nullable ItemStack displayStack, @Nullable UUID ownerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return BindingStorage.addLegacyBinding(
                    stack, dim, pos, blockKey, blockRegKey, displayStack);
        }
        if (!server.isSameThread()) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-Bind] Refused off-thread binding mutation at {} {}", dim, pos);
            return false;
        }

        UUID bookId = ensureBook(stack, server, ownerId);
        return MachineBindingSavedData.get(server).put(
                bookId, ownerId, MachineEndpointId.create(),
                new BindingStorage.BindingEntry(
                        dim, pos.immutable(), blockKey, blockRegKey,
                        copyDisplay(displayStack)));
    }

    static boolean removeBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return BindingStorage.removeLegacyBinding(stack, dim, pos);
        if (!server.isSameThread()) return false;
        UUID bookId = bookId(stack);
        return bookId != null && MachineBindingSavedData.get(server)
                .remove(bookId, dim, pos);
    }

    public static int removeEverywhere(MinecraftServer server,
                                       ResourceLocation dim, BlockPos pos) {
        if (server == null || !server.isSameThread()) return 0;
        return MachineBindingSavedData.get(server).removeEverywhere(dim, pos);
    }

    static boolean replaceBindingBlockKey(ItemStack stack, ResourceLocation dim, BlockPos pos,
                                          String expectedBlockKey, String replacementBlockKey) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return BindingStorage.replaceLegacyBindingBlockKey(
                    stack, dim, pos, expectedBlockKey, replacementBlockKey);
        }
        if (!server.isSameThread()) return false;
        UUID bookId = bookId(stack);
        if (bookId == null) return false;

        MachineBindingSavedData data = MachineBindingSavedData.get(server);
        for (BindingStorage.BindingEntry entry : data.bindings(bookId)) {
            if (!entry.dim().equals(dim) || !entry.pos().equals(pos)
                    || !expectedBlockKey.equals(entry.blockKey())) continue;
            BindingStorage.BindingEntry replacement = new BindingStorage.BindingEntry(
                    entry.dim(), entry.pos(), replacementBlockKey,
                    entry.blockRegKey(), entry.displayStack());
            return data.put(bookId, MachineEndpointId.create(), replacement);
        }
        return false;
    }

    static boolean hasBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        for (BindingStorage.BindingEntry entry : getBindings(stack)) {
            if (entry.dim().equals(dim) && entry.pos().equals(pos)) return true;
        }
        return false;
    }

    static boolean hasAnyBinding(ItemStack stack) {
        return !getBindings(stack).isEmpty();
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
        MachineBindingSavedData data = MachineBindingSavedData.get(server);
        Map<UUID, ResourceLocation> books = new LinkedHashMap<>();
        collectBooks(player.getInventory().items, player, server, data, books);
        collectBooks(player.getInventory().offhand, player, server, data, books);
        collectBooks(player.getInventory().armor, player, server, data, books);
        for (ItemStack curio : CuriosAccess.stacks(player)) {
            collectBook(curio, player, server, data, books);
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
    public static MachineEndpointId endpointId(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        UUID bookId = bookId(stack);
        if (server == null || bookId == null) return null;
        MachineBindingSavedData data = MachineBindingSavedData.peek(server);
        if (data == null && server.isSameThread()) {
            data = MachineBindingSavedData.get(server);
        }
        return data == null ? null : data.endpointId(bookId, dim, pos);
    }

    private static void collectBooks(List<ItemStack> stacks, ServerPlayer player,
                                     MinecraftServer server, MachineBindingSavedData data,
                                     Map<UUID, ResourceLocation> books) {
        for (ItemStack stack : stacks) {
            collectBook(stack, player, server, data, books);
        }
    }

    private static void collectBook(ItemStack stack, ServerPlayer player,
                                    MinecraftServer server, MachineBindingSavedData data,
                                    Map<UUID, ResourceLocation> books) {
        if (stack == null || stack.isEmpty()) return;
        UUID bookId = bookId(stack);
        if (!BindingStorage.readLegacyBindings(stack).isEmpty()) {
            bookId = ensureBook(stack, server, player.getUUID());
        }
        if (bookId == null) return;

        data.claimOwner(bookId, player.getUUID());
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) books.putIfAbsent(bookId, itemId);
    }

    private static UUID ensureBook(ItemStack stack, MinecraftServer server,
                                   @Nullable UUID ownerId) {
        UUID existing = bookId(stack);
        MachineBindingSavedData data = MachineBindingSavedData.get(server);
        List<BindingStorage.BindingEntry> legacy = BindingStorage.readLegacyBindings(stack);

        UUID bookId = existing;
        if (bookId == null) {
            bookId = UUID.randomUUID();
            for (BindingStorage.BindingEntry entry : legacy) {
                data.put(bookId, ownerId, MachineEndpointId.create(), entry);
            }
        } else if (ownerId != null) {
            data.claimOwner(bookId, ownerId);
        }

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
