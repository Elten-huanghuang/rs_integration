package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.util.PinyinUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts display-list filtering, sorting, and rebuild logic
 * from {@link RSSidePanelClient}.
 */
final class DisplayListManager {

    private long revision = -1;
    private Options lastOptions;
    private List<PanelStack> projection = List.of();

    private record Options(String search, int view, int sort, boolean ascending, boolean canSort) {}

    List<PanelStack> project(PanelDataModel model, String search, int view, int sort, boolean ascending, boolean canSort) {
        Options options = new Options(search, view, sort, ascending, canSort);
        if (revision != model.revision() || !options.equals(lastOptions)) {
            List<PanelStack> list = refilter(model.entries(), search, view);
            if (canSort) resort(list, sort, ascending);
            projection = List.copyOf(list);
            revision = model.revision();
            lastOptions = options;
        }
        return projection;
    }

    void clear() {
        revision = -1;
        projection = List.of();
    }

    @SuppressWarnings("resource")
    static List<PanelStack> refilter(List<PanelStack> source, String searchText, int viewType) {
        List<PanelStack> list = new ArrayList<>(source);

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
                                    ? TooltipFlag.Default.ADVANCED
                                    : TooltipFlag.Default.NORMAL)) {
                        String text = ChatFormatting.stripFormatting(line.getString());
                        if (text != null && text.toLowerCase().contains(tq)) return false;
                    }
                    return true;
                });
            } else {
                try {
                    Pattern p = Pattern.compile(query,
                            Pattern.CASE_INSENSITIVE);
                    list.removeIf(ps -> {
                        String name = ps.getName();
                        if (p.matcher(name).find()) return false;
                        return !matchesPinyin(name, query);
                    });
                } catch (PatternSyntaxException e) {
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

        if (viewType != 0) {
            list.removeIf(ps -> viewType == 1 ? ps.craftable : !ps.craftable);
        }
        return list;
    }

    // Reversed .thenComparing order with null-safe comparators to fix sort stability
    private static void resort(List<PanelStack> list, int sm, boolean asc) {

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
            if (PinyinUtil.toPinyin(itemName).contains(lowerQuery)) return true;
            if (PinyinUtil.toPinyinInitials(itemName).contains(lowerQuery)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
