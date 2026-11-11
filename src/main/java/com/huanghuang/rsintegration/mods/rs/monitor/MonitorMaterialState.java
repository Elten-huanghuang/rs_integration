package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 每个菜单只保留一个选中任务的材料基线；关闭菜单即释放。 */
public final class MonitorMaterialState {
    private UUID observedCraft;
    private int observedSequence = -1;
    private UUID craft;
    private long revision;
    private CraftMaterialView view = CraftMaterialView.EMPTY;

    public boolean shouldSample(UUID nextCraft, int sequence) {
        if (Objects.equals(nextCraft, observedCraft) && sequence == observedSequence) return false;
        observedCraft = nextCraft;
        observedSequence = sequence;
        return true;
    }

    public CraftMaterialView view(UUID id) { return Objects.equals(craft, id) ? view : CraftMaterialView.EMPTY; }

    public RsMonitorMaterialsPacket update(int menu, UUID session, UUID nextCraft, CraftMaterialView next) {
        boolean full = !Objects.equals(craft, nextCraft);
        if (!full && view.equals(next)) return null;
        var old = new LinkedHashMap<MaterialKey, CraftMaterialView.Row>();
        if (!full) view.rows().forEach(row -> old.put(row.material(), row));
        var changed = new ArrayList<CraftMaterialView.Row>();
        for (var row : next.rows()) if (!row.equals(old.remove(row.material()))) changed.add(row);
        long base = revision;
        craft = nextCraft;
        view = next;
        return new RsMonitorMaterialsPacket(menu, session, nextCraft, ++revision, base, full,
                changed, List.copyOf(old.keySet()), next.complete());
    }

    public boolean accept(RsMonitorMaterialsPacket packet) {
        if (packet.revision() <= revision || !packet.full()
                && (!Objects.equals(craft, packet.craft()) || packet.baseRevision() != revision)) return false;
        var rows = new LinkedHashMap<MaterialKey, CraftMaterialView.Row>();
        if (!packet.full()) view.rows().forEach(row -> rows.put(row.material(), row));
        packet.removed().forEach(rows::remove);
        packet.changed().forEach(row -> rows.put(row.material(), row));
        if (rows.size() > CraftMaterialView.MAX_ROWS) return false;
        craft = packet.craft();
        revision = packet.revision();
        view = new CraftMaterialView(List.copyOf(rows.values()), packet.complete());
        return true;
    }
}
