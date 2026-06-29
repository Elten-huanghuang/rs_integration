package com.huanghuang.rsintegration.sidepanel.model;

import com.huanghuang.rsintegration.sidepanel.PanelStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;

/**
 * Type-safe enum for item sort criteria, replacing the raw {@code int}
 * (0-3) currently used throughout the side panel stack.
 *
 * <h3>Semantics</h3>
 * <table>
 *   <tr><th>Enum</th><th>Raw int</th><th>Sorts by</th></tr>
 *   <tr><td>{@link #NAME}</td><td>0</td><td>Item display name (alpha)</td></tr>
 *   <tr><td>{@link #COUNT}</td><td>1</td><td>Stack size (numeric)</td></tr>
 *   <tr><td>{@link #MOD_ID}</td><td>2</td><td>Registry key (mod namespace)</td></tr>
 *   <tr><td>{@link #TIMESTAMP}</td><td>3</td><td>Insertion time</td></tr>
 * </table>
 * Each enum constant implements {@link #sort(List, boolean)} with the
 * correct {@code Comparator}, so callers don't need a manual switch.
 *
 * <h3>Wiring status</h3>
 * <b>Not wired</b> — the side panel uses raw {@code int} throughout:
 * <ul>
 *   <li>{@code RSSidePanelClient} stores {@code sortMode} as an {@code int}</li>
 *   <li>{@code SidePanelInputHandler.cycleSideButton()} cycles with
 *       {@code (sortMode + 1) % 4}</li>
 *   <li>Sorting in {@code RSSidePanelClient.refilter()} uses a manual
 *       {@code switch (sortMode)} block</li>
 * </ul>
 * Replacing raw {@code int} with this enum requires touching the full stack:
 * protocol serialization, client state, input handling, and sort execution.
 *
 * <h3>When to wire</h3>
 * During a side panel protocol upgrade or sorting refactor.  The enum:
 * <ul>
 *   <li>Makes sort-mode comparisons self-documenting</li>
 *   <li>Prevents off-by-one errors from magic-number cycling</li>
 *   <li>Centralizes the {@code Comparator} for each mode, removing a
 *       ~30-line {@code switch} block</li>
 *   <li>Integrates naturally with {@link #next()} for button cycling</li>
 * </ul>
 * Wiring checklist:
 * <ol>
 *   <li>Add {@code SortMode} field to relevant network packet buffers
 *       (serialize as ordinal)</li>
 *   <li>Replace {@code int sortMode} fields in {@code RSSidePanelClient}
 *       and {@code SidePanelInputHandler}</li>
 *   <li>Replace the {@code switch (sortMode)} block in {@code refilter()}
 *       with {@code sortMode.sort(list, asc)}</li>
 *   <li>Use {@code sortMode.next()} in {@code cycleSideButton()} instead
 *       of {@code (sortMode + 1) % 4}</li>
 * </ol>
 */
public enum SortMode {
    NAME {
        @Override
        public void sort(List<PanelStack> list, boolean asc) {
            if (asc) list.sort(Comparator.comparing(ps -> ps.getName().toLowerCase()));
            else list.sort(Comparator.comparing((PanelStack ps) -> ps.getName().toLowerCase()).reversed());
        }
    },
    COUNT {
        @Override
        public void sort(List<PanelStack> list, boolean asc) {
            if (asc) list.sort(Comparator.comparingInt(PanelStack::getCount));
            else list.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        }
    },
    MOD_ID {
        @Override
        public void sort(List<PanelStack> list, boolean asc) {
            list.sort((a, b) -> {
                var ka = ForgeRegistries.ITEMS.getKey(a.getStack().getItem());
                var kb = ForgeRegistries.ITEMS.getKey(b.getStack().getItem());
                String ia = ka != null ? ka.toString() : "zzz:zzz";
                String ib = kb != null ? kb.toString() : "zzz:zzz";
                return asc ? ia.compareToIgnoreCase(ib) : ib.compareToIgnoreCase(ia);
            });
        }
    },
    TIMESTAMP {
        @Override
        public void sort(List<PanelStack> list, boolean asc) {
            if (asc) list.sort(Comparator.comparingLong(ps -> ps.timestamp));
            else list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        }
    };

    public abstract void sort(List<PanelStack> list, boolean asc);

    public SortMode next() {
        SortMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
