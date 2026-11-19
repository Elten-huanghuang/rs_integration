package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.huanghuang.rsintegration.crafting.PackTypedPlanningReplay;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/** 用安装包中的固定配方和磁盘库存运行值规划器，不启动游戏。 */
public final class PackPlanningReplay {
    private static final String TARGET = "enigmaticlegacy:enigmatic_item";
    private static final String TARGET_RECIPE = "avaritia:kjs/enigmaticlegacy_enigmatic_item";
    private static final String CUBE = "enigmaticlegacy:the_cube";
    private static final String CUBE_RECIPE = "wizards_reborn:kjs/4pci6vhzba5ac8ojrt43rpwir";
    private static final String INFINITY_CATALYST = "avaritia:infinity_catalyst";
    private static final String CATALYST_RECIPE = "crafttweaker:infinity_catalyst";
    private static final String VOID_FAVOR = "malum:favor_of_the_void";
    private static final String SPIRIT_FOCUSING = "malum:spirit_focusing";
    private static final String CAULDRON = "irons_spellbooks:alchemist_cauldron_brew";
    private static final String ITEM_ID = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final String SEED = "enigmaticdelicacy:enigmatic_seed";
    private static final Map<String, String> ENIGMATIC_ITEM_OPTIONS = Map.of(
            "enigmaticlegacy:evil_ingot", "NefariousIngotEnabled",
            "enigmaticlegacy:cosmic_heart", "HeartoftheCosmosEnabled");

    private final Map<String, JsonObject> raw = new LinkedHashMap<>();
    private final Map<String, List<JsonElement>> tags = new HashMap<>();
    private final Set<String> unsupportedScriptRecipes = new HashSet<>();
    private Set<String> enabledEnigmaticItems = Set.of();

    public static void main(String[] args) throws Exception {
        if (args[0].isBlank()) throw new IllegalArgumentException("需要 -PpackPlanningPath");
        Path root = Path.of(args[0]);
        PackPlanningReplay replay = new PackPlanningReplay();
        replay.enabledEnigmaticItems = readEnigmaticOptions(root);
        replay.readVanilla(root);
        replay.readForge(root);
        replay.readJars(root.resolve("mods"));
        Process node = new ProcessBuilder("node", args[1], root.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        JsonObject scripts;
        try (var reader = new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8)) {
            scripts = JsonParser.parseReader(reader).getAsJsonObject();
        }
        if (node.waitFor() != 0) throw new IOException("KubeJS 配方提取失败");
        replay.applyScripts(scripts);
        replay.applyCraftTweaker(root.resolve("scripts/wujin.zs"));
        replay.solve(root, scripts, args.length > 2 && Boolean.parseBoolean(args[2]));
    }

    private static Set<String> readEnigmaticOptions(Path root) throws IOException {
        Set<String> options = readOptions(root.resolve("config/enigmaticlegacy-common.omniconf"));
        Set<String> enabled = ENIGMATIC_ITEM_OPTIONS.entrySet().stream()
                .filter(entry -> options.contains("B:" + entry.getValue() + "=true"))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(HashSet::new));
        if (readOptions(root.resolve("config/enigmaticdelicacy-common.omniconf")).contains("B:EnigmaticSeedEnabled=true")) {
            enabled.add(SEED);
        }
        return Set.copyOf(enabled);
    }

    private static Set<String> readOptions(Path config) throws IOException {
        if (!Files.isRegularFile(config)) return Set.of();
        return Files.readAllLines(config).stream().map(String::strip).collect(Collectors.toSet());
    }

    private void readVanilla(Path root) throws IOException {
        String folder = root.getFileName().toString();
        Path vanilla = root.resolve(folder + ".jar");
        if (Files.isRegularFile(vanilla)) readJar(vanilla);
    }

    private void readForge(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        Path launcher = absolute.getParent() == null ? null : absolute.getParent().getParent();
        if (launcher == null) return;
        Path profile = absolute.resolve(absolute.getFileName() + ".json");
        if (!Files.isRegularFile(profile)) return;
        JsonObject manifest = JsonParser.parseString(Files.readString(profile)).getAsJsonObject();
        if (!(manifest.get("arguments") instanceof JsonObject args)
                || !(args.get("game") instanceof JsonArray gameArgs)) return;
        String minecraft = launchArgument(gameArgs, "--fml.mcVersion");
        String forge = launchArgument(gameArgs, "--fml.forgeVersion");
        if (!minecraft.matches("[0-9]+(?:\\.[0-9]+)+") || !forge.matches("[0-9]+(?:\\.[0-9]+)+")) return;
        // Forge 的标准物品标签在启动器的 libraries 目录中。
        String version = minecraft + "-" + forge;
        Path universal = launcher.resolve("libraries/net/minecraftforge/forge").resolve(version)
                .resolve("forge-" + version + "-universal.jar");
        boolean present = Files.isRegularFile(universal);
        System.out.printf("forge_recipe_data version=%s present=%s%n", version, present);
        if (present) readJar(universal);
    }

    private static String launchArgument(JsonArray args, String key) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (key.equals(text(args.get(i)))) return text(args.get(i + 1));
        }
        return "";
    }

    private void readJars(Path mods) throws IOException {
        try (var files = Files.list(mods)) {
            for (Path jar : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                readJar(jar);
            }
        }
    }

    private void readJar(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
                    for (var entry : zip.stream().filter(e -> e.getName().startsWith("data/")
                            && e.getName().endsWith(".json")).toList()) {
                        String[] parts = entry.getName().split("/", 4);
                        if (parts.length < 4 || !parts[2].equals("recipes")
                                && !parts[3].startsWith("items/")) continue;
                        try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                            if (parts[2].equals("recipes")) {
                                raw.put(parts[1] + ':' + parts[3].replaceFirst("\\.json$", ""),
                                        JsonParser.parseReader(reader).getAsJsonObject());
                            } else if (parts[2].equals("tags")) {
                                JsonObject tag = JsonParser.parseReader(reader).getAsJsonObject();
                                String id = parts[1] + ':' + parts[3].substring(6).replaceFirst("\\.json$", "");
                                var values = tags.computeIfAbsent(id, ignored -> new ArrayList<>());
                                if (tag.has("replace") && tag.get("replace").getAsBoolean()) values.clear();
                                if (tag.get("values") instanceof JsonArray array) array.forEach(values::add);
                            }
                        } catch (IllegalStateException | JsonParseException ignored) {
                            // 某些包的内部数据不是标准配方或标签 JSON。
                        }
                    }
        }
    }

    private void applyScripts(JsonObject scripts) {
        for (JsonElement element : scripts.getAsJsonArray("removals")) {
            JsonObject rule = element.getAsJsonObject();
            if (rule.size() == 1 && rule.has("id")) raw.remove(text(rule.get("id")));
            if (rule.size() == 1 && rule.has("output")) {
                String removed = text(rule.get("output"));
                raw.values().removeIf(recipe -> removed.equals(item(output(recipe))));
            }
        }
        int ordinal = 0;
        for (JsonElement element : scripts.getAsJsonArray("recipes")) {
            JsonObject row = element.getAsJsonObject();
            if (!(row.get("recipe") instanceof JsonObject recipe)) continue;
            String id = text(row.get("id"));
            String output = item(output(recipe));
            String type = text(recipe.get("type"));
            if (TARGET.equals(output) && type.equals("avaritia:shaped_table")) id = TARGET_RECIPE;
            if (CUBE.equals(output) && type.equals("wizards_reborn:arcane_iterator")) id = CUBE_RECIPE;
            if (id.isEmpty()) id = "pack_replay:kubejs_" + ordinal++;
            raw.put(id, recipe);
            if (row.has("unsupported")) unsupportedScriptRecipes.add(id);
        }
    }

    private void applyCraftTweaker(Path script) throws IOException {
        if (!Files.isRegularFile(script)) return;
        var match = Pattern.compile("addShapeless\\(\"infinity_catalyst\"[^\\[]*\\[([^]]+)]")
                .matcher(Files.readString(script));
        if (!match.find()) return;
        var items = Pattern.compile("<item:\\s*(" + ITEM_ID + ")>").matcher(match.group(1));
        var ingredients = new JsonArray();
        while (items.find()) ingredients.add(items.group(1));
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "avaritia:shapeless_table");
        recipe.addProperty("result", INFINITY_CATALYST);
        recipe.add("ingredients", ingredients);
        raw.values().removeIf(value -> INFINITY_CATALYST.equals(item(output(value)))
                && text(value.get("type")).endsWith("shapeless_table"));
        raw.put(CATALYST_RECIPE, recipe);
    }

    private void solve(Path root, JsonObject scripts, boolean typed) throws IOException {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        Map<String, Set<String>> unprojectedOutputs = new HashMap<>();
        int skipped = 0;
        for (var entry : raw.entrySet()) {
            RecipeNode node = project(entry.getKey(), entry.getValue());
            if (node == null) {
                skipped++;
                String output = entry.getKey().equals("avaritia:eternal_singularity")
                        ? entry.getKey() : item(output(entry.getValue()));
                if (output.matches(ITEM_ID)) unprojectedOutputs.computeIfAbsent(output, ignored -> new HashSet<>())
                        .add(text(entry.getValue().get("type")));
            }
            else recipes.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
        }
        boolean cauldronBrewing = savedCauldronBrewing(root);
        if (cauldronBrewing) addStrengthBrews(recipes);
        System.out.println("saved_allow_cauldron_brewing=" + cauldronBrewing);
        var targetId = ResourceLocation.parse(TARGET_RECIPE);
        var graph = ImmutableRecipeGraphProjector.restrictToDependencies(new ImmutableRecipeGraph(recipes), targetId);
        RecipeNode target = graph.recipesById().get(targetId);
        if (target == null || target.inputs().size() != 81) throw new IllegalStateException("未读取真实 81 槽终端配方");
        Map<MaterialRef, Long> stock = savedStock(root);
        System.out.printf("target=%s slots=%d distinctInputs=%d catalog=%d skipped=%d scriptFailures=%d dependencies=%d types=%d diskStockKeys=%d%n",
                TARGET_RECIPE, target.inputs().size(), target.inputs().stream().distinct().count(),
                raw.size(), skipped, scripts.getAsJsonArray("failures").size(), graph.recipesById().size(),
                graph.recipesById().values().stream().map(RecipeNode::recipeTypeId).distinct().count(), stock.size());
        scripts.getAsJsonArray("failures").forEach(failure -> System.out.println("script_failure=" + failure));
        Map<ResourceLocation, ResourceLocation> forced = Map.of(
                ResourceLocation.parse(CUBE), ResourceLocation.parse(CUBE_RECIPE),
                ResourceLocation.parse(INFINITY_CATALYST), ResourceLocation.parse(CATALYST_RECIPE));
        if (forced.values().stream().anyMatch(id -> !graph.recipesById().containsKey(id))) {
            throw new IllegalStateException("指定的整合包路线未完整投影：" + forced);
        }
        var incomplete = unprojectedOutputs.keySet().stream().map(ResourceLocation::parse)
                .collect(Collectors.toSet());
        var forcedGraph = ForcedRecipeGraphProjector.apply(graph, forced);
        var coverage = PurePlanningCoverage.unprojectedDependency(
                forcedGraph == null ? graph : forcedGraph, targetId, incomplete, forced,
                PlanningPreferences.empty(), Long.MAX_VALUE);
        var uncovered = coverage.uncovered() == null ? "-" : coverage.uncovered().itemId().toString();
        System.out.printf("offline_projection coverage=%s uncovered=%s omittedTypes=%s%n",
                coverage.status(), uncovered, unprojectedOutputs.getOrDefault(uncovered, Set.of()));
        solveCase("saved_disks", graph, target, stock, forced, unprojectedOutputs);
        Map<MaterialRef, Long> frontier = new HashMap<>();
        graph.recipesById().values().forEach(node -> node.inputs().forEach(input ->
                input.alternatives().forEach(item -> {
                    if (!graph.recipesByOutput().containsKey(item)) frontier.put(item, 1_000_000_000_000L);
                })));
        // 可捕捞、采集或击杀取得的原料即使有逆向配方，也属于这一情景的外部供料。
        for (String id : List.of("minecraft:cod", "minecraft:salmon", "minecraft:apple",
                "minecraft:beef", "minecraft:porkchop", "minecraft:rabbit", "minecraft:mutton",
                "minecraft:chicken", "minecraft:carrot", "minecraft:potato", "minecraft:wheat")) {
            frontier.put(material(id), 1_000_000_000_000L);
        }
        long unprojectedFrontier = frontier.keySet().stream()
                .filter(item -> unprojectedOutputs.containsKey(item.itemId().toString())).count();
        System.out.printf("hypothetical_frontier_keys=%d unprojected_frontier_keys=%d%n",
                frontier.size(), unprojectedFrontier);
        solveCase("synthetic_frontier", graph, target, frontier, forced, unprojectedOutputs);
        Map<MaterialRef, Long> gathered = new HashMap<>(frontier);
        // 压缩/解压存在闭环，是否有直接生产配方不能判定物品能否自然取得。
        for (String tag : List.of("forge:ores", "forge:raw_materials", "minecraft:logs")) {
            alternatives(new JsonPrimitive("#" + tag), Set.of())
                    .forEach(item -> gathered.putIfAbsent(item, 1_000_000_000_000L));
        }
        // 模拟采矿、击杀和收获。玉和神秘果分别来自玉矿和成熟神秘灌木的掉落表。
        // 金锭也可由整合包的火焰聚焦采矿事件掉落。
        List<String> directDrops = List.of("minecraft:gold_ingot", "minecraft:gravel",
                "minecraft:ghast_tear", "minecraft:blaze_rod", "minecraft:feather",
                "goety:jade", "enigmaticdelicacy:enigmatic_fruit");
        for (String id : directDrops) {
            gathered.putIfAbsent(material(id), 1_000_000_000_000L);
        }
        System.out.println("assumed_direct_drops=" + directDrops);
        System.out.println("gathered_resource_keys=" + (gathered.size() - frontier.size()));
        solveCase("synthetic_gathered", graph, target, gathered, forced, unprojectedOutputs);
        Map<MaterialRef, List<RecipeNode>> abilityRecipes = new LinkedHashMap<>(recipes);
        for (var entry : raw.entrySet()) {
            if (!VOID_FAVOR.equals(text(entry.getValue().get("type")))) continue;
            RecipeNode node = project(entry.getKey(), entry.getValue(), true);
            if (node != null) {
                var candidates = new ArrayList<>(abilityRecipes.getOrDefault(node.output(), List.of()));
                candidates.add(node);
                abilityRecipes.put(node.output(), candidates);
            }
        }
        var abilityGraph = ImmutableRecipeGraphProjector.restrictToDependencies(
                new ImmutableRecipeGraph(abilityRecipes), targetId);
        inspectMissingMaterialRoutes(graph, abilityGraph, target, stock, forced);
        if (cauldronBrewing) verifyFireShardSupply(abilityGraph);
        // 使用同一组物资，只将现在可以规划的虚空交易产物从假设供料中移除。
        Map<MaterialRef, Long> abilityStock = new HashMap<>(gathered);
        int virtualRecipes = 0;
        for (RecipeNode node : abilityGraph.recipesById().values()) {
            if (VOID_FAVOR.equals(node.recipeTypeId().toString())) {
                abilityStock.remove(node.output());
                virtualRecipes++;
            }
        }
        System.out.println("hypothetical_void_favor_recipes=" + virtualRecipes);
        solveCase("synthetic_gathered_with_void_favor", abilityGraph, target, abilityStock, forced, unprojectedOutputs);
        // Malum 的末影人和烈焰人灵魂数据允许这三种灵魂直接采集。
        Map<MaterialRef, Long> spiritStock = new HashMap<>(abilityStock);
        List<String> spiritDrops = List.of("malum:eldritch_spirit", "malum:arcane_spirit", "malum:infernal_spirit");
        for (String id : spiritDrops) {
            spiritStock.putIfAbsent(material(id), 1_000_000_000_000L);
        }
        System.out.println("assumed_spirit_drops=" + spiritDrops);
        solveCase("synthetic_gathered_with_spirits", abilityGraph, target, spiritStock, forced, unprojectedOutputs);
        Map<MaterialRef, Long> expandedGathered = new HashMap<>(spiritStock);
        // 成熟马铃薯、凋灵骷髅和下界矿脉都有外部获取来源，即使整合包同时注册了生产路线。
        List<String> additionalDrops = List.of(
                "minecraft:poisonous_potato", "minecraft:wither_skeleton_skull", "minecraft:ancient_debris");
        for (String id : additionalDrops) {
            expandedGathered.putIfAbsent(material(id), 1_000_000_000_000L);
        }
        System.out.println("assumed_additional_drops=" + additionalDrops);
        solveCase("synthetic_expanded_gathered", abilityGraph, target, expandedGathered, forced, unprojectedOutputs);
        Map<MaterialRef, Long> farmStock = new HashMap<>(expandedGathered);
        // 农夫乐事作物的方块掉落表、原版采集和酿造材料；余烬精华来自火焰首领或要塞守卫。
        // 灵魂取自 Malum 的鸡、牛和蜘蛛数据，假设已使用能收魂的装备。
        List<String> harvest = List.of("minecraft:flint", "minecraft:pumpkin", "minecraft:nether_wart",
                "minecraft:glowstone_dust", "farmersdelight:cabbage", "farmersdelight:onion",
                "farmersdelight:rice", "farmersdelight:tomato", "irons_spellbooks:cinder_essence",
                "malum:aerial_spirit", "malum:sacred_spirit", "malum:earthen_spirit", "malum:wicked_spirit");
        harvest.forEach(id -> farmStock.putIfAbsent(material(id), 1_000_000_000_000L));
        System.out.println("assumed_harvest_and_spirits=" + harvest);
        solveCase("synthetic_farm_and_spirits", abilityGraph, target, farmStock, forced, unprojectedOutputs);
        verifySupplyChanges(abilityGraph, target, farmStock, forced, unprojectedOutputs);
        if (typed) {
            // 固定物品替身和库存，只切换虚空交易权限假设。
            PackTypedPlanningReplay.run("saved_disks", graph, abilityGraph, target, stock, forced);
            PackTypedPlanningReplay.run("saved_disks_void_favor_enabled", abilityGraph, target, stock, forced);
            // 掉落表允许直接采集，按磁盘库存回放的缺口作有限供料对照。
            Map<MaterialRef, Long> restocked = new HashMap<>(stock);
            restocked.merge(material("alexscaves:pewen_branch"), 18L, Long::sum);
            PackTypedPlanningReplay.run("saved_disks_pewen_restock", abilityGraph, target, restocked, forced);
            PackTypedPlanningReplay.run("synthetic_gathered_with_spirits", abilityGraph, target, spiritStock, forced);
            PackTypedPlanningReplay.run("synthetic_expanded_gathered", abilityGraph, target, expandedGathered, forced);
            PackTypedPlanningReplay.run("synthetic_farm_and_spirits", abilityGraph, target, farmStock, forced);
        }
        solveBranches("isolated_frontier", graph, target, frontier, forced);
        solveBranches("isolated_gathered_with_void_favor", abilityGraph, target, abilityStock, forced);
        solveBranches("isolated_gathered_with_spirits", abilityGraph, target, spiritStock, forced);
        solveBranches("isolated_expanded_gathered", abilityGraph, target, expandedGathered, forced);
        solveBranches("isolated_farm_and_spirits", abilityGraph, target, farmStock, forced);
        for (String id : List.of("thaumcraft:fire_crystal", "thaumcraft:fire_shard")) {
            solveBranch("isolated_farm_and_spirits", abilityGraph, target, farmStock, forced,
                    new IngredientRef(List.of(material(id)), 1L, NbtMatchMode.ANY));
        }
        solveBranch("isolated_farm_and_spirits", abilityGraph, target, farmStock, forced,
                new IngredientRef(List.of(material("minecraft:blaze_powder")), 1L, NbtMatchMode.ANY));
        for (String id : List.of("thaumcraft:air_crystal", "thaumcraft:fire_crystal", "thaumcraft:fire_shard",
                "makit_better:poplar_wood", "minecraft:netherite_scrap")) {
            solveBranch("isolated_reported_dependency", abilityGraph, target, spiritStock, forced,
                    new IngredientRef(List.of(material(id)), 1L, NbtMatchMode.ANY));
        }
    }

    private static void inspectMissingMaterialRoutes(ImmutableRecipeGraph graph, ImmutableRecipeGraph abilityGraph,
                                                     RecipeNode target, Map<MaterialRef, Long> stock,
                                                     Map<ResourceLocation, ResourceLocation> forced) {
        var design = new IngredientRef(List.of(material("malum:anomalous_design")), 13L, NbtMatchMode.ANY);
        solveBranch("isolated_saved_design_without_ability", graph, target, stock, forced, design);
        solveBranch("isolated_saved_design_with_ability", abilityGraph, target, stock, forced, design);
        // 用有限原料验证配方声明，虚空交易权限和机器状态仍由回放假设提供。
        solveBranch("isolated_iron_to_design", abilityGraph, target,
                Map.of(material("minecraft:iron_block"), 13L), forced, design);
        var focus = new IngredientRef(List.of(material("goety:empty_focus")), 8L, NbtMatchMode.ANY);
        solveBranch("isolated_saved_focus", graph, target, stock, forced, focus);
        solveBranch("isolated_amethyst_to_focus", graph, target,
                Map.of(material("minecraft:amethyst_shard"), 8L), forced, focus);
    }

    private static void verifySupplyChanges(ImmutableRecipeGraph graph, RecipeNode target,
                                            Map<MaterialRef, Long> stock,
                                            Map<ResourceLocation, ResourceLocation> forced,
                                            Map<String, Set<String>> unprojectedOutputs) {
        var roots = PureDemandNormalizer.mergeEquivalent(target.inputs()).stream()
                .collect(Collectors.toMap(input -> input.alternatives().get(0).itemId().toString(), input -> input));
        var voidMetal = roots.get("thaumcraft:void_metal_ingot");
        var plan = solveBranch("void_metal_bill_baseline", graph, target, stock, forced, voidMetal);
        if (plan.feasible()) {
            // 按本次实际使用量构造有限库存，再独立求解一次。
            Map<MaterialRef, Long> bill = new HashMap<>();
            stock.forEach((material, count) -> {
                long used = count - plan.remaining().getOrDefault(material, 0L);
                if (used > 0) bill.put(material, used);
            });
            var verified = solveBranch("void_metal_finite_bill", graph, target, bill, forced, voidMetal);
            System.out.printf("void_metal_finite_bill verified=%s inputs=%s types=%s%n", verified.feasible(),
                    bill.entrySet().stream()
                            .map(entry -> entry.getKey().itemId() + "*" + entry.getValue()
                                    + (entry.getKey().nbt().isEmpty() ? "" : entry.getKey().nbt()))
                            .sorted().toList(),
                    verified.steps().stream().map(step -> graph.recipesById().get(step.recipeId()).recipeTypeId())
                            .distinct().sorted().toList());
        }
        // 甜菜可通过种植取得。灵灾把水生之魂列为鱿鱼、溺尸等实体的掉落。
        Map<MaterialRef, Long> supplied = new HashMap<>(stock);
        supplied.put(material("minecraft:beetroot"), 100_000L);
        supplied.put(material("malum:aqueous_spirit"), 100_000L);
        solveCase("synthetic_with_beetroot_and_aqueous_spirit", graph, target, supplied, forced, unprojectedOutputs);
        solveBranch("with_beetroot_and_aqueous_spirit", graph, target, supplied, forced,
                roots.get("avaritia:infinity_ingot"));
        solveBranch("with_beetroot_and_aqueous_spirit", graph, target, supplied, forced,
                roots.get("enigmaticaddons:etherium_core"));

        // 只用来辨别回溯瓶颈。生产烈焰粉的路线已由火碎片回归覆盖。
        supplied.put(material("minecraft:blaze_powder"), 100_000L);
        solveCase("synthetic_with_direct_blaze_powder", graph, target, supplied, forced, unprojectedOutputs);
    }

    private static void solveBranches(String label, ImmutableRecipeGraph graph, RecipeNode target,
                                      Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced) {
        for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(target.inputs())) {
            solveBranch(label, graph, target, stock, forced, input);
        }
    }

    private static PureRecipePlanner.Result solveBranch(String label, ImmutableRecipeGraph graph, RecipeNode target,
                                                        Map<MaterialRef, Long> stock,
                                                        Map<ResourceLocation, ResourceLocation> forced,
                                                        IngredientRef input) {
        long start = System.nanoTime();
        var branch = PureRecipePlanner.resolve(graph, stock, List.of(input), 4096, 65_536, 8192,
                start + 1_000_000_000L, System::nanoTime, Set.of(target.output()), forced,
                PlanningPreferences.empty(), false);
        IngredientRef missing = branch.missing().isEmpty() ? null : branch.missing().get(0);
        String firstMissing = missing == null ? "-" : missing.alternatives().get(0).itemId().toString();
        long initial = missing == null ? 0 : stock.entrySet().stream()
                .filter(entry -> ImmutableRecipeGraphProjector.matchesIngredient(entry.getKey(), missing))
                .mapToLong(Map.Entry::getValue).sum();
        System.out.printf("%s demand=%s*%d status=%s states=%d backtracks=%d firstMissing=%s missingCount=%d initial=%d%n",
                label, input.alternatives().get(0).itemId(), input.count(), branch.status(),
                branch.expandedStates(), branch.backtracks(), firstMissing, missing == null ? 0 : missing.count(), initial);
        return branch;
    }

    private static void solveCase(String label, ImmutableRecipeGraph graph, RecipeNode target,
                                  Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced,
                                  Map<String, Set<String>> unprojectedOutputs) {
        long start = System.nanoTime();
        var result = AsyncPurePlanningService.resolveInScope(graph, stock, target, 1,
                4096, 65_536, 8192, start + 10_000_000_000L, forced, PlanningPreferences.empty());
        var missing = result.missing().stream().limit(12).map(input -> {
            String item = input.alternatives().get(0).itemId().toString();
            long initial = stock.entrySet().stream()
                    .filter(entry -> ImmutableRecipeGraphProjector.matchesIngredient(entry.getKey(), input))
                    .mapToLong(Map.Entry::getValue).sum();
            var omitted = input.alternatives().stream().map(m -> m.itemId().toString())
                    .flatMap(id -> unprojectedOutputs.getOrDefault(id, Set.of()).stream())
                    .filter(type -> !type.isBlank()).distinct().sorted().toList();
            return item + "*" + input.count() + (input.alternatives().size() > 1
                    ? "[" + input.alternatives().size() + " alternatives]" : "")
                    + " initial=" + initial + (omitted.isEmpty() ? "" : " unprojected=" + omitted);
        }).toList();
        long operations = result.steps().stream().mapToLong(PureRecipePlanner.PlannedStep::batches).sum();
        long uniqueRecipes = result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).distinct().count();
        System.out.printf("%s status=%s steps=%d uniqueRecipes=%d operations=%d missing=%d states=%d backtracks=%d ms=%d examples=%s%n",
                label, result.status(), result.steps().size(), uniqueRecipes, operations, result.missing().size(),
                result.expandedStates(), result.backtracks(), (System.nanoTime() - start) / 1_000_000L, missing);
    }

    private RecipeNode project(String id, JsonObject recipe) {
        return project(id, recipe, false);
    }

    private RecipeNode project(String id, JsonObject recipe, boolean assumeVoidFavor) {
        String type = text(recipe.get("type"));
        String output = item(output(recipe));
        if (!output.matches(ITEM_ID) || !id.matches(ITEM_ID) || !type.matches(ITEM_ID)
                || unsupportedScriptRecipes.contains(id)
                || output(recipe) instanceof JsonObject stack && stack.has("unsupported")
                || !hasSupportedConditions(recipe)) return null;
        if (CAULDRON.equals(type)) return cauldronItemBrew(id, recipe);
        boolean reusableFocusing = SPIRIT_FOCUSING.equals(type)
                && recipe.get("durabilityCost") instanceof JsonPrimitive cost
                && cost.isNumber() && cost.getAsDouble() == 0;
        if (SPIRIT_FOCUSING.equals(type) && !reusableFocusing) return null;
        List<JsonElement> slots = new ArrayList<>();
        if (recipe.get("pattern") instanceof JsonArray pattern && recipe.get("key") instanceof JsonObject keys) {
            for (JsonElement line : pattern) {
                for (char key : text(line).toCharArray()) {
                    if (key != ' ' && (key != '_' || keys.has("_"))) slots.add(keys.get(String.valueOf(key)));
                }
            }
        } else if (recipe.get("ingredients") instanceof JsonArray inputs) {
            inputs.forEach(slots::add);
        } else if (recipe.get("ingredient") != null) {
            slots.add(recipe.get("ingredient"));
        } else if ((type.equals("malum:spirit_infusion") || reusableFocusing)
                && recipe.get("input") instanceof JsonObject input
                && recipe.get("spirits") instanceof JsonArray spirits) {
            slots.add(input);
            if (recipe.get("extra_items") instanceof JsonArray extras) extras.forEach(slots::add);
            for (JsonElement spirit : spirits) {
                if (!(spirit instanceof JsonObject spec)) return null;
                String spiritType = text(spec.get("type"));
                if (!spiritType.matches("[a-z0-9_]+")) return null;
                JsonObject item = new JsonObject();
                item.addProperty("item", "malum:" + spiritType + "_spirit");
                item.addProperty("count", count(spec));
                slots.add(item);
            }
        } else if (type.equals("ars_nouveau:enchanting_apparatus")
                && output.equals("enigmaticaddons:etherium_core")
                && recipe.get("reagent") instanceof JsonArray reagent
                && recipe.get("pedestalItems") instanceof JsonArray pedestals) {
            // 回放普通无标签原料。继承中心物品 NBT 的动态变体由游戏内处理器验证。
            slots.add(reagent);
            pedestals.forEach(slots::add);
        } else if (recipe.get("input") != null
                && (Set.of("botania:mana_infusion", "ars_nouveau:imbuement").contains(type)
                    || assumeVoidFavor && type.equals(VOID_FAVOR))) {
            slots.add(recipe.get("input"));
        } else return null;
        if (recipe.has("activation_item")) slots.add(recipe.get("activation_item"));
        var inputs = new ArrayList<IngredientRef>();
        for (JsonElement slot : slots) {
            var alternatives = alternatives(slot, Set.of());
            if (type.equals(VOID_FAVOR)) {
                MaterialRef result = material(output, output(recipe));
                alternatives = alternatives.stream().filter(item -> !item.equals(result)).toList();
            }
            if (alternatives.isEmpty()) return null;
            var mode = alternatives.stream().anyMatch(item -> !item.nbt().isEmpty())
                    ? NbtMatchMode.EXACT : NbtMatchMode.ANY;
            DemandRole role = reusableFocusing && slot == recipe.get("input")
                    ? DemandRole.CATALYST : DemandRole.CONSUMED;
            inputs.add(new IngredientRef(alternatives, count(slot), mode, role));
        }
        if (type.equals("ars_nouveau:imbuement") && recipe.get("pedestalItems") instanceof JsonArray pedestals) {
            for (JsonElement pedestal : pedestals) {
                var alternatives = alternatives(pedestal, Set.of());
                if (alternatives.isEmpty()) return null;
                var mode = alternatives.stream().anyMatch(item -> !item.nbt().isEmpty())
                        ? NbtMatchMode.EXACT : NbtMatchMode.ANY;
                inputs.add(new IngredientRef(alternatives, count(pedestal), mode, DemandRole.CATALYST));
            }
        }
        if (inputs.isEmpty()) return null;
        long outputCount = type.equals("ars_nouveau:imbuement") && output(recipe).isJsonPrimitive()
                ? count(recipe) : count(output(recipe));
        return new RecipeNode(ResourceLocation.parse(id), material(output, output(recipe)), outputCount,
                inputs, type.substring(0, type.indexOf(':')), ResourceLocation.parse(type));
    }

    private boolean hasSupportedConditions(JsonObject recipe) {
        if (recipe.has("forge:conditions")) return false;
        if (!recipe.has("conditions")) return true;
        if (!(recipe.get("conditions") instanceof JsonArray conditions)) return false;
        for (JsonElement condition : conditions) {
            if (!(condition instanceof JsonObject object)) return false;
            String type = text(object.get("type"));
            String item = text(object.get("item"));
            boolean supported = type.equals("enigmaticlegacy:is_enabled") && ENIGMATIC_ITEM_OPTIONS.containsKey(item)
                    || type.equals("enigmaticaddons:is_enabled") && item.equals(SEED);
            if (!supported || !enabledEnigmaticItems.contains(item)) return false;
        }
        return true;
    }

    private List<MaterialRef> alternatives(JsonElement element, Set<String> visited) {
        if (element instanceof JsonArray choices) {
            var values = new ArrayList<MaterialRef>();
            for (JsonElement choice : choices) {
                var parsed = alternatives(choice, visited);
                if (parsed.isEmpty()) return List.of();
                values.addAll(parsed);
            }
            return values.stream().distinct().toList();
        }
        if (element instanceof JsonObject object && (object.has("unsupported")
                || object.has("type") && !text(object.get("type")).equals("forge:nbt"))) return List.of();
        String item = item(element);
        if (item.matches(ITEM_ID)) return List.of(material(item, element));
        String tag = element instanceof JsonObject object ? text(object.get("tag"))
                : item.startsWith("#") ? item.substring(1) : "";
        if (tag.isEmpty() || visited.contains(tag)) return List.of();
        var expanded = new HashSet<>(visited);
        expanded.add(tag);
        return tags.getOrDefault(tag, List.of()).stream()
                .flatMap(value -> alternatives(value instanceof JsonObject object ? object.get("id") : value,
                        expanded).stream()).distinct().toList();
    }

    private static Map<MaterialRef, Long> savedStock(Path root) throws IOException {
        Map<MaterialRef, Long> stock = new HashMap<>();
        try (var paths = Files.walk(root.resolve("saves"), 3)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().equals("refinedstorage_disks.dat")).toList()) {
                var data = NbtIo.readCompressed(path.toFile()).getCompound("data");
                var disks = data.getList("Disks", Tag.TAG_COMPOUND);
                for (int i = 0; i < disks.size(); i++) {
                    var disk = disks.getCompound(i);
                    if (!disk.getString("Type").equals("refinedstorage:item")) continue;
                    var stacks = disk.getCompound("Data").getList("Items", Tag.TAG_COMPOUND);
                    for (int j = 0; j < stacks.size(); j++) {
                        var stack = stacks.getCompound(j);
                        ResourceLocation id = ResourceLocation.tryParse(stack.getString("Id"));
                        long count = stack.getLong("Quantity");
                        if (id != null && count > 0L) stock.merge(new MaterialRef(id,
                                stack.contains("Tag", Tag.TAG_COMPOUND) ? stack.getCompound("Tag").toString() : ""),
                                count, Math::addExact);
                    }
                }
            }
        }
        return stock;
    }

    private static MaterialRef material(String value) {
        return new MaterialRef(ResourceLocation.parse(value), "");
    }

    private static MaterialRef material(String value, JsonElement stack) {
        String nbt = stack instanceof JsonObject object ? text(object.get("nbt")) : "";
        return nbt.isEmpty() ? material(value) : new MaterialRef(ResourceLocation.parse(value), nbt);
    }

    private static JsonElement output(JsonObject recipe) {
        if (CAULDRON.equals(text(recipe.get("type"))) && recipe.get("results") instanceof JsonArray fluids
                && fluids.isEmpty()) return recipe.get("byproduct");
        return recipe.has("result") ? recipe.get("result") : recipe.get("output");
    }

    private RecipeNode cauldronItemBrew(String id, JsonObject recipe) {
        if (!(recipe.get("base_fluid") instanceof JsonObject base) || !(recipe.get("byproduct") instanceof JsonObject item)
                || !(recipe.get("results") instanceof JsonArray fluids) || !fluids.isEmpty()) return null;
        String fluid = text(base.get("FluidName"));
        if (!fluid.matches(ITEM_ID) || !base.has("Amount") || base.get("Amount").getAsLong() <= 0) return null;
        CompoundTag tag = new CompoundTag();
        if (base.has("Tag")) {
            if (!(base.get("Tag") instanceof JsonObject data)) return null;
            for (var entry : data.entrySet()) {
                if (!(entry.getValue() instanceof JsonPrimitive value) || !value.isString()) return null;
                tag.putString(entry.getKey(), value.getAsString());
            }
        }
        var reagent = alternatives(recipe.get("input"), Set.of());
        if (reagent.isEmpty()) return null;
        var mode = reagent.stream().anyMatch(m -> !m.nbt().isEmpty()) ? NbtMatchMode.EXACT : NbtMatchMode.ANY;
        return new RecipeNode(ResourceLocation.parse(id), material(item(item), item), count(item), List.of(
                new IngredientRef(List.of(MaterialRef.fluid(ResourceLocation.parse(fluid), tag.isEmpty() ? "" : tag.toString())),
                        base.get("Amount").getAsLong(), NbtMatchMode.EXACT),
                new IngredientRef(reagent, 1, mode)), "irons_spellbooks", ResourceLocation.parse(CAULDRON));
    }

    private static boolean savedCauldronBrewing(Path root) throws IOException {
        // 回放读取各存档磁盘，因此只在所有相关存档实际开启酿造时启用这条条件路线。
        try (var paths = Files.walk(root.resolve("saves"), 3)) {
            var disks = paths.filter(p -> p.getFileName().toString().equals("refinedstorage_disks.dat")).toList();
            for (Path disk : disks) {
                var config = disk.getParent().getParent().resolve("serverconfig/irons_spellbooks-server.toml");
                if (!readOptions(config).stream().anyMatch(s -> s.matches("allowCauldronBrewing\\s*=\\s*true"))) return false;
            }
            return !disks.isEmpty();
        }
    }

    private static MaterialRef regularPotion(String potion) {
        if (potion.equals("water")) return MaterialRef.fluid(ResourceLocation.parse("minecraft:water"));
        CompoundTag tag = new CompoundTag();
        tag.putString("irons_spellbooks:bottle_type", "REGULAR");
        tag.putString("Potion", "minecraft:" + potion);
        return MaterialRef.fluid(ResourceLocation.parse("irons_spellbooks:potion"), tag.toString());
    }

    private static void addStrengthBrews(Map<MaterialRef, List<RecipeNode>> recipes) {
        // 对应原版三步力量药水，以及 IronSpellBooksRecipe.potionBrewRecipe 的整锅四瓶用量。
        long volume = 4L * InkFluidSupport.BOTTLE_AMOUNT;
        String previous = "water";
        for (var step : List.of(List.of("awkward", "nether_wart"), List.of("strength", "blaze_powder"),
                List.of("strong_strength", "glowstone_dust"))) {
            MaterialRef output = regularPotion(step.get(0));
            var recipe = new RecipeNode(ResourceLocation.parse("pack_replay:alchemist/" + step.get(0)), output,
                    volume, List.of(new IngredientRef(List.of(regularPotion(previous)), volume),
                    new IngredientRef(List.of(material("minecraft:" + step.get(1))), 1, NbtMatchMode.ANY)),
                    "irons_spellbooks", ResourceLocation.parse(CAULDRON));
            recipes.computeIfAbsent(output, ignored -> new ArrayList<>()).add(recipe);
            previous = step.get(0);
        }
    }

    private static void verifyFireShardSupply(ImmutableRecipeGraph graph) {
        // 从烈焰棒和一锅水开始，验证烈焰粉生产、酿造消耗和剩余药水。
        var stock = Map.of(regularPotion("water"), 1000L, material("minecraft:nether_wart"), 1L,
                material("minecraft:blaze_rod"), 1L, material("minecraft:glowstone_dust"), 1L,
                material("irons_spellbooks:cinder_essence"), 1L);
        var demand = new IngredientRef(List.of(material("thaumcraft:fire_shard")), 8);
        var plan = PureRecipePlanner.resolve(graph, stock, List.of(demand), 64, 65_536, 8192);
        if (!plan.feasible() || !plan.remaining().equals(Map.of(
                regularPotion("strong_strength"), 750L, material("minecraft:blaze_powder"), 1L))) {
            throw new IllegalStateException("火碎片材料流与整包配方不符：" + plan.status() + " " + plan.remaining());
        }
        System.out.printf("verified_fire_shard_from_raw steps=%s remaining_strong_strength_mb=750%n", plan.steps());
    }

    private static String item(JsonElement element) {
        return element instanceof JsonObject object ? text(object.get("item")) : text(element);
    }

    private static String text(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static long count(JsonElement element) {
        return element instanceof JsonObject object && object.get("count") != null
                && object.get("count").isJsonPrimitive() ? Math.max(1L, object.get("count").getAsLong()) : 1L;
    }
}
