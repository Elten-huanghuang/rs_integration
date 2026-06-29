package com.huanghuang.rsintegration.sidepanel;

import com.github.stuxuhai.jpinyin.PinyinFormat;
import com.github.stuxuhai.jpinyin.PinyinHelper;
import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts display-list filtering, sorting, and rebuild logic
 * from {@link RSSidePanelClient}.
 */
final class DisplayListManager {

    private DisplayListManager() {}

    // ── Lazy display list rebuild ─────────────────────────────────

    static void ensureDisplayReady() {
        if (!RSSidePanelClient.displayDirty) {
            long now = System.currentTimeMillis();
            for (PanelStack ps : RSSidePanelClient.panels) {
                if (ps.zeroed && now - ps.zeroedAt > 200) {
                    RSSidePanelClient.displayDirty = true;
                    break;
                }
            }
        }
        if (!RSSidePanelClient.displayDirty) return;
        rebuildDisplayList();
    }

    static void rebuildDisplayList() {
        RSSidePanelClient.displayDirty = false;
        List<PanelStack> list = refilter(RSSidePanelClient.panels);
        resort(list);
        if (list.isEmpty() && !RSSidePanelClient.panels.isEmpty()) {
            int zeroedCnt = 0, craftableCnt = 0;
            for (PanelStack ps : RSSidePanelClient.panels) {
                if (ps.zeroed) zeroedCnt++;
                if (ps.craftable) craftableCnt++;
            }
            RSIntegrationMod.LOGGER.warn("[RSI] rebuildDisplayList: panels={} display=0 zeroed={} craftable={} viewType={} search='{}'",
                    RSSidePanelClient.panels.size(), zeroedCnt, craftableCnt,
                    RSSidePanelClient.viewType, SearchController.searchText);
        }
        RSSidePanelClient.displayList.clear();
        RSSidePanelClient.displayList.addAll(list);
    }

    // ── Filter ────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    static List<PanelStack> refilter(List<PanelStack> source) {
        List<PanelStack> list = new ArrayList<>(source);
        String searchText = SearchController.searchText;

        if (!searchText.isEmpty()) {
            String query = searchText.trim();
            if (query.startsWith("@")) {
                String mq = query.substring(1).toLowerCase();
                list.removeIf(ps -> {
                    String modId = ps.getModId().toLowerCase();
                    if (modId.contains(mq)) return false;
                    String modName = ps.getModName().toLowerCase();
                    return !modName.contains(mq);
                });
            } else if (query.startsWith("#")) {
                String tq = query.substring(1).toLowerCase();
                var mc = Minecraft.getInstance();
                list.removeIf(ps -> {
                    for (Component line : ps.getStack().getTooltipLines(mc.player,
                            mc.options.advancedItemTooltips
                                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL)) {
                        String text = ChatFormatting.stripFormatting(line.getString());
                        if (text != null && text.toLowerCase().contains(tq)) return false;
                    }
                    return true;
                });
            } else {
                try {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(query,
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    list.removeIf(ps -> {
                        String name = ps.getName();
                        if (p.matcher(name).find()) return false;
                        return !matchesPinyin(name, query);
                    });
                } catch (java.util.regex.PatternSyntaxException e) {
                    String lower = query.toLowerCase();
                    list.removeIf(ps -> {
                        String name = ps.getName();
                        if (name.toLowerCase().contains(lower)) return false;
                        if (matchesPinyin(name, lower)) return false;
                        var key = ForgeRegistries.ITEMS.getKey(ps.getStack().getItem());
                        return key == null || !key.getPath().contains(lower);
                    });
                }
            }
        }

        if (RSSidePanelClient.viewType != 0) {
            list.removeIf(ps -> RSSidePanelClient.viewType == 1 ? ps.craftable : !ps.craftable);
        }

        list.removeIf(ps -> ps.zeroed && !ps.craftable);

        return list;
    }

    // ── Sort ──────────────────────────────────────────────────────

    static void resort(List<PanelStack> list) {
        if (!RSSidePanelClient.canSort()) return;
        int sm = RSSidePanelClient.sortMode;
        boolean asc = RSSidePanelClient.sortAsc;
        switch (sm) {
            case 0 -> {
                if (asc) list.sort(Comparator.comparing(ps -> ps.getName().toLowerCase()));
                else list.sort(Comparator.comparing((PanelStack ps) -> ps.getName().toLowerCase()).reversed());
            }
            case 1 -> {
                if (asc) list.sort(Comparator.comparingInt(PanelStack::getCount));
                else list.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
            }
            case 2 -> list.sort((a, b) -> {
                var ka = ForgeRegistries.ITEMS.getKey(a.getStack().getItem());
                var kb = ForgeRegistries.ITEMS.getKey(b.getStack().getItem());
                String ia = ka != null ? ka.toString() : "zzz:zzz";
                String ib = kb != null ? kb.toString() : "zzz:zzz";
                return asc ? ia.compareToIgnoreCase(ib) : ib.compareToIgnoreCase(ia);
            });
            case 3 -> {
                if (asc) list.sort(Comparator.comparingLong(ps -> ps.timestamp));
                else list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
            }
        }
    }

    // ── Pinyin ────────────────────────────────────────────────────

    static boolean matchesPinyin(String itemName, String query) {
        try {
            String lowerQuery = query.toLowerCase();
            String pinyin = PinyinHelper.convertToPinyinString(itemName, "",
                    PinyinFormat.WITHOUT_TONE);
            if (pinyin.toLowerCase().contains(lowerQuery)) return true;
            String shortPinyin = PinyinHelper.getShortPinyin(itemName);
            if (shortPinyin != null && shortPinyin.toLowerCase().contains(lowerQuery))
                return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
