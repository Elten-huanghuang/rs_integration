package com.huanghuang.rsintegration.mods.bd;

import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

/**
 * 超越维度（Beyond Dimensions）版本判定。
 *
 * <p>0.8 是一次大改版：终端界面整套换成 Compose 风格（{@code client.ui.*}），旧的
 * {@code client.gui.BDBaseGUI} / {@code client.gui.DimensionsNetGUI} 消失；
 * {@code BDBaseMenu#customClickHandler} 的第四参数由 boolean shift 换成 GLFW 修饰键掩码；
 * 与之配套新增了 {@code api.menu.interaction} 等 API 包。模组这边对 0.8 前后走两套逻辑，
 * 混入层用类存在性判定（见 {@code RSIntegrationMixinPlugin}），运行期需要明确版本时走这里。</p>
 *
 * <p>判定只比较主次版本号：{@code >= 0.8} 即为新版。解析失败（例如模组未加载、版本号为空）
 * 一律按旧版处理，避免把新版逻辑用到旧版上。</p>
 */
public final class BeyondDimensionsVersion {
    /** 0.8 起终端界面为 Compose 版本。 */
    private static final int MODERN_MINOR = 8;

    private static volatile Boolean modernTerminalUi;

    private BeyondDimensionsVersion() {
    }

    /** 当前加载的超越维度是否为 0.8 及以上的新版终端界面。 */
    public static boolean isModernTerminalUi() {
        Boolean cached = modernTerminalUi;
        if (cached != null) return cached;
        boolean modern = isModernVersion(loadedVersion());
        modernTerminalUi = modern;
        return modern;
    }

    /** 解析版本号字符串，判断是否 >= 0.8。测试直接调用这一层。 */
    static boolean isModernVersion(String version) {
        int[] parts = parseMajorMinor(version);
        return parts[0] > 0 || parts[1] >= MODERN_MINOR;
    }

    private static String loadedVersion() {
        ModList modList = ModList.get();
        if (modList == null) return "";
        IModInfo info = modList.getModContainerById(ModIds.BEYOND_DIMENSIONS)
                .map(container -> container.getModInfo())
                .orElse(null);
        return info == null ? "" : info.getVersion().toString();
    }

    /**
     * 取版本号的主、次版本：{@code "0.8.7"} → {@code [0, 8]}，
     * {@code "0.8.7-beta+abc"} → {@code [0, 8]}，非法输入返回 {@code [0, 0]}。
     */
    static int[] parseMajorMinor(String version) {
        int[] result = new int[2];
        if (version == null) return result;
        String trimmed = version.trim();
        if (trimmed.isEmpty()) return result;
        String[] segments = trimmed.split("[.\\-+_ ]");
        for (int index = 0; index < result.length && index < segments.length; index++) {
            result[index] = leadingInt(segments[index]);
        }
        return result;
    }

    private static int leadingInt(String segment) {
        int value = 0;
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character < '0' || character > '9') break;
            value = value * 10 + (character - '0');
            if (value > 9999) return 9999;
        }
        return value;
    }
}
