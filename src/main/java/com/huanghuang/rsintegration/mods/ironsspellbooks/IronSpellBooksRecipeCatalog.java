package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.item.InkItem;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import java.util.Comparator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.stream.LongStream;
import java.util.stream.IntStream;

/** 为仅在 JEI 中展示的卷轴锻造和奥术铁砧生成运行时配方。 */
public final class IronSpellBooksRecipeCatalog {
    public static final RecipeType<IronSpellBooksRecipe> TYPE =
            RecipeType.simple(new ResourceLocation("rs_integration", "irons_spellbooks"));
    private static volatile Catalog catalog;
    private static long generation;
    private static final Map<Class<?>, SpellEntryAccess> SPELL_ENTRY_ACCESS =
            new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> SPELL_ID_ACCESS = new ConcurrentHashMap<>();

    private IronSpellBooksRecipeCatalog() {}

    public record SpellScrollKey(ResourceLocation spellId, int level) {}

    public static Collection<IronSpellBooksRecipe> allRecipes() { return catalog().byId().values(); }

    public static Collection<IronSpellBooksRecipe> allRecipes(Level level) {
        List<IronSpellBooksRecipe> recipes = new ArrayList<>(allRecipes());
        recipes.addAll(IronAlchemistRecipeCatalog.allRecipes(level));
        return List.copyOf(recipes);
    }

    @Nullable
    public static IronSpellBooksRecipe byId(ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace())
                || !id.getPath().startsWith("irons_spellbooks/")) return null;
        if (id.getPath().startsWith("irons_spellbooks/alchemist/")) return IronAlchemistRecipeCatalog.byId(id);
        return catalog().byId().get(id);
    }

    @Nullable
    public static IronSpellBooksRecipe byId(Level level, ResourceLocation id) {
        if (id.getNamespace().equals("rs_integration") && id.getPath().startsWith("irons_spellbooks/alchemist/")) {
            IronAlchemistRecipeCatalog.allRecipes(level);
        }
        return byId(id);
    }

    @Nullable
    public static IronSpellBooksRecipe recipeForTarget(IronSpellBooksRecipe.Machine machine,
                                                        ItemStack target) {
        if (target == null || target.isEmpty()) return null;
        Catalog current = catalog();
        IronSpellBooksRecipe exact = current.byOutput().get(OutputKey.of(machine, target));
        if (exact != null) return exact;
        for (IronSpellBooksRecipe recipe : current.byId().values()) {
            if (recipe.machine() == machine
                    && sameSpellScroll(recipe.getResultItem(RegistryAccess.EMPTY), target)) {
                return recipe;
            }
        }
        return null;
    }

    /** 按法术和等级比较单法术卷轴，忽略 NBT 数值的存储类型。 */
    public static boolean sameSpellScroll(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()
                || left.getItem() != ItemRegistry.SCROLL.get()
                || right.getItem() != ItemRegistry.SCROLL.get()) return false;
        SpellScrollKey leftKey = spellScrollKey(left);
        return leftKey != null && leftKey.equals(spellScrollKey(right));
    }

    /** 原版联动、附属模组和数据包卷轴共用的稳定标识。 */
    @Nullable
    public static SpellScrollKey spellScrollKey(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != ItemRegistry.SCROLL.get()) {
            return null;
        }
        try {
            // 3.15 将列表元素从 SpellData 改为 SpellSlot，但擦除后的方法签名不变。
            // 不在编译时指定元素类型，以兼容两种版本。
            List<?> spells = ISpellContainer.get(stack).getActiveSpells();
            if (spells.size() != 1) return null;
            return spellEntryKey(spells.get(0));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static SpellScrollKey spellEntryKey(@Nullable Object entry) {
        if (entry == null) return null;
        try {
            SpellEntryAccess access = SPELL_ENTRY_ACCESS.computeIfAbsent(
                    entry.getClass(), IronSpellBooksRecipeCatalog::resolveSpellEntryAccess);
            Object spell = access.getSpell().invoke(entry);
            if (spell == null) return null;
            Method spellId = SPELL_ID_ACCESS.computeIfAbsent(
                    spell.getClass(), IronSpellBooksRecipeCatalog::resolveSpellIdAccess);
            Object rawId = spellId.invoke(spell);
            Object rawLevel = access.getLevel().invoke(entry);
            if (!(rawId instanceof String idText) || !(rawLevel instanceof Number number)) {
                return null;
            }
            ResourceLocation id = ResourceLocation.tryParse(idText);
            int level = number.intValue();
            return id == null || level <= 0 ? null : new SpellScrollKey(id, level);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static SpellEntryAccess resolveSpellEntryAccess(Class<?> type) {
        try {
            return new SpellEntryAccess(type.getMethod("getSpell"), type.getMethod("getLevel"));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("unsupported Iron spell entry " + type.getName(),
                    exception);
        }
    }

    private static Method resolveSpellIdAccess(Class<?> type) {
        try {
            return type.getMethod("getSpellId");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("unsupported Iron spell " + type.getName(), exception);
        }
    }

    public static void invalidate() {
        synchronized (IronSpellBooksRecipeCatalog.class) {
            catalog = null;
            generation++;
            IronAlchemistRecipeCatalog.invalidate();
        }
    }

    public static void onSpellConfigApplied() {
        boolean rarityCachesReset = false;
        synchronized (IronSpellBooksRecipeCatalog.class) {
            // 与构建目录的加锁顺序一致：先锁目录，再锁原生初始化。
            // 不在此处获取 RecipeIndex 的锁，避免与正在等待读取目录的构建互相阻塞。
            try {
                IronSpellRarityCache.resetAll(SpellRegistry.none(), SpellRegistry.REGISTRY.get());
                rarityCachesReset = true;
            } catch (RuntimeException | LinkageError failure) {
                // 配置同步发生在登录期间，不能让兼容异常中断玩家进入服务器。
                RSIntegrationMod.LOGGER.error(
                        "[RSI-IronSpells] Could not reset native rarity caches; "
                                + "invalidating dynamic recipes without aborting config sync",
                        failure);
            }
            catalog = null;
            generation++;
            CraftPlanningRevision.bump();
            IronAlchemistRecipeCatalog.invalidate();
        }
        if (rarityCachesReset) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-IronSpells] Applied spell config: reset native rarity caches and invalidated dynamic recipes");
        }
    }

    /** 检测动态目录构建后才生效的法术配置变更。 */
    public static boolean hasRuntimeDrift() {
        Catalog cached = catalog;
        return cached != null && cached.runtimeFingerprint() != runtimeFingerprint();
    }

    private static Catalog catalog() {
        Catalog cached = catalog;
        if (cached != null) return cached;
        synchronized (IronSpellBooksRecipeCatalog.class) {
            cached = catalog;
            if (cached == null) {
                SpellBuild build = new SpellBuild();
                build.steps.advance(() -> false);
                catalog = cached = build.result();
            }
        }
        return cached;
    }

    public static final class IncrementalCatalog {
        private SpellBuild spells;
        private long spellGeneration;
        private final IronAlchemistRecipeCatalog.IncrementalCatalog alchemist;
        private Catalog spellResult;

        public IncrementalCatalog(Level level) {
            alchemist = new IronAlchemistRecipeCatalog.IncrementalCatalog(level);
        }

        public boolean advance(BooleanSupplier budgetExpired) {
            synchronized (IronSpellBooksRecipeCatalog.class) {
                if (catalog == null) {
                    if (budgetExpired.getAsBoolean()) return false;
                    if (spells == null || spellGeneration != generation) {
                        spellGeneration = generation;
                        spells = new SpellBuild();
                    }
                    if (!spells.steps.advance(() -> spellGeneration != generation || budgetExpired.getAsBoolean())
                            || spellGeneration != generation) return false;
                    catalog = spells.result();
                }
                spellResult = catalog;
                spells = null;
            }
            return alchemist.advance(budgetExpired);
        }

        public Collection<IronSpellBooksRecipe> spellRecipes() {
            return spellResult.byId().values();
        }

        public Collection<IronSpellBooksRecipe> alchemistRecipes() {
            return alchemist.result();
        }
    }

    private static final class SpellBuild {
        final CatalogBuildSteps steps = new CatalogBuildSteps();
        final IronSpellCatalogDiagnostics diagnostics = new IronSpellCatalogDiagnostics();
        final Map<ResourceLocation, IronSpellBooksRecipe> recipes = new LinkedHashMap<>();
        final Map<OutputKey, IronSpellBooksRecipe> byOutput = new HashMap<>();
        final Map<ResourceLocation, List<ItemStack>> focuses = new LinkedHashMap<>();
        final Set<SchoolType> scannedSchools = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<AbstractSpell> spells = enabledSpells(diagnostics);
        final List<InkItem> inks = diagnostics.read("inks", "registry", 0,
                IronSpellBooksRecipeCatalog::findInks).orElse(List.of());
        long fingerprint;

        SpellBuild() {
            steps.then(() -> steps.each(spells.iterator(), this::findFocuses));
            steps.then(() -> steps.each(spells.iterator(),
                    spell -> addScrollForgeRecipes(recipes, focuses, spell, inks, diagnostics)));
            steps.then(() -> addArcaneAnvilRecipes(steps, recipes, spells, diagnostics));
            steps.then(() -> IronAlchemistRecipes.addRecipes(steps, recipes, spells, inks, diagnostics));
            steps.then(() -> steps.each(recipes.values().iterator(), recipe -> {
                ItemStack output = recipe.getResultItem(RegistryAccess.EMPTY);
                if (!output.isEmpty()) byOutput.putIfAbsent(OutputKey.of(recipe.machine(), output), recipe);
            }));
            steps.then(() -> scheduleFingerprint(steps, diagnostics, spells, inks, value -> fingerprint = value));
        }

        void findFocuses(AbstractSpell spell) {
            String label = spellLabel(spell);
            var school = diagnostics.read("focus", label, 0, spell::getSchoolType).orElse(null);
            if (school == null) return;
            ResourceLocation id = diagnostics.read("focus", label, 0, school::getId).orElse(null);
            if (id == null || focuses.containsKey(id) || !scannedSchools.add(school)) return;
            List<ItemStack> matches = new ArrayList<>();
            steps.afterCurrent(() -> {
                if (!matches.isEmpty()) focuses.put(id, matches);
            });
            steps.each(ForgeRegistries.ITEMS.getValues().iterator(), item -> {
                var matched = diagnostics.read("focus", label, 0, () -> {
                    ItemStack stack = new ItemStack(item);
                    return school.isFocus(stack) ? stack : null;
                });
                if (matched.value() != null) matches.add(matched.value());
            });
        }

        Catalog result() {
            if (diagnostics.hasFailures()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-IronSpells] Published partial dynamic catalog: retained={} {}; "
                                + "unreadable spell variants were skipped, no rarity or material was guessed",
                        recipes.size(), diagnostics.summary());
            }
            return new Catalog(Collections.unmodifiableMap(recipes),
                    Collections.unmodifiableMap(byOutput), fingerprint);
        }
    }

    private static void addScrollForgeRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result,
                                              Map<ResourceLocation, List<ItemStack>> focuses,
                                              AbstractSpell spell, List<InkItem> inks,
                                              IronSpellCatalogDiagnostics diagnostics) {
        // 沿用原生 JEI 的规则：所有已注册墨水均可作为输入，由稀有度确定起始卷轴等级。
        // 不用 spell.getRarity(level) 反推，自定义法术和旧版本的稀有度曲线可能不同。
        String label = spellLabel(spell);
        diagnostics.read("scroll_forge_spell", label, 0, () -> {
            if (spell == SpellRegistry.none() || !spell.allowCrafting()) return null;
            List<ItemStack> focusOptions = focuses.getOrDefault(spell.getSchoolType().getId(), List.of());
            if (focusOptions.isEmpty()) return null;
            Map<Integer, List<InkItem>> inksByLevel = new LinkedHashMap<>();
            for (InkItem ink : inks) {
                int level = diagnostics.read("scroll_forge_ink", label, 0,
                        () -> spell.getMinLevelForRarity(ink.getRarity())).orElse(-1);
                if (level <= 0 || level > spell.getMaxLevel()) continue;
                inksByLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(ink);
            }
            for (Map.Entry<Integer, List<InkItem>> entry : inksByLevel.entrySet()) {
                int level = entry.getKey();
                diagnostics.read("scroll_forge_output", label, level, () -> {
                    List<InkItem> levelInks = entry.getValue();
                    ItemStack displayInk = new ItemStack(levelInks.get(0));
                    Ingredient inkIngredient = Ingredient.of(levelInks.stream().map(ItemStack::new));
                    ItemStack output = scroll(spell, level);
                    ResourceLocation id = id("scroll_forge", spell.getSpellResource(), level, null);
                    result.put(id, new IronSpellBooksRecipe(id,
                            IronSpellBooksRecipe.Machine.SCROLL_FORGE,
                            List.of(displayInk, new ItemStack(Items.PAPER), focusOptions.get(0)),
                            List.of(inkIngredient, Ingredient.of(Items.PAPER),
                                    ingredientOf(focusOptions)), output, spell.getSpellId(), level));
                    return null;
                });
            }
            return null;
        });
    }

    private static void addArcaneAnvilRecipes(CatalogBuildSteps steps,
                                             Map<ResourceLocation, IronSpellBooksRecipe> result,
                                             List<AbstractSpell> spells,
                                             IronSpellCatalogDiagnostics diagnostics) {
        JeiAnvilAccess access = diagnostics.read("arcane_anvil_api", "jei", 0, () -> {
            Class<?> recipeClass = findClass(
                    "io.redspace.ironsspellbooks.jei.ArcaneAnvilJeiRecipe",
                    "io.redspace.ironsspellbooks.jei.ArcaneAnvilRecipe");
            return new JeiAnvilAccess(recipeClass.getConstructor(AbstractSpell.class, int.class),
                    recipeClass.getMethod("getRecipeItems"));
        }).orElse(null);
        steps.each(spells.iterator(), spell -> {
            int[] range = spellRange(spell, diagnostics);
            if (range == null) return;
            String label = spellLabel(spell);
            steps.each(IntStream.range(range[0], range[1]).iterator(), level -> {
                IronSpellBooksRecipe recipe = diagnostics.withFallback(label, level + 1,
                        access == null ? null : () -> reflectedUpgrade(access, spell, level),
                        () -> nativeUpgrade(spell, level));
                if (recipe != null) result.put(recipe.getId(), recipe);
            });
        });
    }

    private record JeiAnvilAccess(Constructor<?> constructor, Method getRecipeItems) {}

    private static IronSpellBooksRecipe reflectedUpgrade(JeiAnvilAccess access, AbstractSpell spell,
                                                         int level) throws ReflectiveOperationException {
        Object tuple = access.getRecipeItems().invoke(access.constructor().newInstance(spell, level));
        List<ItemStack> leftItems = tupleItems(tuple, "a");
        List<ItemStack> rightItems = tupleItems(tuple, "b");
        List<ItemStack> outputs = tupleItems(tuple, "c");
        if (leftItems.isEmpty() || rightItems.isEmpty() || outputs.isEmpty()) return null;
        ResourceLocation id = id("arcane_anvil/scroll_upgrade", spell.getSpellResource(), level + 1, null);
        return new IronSpellBooksRecipe(id, IronSpellBooksRecipe.Machine.ARCANE_ANVIL,
                List.of(leftItems.get(0), rightItems.get(0)),
                List.of(exactIngredientOf(leftItems), exactIngredientOf(rightItems)),
                outputs.get(0), spell.getSpellId(), level + 1);
    }

    private static IronSpellBooksRecipe nativeUpgrade(AbstractSpell spell, int level) {
        InkItem ink = InkItem.getInkForRarity(spell.getRarity(level + 1));
        if (ink == null) return null;
        ItemStack left = scroll(spell, level);
        ItemStack right = new ItemStack(ink);
        ItemStack output = scroll(spell, level + 1);
        ResourceLocation id = id("arcane_anvil/scroll_upgrade", spell.getSpellResource(), level + 1, null);
        return new IronSpellBooksRecipe(id, IronSpellBooksRecipe.Machine.ARCANE_ANVIL,
                List.of(left, right),
                List.of(exactIngredientOf(List.of(left)), exactIngredientOf(List.of(right))),
                output, spell.getSpellId(), level + 1);
    }

    private static Class<?> findClass(String... names) throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                failure = e;
            }
        }
        throw failure != null ? failure : new ClassNotFoundException();
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> tupleItems(Object tuple, String component)
            throws ReflectiveOperationException {
        Object value = tuple.getClass().getMethod(component).invoke(tuple);
        if (!(value instanceof List<?> list)) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof ItemStack stack && !stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    private static ItemStack scroll(AbstractSpell spell, int level) {
        ItemStack stack = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, level, stack);
        return stack;
    }

    static ItemStack scrollFor(AbstractSpell spell, int level) {
        return level <= 0 ? ItemStack.EMPTY : scroll(spell, level);
    }

    private static Ingredient ingredientOf(List<ItemStack> options) {
        return Ingredient.of(options.stream().map(ItemStack::copy));
    }

    private static Ingredient exactIngredientOf(List<ItemStack> options) {
        Ingredient[] alternatives = options.stream()
                .map(stack -> stack.hasTag()
                        ? (Ingredient) StrictNBTIngredient.of(stack.copy())
                        : Ingredient.of(stack.copy()))
                .toArray(Ingredient[]::new);
        return alternatives.length == 1 ? alternatives[0]
                : CompoundIngredient.of(alternatives);
    }

    private static List<InkItem> findInks() {
        List<InkItem> result = new ArrayList<>();
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            if (item instanceof InkItem ink) result.add(ink);
        }
        return result;
    }

    private static long runtimeFingerprint() {
        IronSpellCatalogDiagnostics diagnostics = new IronSpellCatalogDiagnostics();
        CatalogBuildSteps steps = new CatalogBuildSteps();
        long[] hash = new long[1];
        scheduleFingerprint(steps, diagnostics, enabledSpells(diagnostics),
                diagnostics.read("inks", "registry", 0, IronSpellBooksRecipeCatalog::findInks).orElse(List.of()),
                value -> hash[0] = value);
        steps.advance(() -> false);
        return hash[0];
    }

    private static List<AbstractSpell> enabledSpells(IronSpellCatalogDiagnostics diagnostics) {
        return diagnostics.read("spell_registry", "registry", 0,
                () -> List.copyOf(SpellRegistry.getEnabledSpells())).orElse(List.of());
    }

    private static String spellLabel(AbstractSpell spell) {
        try {
            return spell.getSpellId();
        } catch (RuntimeException | LinkageError failure) {
            return spell.getClass().getName();
        }
    }

    @Nullable
    private static int[] spellRange(AbstractSpell spell, IronSpellCatalogDiagnostics diagnostics) {
        return diagnostics.read("spell_range", spellLabel(spell), 0, () -> {
            int minimum = spell.getMinLevel();
            int maximum = spell.getMaxLevel();
            if (minimum <= 0 || maximum < minimum) {
                throw new IllegalArgumentException("invalid spell range " + minimum + ".." + maximum);
            }
            return new int[]{minimum, maximum};
        }).orElse(null);
    }

    private static void scheduleFingerprint(CatalogBuildSteps steps, IronSpellCatalogDiagnostics diagnostics,
                                            List<AbstractSpell> enabled, List<InkItem> registeredInks,
                                            LongConsumer complete) {
        List<AbstractSpell> spells = new ArrayList<>(enabled);
        spells.sort(Comparator.comparing(IronSpellBooksRecipeCatalog::spellLabel));
        List<InkItem> inks = new ArrayList<>(registeredInks);
        inks.sort(Comparator.comparing(ink -> {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(ink);
            return id == null ? "" : id.toString();
        }));
        long[] hash = {0xcbf29ce484222325L};
        steps.afterCurrent(() -> complete.accept(hash[0]));
        steps.each(spells.iterator(), spell -> {
            String label = spellLabel(spell);
            hash[0] = fingerprint(hash[0], label);
            hash[0] = fingerprintProbe(hash[0], diagnostics, "fingerprint_crafting", label, 0,
                    () -> spell.allowCrafting() ? 1 : 0);
            int[] range = spellRange(spell, diagnostics);
            hash[0] = fingerprint(hash[0], range == null ? "invalid_range" : "valid_range");
            if (range == null) return;
            hash[0] = fingerprint(hash[0], range[0]);
            hash[0] = fingerprint(hash[0], range[1]);
            steps.each(inks.iterator(), ink -> {
                ResourceLocation inkId = ForgeRegistries.ITEMS.getKey(ink);
                hash[0] = fingerprint(hash[0], inkId == null ? "" : inkId.toString());
                hash[0] = fingerprintProbe(hash[0], diagnostics, "fingerprint_ink", label, 0,
                        () -> spell.getMinLevelForRarity(ink.getRarity()));
            });
            steps.each(LongStream.rangeClosed(range[0], range[1]).iterator(), index -> {
                int level = index.intValue();
                hash[0] = fingerprintProbe(hash[0], diagnostics, "fingerprint_rarity", label, level,
                        () -> spell.getRarity(level).getValue());
            });
        });
    }

    static long fingerprintProbe(long hash, IronSpellCatalogDiagnostics diagnostics,
                                 String stage, String spell, int level,
                                 IronSpellCatalogDiagnostics.Probe<Integer> probe) {
        IronSpellCatalogDiagnostics.Result<Integer> result = diagnostics.read(stage, spell, level, probe);
        hash = fingerprint(hash, result.failed() ? "unavailable" : "value");
        return result.failed() ? hash : fingerprint(hash, result.value());
    }

    private static long fingerprint(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long fingerprint(long hash, int value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static ResourceLocation id(String kind, ResourceLocation spell, int level,
                                       @Nullable ResourceLocation material) {
        String path = "irons_spellbooks/" + kind + "/" + spell.getNamespace() + "/"
                + spell.getPath() + "/" + level;
        if (material != null) path += "/" + material.getNamespace() + "/" + material.getPath();
        return new ResourceLocation("rs_integration", path);
    }

    private record Catalog(Map<ResourceLocation, IronSpellBooksRecipe> byId,
                           Map<OutputKey, IronSpellBooksRecipe> byOutput,
                           long runtimeFingerprint) {}

    private record SpellEntryAccess(Method getSpell, Method getLevel) {}

    private record OutputKey(IronSpellBooksRecipe.Machine machine, ResourceLocation itemId,
                             @Nullable CompoundTag tag) {
        private static OutputKey of(IronSpellBooksRecipe.Machine machine, ItemStack stack) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return new OutputKey(machine, itemId, stack.hasTag() ? stack.getTag().copy() : null);
        }
    }
}
