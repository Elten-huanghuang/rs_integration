package com.huanghuang.rsintegration.machine.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.huanghuang.rsintegration.feature.autoeat.client.AutoEatClientEvents;
import com.huanghuang.rsintegration.mods.bd.BeyondDimensionsVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 超越维度 0.8 起终端换成了 Compose 页面系统，机器中心、共振背包与自动进食入口不能再往窗口上叠浮动按钮，
 * 需要通过 {@code BdPages.register} 注册成侧边栏页签。
 *
 * <p>这三个页签是“按钮式页签”：点下去不切换页面（切页会让存储内容消失、窗口还会因为
 * {@code BdPageSize} 变化而缩放），而是由 {@link #handleRailClick(String)} 拦下点击，
 * 在当前存储界面上开关对应面板。点击拦截在 {@code BdPageSelection#open(String)} 的混入里完成。</p>
 *
 * <p>本类用动态代理实现 {@code BdPage}：0.7 里这些类型根本不存在，直接编译期引用会在
 * 加载类时抛 {@code NoClassDefFoundError}，因此所有 0.8 类型都按名字反射获取。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MachineHubPage {
    private static final Logger LOGGER = LoggerFactory.getLogger("rs_integration/bd-pages");
    private static final String MOD_PAGE_PACKAGE = "com.wintercogs.beyonddimensions.api.ui.page.";
    /**
     * 页签只挂到网络终端菜单上。BdPages 按 {@code menuClass.isInstance(menu)} 过滤，
     * 早先传 {@code AbstractContainerMenu} 会让机器中心出现在主网络切换器、能量/熔炉等所有 BD 界面里。
     * 终端菜单 {@code DimensionsNetTerminalMenu} 继承自 {@code DimensionsNetMenu}，因此注册一次即可覆盖两者。
     */
    private static final String TERMINAL_MENU_CLASS =
            "com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu";
    /** 机器中心页签。 */
    public static final ResourceLocation MACHINE_PAGE_ID =
            new ResourceLocation("rs_integration", "machine_hub");
    /** 共振背包页签：点开直接关闭存储、打开共振背包界面。 */
    public static final ResourceLocation BACKPACK_PAGE_ID =
            new ResourceLocation("rs_integration", "resonance_backpack");
    /** 自动进食页签：注册在最后，位置正好在“设置”上面。 */
    public static final ResourceLocation AUTO_EAT_PAGE_ID =
            new ResourceLocation("rs_integration", "auto_eat");
    /**
     * 机器中心图标：纯字符像素画，不引用任何贴图。
     * '#' 点亮、'.' 留空，四块方块表示机器网格。
     */
    private static final String[] MACHINE_ICON = {
            "................",
            "..####....####..",
            "..####....####..",
            "..####....####..",
            "..####....####..",
            "................",
            "................",
            "................",
            "................",
            "..####....####..",
            "..####....####..",
            "..####....####..",
            "..####....####..",
            "................",
            "................",
            "................"
    };
    /** 共振背包图标：一个背包轮廓。 */
    private static final String[] BACKPACK_ICON = {
            "................",
            ".....######.....",
            "....########....",
            "...##......##...",
            "...##......##...",
            ".##############.",
            ".##############.",
            ".####..##..####.",
            ".####..##..####.",
            ".##############.",
            ".##############.",
            ".##############.",
            ".##############.",
            "................",
            "................",
            "................"
    };
    /** 自动进食图标：一只饭碗。 */
    private static final String[] AUTO_EAT_ICON = {
            "................",
            "................",
            ".....####.......",
            ".....####.......",
            ".....####.......",
            "...##########...",
            "..############..",
            "..############..",
            "..############..",
            "...##########...",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
    };

    /** 页面尺寸类型，三页共用一个反射句柄。 */
    private static volatile Class<?> sizeType;
    private static boolean installed;

    private MachineHubPage() {}

    /** 在客户端初始化阶段注册页签；0.8 以下或缺少依赖时什么都不做。 */
    public static void install() {
        if (installed) return;
        try {
            if (!BeyondDimensionsVersion.isModernTerminalUi()) return;
            installed = true;
            ClassLoader loader = MachineHubPage.class.getClassLoader();
            Class<?> menuType = terminalMenuType(loader);
            if (menuType == null) {
                LOGGER.warn("超越维度 0.8 找不到网络终端菜单 {}，跳过页面注册", TERMINAL_MENU_CLASS);
                return;
            }
            sizeType = Class.forName(MOD_PAGE_PACKAGE + "BdPageSize", true, loader);
            registerPage(MACHINE_PAGE_ID, menuType, "rsi.machine_hub.page", MACHINE_ICON);
            registerPage(BACKPACK_PAGE_ID, menuType, "rsi.resonance_backpack.page", BACKPACK_ICON);
            // 最后注册，侧边栏里正好落在“设置”上面。
            registerPage(AUTO_EAT_PAGE_ID, menuType, "rsi.auto_eat.page", AUTO_EAT_ICON);
        } catch (Throwable error) {
            LOGGER.warn("无法注册超越维度页面", error);
        }
    }

    /**
     * 侧边栏页签被点击。返回 true 表示这次点击已经处理完毕，调用方应取消原本的切页动作，
     * 让存储内容继续留在屏幕上。
     */
    public static boolean handleRailClick(String pageId) {
        if (pageId == null || pageId.isEmpty()) return false;
        if (pageId.equals(MACHINE_PAGE_ID.toString())) {
            MachineTabHandler.toggleMachineCenter();
            return true;
        }
        if (pageId.equals(BACKPACK_PAGE_ID.toString())) {
            // 交给服务端打开背包界面：新界面会顶掉当前终端，等于“关闭存储、打开背包”。
            MachineTabHandler.toggleResonanceBackpack(true);
            return true;
        }
        if (pageId.equals(AUTO_EAT_PAGE_ID.toString())) {
            AutoEatClientEvents.toggleModernControls();
            return true;
        }
        return false;
    }

    /** 机器中心面板当前是否展开；展开时机器网格覆盖绘制在存储界面上。 */
    public static boolean isOpen() {
        return MachineHub.isVisible();
    }

    /** 屏幕关闭时收起面板并清掉自动进食面板状态，避免下次打开沿用旧状态。 */
    public static void reset() {
        if (MachineHub.isVisible()) MachineHub.hide();
        AutoEatClientEvents.resetModernControls();
    }

    /** 供测试检查字符画尺寸，避免出现长短不一的图标。 */
    static String[] machineIconArt() {
        return MACHINE_ICON.clone();
    }

    /** 三个页签的 id，按注册顺序排列。 */
    public static List<String> pageIds() {
        return List.of(MACHINE_PAGE_ID.toString(),
                BACKPACK_PAGE_ID.toString(), AUTO_EAT_PAGE_ID.toString());
    }

    /** 三个页签的标题翻译键，按注册顺序排列。 */
    public static List<String> actionKeys() {
        return List.of("rsi.machine_hub.page",
                "rsi.resonance_backpack.page", "rsi.auto_eat.page");
    }

    static String[] backpackIconArt() {
        return BACKPACK_ICON.clone();
    }

    static String[] autoEatIconArt() {
        return AUTO_EAT_ICON.clone();
    }

    private static void registerPage(ResourceLocation id, Class<?> menuType, String titleKey, String[] art)
            throws ReflectiveOperationException {
        ClassLoader loader = MachineHubPage.class.getClassLoader();
        Class<?> pageType = Class.forName(MOD_PAGE_PACKAGE + "BdPage", true, loader);
        Class<?> pagesType = Class.forName(MOD_PAGE_PACKAGE + "BdPages", true, loader);
        Class<?> iconType = Class.forName(
                "com.wintercogs.beyonddimensions.client.ui.kit.BdPixelIcon", true, loader);

        Object icon = createIcon(iconType, art);
        PageHandler handler = new PageHandler(icon, titleKey);
        Object page = Proxy.newProxyInstance(loader, new Class<?>[] {pageType}, handler);

        Object pagesInstance = instanceOf(pagesType);
        Method register = findMethod(pagesType, "register", 4);
        if (register == null) {
            LOGGER.warn("超越维度 0.8 缺少 BdPages#register，跳过页面 {}", id);
            return;
        }
        register.setAccessible(true);
        Object placement = createPlacement(pagesType, pagesInstance);
        Object target = Modifier.isStatic(register.getModifiers()) ? null : pagesInstance;
        register.invoke(target, id, menuType, page, placement);
        LOGGER.info("[RSI-bd] 已注册超越维度页签 {}", id);
    }

    /** 按名字取网络终端菜单类型；缺失时返回 {@code null}，由调用方跳过注册。 */
    private static Class<?> terminalMenuType(ClassLoader loader) {
        try {
            return Class.forName(TERMINAL_MENU_CLASS, false, loader);
        } catch (ClassNotFoundException | LinkageError error) {
            return null;
        }
    }

    private static Object createIcon(Class<?> iconType, String[] art) throws ReflectiveOperationException {
        Constructor<?> constructor = iconType.getConstructor(String[].class);
        return constructor.newInstance((Object) art.clone());
    }

    /**
     * 页面尺寸沿用 BD 主页面尺寸。BdTabbedWindow 会按页面 size 动画缩放窗口，
     * 早先返回“内容自适应”会让终端在切到我们的页签时整窗变小，看着像触发了 BD 界面变化。
     */
    private static Object createDefaultSize() {
        Class<?> type = sizeType;
        if (type == null) return null;
        Object main = mainPageSize(type);
        if (main != null) return main;
        try {
            Class<?> contentType = Class.forName(MOD_PAGE_PACKAGE + "BdPageSize$Content", true,
                    type.getClassLoader());
            try {
                Constructor<?> constructor = contentType.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (NoSuchMethodException ignored) {
                Constructor<?> constructor = contentType.getDeclaredConstructor(Integer.class, Integer.class);
                constructor.setAccessible(true);
                return constructor.newInstance(null, null);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 下面用固定尺寸兜底。
        }
        try {
            Class<?> fixedType = Class.forName(MOD_PAGE_PACKAGE + "BdPageSize$Fixed", true,
                    type.getClassLoader());
            Constructor<?> constructor = fixedType.getDeclaredConstructor(int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(200, 180);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn("无法构造超越维度页面尺寸，使用默认值", error);
            return null;
        }
    }

    /** {@code BdPageSize$Main.INSTANCE}，即 BD 主页面的窗口尺寸。 */
    private static Object mainPageSize(Class<?> sizeType) {
        try {
            Class<?> mainType = Class.forName(MOD_PAGE_PACKAGE + "BdPageSize$Main", true,
                    sizeType.getClassLoader());
            Field instance = mainType.getField("INSTANCE");
            instance.setAccessible(true);
            return instance.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** 页签按注册顺序追加在 BD 自己的页之后，用 last() 无需引用不存在的锚点 id。 */
    private static Object createPlacement(Class<?> pagesType, Object pagesInstance) {
        try {
            Method last = pagesType.getMethod("last");
            last.setAccessible(true);
            return last.invoke(Modifier.isStatic(last.getModifiers()) ? null : pagesInstance);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** Kotlin object 取 INSTANCE；纯静态类返回 null。 */
    private static Object instanceOf(Class<?> type) {
        try {
            Field field = type.getField("INSTANCE");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    /**
     * BdPage 的代理实现：只提供页签图标、标题与尺寸。
     * 这些页签不会被真正选中（点击已被拦截），所以内容回调保持空实现。
     */
    private static final class PageHandler implements InvocationHandler {
        private final Object icon;
        private final String titleKey;

        private PageHandler(Object icon, String titleKey) {
            this.icon = icon;
            this.titleKey = titleKey;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getIcon":
                    return icon;
                case "title":
                    return Component.translatable(titleKey);
                case "appliesTo":
                    return Boolean.TRUE;
                case "size":
                    return createDefaultSize();
                case "snapshot":
                    return null;
                case "handle":
                    return null;
                case "Content":
                    // 兜底：正常情况下点击已在 BdPageSelection#open 里被拦下，页面永远不会被选中。
                    // 万一拦截没生效（例如 BD 改了切页入口），这里立刻切回主页面，
                    // 至少保证存储内容还在屏幕上，而不是变成一个空白页。
                    returnToMainPage(args);
                    return null;
                case "toString":
                    return "RSI page " + titleKey;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                default:
                    if (method.isDefault()) {
                        try {
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    }
                    return defaultReturn(method.getReturnType());
            }
        }

        /** 把页面切回 BD 主页面（存储界面）。参数顺序见 {@code BdPage#Content} 的签名。 */
        private static void returnToMainPage(Object[] args) {
            if (args == null || args.length < 3 || args[2] == null) return;
            try {
                Method open = args[2].getClass().getMethod("open", String.class);
                open.invoke(args[2], "main");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 拿不到作用域就什么都不做，保持 BD 自己的行为。
            }
        }

        private static Object defaultReturn(Class<?> returnType) {            if (returnType == boolean.class) return Boolean.FALSE;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            if (returnType == double.class) return 0d;
            return null;
        }
    }
}
