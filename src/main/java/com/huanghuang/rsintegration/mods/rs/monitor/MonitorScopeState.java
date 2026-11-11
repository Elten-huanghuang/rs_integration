package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.storage.StorageReference;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 每个原生菜单独立持有范围和版本；不共享玩家之间的同步基线。 */
public final class MonitorScopeState {
    public record Scope(UUID session, long revision, @Nullable StorageReference network,
                        List<UUID> crafts) {
        public Scope {
            Objects.requireNonNull(session, "session");
            if (revision <= 0) throw new IllegalArgumentException("invalid revision");
            crafts = List.copyOf(crafts);
            if (crafts.stream().distinct().count() != crafts.size()) {
                throw new IllegalArgumentException("duplicate craft ids");
            }
            if (network == null && !crafts.isEmpty()) {
                throw new IllegalArgumentException("crafts without network");
            }
        }
    }

    private Scope current;
    private final MonitorMaterialState materials = new MonitorMaterialState();
    public MonitorMaterialState materials() { return materials; }

    @Nullable public Scope current() { return current; }

    /** 返回 null 表示范围未变，不需要发包或重建标签。 */
    @Nullable
    public Scope update(@Nullable StorageReference network, List<UUID> crafts) {
        if (current != null && Objects.equals(current.network(), network)
                && current.crafts().equals(crafts)) return null;
        current = new Scope(current == null ? UUID.randomUUID() : current.session(),
                current == null ? 1 : Math.incrementExact(current.revision()), network, crafts);
        return current;
    }

    public boolean accept(Scope next) {
        if (current != null && (!current.session().equals(next.session())
                || next.revision() <= current.revision())) return false;
        current = next;
        return true;
    }

    public boolean allows(UUID session, UUID craft, @Nullable StorageReference liveNetwork) {
        return current != null && current.session().equals(session) && liveNetwork != null
                && liveNetwork.equals(current.network()) && current.crafts().contains(craft);
    }
}
