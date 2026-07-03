package com.huanghuang.rsintegration.sidepanel;

import com.github.stuxuhai.jpinyin.PinyinFormat;
import com.github.stuxuhai.jpinyin.PinyinHelper;
import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
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

    static void ensureDisplayReady() {
        if (!RSSidePanelClient.displayDirty) return;
        rebuildDisplayList();
    }

    static void rebuildDisplayList() {
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
        RSSidePanelClient.displayDirty = false;
    }

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
        return list;
    }

    // Reversed .thenComparing order with null-safe comparators to fix sort stability
    static void resort(List<PanelStack> list) {
        if (!RSSidePanelClient.canSort()) return;
        int sm = RSSidePanelClient.sortMode;
        boolean asc = RSSidePanelClient.sortAsc;

        // 基础名称比较器（兜底用）
        Comparator<PanelStack> nameCmp = Comparator.comparing(PanelStack::getName);

        switch (sm) {
            case 0 -> {
                // 按名称
                if (asc) list.sort(nameCmp);
                else list.sort(nameCmp.reversed());
            }
            case 1 -> {
                // 按数量（数量为主，名称为辅）
                Comparator<PanelStack> qtyCmp = Comparator.comparingInt(PanelStack::getCount);
                if (asc) list.sort(qtyCmp.thenComparing(nameCmp));
                else list.sort(qtyCmp.reversed().thenComparing(nameCmp.reversed()));
            }
            case 2 -> {
                // 按 ID（ID为主，名称为辅）
                Comparator<PanelStack> idCmp = Comparator.comparingInt(ps -> Item.getId(ps.getStack().getItem()));
                if (asc) list.sort(idCmp.thenComparing(nameCmp));
                else list.sort(idCmp.reversed().thenComparing(nameCmp.reversed()));
            }
            case 3 -> {
                // 按最后修改时间（时间为主，名称为辅）
                Comparator<PanelStack> tsCmp = Comparator.comparingLong(ps -> ps.timestamp);
                if (asc) list.sort(tsCmp.thenComparing(nameCmp));
                else list.sort(tsCmp.reversed().thenComparing(nameCmp.reversed()));
            }
        }
    }

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