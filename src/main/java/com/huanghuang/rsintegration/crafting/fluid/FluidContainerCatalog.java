package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.RecipeIndexDiagnostics;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.planning.PlanningThreadContext;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/** 从真实 Forge 容器能力生成执行与 JEI 共用的转换定义。 */
public final class FluidContainerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    // 每个 manager 独占一份完整目录；弱键释放时，其 ID 子表也一并释放。
    private static final Map<RecipeManager, PublishedCatalog> CATALOGS = new WeakHashMap<>();
    // 无 manager 的同步兼容入口独立持有定义，生命周期截至 invalidate。
    private static final Map<ResourceLocation, FluidContainerRecipe> COMPATIBILITY_BY_ID = new LinkedHashMap<>();

    private record PublishedCatalog(List<FluidContainerRecipe> recipes,
                                    Map<ResourceLocation, FluidContainerRecipe> byId) {}

    private static final Map<RecipeManager, IncrementalIndex> PENDING = new WeakHashMap<>();
    private static long epoch;

    private FluidContainerCatalog() {}

    /** 显式同步兼容入口与增量入口使用同一个状态机。 */
    public static void index(Level level, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        IncrementalIndex build = incrementalIndex(level, index, seen, projected, () -> true);
        try {
            build.advance(() -> false);
            build.publish();
        } finally {
            build.cancel();
        }
    }

    public static synchronized IncrementalIndex incrementalIndex(Level level,
            Map<Item, List<RecipeIndex.Entry>> index, Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected,
            BooleanSupplier current) {
        PlanningThreadContext.requireMainThread("fluid container catalog");
        IncrementalIndex previous = PENDING.get(level.getRecipeManager());
        if (previous != null) previous.cancel();
        IncrementalIndex build = new IncrementalIndex(level, index, seen, projected, current);
        PENDING.put(build.manager, build);
        return build;
    }

    /** 同步完整定义列表的兼容入口；增量会话只调用 stage，完成前不走此入口。 */
    static synchronized void publish(List<FluidContainerRecipe> definitions, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        Map<ResourceLocation, FluidContainerRecipe> complete = new LinkedHashMap<>();
        for (FluidContainerRecipe recipe : definitions) {
            stage(recipe, index, seen, projected);
            complete.put(recipe.getId(), recipe);
        }
        COMPATIBILITY_BY_ID.putAll(complete);
    }

    private static void stage(FluidContainerRecipe recipe, Map<Item, List<RecipeIndex.Entry>> index,
            Set<ResourceLocation> seen,
            Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected) {
        if (!seen.add(recipe.getId())) return;
        index.computeIfAbsent(recipe.output().getItem(), ignored -> new ArrayList<>())
                .add(new RecipeIndex.Entry(recipe, ModType.FLUID_CONTAINER, FluidContainerRecipe.TYPE_ID, true));
        var node = ImmutableRecipeGraphProjector.projectRecipe(recipe.getId(), recipe.output(),
                recipe.specs(), ModType.FLUID_CONTAINER.id(), FluidContainerRecipe.TYPE_ID);
        if (node != null) projected.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
    }

    public static synchronized List<FluidContainerRecipe> allRecipes(Level level) {
        if (level == null) return List.of();
        PublishedCatalog ready = CATALOGS.get(level.getRecipeManager());
        if (ready != null) return ready.recipes();
        // 服务端会话尚在其他 source 时也不能由执行器/预览触发第二次同步扫描。
        if (PENDING.containsKey(level.getRecipeManager())
                || !level.isClientSide && RecipeIndex.isBuildInFlight()) return List.of();
        IncrementalIndex build = incrementalIndex(level, new LinkedHashMap<>(), new HashSet<>(),
                new LinkedHashMap<>(), () -> true);
        try {
            build.advance(() -> false);
            return build.publish() ? build.definitions : List.of();
        } finally {
            build.cancel();
        }
    }

    public static synchronized FluidContainerRecipe byId(ResourceLocation id) {
        FluidContainerRecipe compatible = COMPATIBILITY_BY_ID.get(id);
        if (compatible != null) return compatible;
        // ID 由完整转换内容生成；跨 manager 的相同 ID 表示同一转换。
        // 这里只遍历已发布目录个数，每份子表直接查 ID，不扫描配方或合并整表。
        for (PublishedCatalog catalog : CATALOGS.values()) {
            FluidContainerRecipe recipe = catalog.byId().get(id);
            if (recipe != null) return recipe;
        }
        return null;
    }

    public static synchronized void invalidate() {
        epoch++;
        COMPATIBILITY_BY_ID.clear();
        CATALOGS.clear();
        PENDING.clear();
    }

    public static synchronized FluidContainerRecipe resolve(Level level, ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace()) || !id.getPath().startsWith("fluid_container/")) return null;
        PublishedCatalog local = level == null ? null : CATALOGS.get(level.getRecipeManager());
        if (local != null) {
            FluidContainerRecipe recipe = local.byId().get(id);
            if (recipe != null) return recipe;
        }
        FluidContainerRecipe recipe = byId(id);
        if (recipe == null) { allRecipes(level); recipe = byId(id); }
        return recipe;
    }

    /** 所有真实配方和能力访问都在创建会话的主线程执行，每个循环单元前检查截止时间。 */
    public static final class IncrementalIndex {
        private final Level level;
        private final RecipeManager manager;
        private final long generation = epoch;
        private final long revision = CraftPlanningRevision.current();
        private final Thread owner = Thread.currentThread();
        private final BooleanSupplier current;
        private final Map<Item, List<RecipeIndex.Entry>> index;
        private final Set<ResourceLocation> seen;
        private final Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected;
        private final List<ItemStack> samples = new ArrayList<>();
        private final List<FluidStack> fluids = new ArrayList<>();
        private final Set<ResourceLocation> inspected = new HashSet<>();
        private final Map<ResourceLocation, FluidContainerRecipe> byId = new LinkedHashMap<>();
        private Iterator<Item> items;
        private Iterator<Fluid> registryFluids;
        private Iterator<Recipe<?>> nativeRecipes;
        private Iterator<List<RecipeIndex.Entry>> entryGroups;
        private Iterator<RecipeIndex.Entry> entries = Collections.emptyIterator();
        private Iterator<FluidContainerRecipe> staged;
        private RecipeSamples reading;
        private Discovery discovery;
        private List<FluidContainerRecipe> definitions;
        private PublishedCatalog completed;
        private int phase;
        private int sampleCursor;
        private boolean cancelled;
        private boolean published;
        private long advances;
        private long computeNanos;
        private long maxAdvanceNanos;

        private IncrementalIndex(Level level, Map<Item, List<RecipeIndex.Entry>> index,
                Set<ResourceLocation> seen,
                Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected,
                BooleanSupplier current) {
            this.level = level;
            this.manager = level.getRecipeManager();
            this.index = index;
            this.seen = seen;
            this.projected = projected;
            this.current = current;
        }

        private boolean isCurrent() {
            synchronized (FluidContainerCatalog.class) {
                return !cancelled && generation == epoch && revision == CraftPlanningRevision.current()
                        && manager == level.getRecipeManager() && current.getAsBoolean();
            }
        }

        public boolean advance(BooleanSupplier shouldYield) {
            checkThread();
            if (!isCurrent()) { cancel(); return false; }
            if (phase == 8) return true;
            long started = System.nanoTime();
            try {
                while (phase != 8 && !shouldYield.getAsBoolean()) {
                    if (!isCurrent()) { cancel(); return false; }
                    long unitStart = System.nanoTime();
                    int activePhase = phase;
                    step(shouldYield);
                    RecipeIndexDiagnostics.record("fluid_container_phase_" + activePhase,
                            System.nanoTime() - unitStart, "<catalog>", "container",
                            FluidContainerCatalog.class, samples.size(), byId.size());
                }
                return phase == 8;
            } finally {
                long elapsed = System.nanoTime() - started;
                advances++;
                computeNanos += elapsed;
                maxAdvanceNanos = Math.max(maxAdvanceNanos, elapsed);
            }
        }

        private void step(BooleanSupplier shouldYield) {
            switch (phase) {
                case 0 -> {
                    if (items == null) items = BuiltInRegistries.ITEM.iterator();
                    if (items.hasNext()) samples.add(items.next().getDefaultInstance());
                    else phase++;
                }
                case 1 -> {
                    if (registryFluids == null) registryFluids = BuiltInRegistries.FLUID.iterator();
                    if (!registryFluids.hasNext()) { phase++; return; }
                    Fluid fluid = registryFluids.next();
                    if (fluid != Fluids.EMPTY && (!(fluid instanceof FlowingFluid flowing) || flowing.getSource() == fluid))
                        fluids.add(new FluidStack(fluid, 1));
                }
                case 2, 3 -> {
                    if (reading != null) {
                        if (reading.advance()) reading = null;
                        return;
                    }
                    Recipe<?> recipe;
                    if (phase == 2) {
                        if (nativeRecipes == null) nativeRecipes = manager.getRecipes().iterator();
                        if (!nativeRecipes.hasNext()) { phase++; return; }
                        recipe = nativeRecipes.next();
                    } else {
                        if (entryGroups == null) entryGroups = index.values().iterator();
                        if (!entries.hasNext()) {
                            if (!entryGroups.hasNext()) { phase++; return; }
                            entries = entryGroups.next().iterator();
                            return;
                        }
                        recipe = entries.next().recipe();
                    }
                    if (inspected.add(recipe.getId()) && !(recipe instanceof FluidContainerRecipe)) reading = new RecipeSamples(recipe);
                }
                case 4 -> {
                    if (sampleCursor == samples.size()) { phase++; return; }
                    ItemStack sample = samples.get(sampleCursor++);
                    if (InkFluidSupport.isToken(sample)) fluids.add(InkFluidSupport.fluid(sample));
                }
                case 5 -> {
                    if (discovery == null) discovery = new Discovery(samples, fluids, FluidContainerCatalog::handler, InkFluidSupport::token);
                    if (discovery.advance(shouldYield)) {
                        definitions = discovery.result();
                        staged = definitions.iterator();
                        phase++;
                    }
                }
                case 6 -> {
                    if (!staged.hasNext()) { phase++; return; }
                    FluidContainerRecipe recipe = staged.next();
                    byId.put(recipe.getId(), recipe);
                    stage(recipe, index, seen, projected);
                }
                case 7 -> {
                    // 所有容器均由本会话独占；冻结视图不再整表复制，发布时只替换引用。
                    completed = new PublishedCatalog(definitions, Collections.unmodifiableMap(byId));
                    phase++;
                }
                default -> throw new IllegalStateException("unknown fluid catalog phase");
            }
        }

        public boolean publish() {
            checkThread();
            synchronized (FluidContainerCatalog.class) {
                if (published) return isCurrent() && CATALOGS.get(manager) == completed;
                if (phase != 8 || !isCurrent() || PENDING.get(manager) != this) return false;
                CATALOGS.put(manager, completed);
                PENDING.remove(manager);
                published = true;
                LOGGER.info("[FluidContainerCatalog] ready recipes={} advances={} computeMs={} maxAdvanceMs={}",
                        definitions.size(), advances, computeNanos / 1_000_000.0, maxAdvanceNanos / 1_000_000.0);
                return true;
            }
        }

        public void cancel() {
            synchronized (FluidContainerCatalog.class) {
                if (!published) cancelled = true;
                if (PENDING.get(manager) == this) PENDING.remove(manager);
            }
        }

        private void checkThread() {
            PlanningThreadContext.requireMainThread("fluid container catalog");
            if (Thread.currentThread() != owner) throw new IllegalStateException("fluid catalog changed thread");
            if (!level.isClientSide && level.getServer() != null && !level.getServer().isSameThread()) {
                throw new PlanningThreadContext.MainThreadPlanningRequiredException("fluid container catalog");
            }
        }

        private final class RecipeSamples {
            private final Recipe<?> recipe;
            private int step;
            private Iterator<ItemStack> secondary;
            private Iterator<IngredientSpec> specs;
            private ItemStack[] alternatives;
            private int alternative;

            private RecipeSamples(Recipe<?> recipe) { this.recipe = recipe; }

            private boolean advance() {
                try {
                    switch (step) {
                        case 0 -> { samples.add(ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess())); step++; }
                        case 1 -> { secondary = ModRecipeHandlers.tryGetSecondaryOutputs(recipe, level.registryAccess()).iterator(); step++; }
                        case 2 -> { if (secondary.hasNext()) samples.add(secondary.next()); else step++; }
                        case 3 -> {
                            List<IngredientSpec> extracted = CraftPacketUtils.extractIngredientSpecs(recipe);
                            specs = extracted == null ? Collections.emptyIterator() : extracted.iterator();
                            step++;
                        }
                        case 4 -> {
                            if (alternatives != null && alternative < alternatives.length) samples.add(alternatives[alternative++]);
                            else if (specs.hasNext()) { alternatives = specs.next().ingredient().getItems(); alternative = 0; }
                            else return true;
                        }
                        default -> throw new IllegalStateException();
                    }
                } catch (RuntimeException | LinkageError error) {
                    LOGGER.debug("无法读取液体容器候选配方 {}", recipe.getId(), error);
                    return true;
                }
                return false;
            }
        }
    }
    private static IFluidHandlerItem handler(ItemStack stack) {
        IFluidHandlerItem bottle = InkBottleFluidHandler.create(stack.copyWithCount(1));
        return bottle != null ? bottle : FluidUtil.getFluidHandler(stack.copyWithCount(1)).orElse(null);
    }

    static List<FluidContainerRecipe> discover(List<ItemStack> samples, List<FluidStack> candidates,
            Function<ItemStack, IFluidHandlerItem> handlers, Function<FluidStack, ItemStack> tokens) {
        Discovery build = new Discovery(samples, candidates, handlers, tokens);
        build.advance(() -> false);
        return build.result();
    }

    /** 与同步 discover 共用的逐单元探测，单次第三方调用无法抢占。 */
    static final class Discovery {
        private final List<ItemStack> samples;
        private final List<FluidStack> candidates;
        private final FluidContainerBucketSupport.HandlerIndex buckets;
        private final Function<ItemStack, IFluidHandlerItem> handlers;
        private final Function<FluidStack, ItemStack> tokens;
        private final Map<CompoundTag, ItemStack> empty = new LinkedHashMap<>();
        private final Map<CompoundTag, FluidStack> fluids = new LinkedHashMap<>();
        private final Map<ResourceLocation, FluidContainerRecipe> recipes = new LinkedHashMap<>();
        private final Set<CompoundTag> seen = new HashSet<>();
        private final List<FluidContainerRecipe> result = new ArrayList<>();
        private Iterator<ItemStack> containers;
        private Iterator<FluidStack> identities = Collections.emptyIterator();
        private Iterator<FluidContainerRecipe> results;
        private ItemStack container;
        private int phase;
        private int cursor;

        Discovery(List<ItemStack> samples, List<FluidStack> candidates,
                Function<ItemStack, IFluidHandlerItem> handlers, Function<FluidStack, ItemStack> tokens) {
            this.samples = samples;
            this.candidates = candidates;
            this.buckets = new FluidContainerBucketSupport.HandlerIndex(handlers);
            this.handlers = buckets::apply;
            this.tokens = tokens;
        }

        boolean advance(BooleanSupplier shouldYield) {
            while (phase != 5 && !shouldYield.getAsBoolean()) {
                long start = System.nanoTime();
                switch (phase) {
                    case 0 -> {
                        if (cursor < samples.size()) buckets.inspect(samples.get(cursor++));
                        else { phase++; cursor = 0; }
                    }
                    case 1 -> {
                        if (cursor < candidates.size()) addFluid(fluids, candidates.get(cursor++));
                        else { phase++; cursor = 0; }
                    }
                    case 2 -> {
                        if (cursor < samples.size()) inspect(samples.get(cursor++));
                        else { phase++; containers = empty.values().iterator(); }
                    }
                    case 3 -> {
                        if (identities.hasNext()) fill(container, identities.next());
                        else if (containers.hasNext()) { container = containers.next(); identities = fluids.values().iterator(); }
                        else { phase++; results = recipes.values().iterator(); }
                    }
                    case 4 -> { if (results.hasNext()) result.add(results.next()); else phase++; }
                    default -> throw new IllegalStateException();
                }
                RecipeIndexDiagnostics.record("fluid_container_probe", System.nanoTime() - start,
                        "<catalog>", "container", FluidContainerCatalog.class, samples.size(), recipes.size());
            }
            return phase == 5;
        }

        List<FluidContainerRecipe> result() {
            if (phase != 5) throw new IllegalStateException("fluid discovery is incomplete");
            return Collections.unmodifiableList(result);
        }

        private void inspect(ItemStack sample) {
            if (sample.isEmpty()) return;
            CompoundTag sampleKey = stackKey(sample);
            if (!seen.add(sampleKey)) return;
            try {
                IFluidHandlerItem handler = handlers.apply(sample.copyWithCount(1));
                if (handler == null || handler.getTanks() != 1) return;
                FluidStack content = handler.getFluidInTank(0).copy();
                if (content.isEmpty()) empty.putIfAbsent(sampleKey, sample.copyWithCount(1));
                else {
                    addFluid(fluids, content);
                    ItemStack drained = drain(sample, content, handlers);
                    if (!drained.isEmpty()) {
                        empty.putIfAbsent(stackKey(drained), drained);
                        add(recipes, false, drained, sample, content, tokens);
                    }
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.debug("无法探测液体容器 {}", sample, error);
            }

        }

        private void fill(ItemStack container, FluidStack identity) {
            try {
                IFluidHandlerItem handler = handlers.apply(container.copyWithCount(1));
                if (handler == null || handler.getTanks() != 1 || !handler.getFluidInTank(0).isEmpty()) return;
                int capacity = handler.getTankCapacity(0);
                if (capacity <= 0) return;
                FluidStack offered = identity.copy();
                offered.setAmount(capacity);
                int accepted = handler.fill(offered.copy(), IFluidHandler.FluidAction.SIMULATE);
                if (accepted <= 0 || accepted > capacity || !handler.getFluidInTank(0).isEmpty()) return;
                offered.setAmount(accepted);
                if (handler.fill(offered.copy(), IFluidHandler.FluidAction.EXECUTE) != accepted) return;
                ItemStack filled = handler.getContainer().copy();
                if (filled.isEmpty() || filled.getCount() != 1 || ItemStack.isSameItemSameTags(container, filled)) return;
                IFluidHandlerItem result = handlers.apply(filled.copy());
                if (result == null || result.getTanks() != 1 || !sameFluid(result.getFluidInTank(0), offered)) return;
                add(recipes, true, container, filled, offered, tokens);
                ItemStack drained = drain(filled, offered, handlers);
                if (!drained.isEmpty()) add(recipes, false, drained, filled, offered, tokens);
            } catch (RuntimeException | LinkageError error) {
                LOGGER.debug("无法装填液体容器 {}", container, error);
            }

        }
    }
    private static ItemStack drain(ItemStack filled, FluidStack content,
                                  Function<ItemStack, IFluidHandlerItem> handlers) {
        IFluidHandlerItem handler = handlers.apply(filled.copyWithCount(1));
        if (handler == null || handler.getTanks() != 1 || !sameFluid(handler.getFluidInTank(0), content)) return ItemStack.EMPTY;
        if (!sameFluid(handler.drain(content.copy(), IFluidHandler.FluidAction.SIMULATE), content)
                || !sameFluid(handler.getFluidInTank(0), content)) return ItemStack.EMPTY;
        if (!sameFluid(handler.drain(content.copy(), IFluidHandler.FluidAction.EXECUTE), content)) return ItemStack.EMPTY;
        ItemStack empty = handler.getContainer().copy();
        if (empty.isEmpty() || empty.getCount() != 1 || ItemStack.isSameItemSameTags(empty, filled)) return ItemStack.EMPTY;
        IFluidHandlerItem result = handlers.apply(empty.copy());
        return result != null && result.getTanks() == 1 && result.getFluidInTank(0).isEmpty() ? empty : ItemStack.EMPTY;
    }

    public static boolean isValid(FluidContainerRecipe recipe) {
        try {
            ItemStack empty = recipe.emptyContainer();
            ItemStack filled = recipe.filledContainer();
            Function<ItemStack, IFluidHandlerItem> handlers =
                    empty.getItem() instanceof BucketItem || filled.getItem() instanceof BucketItem
                            ? FluidContainerBucketSupport.handlers(List.of(empty, filled),
                                    FluidContainerCatalog::handler)
                            : stack -> FluidContainerBucketSupport.defaultHandler(stack,
                                    FluidContainerCatalog::handler);
            if (!recipe.filling()) return ItemStack.isSameItemSameTags(empty,
                    drain(filled, recipe.fluid(), handlers));
            IFluidHandlerItem handler = handlers.apply(empty);
            if (handler == null || handler.getTanks() != 1 || !handler.getFluidInTank(0).isEmpty()) return false;
            FluidStack fluid = recipe.fluid();
            if (handler.fill(fluid.copy(), IFluidHandler.FluidAction.SIMULATE) != fluid.getAmount()
                    || handler.fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE) != fluid.getAmount()) return false;
            if (!ItemStack.isSameItemSameTags(handler.getContainer(), filled)) return false;
            IFluidHandlerItem result = handlers.apply(handler.getContainer());
            return result != null && result.getTanks() == 1 && sameFluid(result.getFluidInTank(0), fluid);
        } catch (RuntimeException | LinkageError error) { return false; }
    }

    private static boolean sameFluid(FluidStack left, FluidStack right) {
        return left.isFluidEqual(right) && left.getAmount() == right.getAmount();
    }

    private static void addFluid(Map<CompoundTag, FluidStack> fluids, FluidStack fluid) {
        if (fluid.isEmpty()) return;
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        fluids.putIfAbsent(identity.writeToNBT(new CompoundTag()), identity);
    }

    private static CompoundTag stackKey(ItemStack stack) {
        // 去重忽略数量；直接归一化已保存的键，避免先复制整只堆叠。
        CompoundTag key = stack.save(new CompoundTag());
        key.putByte("Count", (byte) 1);
        return key;
    }

    private static void add(Map<ResourceLocation, FluidContainerRecipe> recipes, boolean filling,
                            ItemStack empty, ItemStack filled, FluidStack fluid,
                            Function<FluidStack, ItemStack> tokens) {
        CompoundTag identity = new CompoundTag();
        identity.put("empty", empty.copyWithCount(1).save(new CompoundTag()));
        identity.put("filled", filled.copyWithCount(1).save(new CompoundTag()));
        identity.put("fluid", fluid.writeToNBT(new CompoundTag()));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
            ResourceLocation id = new ResourceLocation("rs_integration",
                    "fluid_container/" + (filling ? "fill/" : "drain/") + hash);
            recipes.computeIfAbsent(id, ignored ->
                    new FluidContainerRecipe(id, filling, empty, filled, fluid, tokens.apply(fluid.copy())));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
