package com.huanghuang.rsintegration.disk.persistence;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore.Limits;
import com.huanghuang.rsintegration.disk.core.ResourceTable.PageSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 不可变页面先落盘，manifest 原子替换后两类库存才一起提交。 */
public final class DiskFileStore {
    private static final int MAGIC = 0x52534955;
    public static final int FORMAT = 1;
    private static final int SEGMENT_BYTES = 4 * 1024 * 1024;
    private static final int MANIFEST_BYTES = 1048576;
    private static final long PAGE_RETENTION_MILLIS = Duration.ofDays(1).toMillis();
    public record PageFiles(List<String> keys, String amounts) {
        public PageFiles { keys = List.copyOf(keys); }
    }
    public record Manifest(UUID world, UUID disk, UUID owner, Limits limits, long commit,
                           List<PageFiles> items, List<PageFiles> fluids) {
        public Manifest { items = List.copyOf(items); fluids = List.copyOf(fluids); }
    }
    public record Snapshot(UUID world, UUID disk, UUID owner, Limits limits,
                           int itemPages, int fluidPages, List<PageSnapshot> items, List<PageSnapshot> fluids) {
        public static Snapshot freeze(UnifiedDiskCore core) {
            core.checkThread();
            return new Snapshot(core.worldId, core.diskId, core.owner, core.limits,
                    core.items.pageCount(), core.fluids.pageCount(), core.items.snapshot(false), core.fluids.snapshot(false));
        }
    }
    public record Saved(Manifest manifest, int keySegmentsWritten, int amountPagesWritten) {}
    @FunctionalInterface public interface BeforeCommit { void run() throws IOException; }
    private final Path root;
    private final BeforeCommit beforeCommit;

    public DiskFileStore(Path root) { this(root, () -> {}); }
    public DiskFileStore(Path root, BeforeCommit beforeCommit) { this.root = root; this.beforeCommit = beforeCommit; }

    public UUID worldIdentity() throws IOException {
        Files.createDirectories(root);
        Path file = root.resolve("world.bin");
        if (Files.exists(file)) {
            try (DataInputStream input = input(readChecked(file, 16))) {
                return uuid(input);
            }
        }
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(root)) {
            if (existing.iterator().hasNext()) throw new IOException("世界身份文件缺失但存在统一盘存档，拒绝重建身份");
        }
        UUID id = UUID.randomUUID();
        writeAtomic(file, bytes(output -> uuid(output, id)));
        return id;
    }

    public Manifest manifest(UUID id) throws IOException {
        return manifest(directory(id).resolve("manifest.bin"), id);
    }

    private Manifest manifest(Path file, UUID id) throws IOException {
        try (DataInputStream input = input(readChecked(file, MANIFEST_BYTES))) {
            if (input.readInt() != FORMAT) throw new IOException("不支持的统一盘格式");
            UUID world = uuid(input), disk = uuid(input), owner = input.readBoolean() ? uuid(input) : null;
            if (!disk.equals(id)) throw new IOException("统一盘 manifest 身份不一致");
            Limits limits;
            try { limits = new Limits(input.readInt(), input.readInt(), input.readInt(), input.readInt()); }
            catch (IllegalArgumentException e) { throw new IOException("统一盘容量非法", e); }
            long commit = input.readLong();
            if (commit < 1) throw new IOException("统一盘提交代数非法");
            List<PageFiles> items = readReferences(input, limits.items());
            List<PageFiles> fluids = readReferences(input, limits.fluids());
            if (input.available() != 0) throw new IOException("manifest 尾随字节");
            return new Manifest(world, disk, owner, limits, commit, items, fluids);
        }
    }

    /** 只列出已有检查点的 UUID，不加载库存页面；临时或未知目录不作为可找回磁盘。 */
    public List<UUID> savedDiskIds() throws IOException {
        List<UUID> ids = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                String name = directory.getFileName().toString();
                UUID id;
                try { id = UUID.fromString(name); }
                catch (IllegalArgumentException ignored) { continue; }
                if (id.toString().equals(name) && Files.isRegularFile(directory.resolve("manifest.bin"))) ids.add(id);
            }
        }
        ids.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(ids);
    }

    public Saved save(Snapshot snapshot, Manifest previous) throws IOException {
        Path directory = directory(snapshot.disk);
        Files.createDirectories(directory);
        Path current = directory.resolve("manifest.bin");
        if (Files.exists(current) ? previous == null || !manifest(snapshot.disk).equals(previous) : previous != null) {
            throw new IOException("统一盘检查点已改变或缺失，拒绝覆盖旧库存");
        }
        if (previous != null && (!previous.disk.equals(snapshot.disk) || !previous.world.equals(snapshot.world)
                || !previous.limits.expandEntries(snapshot.limits.items(), snapshot.limits.fluids())
                .equals(snapshot.limits))) throw new IOException("提交来源身份或容量不一致");
        int[] writes = new int[2];
        List<PageFiles> items = savePages(directory, "item", snapshot.itemPages, snapshot.items,
                previous == null ? List.of() : previous.items, writes);
        List<PageFiles> fluids = savePages(directory, "fluid", snapshot.fluidPages, snapshot.fluids,
                previous == null ? List.of() : previous.fluids, writes);
        Manifest result = new Manifest(snapshot.world, snapshot.disk, snapshot.owner, snapshot.limits,
                previous == null ? 1 : Math.addExact(previous.commit, 1), items, fluids);
        byte[] encoded = encodeManifest(result);
        beforeCommit.run();
        for (Path page : referencedPages(directory, result)) {
            if (!Files.isRegularFile(page)) throw new NoSuchFileException(page.toString());
        }
        // 保留上一完整检查点；加载不自动猜测未提交的临时页。
        if (Files.exists(current)) writeAtomic(directory.resolve("manifest.previous.bin"), readChecked(current, MANIFEST_BYTES));
        writeAtomic(current, encoded);
        collectPages(directory, result, previous);
        return new Saved(result, writes[0], writes[1]);
    }

    private void collectPages(Path directory, Manifest current, Manifest previous) {
        try {
            Set<Path> keep = referencedPages(directory, current);
            if (previous != null) keep.addAll(referencedPages(directory, previous));
            long cutoff = System.currentTimeMillis() - PAGE_RETENTION_MILLIS;
            // 除备份期间暂停提交外，再延迟回收，给外部复制和历史检查点留下恢复窗口。
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                int removed = 0;
                for (Path file : paths) {
                    String name = file.getFileName().toString();
                    if (!name.matches("(item|fluid)-[0-9]{1,4}-(key|amount)-[0-9a-f]{64}\\.bin")
                            || keep.contains(file) || Files.getLastModifiedTime(file).toMillis() >= cutoff) continue;
                    Files.delete(file);
                    if (++removed >= 512) break;
                }
            }
        } catch (IOException ignored) {
            // 回收失败不能将已提交检查点报成保存失败。
        }
    }

    /** 备份期间暂停提交后调用；让按修改时间筛选的增量备份携带完整检查点依赖。 */
    public void prepareBackup() throws IOException {
        UUID world = worldIdentity();
        Set<Path> dependencies = new HashSet<>();
        dependencies.add(root.resolve("world.bin"));
        for (UUID id : savedDiskIds()) {
            Path directory = directory(id);
            for (String name : List.of("manifest.bin", "manifest.previous.bin")) {
                Path file = directory.resolve(name);
                if (!Files.exists(file)) continue;
                Manifest manifest = manifest(file, id);
                if (!manifest.world.equals(world)) throw new IOException("备份中的统一盘属于另一个存档: " + id);
                dependencies.add(file);
                for (Path page : referencedPages(directory, manifest)) {
                    reference(directory, page.getFileName().toString());
                    dependencies.add(page);
                }
            }
        }
        FileTime timestamp = FileTime.fromMillis(System.currentTimeMillis() + 1);
        for (Path file : dependencies) Files.setLastModifiedTime(file, timestamp);
    }

    private Set<Path> referencedPages(Path directory, Manifest manifest) throws IOException {
        Set<Path> result = new HashSet<>();
        for (List<PageFiles> pages : List.of(manifest.items, manifest.fluids)) {
            for (PageFiles page : pages) {
                for (String name : page.keys) result.add(pagePath(directory, name));
                result.add(pagePath(directory, page.amounts));
            }
        }
        return result;
    }

    public UnifiedDiskCore load(UUID worldId, UUID diskId) throws IOException {
        Manifest manifest = manifest(diskId);
        return load(worldId, diskId, manifest, manifest.limits);
    }

    /** 按服务端配置扩大条目容量；旧文件仍按其原容量校验，减小配置不缩容。 */
    public UnifiedDiskCore load(UUID worldId, UUID diskId, int itemCapacity, int fluidCapacity) throws IOException {
        Manifest manifest = manifest(diskId);
        Limits expanded = manifest.limits.expandEntries(itemCapacity, fluidCapacity);
        return load(worldId, diskId, manifest, expanded);
    }

    private UnifiedDiskCore load(UUID worldId, UUID diskId, Manifest manifest, Limits limits) throws IOException {
        if (!manifest.world.equals(worldId)) throw new IOException("磁盘属于另一个存档");
        UnifiedDiskCore core = new UnifiedDiskCore(worldId, diskId, manifest.owner, limits);
        int itemBytes = loadPages(core.items, FrozenKey.Kind.ITEM, manifest.items, directory(diskId), manifest.limits);
        int fluidBytes = loadPages(core.fluids, FrozenKey.Kind.FLUID, manifest.fluids, directory(diskId), manifest.limits);
        if (fluidBytes > manifest.limits.payloadBytes() - itemBytes) throw new IOException("磁盘载荷总量超过容量描述");
        core.restorePayloadSize(itemBytes + fluidBytes);
        return core;
    }

    private List<PageFiles> savePages(Path directory, String kind, int pageCount, List<PageSnapshot> changed,
                                     List<PageFiles> previous, int[] writes) throws IOException {
        List<PageFiles> result = new ArrayList<>(previous);
        while (result.size() < pageCount) result.add(null);
        for (PageSnapshot page : changed) {
            PageFiles old = page.index() < previous.size() ? previous.get(page.index()) : null;
            List<String> keys = old == null ? null : old.keys;
            if (page.keys() != null) {
                keys = new ArrayList<>();
                ByteArrayOutputStream segment = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(segment);
                for (int slot = 0; slot < ResourceTable.PAGE_SIZE; slot++) {
                    byte[] key = page.keys()[slot];
                    int length = key == null ? 0 : key.length;
                    if (segment.size() + length + 20 > SEGMENT_BYTES) {
                        keys.add(writePage(directory, kind, page.index(), "key", segment.toByteArray()));
                        writes[0]++;
                        segment.reset();
                    }
                    output.writeInt(slot);
                    output.writeInt(page.generations()[slot]);
                    output.writeLong(page.orders()[slot]);
                    output.writeInt(length);
                    if (key != null) output.write(key);
                }
                keys.add(writePage(directory, kind, page.index(), "key", segment.toByteArray()));
                writes[0]++;
            }
            if (keys == null) throw new IOException("新页面缺少资源身份");
            String amounts = writePage(directory, kind, page.index(), "amount", bytes(output -> {
                for (int amount : page.amounts()) output.writeInt(amount);
            }));
            writes[1]++;
            result.set(page.index(), new PageFiles(keys, amounts));
        }
        if (result.size() != pageCount || result.contains(null)) throw new IOException("提交页面不完整");
        return result;
    }

    private record Record(int slot, int generation, long order, FrozenKey key, int amount) {}

    private int loadPages(ResourceTable table, FrozenKey.Kind kind, List<PageFiles> pages, Path directory,
                          Limits limits) throws IOException {
        int storedCapacity = kind == FrozenKey.Kind.ITEM ? limits.items() : limits.fluids();
        int slots = Math.min(storedCapacity, pages.size() * ResourceTable.PAGE_SIZE);
        int[] generations = new int[slots];
        List<Record> records = new ArrayList<>();
        int totalBytes = 0;
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            PageFiles page = pages.get(pageIndex);
            int[] amounts = new int[ResourceTable.PAGE_SIZE];
            try (DataInputStream input = input(readChecked(reference(directory, page.amounts), ResourceTable.PAGE_SIZE * 4))) {
                for (int i = 0; i < amounts.length; i++) amounts[i] = input.readInt();
                if (input.available() != 0) throw new IOException("数量页布局非法");
            }
            BitSet seen = new BitSet(ResourceTable.PAGE_SIZE);
            for (String name : page.keys) {
                try (DataInputStream input = input(readChecked(reference(directory, name), SEGMENT_BYTES))) {
                    while (input.available() > 0) {
                        int offset = input.readInt(), generation = input.readInt();
                        long order = input.readLong();
                        int length = input.readInt();
                        if (offset < 0 || offset >= ResourceTable.PAGE_SIZE || seen.get(offset) || generation < 0
                                || length < 0 || length > limits.entryBytes()) throw new IOException("身份页布局非法");
                        seen.set(offset);
                        int slot = pageIndex * ResourceTable.PAGE_SIZE + offset;
                        if (slot < slots) generations[slot] = generation;
                        int amount = amounts[offset];
                        if (length == 0) {
                            if (amount != 0) throw new IOException("无身份的数量记录");
                            continue;
                        }
                        if (slot >= slots || amount <= 0 || generation <= 0 || order <= 0
                                || length > limits.payloadBytes() - totalBytes) throw new IOException("资源数量或载荷越界");
                        byte[] payload = input.readNBytes(length);
                        if (payload.length != length) throw new IOException("资源载荷截断");
                        totalBytes += length;
                        records.add(new Record(slot, generation, order, FrozenKey.load(kind, payload), amount));
                    }
                }
            }
            if (seen.cardinality() != ResourceTable.PAGE_SIZE) throw new IOException("身份页缺少槽位");
        }
        records.sort(Comparator.comparingLong(Record::order));
        long previousOrder = 0;
        try {
            for (Record record : records) {
                if (previousOrder == record.order) throw new IOException("资源插入顺序重复");
                previousOrder = record.order;
                table.restore(record.slot, record.key, record.generation, record.order, record.amount);
            }
            table.finishRestore(slots, generations);
        } catch (IllegalArgumentException e) { throw new IOException("磁盘资源记录非法", e); }
        return totalBytes;
    }

    private byte[] encodeManifest(Manifest manifest) throws IOException {
        return bytes(output -> {
            output.writeInt(FORMAT);
            uuid(output, manifest.world); uuid(output, manifest.disk);
            output.writeBoolean(manifest.owner != null);
            if (manifest.owner != null) uuid(output, manifest.owner);
            output.writeInt(manifest.limits.items()); output.writeInt(manifest.limits.fluids());
            output.writeInt(manifest.limits.entryBytes()); output.writeInt(manifest.limits.payloadBytes());
            output.writeLong(manifest.commit);
            writeReferences(output, manifest.items); writeReferences(output, manifest.fluids);
        });
    }

    private void writeReferences(DataOutputStream output, List<PageFiles> pages) throws IOException {
        output.writeInt(pages.size());
        for (PageFiles page : pages) {
            output.writeInt(page.keys.size());
            for (String name : page.keys) output.writeUTF(name);
            output.writeUTF(page.amounts);
        }
    }

    private List<PageFiles> readReferences(DataInputStream input, int capacity) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > (capacity + ResourceTable.PAGE_SIZE - 1) / ResourceTable.PAGE_SIZE) throw new IOException("manifest 页数越界");
        List<PageFiles> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int segments = input.readInt();
            if (segments < 1 || segments > ResourceTable.PAGE_SIZE) throw new IOException("manifest 段数越界");
            List<String> names = new ArrayList<>();
            for (int j = 0; j < segments; j++) names.add(input.readUTF());
            result.add(new PageFiles(names, input.readUTF()));
        }
        return result;
    }

    private String writePage(Path directory, String kind, int page, String column, byte[] body) throws IOException {
        String name = kind + "-" + page + "-" + column + "-" + HexFormat.of().formatHex(digest(body)) + ".bin";
        Path target = directory.resolve(name);
        if (Files.exists(target)) {
            if (!Arrays.equals(readChecked(target, SEGMENT_BYTES), body)) throw new IOException("已有不可变页面损坏");
        } else writeAtomic(target, body);
        return name;
    }

    private Path reference(Path directory, String name) throws IOException {
        Path file = pagePath(directory, name);
        byte[] body = readChecked(file, SEGMENT_BYTES);
        if (!name.endsWith("-" + HexFormat.of().formatHex(digest(body)) + ".bin")) throw new IOException("页面内容地址校验失败");
        return file;
    }

    private Path pagePath(Path directory, String name) throws IOException {
        if (!name.matches("(item|fluid)-[0-9]{1,4}-(key|amount)-[0-9a-f]{64}\\.bin")) throw new IOException("非法页面文件名");
        return directory.resolve(name);
    }

    private Path directory(UUID id) { return root.resolve(id.toString()); }
    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static UUID uuid(DataInputStream input) throws IOException { return new UUID(input.readLong(), input.readLong()); }
    private static void uuid(DataOutputStream output, UUID id) throws IOException { output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits()); }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream output) throws IOException; }
    private static byte[] bytes(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        encoder.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static byte[] digest(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] readChecked(Path file, int limit) throws IOException {
        long length = Files.size(file);
        if (length < 44 || length > limit + 44L) throw new IOException("存档文件长度越界: " + file.getFileName());
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) throw new IOException("存档文件格式错误");
            int bodyLength = input.readInt();
            if (bodyLength < 0 || bodyLength != length - 44) throw new IOException("存档文件截断");
            byte[] body = input.readNBytes(bodyLength), expected = input.readNBytes(32);
            if (!MessageDigest.isEqual(digest(body), expected)) throw new IOException("存档文件校验失败: " + file.getFileName());
            return body;
        }
    }

    private static void writeAtomic(Path file, byte[] body) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        byte[] encoded = bytes(output -> {
            output.writeInt(MAGIC); output.writeInt(FORMAT); output.writeInt(body.length);
            output.write(body); output.write(digest(body));
        });
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!Arrays.equals(body, readChecked(temporary, Math.max(SEGMENT_BYTES, body.length)))) throw new IOException("写后校验失败");
            // 不支持原子替换时拒绝本次提交，保留上一个完整 manifest。
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
