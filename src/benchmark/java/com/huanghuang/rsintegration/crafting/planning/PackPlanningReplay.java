package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
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
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

/** 用安装包中的固定配方和磁盘库存运行值规划器，不启动游戏。 */
public final class PackPlanningReplay {
    private static final String TARGET = "enigmaticlegacy:enigmatic_item";
    private static final String TARGET_RECIPE = "avaritia:kjs/enigmaticlegacy_enigmatic_item";
    private static final String CUBE = "enigmaticlegacy:the_cube";
    private static final String CUBE_RECIPE = "wizards_reborn:kjs/4pci6vhzba5ac8ojrt43rpwir";
    private static final String INFINITY_CATALYST = "avaritia:infinity_catalyst";
    private static final String CATALYST_RECIPE = "crafttweaker:infinity_catalyst";
    private static final String VOID_FAVOR = "malum:favor_of_the_void";
    private static final String ITEM_ID = "[a-z0-9_.-]+:[a-z0-9_./-]+";

    private final Map<String, JsonObject> raw = new LinkedHashMap<>();
    private final Map<String, List<JsonElement>> tags = new HashMap<>();
    private final Set<String> unsupportedScriptRecipes = new HashSet<>();

    public static void main(String[] args) throws Exception {
        if (args[0].isBlank()) throw new IllegalArgumentException("需要 -PpackPlanningPath");
        Path root = Path.of(args[0]);
        PackPlanningReplay replay = new PackPlanningReplay();
        replay.readVanilla(root);
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
        replay.solve(root, scripts);
    }

    private void readVanilla(Path root) throws IOException {
        String folder = root.getFileName().toString();
        Path vanilla = root.resolve(folder + ".jar");
        if (Files.isRegularFile(vanilla)) readJar(vanilla);
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

    private void solve(Path root, JsonObject scripts) throws IOException {
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
        for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(target.inputs())) {
            long start = System.nanoTime();
            var branch = PureRecipePlanner.resolve(graph, frontier, List.of(input), 4096, 65_536, 8192,
                    start + 1_000_000_000L, System::nanoTime, Set.of(target.output()), forced,
                    PlanningPreferences.empty(), false);
            String firstMissing = branch.missing().isEmpty() ? "-"
                    : branch.missing().get(0).alternatives().get(0).itemId().toString();
            System.out.printf("isolated_frontier demand=%s*%d status=%s states=%d firstMissing=%s%n",
                    input.alternatives().get(0).itemId(), input.count(),
                    branch.status(), branch.expandedStates(), firstMissing);
        }
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
                || recipe.has("conditions") || recipe.has("forge:conditions")) return null;
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
        } else if (type.equals("malum:spirit_infusion")
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
            inputs.add(new IngredientRef(alternatives, count(slot), mode));
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
        return recipe.has("result") ? recipe.get("result") : recipe.get("output");
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
