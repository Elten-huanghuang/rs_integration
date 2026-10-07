package com.huanghuang.rsintegration.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

public final class RSIntegrationConfig {
    public static final int REPEAT_COUNT_DEFAULT = 1024;
    public static final int REPEAT_COUNT_ABSOLUTE_MAX = 1024;
    public static final int SERVER_CONFIG_SCHEMA = 7;
    public static final List<String> DEFAULT_ANVIL_MEMORY_ADAPTERS = List.of(
            "minecraft_anvil", "goety_dark_anvil", "irons_spellbooks_arcane_anvil");
    public static final List<String> DEFAULT_FREE_WATER_MACHINES = List.of(
            "farmersrespite_kettle", "youkaishomecoming_kettle", "youkaishomecoming_ferment",
            "youkaishomecoming_moka", "youkaishomecoming_steamer", "irons_spellbooks_alchemist_cauldron",
            "eidolon_crucible", "botania_petal_apothecary", "wizards_reborn_alchemy");
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> FREE_WATER_MACHINES;
    private static final List<String> LEGACY_DEFAULT_PASSIVE_TICK_ITEMS = List.of(
            "reliquary:pyromancer_staff|mutates",
            "enigmaticaddons:artificial_flower|mutates",
            "forbidden_arcanus:spectral_eye_amulet|mutates",
            "apotheosis:potion_charm|mutates",
            "muyimeng_charm:fused_potion_charm|mutates");
    public static final List<String> DEFAULT_PASSIVE_TICK_ITEMS = List.of(
            "reliquary:pyromancer_staff|mutates",
            "enigmaticaddons:artificial_flower|mutates",
            "forbidden_arcanus:spectral_eye_amulet|mutates",
            "apotheosis:potion_charm|mutates",
            "muyimeng_charm:fused_potion_charm|mutates",
            "composite_material:primitive_totem");
    public static final List<String> DEFAULT_PREFERRED_INGREDIENT_VARIANTS = List.of(
            // Stable color families.
            "minecraft:white_wool",
            "minecraft:white_carpet",
            "minecraft:white_bed",
            "minecraft:glass",
            "minecraft:glass_pane",
            "minecraft:white_stained_glass",
            "minecraft:white_stained_glass_pane",
            "minecraft:terracotta",
            "minecraft:white_terracotta",
            "minecraft:white_concrete",
            "minecraft:white_concrete_powder",
            "minecraft:candle",
            "minecraft:white_dye",
            // Canonical oak variants for broad wooden ingredients.
            "minecraft:oak_log",
            "minecraft:oak_wood",
            "minecraft:stripped_oak_log",
            "minecraft:stripped_oak_wood",
            "minecraft:oak_planks",
            "minecraft:oak_slab",
            "minecraft:oak_stairs",
            "minecraft:oak_fence",
            "minecraft:oak_fence_gate",
            "minecraft:oak_door",
            "minecraft:oak_trapdoor",
            "minecraft:oak_pressure_plate",
            "minecraft:oak_button",
            "minecraft:oak_sign",
            "minecraft:oak_hanging_sign",
            "minecraft:oak_boat",
            "minecraft:oak_chest_boat",
            "minecraft:oak_sapling",
            "minecraft:oak_leaves",
            "minecraft:chest",
            "minecraft:barrel",
            // Common base materials and canonical vanilla mineral forms.
            "minecraft:cobblestone",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:sand",
            "minecraft:charcoal",
            "minecraft:iron_ingot",
            "minecraft:copper_ingot",
            "minecraft:gold_ingot",
            "minecraft:netherite_ingot",
            "minecraft:iron_nugget",
            "minecraft:gold_nugget",
            "minecraft:raw_iron",
            "minecraft:raw_copper",
            "minecraft:raw_gold",
            "minecraft:diamond",
            "minecraft:emerald",
            "minecraft:lapis_lazuli",
            "minecraft:quartz",
            "minecraft:amethyst_shard",
            "minecraft:redstone",
            "minecraft:glowstone_dust",
            "minecraft:iron_block",
            "minecraft:copper_block",
            "minecraft:gold_block",
            "minecraft:diamond_block",
            "minecraft:emerald_block",
            "minecraft:lapis_block",
            "minecraft:redstone_block",
            "minecraft:coal_block",
            "minecraft:netherite_block");
    public static final int DEFAULT_CRAFTING_PLANNING_WORKERS = CraftingPlanningConfig.DEFAULT_WORKERS;
    public static final int DEFAULT_CRAFTING_PLANNING_QUEUE_CAPACITY =
            CraftingPlanningConfig.DEFAULT_QUEUE_CAPACITY;
    public static final int DEFAULT_CRAFTING_PURE_SEARCH_MAX_STATES =
            CraftingPlanningConfig.DEFAULT_SEARCH_STATES;
    public static final int DEFAULT_CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES =
            CraftingPlanningConfig.DEFAULT_MEMOIZED_FAILURES;
    public static final int DEFAULT_CRAFTING_PURE_DEMAND_MAX_NODES =
            CraftingPlanningConfig.DEFAULT_DEMAND_TREE_NODES;
    public static final int DEFAULT_CRAFTING_PURE_PLANNING_TIMEOUT_MS =
            CraftingPlanningConfig.DEFAULT_PURE_TIMEOUT_MS;
    public static final int DEFAULT_CRAFTING_TYPED_PREVIEW_TIMEOUT_MS =
            CraftingPlanningConfig.DEFAULT_TYPED_PREVIEW_TIMEOUT_MS;
    public static final int DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY = 32;
    public static final int DEFAULT_CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK = 1;
    public static final int DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_CRAFTING_RESOLVE_TIMEOUT_MS = 2_000;
    public static final int DEFAULT_CRAFTING_MAX_ENSURE_CALLS = 10_000;
    public static final int DEFAULT_CRAFTING_VANILLA_OPERATIONS_PER_TICK = 8;
    public static final int DEFAULT_CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK = 24;
    public static final int DEFAULT_CRAFTING_SERVER_TICK_BUDGET_MS = 8;
    public static final int DEFAULT_CRAFTING_OPERATIONS_PER_DISPATCH = 32;
    public static final int DEFAULT_CRAFTING_SETTLEMENT_STACKS_PER_TICK = 16;
    public static final int DEFAULT_CRAFTING_COMPLETION_CALLBACKS_PER_TICK = 1;
    public static final int DEFAULT_CRAFTING_PREVIEW_RATE_LIMIT_MS =
            CraftingPreviewPolicy.DEFAULT_RATE_LIMIT_MS;
    public static final int DEFAULT_CRAFTING_PLAN_CACHE_TTL_MS =
            CraftingPreviewPolicy.DEFAULT_CACHE_TTL_MS;
    public static final int DEFAULT_CRAFTING_PLAN_CACHE_MAX_ENTRIES =
            CraftingPreviewPolicy.DEFAULT_CACHE_MAX_ENTRIES;
    public static final int DEFAULT_GUI_OPEN_RATE_LIMIT_MS =
            GuiTimingConfig.DEFAULT_OPEN_RATE_LIMIT_MS;
    public static final int DEFAULT_SIDE_PANEL_NAVIGATION_TIMEOUT_MS =
            GuiTimingConfig.DEFAULT_NAVIGATION_TIMEOUT_MS;
    public static final int DEFAULT_CATALYST_RECIPE_PREFERENCE_BONUS = 1000;
    public static final int DEFAULT_GRID_SEARCH_IDLE_BUDGET_MICROS = 1_000;
    public static final int DEFAULT_GRID_SEARCH_ACTIVE_BUDGET_MICROS = 3_000;
    public static final int DEFAULT_GRID_SEARCH_DEBOUNCE_MS = 80;
    public static final int DEFAULT_GRID_SEARCH_PARTIAL_REFRESH_MS = 50;
    public static final int DEFAULT_GRID_SEARCH_QUERY_CACHE_ENTRIES = 16;
    public static final int DEFAULT_GRID_SEARCH_EMPTY_SNAPSHOT_GRACE_MS = 250;
    public static final int DEFAULT_GRID_SEARCH_CANDIDATE_INDEX_MAX_PERCENT = 60;
    public static final int DEFAULT_GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS = 100;
    public static final int DEFAULT_GRID_SEARCH_PINYIN_WORKERS = 2;
    public static final int DEFAULT_GRID_SEARCH_DISK_CACHE_ENTRIES = 20_000;
    public static final int DEFAULT_GRID_SEARCH_DISK_CACHE_MAX_MIB = 32;
    public static final int DEFAULT_GRID_SEARCH_DISK_CACHE_SAVE_DELAY_MS = 2_000;
    public static final int DEFAULT_RECENT_SEARCH_MAX_VISIBLE_ENTRIES = 8;
    public static final int DEFAULT_RECENT_SEARCH_MAX_STORED_ENTRIES = 100;

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;
    private static ModConfig clientModConfig;

    //  master switches
    public static ForgeConfigSpec.BooleanValue ENABLE_BINDING;
    public static ForgeConfigSpec.IntValue NEARBY_BINDING_HORIZONTAL_RADIUS;
    public static ForgeConfigSpec.IntValue NEARBY_BINDING_VERTICAL_RADIUS;
    public static ForgeConfigSpec.IntValue NEARBY_BINDING_MAX_MACHINES;
    public static ForgeConfigSpec.IntValue NEARBY_BINDING_TICK_BUDGET_MICROS;
    public static ForgeConfigSpec.IntValue NEARBY_BINDING_COOLDOWN_MS;
    public static ForgeConfigSpec.BooleanValue ENABLE_AUTO_CRAFTING;
    public static ForgeConfigSpec.BooleanValue ENABLE_MULTIBLOCK_AUTO_CRAFTING;
    public static ForgeConfigSpec.BooleanValue ENABLE_CATALYST_RECIPE_PREFERENCE;
    public static ForgeConfigSpec.IntValue CATALYST_RECIPE_PREFERENCE_BONUS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PREFERRED_RECIPES;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PREFERRED_INGREDIENT_VARIANTS;
    public static ForgeConfigSpec.IntValue MULTIBLOCK_CRAFT_TIMEOUT_SECONDS;
    public static ForgeConfigSpec.IntValue CRAFTING_CHAIN_GLOBAL_TIMEOUT_SECONDS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> MULTIBLOCK_RECIPE_BLACKLIST;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> MULTIBLOCK_RECIPE_ALLOWLIST;

    //  per-mod integration
    public static ForgeConfigSpec.BooleanValue ENABLE_GOETY;
    public static ForgeConfigSpec.BooleanValue ENABLE_MALUM;
    public static ForgeConfigSpec.BooleanValue ENABLE_BOTANIA;
    public static ForgeConfigSpec.BooleanValue ENABLE_WIZARDS_REBORN;
    public static ForgeConfigSpec.BooleanValue ENABLE_FORBIDDEN_ARCANUS;
    public static ForgeConfigSpec.BooleanValue ENABLE_EIDOLON;
    public static ForgeConfigSpec.BooleanValue ENABLE_TOUHOU_LITTLE_MAID;
    public static ForgeConfigSpec.BooleanValue ENABLE_EMBERS_ALCHEMY;
    public static ForgeConfigSpec.BooleanValue ENABLE_AETHERWORKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_AETHER;
    public static ForgeConfigSpec.BooleanValue ENABLE_ARS_NOUVEAU;
    public static ForgeConfigSpec.BooleanValue ENABLE_CROCKPOT;
    public static ForgeConfigSpec.BooleanValue ENABLE_TACZ;
    public static ForgeConfigSpec.BooleanValue ENABLE_FARMINGFORBLOCKHEADS;
    public static ForgeConfigSpec.BooleanValue ENABLE_EMBERS_ALCHEMY_CALC;
    public static ForgeConfigSpec.BooleanValue ENABLE_SLASHBLADE;
    public static ForgeConfigSpec.BooleanValue ENABLE_AVARITIA;
    public static ForgeConfigSpec.BooleanValue ENABLE_CONFLUENCE;
    public static ForgeConfigSpec.BooleanValue ENABLE_IMMORTERS_DELIGHT;
    public static ForgeConfigSpec.BooleanValue ENABLE_FARMERSDELIGHT;
    public static ForgeConfigSpec.BooleanValue ENABLE_YOUKAISHOMECOMING;
    public static ForgeConfigSpec.BooleanValue ENABLE_FARMERSRESPITE;
    public static ForgeConfigSpec.BooleanValue ENABLE_IRON_FURNACES;
    public static ForgeConfigSpec.BooleanValue ENABLE_DISTANT_WORLDS;
    public static ForgeConfigSpec.BooleanValue ENABLE_LYCHEE;
    public static ForgeConfigSpec.BooleanValue ENABLE_BIOMANCY;
    public static ForgeConfigSpec.BooleanValue ENABLE_CTHULHU_CREATURES;
    public static ForgeConfigSpec.BooleanValue ENABLE_PMMO;
    public static ForgeConfigSpec.BooleanValue ENABLE_WISHING_FOUNTAIN;
    public static ForgeConfigSpec.BooleanValue ENABLE_SRFIX;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> FTB_QUEST_CHECKMARK_BLACKLIST;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> LYCHEE_RECIPE_ALLOWLIST;
    public static ForgeConfigSpec.BooleanValue ALLOW_DISTANT_WORLDS_RESEARCH_BYPASS;
    public static ForgeConfigSpec.BooleanValue DISABLE_DISTANT_WORLDS_FIRON_FAILURE;
    public static ForgeConfigSpec.BooleanValue ALLOW_DISTANT_WORLDS_FUEL_AUTOMATION;
    public static ForgeConfigSpec.IntValue DISTANT_WORLDS_FUEL_SEARCH_RADIUS;
    public static ForgeConfigSpec.IntValue DISTANT_WORLDS_FUEL_BATCH_SIZE;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> DISTANT_WORLDS_FUEL_PRIORITY;
    public static ForgeConfigSpec.BooleanValue ENABLE_APOTHEOSIS;
    public static ForgeConfigSpec.BooleanValue ENABLE_IRONS_SPELLBOOKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_APPRENTICE_CODEX;
    public static ForgeConfigSpec.BooleanValue ENABLE_ISS_CSW;
    public static ForgeConfigSpec.BooleanValue ENABLE_VANILLA_MACHINES;
    public static ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_FTB_QUEST_EXTERNAL_ITEM_PROGRESS;
    public static ForgeConfigSpec.BooleanValue ENABLE_FTB_QUEST_CHECKMARK_BUTTON;
    public static ForgeConfigSpec.BooleanValue ENABLE_FTB_QUEST_STORAGE_SCAN_BUTTON;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI_NETWORK_OVERLAY;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI_CRAFTING_SHORTAGE_OVERLAY;
    public static ForgeConfigSpec.DoubleValue JEI_NETWORK_OVERLAY_SCALE;
    public static ForgeConfigSpec.DoubleValue JEI_CRAFTING_SHORTAGE_OVERLAY_SCALE;
    public static ForgeConfigSpec.BooleanValue ENABLE_VILLAGER_TRADE_LOCK;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI_MARQUEE_SELECTION;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION;
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_GRID_SWIPE_EXTRACT;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_IDLE_BUDGET_MICROS;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_ACTIVE_BUDGET_MICROS;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_DEBOUNCE_MS;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_PARTIAL_REFRESH_MS;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_QUERY_CACHE_ENTRIES;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_EMPTY_SNAPSHOT_GRACE_MS;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_CANDIDATE_INDEX_MAX_PERCENT;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_PINYIN_WORKERS;
    public static ForgeConfigSpec.BooleanValue GRID_SEARCH_DISK_CACHE_ENABLED;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_DISK_CACHE_ENTRIES;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_DISK_CACHE_MAX_MIB;
    public static ForgeConfigSpec.IntValue GRID_SEARCH_DISK_CACHE_SAVE_DELAY_MS;
    public static ForgeConfigSpec.BooleanValue LIGHTWEIGHT_SLASHBLADE_LIST_RENDERING;
    public static ForgeConfigSpec.BooleanValue RS_RECENT_SEARCH_ENABLED;
    public static ForgeConfigSpec.IntValue RS_RECENT_SEARCH_MAX_VISIBLE_ENTRIES;
    public static ForgeConfigSpec.IntValue RS_RECENT_SEARCH_MAX_STORED_ENTRIES;
    public static ForgeConfigSpec.BooleanValue RS_RECENT_SEARCH_FAVORITES_ENABLED;
    public static ForgeConfigSpec.BooleanValue RS_RECENT_SEARCH_DELETE_BUTTONS_ENABLED;
    public static ForgeConfigSpec.BooleanValue DEPOSIT_UPGRADE_RS;
    public static ForgeConfigSpec.BooleanValue ENABLE_MAJ_ACCESSORY_COMPRESSION;
    public static ForgeConfigSpec.BooleanValue ENABLE_MACHINE_GUI_TABS;
    public static ForgeConfigSpec.BooleanValue REQUIRE_MACHINE_BINDING_FOR_GUI;
    public static ForgeConfigSpec.BooleanValue REQUIRE_BOUND_MACHINE_FOR_VIRTUAL_STATION;
    //  auto-eat
    public static ForgeConfigSpec.BooleanValue ENABLE_AUTO_EAT;
    public static ForgeConfigSpec.ConfigValue<String> AUTO_EAT_REQUIRED_EFFECT;
    public static ForgeConfigSpec.ConfigValue<String> AUTO_EAT_COST_ITEM;
    public static ForgeConfigSpec.IntValue AUTO_EAT_COST_PER_ITEM;
    public static ForgeConfigSpec.IntValue AUTO_EAT_MAX_PER_BATCH;

    public static ForgeConfigSpec.BooleanValue ENABLE_CONTAINER_TRANSFER;
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_SIDE_PANEL;
    public static ForgeConfigSpec.BooleanValue ENABLE_ANVIL_MEMORY;
    public static ForgeConfigSpec.IntValue ANVIL_MEMORY_RESTOCK_TARGET;
    public static ForgeConfigSpec.BooleanValue ANVIL_MEMORY_REMEMBER_NBT;
    public static ForgeConfigSpec.BooleanValue ANVIL_MEMORY_PREFER_PLAYER_INVENTORY;
    public static ForgeConfigSpec.BooleanValue ANVIL_MEMORY_BOOKMARK_MISSING;
    public static ForgeConfigSpec.BooleanValue ANVIL_MEMORY_IPN_COMPAT;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> ANVIL_MEMORY_ADAPTERS;
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_PASSIVE_EFFECTS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PASSIVE_TICK_ITEMS;
    public static ForgeConfigSpec.IntValue NINE_SWORD_MAX_COUNT;
    public static ForgeConfigSpec.BooleanValue DIAGNOSTIC_VERBOSE_LOGGING;

    //  per-mod tuning (server, per-world)
    public static ForgeConfigSpec.ConfigValue<String> CROCKPOT_FILLER_ITEM;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CROCKPOT_FUEL_PRIORITY;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> VANILLA_FURNACE_FUEL_PRIORITY;
    public static ForgeConfigSpec.BooleanValue ENABLE_VANILLA_FURNACE_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue VANILLA_FURNACE_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_BRICK_FURNACE_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue BRICK_FURNACE_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_IRON_FURNACE_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue IRON_FURNACE_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_GOETY_INFUSER_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue GOETY_INFUSER_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_APPRENTICE_CODEX_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue APPRENTICE_CODEX_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_MALUM_CRUCIBLE_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue MALUM_CRUCIBLE_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_MALUM_ALTAR_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue MALUM_ALTAR_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_AETHER_FURNACE_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue AETHER_FURNACE_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_CLIBANO_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue CLIBANO_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_ENCHANTAL_COOLER_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue ENCHANTAL_COOLER_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_FARMERS_DELIGHT_COOKING_POT_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue FARMERS_DELIGHT_COOKING_POT_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_MINERS_DELIGHT_COPPER_POT_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue MINERS_DELIGHT_COPPER_POT_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_MOKA_POT_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue MOKA_POT_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_BOTANIA_MANA_POOL_BATCH;
    public static ForgeConfigSpec.IntValue BOTANIA_MANA_POOL_BATCH_LIMIT;
    public static ForgeConfigSpec.BooleanValue ENABLE_BOTANIA_ELVEN_TRADE_INPUT_BUFFER;
    public static ForgeConfigSpec.IntValue BOTANIA_ELVEN_TRADE_INPUT_BUFFER_LIMIT;
    public static ForgeConfigSpec.IntValue EMBERS_INFER_MAX_ATTEMPTS;
    public static ForgeConfigSpec.IntValue EMBERS_INFER_ZERO_BLACK_LIMIT;
    public static ForgeConfigSpec.IntValue EMBERS_LOCK_TIMEOUT_MINUTES;
    public static ForgeConfigSpec.IntValue EMBERS_PROGRESS_TIMEOUT_TICKS;

    //  numeric params
    public static ForgeConfigSpec.IntValue MACHINE_TAB_THRESHOLD;
    public static ForgeConfigSpec.IntValue MACHINE_HUB_TOGGLE_KEY;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CUSTOM_GUI_MACHINE_MODS;
    public static ForgeConfigSpec.IntValue REPEAT_COUNT_MAX;
    public static ForgeConfigSpec.IntValue SERVER_CONFIG_SCHEMA_VERSION;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PROTECTED_ITEMS;
    public static ForgeConfigSpec.IntValue PROTECTED_RESERVE;
    public static ForgeConfigSpec.IntValue CONTAINER_TRANSFER_KEY;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_KEY;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_MAX_SLOTS;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_SYNC_INTERVAL;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_EXTRACTION_TIMEOUT;
    public static ForgeConfigSpec.IntValue GUI_OPEN_RATE_LIMIT_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_DEPTH;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_STEPS;
    public static ForgeConfigSpec.IntValue CRAFTING_PLANNING_WORKERS;
    public static ForgeConfigSpec.IntValue CRAFTING_PLANNING_QUEUE_CAPACITY;
    public static ForgeConfigSpec.IntValue CRAFTING_PURE_SEARCH_MAX_STATES;
    public static ForgeConfigSpec.IntValue CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES;
    public static ForgeConfigSpec.IntValue CRAFTING_PURE_DEMAND_MAX_NODES;
    public static ForgeConfigSpec.IntValue CRAFTING_PURE_PLANNING_TIMEOUT_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_TYPED_PREVIEW_TIMEOUT_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY;
    public static ForgeConfigSpec.IntValue CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS;
    public static ForgeConfigSpec.BooleanValue ENABLE_CRAFTING_VARIANT_CONVERSION_GUARD;
    public static ForgeConfigSpec.IntValue CRAFTING_PREVIEW_RATE_LIMIT_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_PLAN_CACHE_TTL_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_PLAN_CACHE_MAX_ENTRIES;
    public static ForgeConfigSpec.IntValue CRAFTING_RESOLVE_TIMEOUT_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_ENSURE_CALLS;
    public static ForgeConfigSpec.IntValue CRAFTING_VANILLA_OPERATIONS_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_SERVER_TICK_BUDGET_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_OPERATIONS_PER_DISPATCH;
    public static ForgeConfigSpec.IntValue CRAFTING_SETTLEMENT_STACKS_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_COMPLETION_CALLBACKS_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_CONCURRENT_GRAPH_NODES;
    public static ForgeConfigSpec.IntValue CRAFTING_GRAPH_DISPATCH_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_GRAPH_DISPATCH_PER_CRAFT;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_CONCURRENT_OPERATIONS;
    public static ForgeConfigSpec.IntValue CRAFTING_OPERATION_DISPATCH_PER_CRAFT;
    public static ForgeConfigSpec.IntValue CRAFTING_PROBABILISTIC_ATTEMPT_MULTIPLIER;
    public static ForgeConfigSpec.IntValue CRAFTING_PROBABILISTIC_MAX_ATTEMPTS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CRAFTING_PARALLEL_DISABLED_MODS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CRAFTING_PARALLEL_DELEGATE_POLICIES;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_MAX_DEPTH;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_MAX_NODES;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_BATCH_DEBOUNCE_MS;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_MAX_CANDIDATES;
    public static ForgeConfigSpec.BooleanValue REQUIRE_RS_NETWORK_FOR_RECIPE_TREE;

    //  client-only
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_X;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_Y;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_WIDTH;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_HEIGHT;
    public static ForgeConfigSpec.BooleanValue RS_SIDE_PANEL_HIDDEN;
    public static ForgeConfigSpec.BooleanValue AUTO_EAT_MENU_EXPANDED;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_NAVIGATION_TIMEOUT_MS;
    public static ForgeConfigSpec.BooleanValue ENABLE_DISTANT_WORLDS_HUD;
    public static ForgeConfigSpec.BooleanValue TETRA_JEI_PREVIEW_CACHE;


    static {
        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder s = new ForgeConfigSpec.Builder();

        //  COMMON: feature toggles

        c.comment("基本设置").push("general");
        ENABLE_BINDING = c
                .comment("启用机器绑定",
                        "机器绑定功能的总开关；关闭后所有按模组设置的绑定功能都会停用。")
                .define("enableBinding", true);
        NEARBY_BINDING_HORIZONTAL_RADIUS = c
                .comment("附近绑定水平半径（格）",
                        "单次附近绑定的水平搜索半径；只扫描已加载的区块。")
                .defineInRange("nearbyBindingHorizontalRadius", 24, 1, 64);
        NEARBY_BINDING_VERTICAL_RADIUS = c
                .comment("附近绑定垂直半径（格）",
                        "玩家上下的搜索半径；小于水平半径可避免扫描无用高度。")
                .defineInRange("nearbyBindingVerticalRadius", 12, 1, 24);
        NEARBY_BINDING_MAX_MACHINES = c
                .comment("单次附近绑定机器上限",
                        "限制一次扫描绑定的机器数量，避免连接器 NBT 过大。")
                .defineInRange("nearbyBindingMaxMachines", 128, 1, 128);
        NEARBY_BINDING_TICK_BUDGET_MICROS = c
                .comment("附近绑定每 tick 预算（微秒）",
                        "服务端每 tick 用于附近绑定扫描的时间预算；达到后在后续 tick 继续。")
                .defineInRange("nearbyBindingTickBudgetMicros", 1000, 100, 5000);
        NEARBY_BINDING_COOLDOWN_MS = c
                .comment("附近绑定冷却（毫秒）",
                        "同一玩家两次附近绑定请求之间的最小时间间隔。")
                .defineInRange("nearbyBindingCooldownMs", 2000, 250, 10000);
        ENABLE_AUTO_CRAFTING = c
                .comment("自动合成",
                        "材料不足时通过存储网络递归合成中间材料；关闭后只使用已有库存。")
                .define("enableAutoCrafting", true);
        c.pop();

        c.comment("模组兼容").push("integrations");
        ENABLE_GOETY = c
                .comment("启用诡厄巫法兼容",
                        "支持黑暗祭坛等机器绑定与远程合成。")
                .define("enableGoety", true);
        ENABLE_MALUM = c
                .comment("启用灵灾兼容",
                        "支持精魂祭坛、精魂坩埚等机器绑定与远程合成。")
                .define("enableMalum", true);
        ENABLE_BOTANIA = c
                .comment("启用植物魔法兼容",
                        "支持植物魔法机器参与递归合成。")
                .define("enableBotania", true);
        ENABLE_WIZARDS_REBORN = c
                .comment("启用巫师重生兼容",
                        "支持智慧结晶器、奥术迭代器等机器的远程合成。")
                .define("enableWizardsReborn", true);
        ENABLE_FORBIDDEN_ARCANUS = c
                .comment("启用禁忌与奥秘兼容",
                        "支持赫菲斯托斯锻炉远程仪式合成。")
                .define("enableForbiddenArcanus", true);
        ENABLE_EIDOLON = c
                .comment("启用幻梦兼容",
                        "支持幻之坩锅等机器的远程合成。")
                .define("enableEidolon", true);
        ENABLE_TOUHOU_LITTLE_MAID = c
                .comment("启用车万女仆兼容",
                        "支持女仆祭坛远程合成。")
                .define("enableTouhouLittleMaid", true);
        ENABLE_SRFIX = c
                .comment("启用召唤仪式兼容",
                        "通过远程存储准备及回收祭坛物品；不会启动仪式或收集世界掉落物。")
                .define("enableBetterSummoningRituals", true);
        ENABLE_SLASHBLADE = c
                .comment("启用拔刀剑兼容",
                        "支持带 NBT 条件的拔刀剑工作台配方。")
                .define("enableSlashblade", true);
        ENABLE_AVARITIA = c
                .comment("启用无尽贪婪兼容",
                        "支持无尽合成台、终极锻造台、中子态素收集器、箱子、超立方体及铁砧。")
                .define("enableAvaritia", true);
        ENABLE_CONFLUENCE = c
                .comment("启用汇流来世兼容",
                        "支持汇流来世工作坊递归合成。")
                .define("enableConfluence", true);
        ENABLE_IMMORTERS_DELIGHT = c
                .comment("启用千古乐事兼容",
                        "支持魔凝机远程合成。")
                .define("enableImmortersDelight", true);
        ENABLE_FARMERSDELIGHT = c
                .comment("启用农夫乐事兼容",
                        "支持烹饪锅和煎锅远程合成。")
                .define("enableFarmersDelight", true);
        ENABLE_YOUKAISHOMECOMING = c
                .comment("启用妖怪归家兼容",
                        "支持摩卡壶、蒸锅等机器的远程合成。")
                .define("enableYoukaisHomecoming", true);
        ENABLE_FARMERSRESPITE = c
                .comment("启用农夫暇事兼容",
                        "支持水壶的流体泡制。")
                .define("enableFarmersRespite", true);
        ENABLE_IRON_FURNACES = c
                .comment("启用更多熔炉兼容",
                        "支持普通熔炉和工厂的熔炼、烧炼及烟熏配方；不支持发电模式。")
                .define("enableIronFurnaces", true);
        ENABLE_DISTANT_WORLDS = c
                .comment("启用遥远世界兼容",
                        "支持锂祭坛的 Firon 配方。")
                .define("enableDistantWorlds", true);
        ENABLE_LYCHEE = c
                .comment("启用lychee虚拟合成",
                        "只支持已适配的 item_inside 配方；共振盘必须有匹配的基底桶，基底不会被消耗。")
                .define("enableLychee", true);
        ENABLE_BIOMANCY = c
                .comment("启用血肉重铸兼容",
                        "支持生物炼金机器绑定与远程合成。")
                .define("enableBiomancy", true);
        ENABLE_CTHULHU_CREATURES = c
                .comment("启用克苏鲁生物血肉祭坛兼容",
                        "支持带数量的材料、批量投料、多祭坛并行、远程界面与递归合成。")
                .define("enableCthulhuCreatures", true);
        ENABLE_PMMO = c
                .comment("启用 PMMO 回收兼容",
                        "通过配置指定的回收方块递归回收；请求数量为独立尝试次数，不保证相同数量的产物。")
                .define("enablePmmoSalvage", true);
        ENABLE_WISHING_FOUNTAIN = c
                .comment("启用许愿泉兼容",
                        "支持完整许愿泉的绑定及产物类愿望的递归合成；天气愿望仍需手动进行。")
                .define("enableWishingFountain", true);
        LYCHEE_RECIPE_ALLOWLIST = c
                .comment("lychee虚拟配方白名单",
                        "填写配方 ID，只接受已内置适配的细雪、希腊火、矮人油和深层天境毒液类型；清空后关闭所有虚拟配方。")
                .defineListAllowEmpty("lycheeRecipeAllowlist", List.of(
                                "crafttweaker:avaritia.diamond_lattice.1",
                                "crafttweaker:avaritia.diamond_lattice.2",
                                "crafttweaker:avaritia.diamond_lattice.3",
                                "crafttweaker:avaritia.diamond_lattice.4",
                                "crafttweaker:avaritia.diamond_lattice.6",
                                "crafttweaker:avaritia.diamond_lattice.7",
                                "crafttweaker:avaritia.diamond_lattice.8",
                                "crafttweaker:avaritia.diamond_lattice.9",
                                "crafttweaker:avaritia.diamond_lattice.10",
                                "crafttweaker:avaritia.diamond_lattice.11",
                                "crafttweaker:avaritia.diamond_lattice.12",
                                "crafttweaker:too_many_bows.power_crystal.ex",
                                "crafttweaker:irons_spellbooks.lurker_ring",
                                "crafttweaker:eidolon.lead_ingot.1",
                                "crafttweaker:eidolon.lead_ingot.2",
                                "crafttweaker:eidolon.lead_ingot.3",
                                "crafttweaker:eidolon.silver_ingot.1",
                                "crafttweaker:eidolon.silver_ingot.2",
                                "crafttweaker:eidolon.silver_ingot.3",
                                "crafttweaker:minecraft.copper_block",
                                "crafttweaker:minecraft.exposed_copper",
                                "crafttweaker:minecraft.oxidized_copper",
                                "crafttweaker:minecraft.weathered_copper",
                                "crafttweaker:minecraft.sugar.1",
                                "crafttweaker:minecraft.sugar.2",
                                "crafttweaker:nameless_trinkets.dubious_dust",
                                "crafttweaker:refinedstorage.advanced_processor",
                                "crafttweaker:refinedstorage.basic_processor",
                                "crafttweaker:refinedstorage.improved_processor",
                                "crafttweaker:refinedstorage.processor_binding.1",
                                "crafttweaker:refinedstorage.processor_binding.2",
                                "crafttweaker:refinedstorage.silicon",
                                "crafttweaker:avaritia.eternal_singularity",
                                "crafttweaker:embers.lead_ingot.special",
                                "crafttweaker:embers.silver_ingot.special",
                                "crafttweaker:yuusha.epic_material",
                                "crafttweaker:yuusha.legendary_material",
                                "crafttweaker:yuusha.rare_material",
                                "crafttweaker:yuusha.ultimate_material",
                                "crafttweaker:yuusha.uncommon_material",
                                "crafttweaker:deep_aether.sterling_aercloud",
                                "crafttweaker:aether_redux.sentrite",
                                "crafttweaker:hmag.evil_crystal_fragment"),
                        value -> value instanceof String recipeId
                                && ResourceLocation.tryParse(recipeId) != null);
        ALLOW_DISTANT_WORLDS_RESEARCH_BYPASS = c
                .comment("允许跳过遥远世界研究要求",
                        "RSI 自动化可不持有 distant_worlds:incandescent_forever 进度执行 Firon 配方；原模组祭坛操作不受影响。")
                .define("allowDistantWorldsResearchBypass", true);
        DISABLE_DISTANT_WORLDS_FIRON_FAILURE = c
                .comment("关闭 Firon 仪式随机失败",
                        "关闭 distant_worlds:firon_* 祭坛仪式的随机失败和爆炸分支，使自动合成稳定产出真实结果。")
                .define("disableDistantWorldsFironFailure", true);
        ALLOW_DISTANT_WORLDS_FUEL_AUTOMATION = c
                .comment("自动供给遥远世界熔炉燃料",
                        "RSI 祭坛合成期间为附近遥远世界熔炉补充符合标签的燃料。")
                .define("allowDistantWorldsFuelAutomation", true);
        DISTANT_WORLDS_FUEL_SEARCH_RADIUS = c
                .comment("遥远世界熔炉搜索半径（格）",
                        "祭坛合成寻找遥远世界熔炉时使用的最大方块半径。")
                .defineInRange("distantWorldsFuelSearchRadius", 8, 1, 16);
        DISTANT_WORLDS_FUEL_BATCH_SIZE = c
                .comment("遥远世界熔炉单次补充燃料上限",
                        "每次补充到选中遥远世界熔炉的最大燃料数量。")
                .defineInRange("distantWorldsFuelBatchSize", 4, 1, 64);
        DISTANT_WORLDS_FUEL_PRIORITY = c
                .comment("遥远世界熔炉燃料优先级",
                        "填写物品 ID，越靠前优先级越高。")
                .defineListAllowEmpty(List.of("distantWorldsFuelPriority"),
                        List.of("distant_worlds:curelite_block", "distant_worlds:raw_curelite_block",
                                "distant_worlds:curelite", "distant_worlds:raw_curelite"),
                        value -> value instanceof String sValue && ResourceLocation.tryParse(sValue) != null);
        ENABLE_APOTHEOSIS = c
                .comment("启用神化兼容",
                        "支持制箭台配方及远程界面；仅在安装神化时生效。")
                .define("enableApotheosis", true);
        ENABLE_IRONS_SPELLBOOKS = c
                .comment("启用铁魔法兼容",
                        "支持卷轴撰写台及奥术铁砧。")
                .define("enableIronsSpellbooks", true);
        ENABLE_APPRENTICE_CODEX = c
                .comment("启用学徒法典兼容",
                        "支持精华熏制炉及施法工作台递归合成。")
                .define("enableApprenticeCodex", true);
        ENABLE_ISS_CSW = c
                .comment("启用奥术交错兼容",
                        "支持奥术交错台递归合成。")
                .define("enableIssCsw", true);
        ENABLE_AETHERWORKS = c
                .comment("启用天华工艺兼容",
                        "支持天华砧远程合成及自动敲击。")
                .define("enableAetherworks", true);
        ENABLE_AETHER = c
                .comment("启用天境兼容",
                        "支持冷冻器、孵化器及祭坛。")
                .define("enableAether", true);
        ENABLE_ARS_NOUVEAU = c
                .comment("启用新生魔艺兼容",
                        "只自动化浸润仪和附魔装置的物品配方；附魔、魔符、反应及染色等涉及 NBT 或世界交互的配方仍需手动完成。")
                .define("enableArsNouveau", true);
        ENABLE_CROCKPOT = c
                .comment("启用烹饪锅兼容",
                        "支持烹饪锅和便携烹饪锅。")
                .define("enableCrockPot", true);
        ENABLE_TACZ = c
                .comment("启用永恒枪械工坊兼容",
                        "支持枪械工作台递归合成。")
                .define("enableTacz", true);
        ENABLE_EMBERS_ALCHEMY = c
                .comment("启用余烬炼金兼容",
                        "支持余烬复燃的炼金台远程合成。")
                .define("enableEmbersAlchemy", true);
        ENABLE_EMBERS_ALCHEMY_CALC = c
                .comment("启用余烬炼金计算模式",
                        "显示确定的基座布局；关闭后仅可使用试错推断模式，需要同时启用余烬炼金兼容。")
                .define("enableEmbersAlchemyCalculate", false);
        ENABLE_VANILLA_MACHINES = c
                .comment("启用原版机器兼容",
                        "支持熔炉、高炉、烟熏炉、营火、切石机及锻造台的绑定和远程合成。")
                .define("enableVanillaMachines", true);
        ENABLE_SOPHISTICATED_BACKPACKS = c
                .comment("启用精妙背包兼容",
                        "启用基于 RS 网络的背包升级物品。")
                .define("enableSophisticatedBackpacks", true);
        ENABLE_FTB_QUEST_EXTERNAL_ITEM_PROGRESS = c
                .comment("计入 FTB 任务外部存入进度",
                        "背包升级和合成实际存入 RS 的物品计入符合条件的物品任务；模拟、销毁、退款及回收物品不计入。")
                .define("enableFtbQuestExternalItemProgress", true);
        ENABLE_FTB_QUEST_CHECKMARK_BUTTON = c
                .comment("显示 FTB 任务批量确认按钮",
                        "在侧边栏显示批量确认勾选任务的按钮。")
                .define("enableFtbQuestCheckmarkButton", true);
        ENABLE_FTB_QUEST_STORAGE_SCAN_BUTTON = c
                .comment("显示 FTB 任务库存扫描按钮",
                        "在侧边栏显示一次性扫描存储网络和玩家背包的按钮。")
                .define("enableFtbQuestStorageScanButton", true);
        ENABLE_JEI = c
                .comment("启用 JEI 兼容",
                        "在 JEI 配方页显示远程合成加号；由服务端同步开关到客户端。")
                .define("enableJeiIntegration", true);
        ENABLE_JEI_NETWORK_OVERLAY = c
                .comment("显示 JEI 网络库存标记",
                        "在 JEI 物品槽显示 RS 或超越维度网络库存；初始快照后使用增量更新，由服务端同步开关。")
                .define("enableJeiNetworkOverlay", true);
        ENABLE_JEI_CRAFTING_SHORTAGE_OVERLAY = c
                .comment("显示 JEI 合成缺料标记",
                        "在 JEI 物品槽显示当前递归合成计划的红色缺料标记，与网络库存数字分开，由服务端同步开关。")
                .define("enableJeiCraftingShortageOverlay", true);
        ENABLE_JEI_MARQUEE_SELECTION = c
                .comment("启用 JEI 物品框选",
                        "在 JEI 物品列表拖动框选以批量收藏或隐藏；与其他拖动操作冲突时可关闭，由服务端同步开关。")
                .define("enableJeiMarqueeSelection", true);
        ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION = c
                .comment("启用 JEI 书签框选",
                        "在 JEI 左侧书签栏拖动框选以批量移除或隐藏，由服务端同步开关。")
                .define("enableJeiBookmarkMarqueeSelection", true);
        ENABLE_RS_GRID_SWIPE_EXTRACT = c
                .comment("启用 RS 网格滑动取出",
                        "按住 Ctrl 在 RS 网格中拖动鼠标，每个经过的物品取出一个；与其他手势冲突时可关闭。")
                .define("enableRSGridSwipeExtract", true);
        ENABLE_FARMINGFORBLOCKHEADS = c
                .comment("启用懒人厨房兼容",
                        "支持贸易站等已适配机器的远程操作。")
                .define("enableFarmingForBlockheads", true);
        c.pop();

        c.comment("自动合成").push("autoCrafting");
        ENABLE_MULTIBLOCK_AUTO_CRAFTING = c
                .comment("中间材料使用多方块机器",
                        "递归合成时可用祭坛、锻炉、坩埚等机器制作中间材料；关闭后中间材料仅使用原版工作台配方。")
                .define("enableMultiblockAutoCrafting", true);
        c.pop();

        c.comment("精妙背包").push("sophisticated_backpacks");
        DEPOSIT_UPGRADE_RS = c
                .comment("存入升级连接 RS 网络",
                        "开启后背包存入升级可向 RS 终端推送物品；关闭后使用精妙背包原有行为。")
                .define("depositUpgradeRS", true);
        ENABLE_MAJ_ACCESSORY_COMPRESSION = c
                .comment("压缩升级合并 MAJ 饰品",
                        "匹配白名单的 Majrusz 饰品会两两合并，从最高效率饰品开始，直到达到 100%。")
                .define("enableMajAccessoryCompression", true);
        c.pop();

        c.comment("被动效果").push("passiveEffects");
        ENABLE_RS_PASSIVE_EFFECTS = c
                .comment("启用网络被动效果",
                        "绑定 RS 网络中的物品可提供原本在背包或快捷栏生效的被动效果，包括属性、白名单物品 tick 及已适配事件效果。")
                .define("enableRSPassiveEffects", true);
        PASSIVE_TICK_ITEMS = c
                .comment("模拟背包 tick 的物品",
                        "格式为 modid:item_id 或 modid:item_id|mutates；带 mutates 的条目会取出、执行 tick 并存回以保存 NBT，其他条目使用只读副本。")
                .defineList("passiveTickItems",
                        DEFAULT_PASSIVE_TICK_ITEMS,
                        obj -> obj instanceof String && ((String) obj).contains(":"));
        NINE_SWORD_MAX_COUNT = c
                .comment("九剑书生效数量上限",
                        "统计玩家背包和共振盘中的九剑书；超出数量不生效，原版快捷栏上限为 9。")
                .defineInRange("nineSwordMaxCount", 9, 1, 36);
        c.pop();

        c.comment("自动进食").push("autoEat");
        ENABLE_AUTO_EAT = c
                .comment("启用自动进食",
                        "在 RS 和超越维度终端提供多样饮食、堆叠暴食及膳食均衡；食物和消耗物品由所选存储网络提供。")
                .define("enableAutoEat", true);
        AUTO_EAT_REQUIRED_EFFECT = c
                .comment("自动进食所需状态效果",
                        "填写 modid:effect_id；留空表示无需效果，例如 crockpot:gnaws_gift。")
                .define("requiredEffect", "");
        AUTO_EAT_COST_ITEM = c
                .comment("自动进食消耗物品",
                        "填写 modid:item_id；minecraft:air 表示不消耗物品。")
                .define("costItem", "minecraft:air");
        AUTO_EAT_COST_PER_ITEM = c
                .comment("每份食物消耗数量",
                        "每吃一份食物需从网络扣除的消耗物品数量；0 表示免费。")
                .defineInRange("costPerItem", 0, 0, 64);
        AUTO_EAT_MAX_PER_BATCH = c
                .comment("单次进食数量上限",
                        "每次批量进食最多吃掉的食物数量。")
                .defineInRange("maxPerBatch", 64, 1, 1024);
        c.pop();

        c.comment("容器传输").push("containerTransfer");
        ENABLE_CONTAINER_TRANSFER = c
                .comment("启用一键容器传输",
                        "打开容器后按指定按键将物品存入绑定网络；需要维度访问器及已绑定的 RS 网络。")
                .define("enableContainerTransfer", true);
        c.pop();

        c.comment("侧边面板").push("sidePanel");
        ENABLE_RS_SIDE_PANEL = c
                .comment("启用 RS 侧边面板",
                        "通过指定按键显示可折叠、拖动的网络物品面板。")
                .define("enableRSSidePanel", false);
        c.pop();

        c.comment("远程机器").push("remoteMachineGui");
        ENABLE_MACHINE_GUI_TABS = c
                .comment("启用虚拟机器界面",
                        "在机器中心和 RS 终端显示虚拟机器界面；关闭后拒绝相应远程请求，与机器是否必须绑定的选项独立。")
                .define("enableMachineGuiTabs", true);
        REQUIRE_MACHINE_BINDING_FOR_GUI = c
                .comment("远程界面要求机器绑定",
                        "开启后拒绝打开未绑定机器的界面；关闭后有效机器位置可直接远程打开。")
                .define("requireMachineBindingForGui", true);
        REQUIRE_BOUND_MACHINE_FOR_VIRTUAL_STATION = c
                .comment("虚拟工作站要求实体机器",
                        "虚拟切石机、锻造台和铁砧必须有已绑定的对应实体机器；原生 3×3 合成不受影响。")
                .define("requireBoundMachineForVirtualStation", false);
        CUSTOM_GUI_MACHINE_MODS = c
                .comment("自定义远程界面模组",
                        "填写模组 ID，为其机器提供绑定和远程打开界面，不提供批量合成；已有完整模块支持的模组无需填写。")
                .defineList("customGuiMachineMods", List.of("crabbersdelight", "metalbarrels", "pgp", "emxarms", "ancientreforging"),
                        obj -> obj instanceof String);
        c.pop();

        c.comment("高级设置").push("advanced");
        DIAGNOSTIC_VERBOSE_LOGGING = c
                .comment("启用详细诊断日志",
                        "仅建议排查问题时启用，可能产生大量服务端日志。")
                .define("diagnosticVerboseLogging", false);
        c.pop();

        COMMON_SPEC = c.build();

        //  SERVER: per-world tuning

        SERVER_CONFIG_SCHEMA_VERSION = s
                .comment("Internal server-config schema version. Do not edit manually.")
                .defineInRange("configSchemaVersion", 1, 1, SERVER_CONFIG_SCHEMA);

        s.comment("模组兼容").push("integrations");
        ENABLE_VILLAGER_TRADE_LOCK = s
                .comment("书签匹配时锁定村民交易",
                        "当前村民交易结果已在 JEI 收藏时锁定交易刷新；支持 Retraining 和 Trade Cycling。")
                .define("enableVillagerTradeLock", true);
        FTB_QUEST_CHECKMARK_BLACKLIST = s
                .comment("FTB 任务批量确认黑名单",
                        "使用任务节点的十六进制 ID，不加 0x，不能使用内部子任务 ID；仅排除批量确认，仍可手动确认。")
                .defineListAllowEmpty("ftbQuestCheckmarkBlacklist", List.of(),
                        value -> value instanceof String id
                                && id.matches("(?i)[0-9a-f]{1,16}"));
        CROCKPOT_FILLER_ITEM = s
                .comment("烹饪锅默认填充物",
                        "配方必要材料未占满输入槽时使用此物品补齐；填写 modid:item_id，默认 minecraft:stick。")
                .define("crockpotFillerItem", "minecraft:stick");
        CROCKPOT_FUEL_PRIORITY = s
                .comment("烹饪锅燃料优先级",
                        "填写物品 ID，优先使用靠前且库存充足的燃料。没有匹配时使用安全的批量燃料，跳过工具、弓、容器燃料及带 NBT 的物品；合成结束后回收未使用的自动补充燃料。")
                .defineList("crockpotFuelPriority",
                        List.of("minecraft:coal", "minecraft:charcoal", "minecraft:coal_block"),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        VANILLA_FURNACE_FUEL_PRIORITY = s
                .comment("熔炉燃料优先级",
                        "适用于熔炉、高炉、烟熏炉及炽炉。空槽按列表选燃料，非空槽只补同类燃料；跳过工具、返还容器的燃料及带 NBT 的物品。")
                .defineList("vanillaFurnaceFuelPriority",
                        List.of("minecraft:coal", "minecraft:charcoal"),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        ENABLE_VANILLA_FURNACE_INPUT_BUFFER = s
                .comment("启用原版熔炉输入预载",
                        "熔炉、高炉和烟熏炉每周期仍消耗一份输入，实际批次受输入输出堆叠容量和派发上限限制。")
                .define("enableVanillaFurnaceInputBuffer", true);
        VANILLA_FURNACE_INPUT_BUFFER_LIMIT = s
                .comment("原版熔炉输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。熔炉、高炉和烟熏炉每周期仍消耗一份输入，实际批次受输入输出堆叠容量和派发上限限制。")
                .defineInRange("vanillaFurnaceInputBufferLimit", 64, 1, 64);
        ENABLE_BRICK_FURNACE_INPUT_BUFFER = s
                .comment("启用砖制熔炉输入预载",
                        "熔炼、烧炼和烟熏仍每周期消耗一份输入，实际批次受槽位容量限制。")
                .define("enableBrickFurnaceInputBuffer", true);
        BRICK_FURNACE_INPUT_BUFFER_LIMIT = s
                .comment("砖制熔炉输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。熔炼、烧炼和烟熏仍每周期消耗一份输入，实际批次受槽位容量限制。")
                .defineInRange("brickFurnaceInputBufferLimit", 64, 1, 64);
        ENABLE_IRON_FURNACE_INPUT_BUFFER = s
                .comment("启用更多熔炉输入预载",
                        "普通熔炉和工厂每通道仍每周期处理一份输入，彩虹熔炉保持原有倍率；批次受槽位容量和通道数限制。")
                .define("enableIronFurnaceInputBuffer", true);
        IRON_FURNACE_INPUT_BUFFER_LIMIT = s
                .comment("更多熔炉输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。普通熔炉和工厂每通道仍每周期处理一份输入，彩虹熔炉保持原有倍率；批次受槽位容量和通道数限制。")
                .defineInRange("ironFurnaceInputBufferLimit", 64, 1, 64);
        ENABLE_GOETY_INFUSER_INPUT_BUFFER = s
                .comment("启用诡厄巫法注入器输入预载",
                        "仍每周期处理一个放置物品，世界产物统一结算；批次受空配方槽和机器等级限制。")
                .define("enableGoetyInfuserInputBuffer", true);
        GOETY_INFUSER_INPUT_BUFFER_LIMIT = s
                .comment("诡厄巫法注入器输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。仍每周期处理一个放置物品，世界产物统一结算；批次受空配方槽和机器等级限制。")
                .defineInRange("goetyInfuserInputBufferLimit", 64, 1, 64);
        ENABLE_APPRENTICE_CODEX_INPUT_BUFFER = s
                .comment("启用学徒法典精华熏制炉输入预载",
                        "最多预载一个催化剂及八份材料；催化剂按实体熏制周期只预留一次，不随材料数量翻倍。")
                .define("enableApprenticeCodexInputBuffer", true);
        APPRENTICE_CODEX_INPUT_BUFFER_LIMIT = s
                .comment("学徒法典精华熏制炉输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。最多预载一个催化剂及八份材料；催化剂按实体熏制周期只预留一次，不随材料数量翻倍。")
                .defineInRange("apprenticeCodexInputBufferLimit", 8, 1, 8);
        ENABLE_MALUM_CRUCIBLE_INPUT_BUFFER = s
                .comment("启用精魂坩埚输入预载",
                        "可堆叠预载精魂，每次仍只执行一份；可复用催化剂只预留一次，消耗耐久或转换催化剂的配方仍走原路径。")
                .define("enableMalumCrucibleInputBuffer", true);
        MALUM_CRUCIBLE_INPUT_BUFFER_LIMIT = s
                .comment("精魂坩埚输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。可堆叠预载精魂，每次仍只执行一份；可复用催化剂只预留一次，消耗耐久或转换催化剂的配方仍走原路径。")
                .defineInRange("malumCrucibleInputBufferLimit", 64, 1, 64);
        ENABLE_MALUM_ALTAR_INPUT_BUFFER = s
                .comment("启用精魂祭坛输入预载",
                        "可堆叠预载中央、基座及精魂材料；每次完成一份配方后开始下一份，批次受各槽位容量限制。")
                .define("enableMalumAltarInputBuffer", true);
        MALUM_ALTAR_INPUT_BUFFER_LIMIT = s
                .comment("精魂祭坛输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。可堆叠预载中央、基座及精魂材料；每次完成一份配方后开始下一份，批次受各槽位容量限制。")
                .defineInRange("malumAltarInputBufferLimit", 64, 1, 64);
        ENABLE_AETHER_FURNACE_INPUT_BUFFER = s
                .comment("启用天境冷冻器和祭坛输入预载",
                        "每周期仍只处理一份输入，批次受输入输出槽容量限制；孵化器仍使用单次操作。")
                .define("enableAetherFurnaceInputBuffer", true);
        AETHER_FURNACE_INPUT_BUFFER_LIMIT = s
                .comment("天境冷冻器和祭坛输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。每周期仍只处理一份输入，批次受输入输出槽容量限制；孵化器仍使用单次操作。")
                .defineInRange("aetherFurnaceInputBufferLimit", 64, 1, 64);
        ENABLE_CLIBANO_INPUT_BUFFER = s
                .comment("启用炽炉输入预载",
                        "在空闲通道预载材料，每次仍完成一份配方；批次受槽位容量和派发上限限制。")
                .define("enableClibanoInputBuffer", true);
        CLIBANO_INPUT_BUFFER_LIMIT = s
                .comment("炽炉输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。在空闲通道预载材料，每次仍完成一份配方；批次受槽位容量和派发上限限制。")
                .defineInRange("clibanoInputBufferLimit", 64, 1, 64);
        ENABLE_ENCHANTAL_COOLER_INPUT_BUFFER = s
                .comment("启用魔凝机输入预载",
                        "预载材料后仍按原有机器周期处理，实际批次受槽位容量限制。")
                .define("enableEnchantalCoolerInputBuffer", true);
        ENCHANTAL_COOLER_INPUT_BUFFER_LIMIT = s
                .comment("魔凝机输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。预载材料后仍按原有机器周期处理，实际批次受槽位容量限制。")
                .defineInRange("enchantalCoolerInputBufferLimit", 64, 1, 64);
        ENABLE_FARMERS_DELIGHT_COOKING_POT_INPUT_BUFFER = s
                .comment("启用农夫乐事烹饪锅输入预载",
                        "预载食材和盛装容器，每周期仍只烹饪一份；有材料返还的配方及奥术烹饪锅仍走原路径。")
                .define("enableFarmersDelightCookingPotInputBuffer", true);
        FARMERS_DELIGHT_COOKING_POT_INPUT_BUFFER_LIMIT = s
                .comment("农夫乐事烹饪锅输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。预载食材和盛装容器，每周期仍只烹饪一份；有材料返还的配方及奥术烹饪锅仍走原路径。")
                .defineInRange("farmersDelightCookingPotInputBufferLimit", 64, 1, 64);
        ENABLE_MINERS_DELIGHT_COPPER_POT_INPUT_BUFFER = s
                .comment("启用矿工乐事铜锅输入预载",
                        "预载食材和铜杯，有材料返还的配方仍走原路径；批次和结算使用原生铜杯双倍产量，受四个输入槽及容器输出槽限制。")
                .define("enableMinersDelightCopperPotInputBuffer", true);
        MINERS_DELIGHT_COPPER_POT_INPUT_BUFFER_LIMIT = s
                .comment("矿工乐事铜锅输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。预载食材和铜杯，有材料返还的配方仍走原路径；批次和结算使用原生铜杯双倍产量，受四个输入槽及容器输出槽限制。")
                .defineInRange("minersDelightCopperPotInputBufferLimit", 64, 1, 64);
        ENABLE_MOKA_POT_INPUT_BUFFER = s
                .comment("启用妖怪归家摩卡壶输入预载",
                        "预载材料和容器，每周期仍只冲泡一份；有材料返还的配方走原路径，容器与材料在同一存储事务中预留。")
                .define("enableMokaPotInputBuffer", true);
        MOKA_POT_INPUT_BUFFER_LIMIT = s
                .comment("妖怪归家摩卡壶输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。预载材料和容器，每周期仍只冲泡一份；有材料返还的配方走原路径，容器与材料在同一存储事务中预留。")
                .defineInRange("mokaPotInputBufferLimit", 64, 1, 64);
        ENABLE_BOTANIA_MANA_POOL_BATCH = s
                .comment("启用魔力池批量转化",
                        "一次投入多份相同物品；每份仍消耗配方原有魔力，等待整批处理完后结算。")
                .define("enableBotaniaManaPoolBatch", true);
        BOTANIA_MANA_POOL_BATCH_LIMIT = s
                .comment("魔力池批量转化操作上限",
                        "一次实体批次最多投入的逻辑操作数，实际数量还受当前可用魔力限制。")
                .defineInRange("botaniaManaPoolBatchLimit", 1024, 1, 1024);
        ENABLE_BOTANIA_ELVEN_TRADE_INPUT_BUFFER = s
                .comment("启用精灵门交易输入预载",
                        "每次仍完成一份配方并消耗 500 魔力，所有声明的产物统一结算。")
                .define("enableBotaniaElvenTradeInputBuffer", true);
        BOTANIA_ELVEN_TRADE_INPUT_BUFFER_LIMIT = s
                .comment("精灵门交易输入预载操作上限",
                        "单台机器一次预载的最大逻辑操作数。每次仍完成一份配方并消耗 500 魔力，所有声明的产物统一结算。")
                .defineInRange("botaniaElvenTradeInputBufferLimit", 64, 1, 64);
        EMBERS_INFER_MAX_ATTEMPTS = s
                .comment("余烬炼金最大推断次数",
                        "推断模式最多尝试的次数；失败会按余烬原有机制消耗部分材料。")
                .defineInRange("embersInferMaxAttempts", 20, 5, 200);
        EMBERS_INFER_ZERO_BLACK_LIMIT = s
                .comment("余烬炼金连续零黑针上限",
                        "连续达到该次数的零黑针结果时提前终止推断；零黑针表示没有任何要素位置正确。")
                .defineInRange("embersInferZeroBlackLimit", 5, 3, 50);
        EMBERS_LOCK_TIMEOUT_MINUTES = s
                .comment("余烬炼金锁超时（分钟）",
                        "炼金台锁超过此时间后自动释放，避免玩家中途断线导致永久占用。")
                .defineInRange("embersLockTimeoutMinutes", 10, 1, 60);
        EMBERS_PROGRESS_TIMEOUT_TICKS = s
                .comment("余烬炼金加工超时（tick）",
                        "等待炼金台完成加工的最长时间；20 tick 为 1 秒。")
                .defineInRange("embersProgressTimeoutTicks", 600, 100, 2400);
        s.pop();

        s.comment("自动合成").push("autoCrafting");
        FREE_WATER_MACHINES = s
                .comment("免费补水的机器类型",
                        "允许免费补水的机器类型列表。清空列表可关闭所有免费补水。",
                        "未列出的机器从 RS 流体存储扣除补入的水；已有的水可以继续使用。",
                        "Supported: farmersrespite_kettle, youkaishomecoming_kettle, youkaishomecoming_ferment,",
                        "youkaishomecoming_moka, youkaishomecoming_steamer, irons_spellbooks_alchemist_cauldron,",
                        "eidolon_crucible, botania_petal_apothecary, wizards_reborn_alchemy.",
                        "摩卡壶、蒸锅和花药台每次从无水变为有水时消耗 1000 mB，其余机器按缺水量扣除。")
                .defineListAllowEmpty("freeWaterMachines", DEFAULT_FREE_WATER_MACHINES,
                        value -> value instanceof String name && name.matches("[a-z0-9_]+"));
        ENABLE_CATALYST_RECIPE_PREFERENCE = s
                .comment("优先可重复使用催化剂配方",
                        "自动识别 CraftTweaker 的 .reuse() 输入；无法解析催化剂路线时仍可使用普通配方。")
                .define("enableCatalystRecipePreference", true);
        CATALYST_RECIPE_PREFERENCE_BONUS = s
                .comment("催化剂配方评分加成",
                        "给可复用催化剂配方及其直接下游配方增加评分；其他自定义评分仍压过该路线时可调高。")
                .defineInRange("catalystRecipePreferenceBonus",
                        DEFAULT_CATALYST_RECIPE_PREFERENCE_BONUS, 0, 100000);
        PREFERRED_RECIPES = s
                .comment("优先配方",
                        "填写配方 ID；同一产物的优先配方获得 10000 分加成。")
                .defineList("preferredRecipes", List.of(), obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        PREFERRED_INGREDIENT_VARIANTS = s
                .comment("优先材料变体",
                        "填写物品 ID，按顺序优先选择配方接受的变体；已有可用库存优先于新合成，明确锁定的材料仍是硬性限制。")
                .defineList("preferredIngredientVariants", DEFAULT_PREFERRED_INGREDIENT_VARIANTS,
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        MULTIBLOCK_CRAFT_TIMEOUT_SECONDS = s
                .comment("多方块合成超时（秒）",
                        "超过等待时间后终止该合成链并执行回收。")
                .defineInRange("multiblockCraftTimeoutSeconds", 300, 10, 600);
        CRAFTING_CHAIN_GLOBAL_TIMEOUT_SECONDS = s
                .comment("合成链无进展超时（秒）",
                        "完成操作、发布产物及完成节点会重置计时；持续有进展的长任务可超过此时长。终止时只退回尚未派发或已结算的材料，不退款已投入机器的材料。")
                .defineInRange("craftingChainGlobalTimeoutSeconds", 900, 60, 3600);
        MULTIBLOCK_RECIPE_BLACKLIST = s
                .comment("多方块配方黑名单",
                        "填写 modid:recipe_id；名单中的配方禁止参与自动合成。")
                .defineList("multiblockRecipeBlacklist", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        MULTIBLOCK_RECIPE_ALLOWLIST = s
                .comment("多方块配方白名单",
                        "非空时仅允许这些配方；空列表表示允许所有不在黑名单中的配方。")
                .defineList("multiblockRecipeAllowlist", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        REPEAT_COUNT_MAX = s
                .comment("计划重复次数上限",
                        "提高上限允许更大的合成任务，也会增加服务端负载。")
                .defineInRange("repeatCountMax", REPEAT_COUNT_DEFAULT,
                        1, REPEAT_COUNT_ABSOLUTE_MAX);
        CRAFTING_MAX_DEPTH = s
                .comment("合成递归深度上限",
                        "限制连续解析的子配方层数；深层整合包可提高，服务端严格限流时可降低。")
                .defineInRange("craftingMaxDepth", 16, 4, 32);
        CRAFTING_MAX_STEPS = s
                .comment("单次计划步骤上限",
                        "限制单个计划的总步骤，避免过大任务耗尽资源；上限与合成进度网络协议一致。")
                .defineInRange("craftingMaxSteps", 4096, 256, 4096);
        CRAFTING_PLANNING_WORKERS = s
                .comment("后台规划线程数",
                        "只执行不可变预览规划，世界、方块实体和 RS 修改仍在服务端线程；配置重载后生效。")
                .defineInRange("craftingPlanningWorkers",
                        DEFAULT_CRAFTING_PLANNING_WORKERS,
                        CraftingPlanningConfig.MIN_WORKERS, CraftingPlanningConfig.MAX_WORKERS);
        CRAFTING_PLANNING_QUEUE_CAPACITY = s
                .comment("后台规划等待队列上限",
                        "等待后台线程的预览数量；满队列时返回可重试的繁忙提示。")
                .defineInRange("craftingPlanningQueueCapacity",
                        DEFAULT_CRAFTING_PLANNING_QUEUE_CAPACITY,
                        CraftingPlanningConfig.MIN_QUEUE_CAPACITY,
                        CraftingPlanningConfig.MAX_QUEUE_CAPACITY);
        CRAFTING_PURE_SEARCH_MAX_STATES = s
                .comment("纯规划搜索状态上限",
                        "单次回溯搜索最多展开的状态数量；耗尽后返回未知结果，不回退到其他规划器。")
                .defineInRange("craftingPureSearchMaxStates",
                        DEFAULT_CRAFTING_PURE_SEARCH_MAX_STATES,
                        CraftingPlanningConfig.MIN_SEARCH_STATES,
                        CraftingPlanningConfig.MAX_SEARCH_STATES);
        CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES = s
                .comment("纯规划失败缓存上限",
                        "缓存已证实失败的状态，减少重复回溯；增大将占用更多临时内存，0 表示关闭。")
                .defineInRange("craftingPureSearchMaxMemoizedFailures",
                        DEFAULT_CRAFTING_PURE_SEARCH_MAX_MEMOIZED_FAILURES,
                        CraftingPlanningConfig.MIN_MEMOIZED_FAILURES,
                        CraftingPlanningConfig.MAX_MEMOIZED_FAILURES);
        CRAFTING_PURE_DEMAND_MAX_NODES = s
                .comment("纯需求树检查节点上限",
                        "库存感知路线检查最多访问的节点数量；达到上限时转交类型化解析器。")
                .defineInRange("craftingPureDemandMaxNodes",
                        DEFAULT_CRAFTING_PURE_DEMAND_MAX_NODES,
                        CraftingPlanningConfig.MIN_DEMAND_TREE_NODES,
                        CraftingPlanningConfig.MAX_DEMAND_TREE_NODES);
        CRAFTING_PURE_PLANNING_TIMEOUT_MS = s
                .comment("纯规划搜索预算（毫秒）",
                        "普通搜索从库存准备完成后开始计时，不含排队和路由时间；最大可合成探测共享搜索预算，超时返回未知。")
                .defineInRange("craftingPurePlanningTimeoutMs",
                        DEFAULT_CRAFTING_PURE_PLANNING_TIMEOUT_MS,
                        CraftingPlanningConfig.MIN_PURE_TIMEOUT_MS,
                        CraftingPlanningConfig.MAX_PURE_TIMEOUT_MS);
        CRAFTING_TYPED_PREVIEW_TIMEOUT_MS = s
                .comment("类型化预览执行预算（毫秒）",
                        "服务端线程解析预览的硬性时间预算；超时终止预览，不当作缺料。")
                .defineInRange("craftingTypedPreviewTimeoutMs",
                        DEFAULT_CRAFTING_TYPED_PREVIEW_TIMEOUT_MS,
                        CraftingPlanningConfig.MIN_TYPED_PREVIEW_TIMEOUT_MS,
                        CraftingPlanningConfig.MAX_TYPED_PREVIEW_TIMEOUT_MS);
        CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY = s
                .comment("类型化预览排队人数上限",
                        "每位玩家只保留最新请求。")
                .defineInRange("craftingTypedPreviewQueueCapacity",
                        DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_CAPACITY, 1, 256);
        CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK = s
                .comment("每 tick 接收类型化预览上限",
                        "类型化解析会读取实时世界和网络状态，建议保持较小的值。")
                .defineInRange("craftingTypedPreviewAdmissionsPerTick",
                        DEFAULT_CRAFTING_TYPED_PREVIEW_ADMISSIONS_PER_TICK, 1, 8);
        CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS = s
                .comment("类型化预览排队超时（毫秒）",
                        "排队请求的过期宽限时间，不是额外等待或执行预算；过期返回规划器繁忙提示。")
                .defineInRange("craftingTypedPreviewQueueTimeoutMs",
                        DEFAULT_CRAFTING_TYPED_PREVIEW_QUEUE_TIMEOUT_MS, 100, 10_000);
        ENABLE_CRAFTING_VARIANT_CONVERSION_GUARD = s
                .comment("防止材料变体循环转换",
                        "跳过对宽泛标签需求没有净收益的颜色和材质转换；明确目标产物的转换仍可使用。")
                .define("enableCraftingVariantConversionGuard", true);
        CRAFTING_PREVIEW_RATE_LIMIT_MS = s
                .comment("合成预览最小间隔（毫秒）",
                        "限制同一玩家发送合成预览请求的频率。")
                .defineInRange("craftingPreviewRateLimitMs",
                        DEFAULT_CRAFTING_PREVIEW_RATE_LIMIT_MS,
                        CraftingPreviewPolicy.MIN_RATE_LIMIT_MS,
                        CraftingPreviewPolicy.MAX_RATE_LIMIT_MS);
        CRAFTING_PLAN_CACHE_TTL_MS = s
                .comment("合成预览缓存有效期（毫秒）",
                        "复用预览计划的最长时间；使用前只重新校验该计划实际需要的材料。")
                .defineInRange("craftingPlanCacheTtlMs",
                        DEFAULT_CRAFTING_PLAN_CACHE_TTL_MS,
                        CraftingPreviewPolicy.MIN_CACHE_TTL_MS,
                        CraftingPreviewPolicy.MAX_CACHE_TTL_MS);
        CRAFTING_PLAN_CACHE_MAX_ENTRIES = s
                .comment("合成预览缓存数量上限",
                        "内存中最多保留的可复用预览计划数量。")
                .defineInRange("craftingPlanCacheMaxEntries",
                        DEFAULT_CRAFTING_PLAN_CACHE_MAX_ENTRIES,
                        CraftingPreviewPolicy.MIN_CACHE_MAX_ENTRIES,
                        CraftingPreviewPolicy.MAX_CACHE_MAX_ENTRIES);
        CRAFTING_RESOLVE_TIMEOUT_MS = s
                .comment("递归解析预算（毫秒）",
                        "单次计划解析的最长实际时间。深层或互相依赖配方可提高，但解析在服务端线程进行，过大会造成短暂卡顿。")
                .defineInRange("craftingResolveTimeoutMs",
                        DEFAULT_CRAFTING_RESOLVE_TIMEOUT_MS, 200, 10000);
        CRAFTING_MAX_ENSURE_CALLS = s
                .comment("材料递归调用上限",
                        "限制单个计划的递归材料解析次数；与解析超时共同防止失控递归。")
                .defineInRange("craftingMaxEnsureCalls",
                        DEFAULT_CRAFTING_MAX_ENSURE_CALLS, 1000, 100000);
        CRAFTING_VANILLA_OPERATIONS_PER_TICK = s
                .comment("单条合成链每 tick 工作台操作上限",
                        "限制每条合成链的同步原版合成次数，大任务分多个 tick 继续。")
                .defineInRange("craftingVanillaOperationsPerTick",
                        DEFAULT_CRAFTING_VANILLA_OPERATIONS_PER_TICK, 1, 256);
        CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK = s
                .comment("全局每 tick 工作台操作上限",
                        "所有活动合成链共享的原版合成次数预算，管理器公平分配。")
                .defineInRange("craftingGlobalVanillaOperationsPerTick",
                        DEFAULT_CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK, 1, 1024);
        CRAFTING_SERVER_TICK_BUDGET_MS = s
                .comment("合成推进预算（毫秒 / tick）",
                        "达到时间预算后剩余任务延到后续 tick；无法中断已开始的第三方接口调用。")
                .defineInRange("craftingServerTickBudgetMs",
                        DEFAULT_CRAFTING_SERVER_TICK_BUDGET_MS, 1, 40);
        CRAFTING_OPERATIONS_PER_DISPATCH = s
                .comment("单次机器派发操作上限",
                        "限制单次非原版派发的预留、存储调用和产物工作量；大节点使用分 tick 执行器，已适配机器可提升到一次有界实体批次。")
                .defineInRange("craftingOperationsPerDispatch",
                        DEFAULT_CRAFTING_OPERATIONS_PER_DISPATCH, 1, 256);
        CRAFTING_SETTLEMENT_STACKS_PER_TICK = s
                .comment("单条合成链每 tick 结算堆叠上限",
                        "已完成产物分多个 tick 送回，避免大任务阻塞终端交互。")
                .defineInRange("craftingSettlementStacksPerTick",
                        DEFAULT_CRAFTING_SETTLEMENT_STACKS_PER_TICK, 1, 256);
        CRAFTING_COMPLETION_CALLBACKS_PER_TICK = s
                .comment("每 tick 完成回调上限",
                        "限制完成回调及其后续批次调度，维持服务端响应。")
                .defineInRange("craftingCompletionCallbacksPerTick",
                        DEFAULT_CRAFTING_COMPLETION_CALLBACKS_PER_TICK, 1, 32);
        CRAFTING_MAX_CONCURRENT_GRAPH_NODES = s
                .comment("并行配方节点上限",
                        "只派发不存在材料、机器或捕获冲突的节点；1 表示串行，增大可利用多台机器。")
                .defineInRange("craftingMaxConcurrentGraphNodes", 4, 1, 16);
        CRAFTING_GRAPH_DISPATCH_PER_TICK = s
                .comment("单条任务每 tick 新节点上限",
                        "限制一次合成在一个 tick 中派发的新图节点数量；接收重试不计入。")
                .defineInRange("craftingGraphDispatchPerTick", 4, 1, 16);
        CRAFTING_GRAPH_DISPATCH_PER_CRAFT = s
                .comment("单次合成节点派发总上限",
                        "防止重试或回调错误无限派发；常规大计划应高于步骤上限。")
                .defineInRange("craftingGraphDispatchPerCraft", 8192, 16, 32768);
        CRAFTING_MAX_CONCURRENT_OPERATIONS = s
                .comment("单次合成并发机器操作上限",
                        "普通节点计一次，平行组每个运行中的工作单元计一次。")
                .defineInRange("craftingMaxConcurrentOperations", 8, 1, 64);
        CRAFTING_OPERATION_DISPATCH_PER_CRAFT = s
                .comment("单次合成机器启动总上限",
                        "限制机器实际操作启动次数，委托启动之前的重试不计入。")
                .defineInRange("craftingOperationDispatchPerCraft", 16384, 16, 65536);
        CRAFTING_PROBABILISTIC_ATTEMPT_MULTIPLIER = s
                .comment("概率材料尝试次数倍数",
                        "递归概率材料的最大尝试次数倍数，以最初计划的执行次数为基数。",
                        "达到上限时终止并保留真实产物，不退还已消耗材料。")
                .defineInRange("craftingProbabilisticAttemptMultiplier", 16, 1, 1024);
        CRAFTING_PROBABILISTIC_MAX_ATTEMPTS = s
                .comment("概率材料尝试次数绝对上限",
                        "每个递归概率材料目标的绝对尝试次数上限，同时受全任务操作预算限制。")
                .defineInRange("craftingProbabilisticMaxAttempts", 4096, 1, 65536);
        CRAFTING_PARALLEL_DISABLED_MODS = s
                .comment("禁止并行的模组或委托类型",
                        "填写类型 ID，例如 malum、goety；强制对应图节点独占运行，覆盖委托自身的并发声明。")
                .defineList("craftingParallelDisabledMods", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str + ":dummy") != null);
        CRAFTING_PARALLEL_DELEGATE_POLICIES = s
                .comment("模组或委托并行策略",
                        "格式为 id=AUTO、id=OFF 或 id=FORCE_WITH_GUARDS；完整或简短委托类名比模组类型更具体，强制模式也不会绕过安全检查。")
                .defineList("craftingParallelDelegatePolicies", List.of(),
                        obj -> obj instanceof String str && str.contains("="));
        RECIPE_TREE_MAX_DEPTH = s
                .comment("配方树深度上限",
                        "限制客户端配方树显示的嵌套层数。")
                .defineInRange("recipeTreeMaxDepth", 16, 4, 32);
        RECIPE_TREE_MAX_NODES = s
                .comment("配方树节点上限",
                        "限制显示节点数量，避免渲染占用过多客户端资源。")
                .defineInRange("recipeTreeMaxNodes", 512, 64, 4096);
        RECIPE_TREE_BATCH_DEBOUNCE_MS = s
                .comment("配方树数量防抖（毫秒）",
                        "批次数量滚动停止后等待的时间；越短越灵敏，越长服务端请求越少。")
                .defineInRange("recipeTreeBatchDebounceMs", 300, 100, 2000);
        RECIPE_TREE_MAX_CANDIDATES = s
                .comment("配方树备选配方上限",
                        "每个节点下拉列表最多显示的配方数量；超出的配方会被隐藏并标记为受限。")
                .defineInRange("recipeTreeMaxCandidates", 8, 2, 32);
        REQUIRE_RS_NETWORK_FOR_RECIPE_TREE = s
                .comment("配方树预览要求 RS 网络",
                        "关闭后可仅用玩家背包预览，无需无线终端；实际合成仍进行正常网络校验。")
                .define("requireRsNetworkForRecipeTree", false);
        PROTECTED_ITEMS = s
                .comment("递归合成保留物品",
                        "填写物品 ID；配方消耗这些物品前先补足，确保合成后仍保留指定数量。")
                .defineList("protectedItems", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        PROTECTED_RESERVE = s
                .comment("保留物品最低数量",
                        "对保留物品列表中的每种物品设置最低保有数量。")
                .defineInRange("protectedReserve", 2, 1, 1024);
        s.pop();

        s.comment("容器传输").push("containerTransfer");
        CONTAINER_TRANSFER_KEY = s
                .comment("容器传输按键码",
                        "使用 GLFW 按键码，默认 F 为 70。")
                .defineInRange("containerTransferKey", 70, 32, 348);
        s.pop();

        s.comment("侧边面板").push("sidePanel");
        RS_SIDE_PANEL_KEY = s
                .comment("侧边面板按键码",
                        "使用 GLFW 按键码，默认 Y 为 89。")
                .defineInRange("rsSidePanelKey", 89, 32, 348);
        RS_SIDE_PANEL_MAX_SLOTS = s
                .comment("侧边面板最大显示槽位",
                        "降低可减少网络流量和客户端内存占用。")
                .defineInRange("rsSidePanelMaxSlots", 256, 36, 1024);
        SIDE_PANEL_SYNC_INTERVAL = s
                .comment("侧边面板完整同步间隔（tick）",
                        "默认 300 tick 为 15 秒；越短响应越快，网络流量越大。")
                .defineInRange("sidePanelSyncInterval", 300, 20, 1200);
        SIDE_PANEL_EXTRACTION_TIMEOUT = s
                .comment("侧边面板取出超时（毫秒）",
                        "从网络取出物品操作的最长等待时间。")
                .defineInRange("sidePanelExtractionTimeout", 2000, 500, 10000);
        s.pop();

        s.comment("铁砧记忆").push("anvilMemory");
        ENABLE_ANVIL_MEMORY = s
                .comment("启用铁砧记忆",
                        "为支持的铁砧提供按玩家保存的材料记忆及补货。")
                .define("enabled", true);
        ANVIL_MEMORY_RESTOCK_TARGET = s
                .comment("铁砧自动补货目标数量",
                        "点击记忆条目时的材料目标数量，实际数量不超过物品堆叠上限。")
                .defineInRange("restockTarget", 64, 1, 64);
        ANVIL_MEMORY_REMEMBER_NBT = s
                .comment("记忆物品 NBT",
                        "将物品相同但 NBT 不同的材料记为不同条目。")
                .define("rememberNbt", true);
        ANVIL_MEMORY_PREFER_PLAYER_INVENTORY = s
                .comment("优先使用玩家背包物品",
                        "先使用玩家背包中的匹配材料，再从网络取出。")
                .define("preferPlayerInventory", true);
        ANVIL_MEMORY_BOOKMARK_MISSING = s
                .comment("自动收藏缺少的物品",
                        "通知客户端把仍缺少的材料加入 JEI 书签。")
                .define("bookmarkMissing", true);
        ANVIL_MEMORY_IPN_COMPAT = s
                .comment("兼容 IPN 快速重命名",
                        "IPN 快速重命名恢复玩家背包后，从 RS 补足材料槽的剩余缺口。")
                .define("ipnFastRenameCompat", true);
        ANVIL_MEMORY_ADAPTERS = s
                .comment("启用的铁砧适配器",
                        "填写启用的适配器 ID；模组兼容可在代码中注册更多适配器。")
                .defineList("adapters", DEFAULT_ANVIL_MEMORY_ADAPTERS,
                        value -> value instanceof String id && !id.isBlank());
        s.pop();

        s.comment("请求限流").push("rateLimits");
        GUI_OPEN_RATE_LIMIT_MS = s
                .comment("远程界面请求最小间隔（毫秒）",
                        "按玩家限制机器和支持的背包远程打开请求频率。")
                .defineInRange("guiOpenRateLimitMs",
                        DEFAULT_GUI_OPEN_RATE_LIMIT_MS,
                        GuiTimingConfig.MIN_OPEN_RATE_LIMIT_MS,
                        GuiTimingConfig.MAX_OPEN_RATE_LIMIT_MS);
        s.pop();

        s.comment("远程机器").push("remoteMachineGui");
        MACHINE_TAB_THRESHOLD = s
                .comment("机器快捷页签折叠阈值",
                        "机器快捷页签超过该数量时折叠为机器中心按钮；0 表示始终使用机器中心。")
                .defineInRange("machineTabThreshold", 0, 0, 64);
        MACHINE_HUB_TOGGLE_KEY = s
                .comment("机器中心按键码",
                        "在 RS 网格中切换机器中心面板，GLFW 按键码默认 H 为 72。")
                .defineInRange("machineHubToggleKey", 72, 32, 348);
        s.pop();

        s.comment("高级设置").push("advanced");
        s.pop();

        SERVER_SPEC = s.build();

        //  CLIENT

        ForgeConfigSpec.Builder cl = new ForgeConfigSpec.Builder();
        cl.comment("侧边面板").push("sidePanel");
        RS_SIDE_PANEL_X = cl.comment("侧边面板水平位置",
                        "侧边面板左边缘的水平屏幕坐标，单位为像素。")
                .defineInRange("x", 100, 0, 4000);
        RS_SIDE_PANEL_Y = cl.comment("侧边面板垂直位置",
                        "侧边面板上边缘的垂直屏幕坐标，单位为像素。")
                .defineInRange("y", 100, 0, 4000);
        RS_SIDE_PANEL_WIDTH = cl
                .comment("侧边面板宽度（0 为默认）",
                        "侧边面板宽度，单位为像素；0 使用 RS 默认宽度 247。")
                .defineInRange("width", 0, 0, 600);
        RS_SIDE_PANEL_HEIGHT = cl
                .comment("侧边面板高度（0 为自动）",
                        "侧边面板高度，单位为像素；0 按网格行数自动计算。")
                .defineInRange("height", 0, 0, 600);
        RS_SIDE_PANEL_HIDDEN = cl
                .comment("折叠侧边面板",
                        "将侧边面板折叠为窄条。")
                .define("hidden", false);
        SIDE_PANEL_NAVIGATION_TIMEOUT_MS = cl
                .comment("远程界面返回等待（毫秒）",
                        "从远程机器界面返回 RS 网格的最长等待时间；超时丢弃旧导航状态。")
                .defineInRange("navigationTimeoutMs",
                        DEFAULT_SIDE_PANEL_NAVIGATION_TIMEOUT_MS,
                        GuiTimingConfig.MIN_NAVIGATION_TIMEOUT_MS,
                        GuiTimingConfig.MAX_NAVIGATION_TIMEOUT_MS);
        cl.pop();
        cl.comment("自动进食").push("autoEat");
        AUTO_EAT_MENU_EXPANDED = cl
                .comment("展开自动进食菜单",
                        "记住自动进食菜单是否展开。")
                .define("menuExpanded", false);
        cl.pop();
        cl.comment("JEI 标记").push("jeiOverlay");
        JEI_NETWORK_OVERLAY_SCALE = cl
                .comment("JEI 网络库存字号比例",
                        "JEI 物品槽右下角白色网络库存数字的缩放比例，只影响本地显示。")
                .defineInRange("networkCountScale", 0.70D, 0.40D, 1.00D);
        JEI_CRAFTING_SHORTAGE_OVERLAY_SCALE = cl
                .comment("JEI 合成缺料字号比例",
                        "JEI 物品槽左上角红色缺料数字的最大缩放比例；过长数字仍会自动缩小。")
                .defineInRange("craftingShortageScale", 0.75D, 0.40D, 1.00D);
        cl.pop();
        cl.comment("Tetra 预览").push("tetra");
        TETRA_JEI_PREVIEW_CACHE = cl
                .comment("保留 JEI 材料悬停预览",
                        "鼠标离开 JEI 材料后保留最近的悬停预览；目标、槽位、蓝图或工作台变化时清除。")
                .define("keepJeiMaterialPreview", true);
        cl.pop();
        cl.comment("网格搜索").push("gridSearch");
        GRID_SEARCH_IDLE_BUDGET_MICROS = cl
                .comment("后台预热预算（微秒 / tick）",
                        "RS 网格未执行特殊搜索时，每个客户端 tick 用于后台预热搜索索引的时间预算。",
                        "范围：100-10000 微秒。")
                .defineInRange("idleBudgetMicros", DEFAULT_GRID_SEARCH_IDLE_BUDGET_MICROS,
                        100, 10_000);
        GRID_SEARCH_ACTIVE_BUDGET_MICROS = cl
                .comment("搜索计算预算（微秒 / tick）",
                        "正在等待 @、# 或 $ 搜索时，每个客户端 tick 用于索引和匹配的时间预算。",
                        "范围：500-10000 微秒。")
                .defineInRange("activeBudgetMicros", DEFAULT_GRID_SEARCH_ACTIVE_BUDGET_MICROS,
                        500, 10_000);
        GRID_SEARCH_DEBOUNCE_MS = cl
                .comment("输入防抖等待（毫秒）",
                        "输入停止后开始执行特殊搜索的等待时间，避免每输入一个字符都重复扫描。",
                        "范围：0-500 毫秒。")
                .defineInRange("debounceMs", DEFAULT_GRID_SEARCH_DEBOUNCE_MS, 0, 500);
        GRID_SEARCH_PARTIAL_REFRESH_MS = cl
                .comment("部分结果刷新间隔（毫秒）",
                        "# 搜索索引尚未完成时，部分结果刷新到网格的最小间隔。",
                        "较小的值响应更快，较大的值可减少网格重排。范围：16-500 毫秒。")
                .defineInRange("partialRefreshMs", DEFAULT_GRID_SEARCH_PARTIAL_REFRESH_MS,
                        16, 500);
        GRID_SEARCH_QUERY_CACHE_ENTRIES = cl
                .comment("搜索结果缓存条数",
                        "保留的 @、#、$ 单项搜索结果数量，用于重复搜索和继续输入时复用结果。",
                        "范围：8-128。")
                .defineInRange("queryCacheEntries", DEFAULT_GRID_SEARCH_QUERY_CACHE_ENTRIES,
                        8, 128);
        GRID_SEARCH_EMPTY_SNAPSHOT_GRACE_MS = cl
                .comment("空快照保留等待（毫秒）",
                        "RS 重建网格视图时，保留上一份非空搜索快照的等待时间。",
                        "用于忽略短暂的空列表，避免清空并重建全部索引。范围：0-2000 毫秒。")
                .defineInRange("emptySnapshotGraceMs",
                        DEFAULT_GRID_SEARCH_EMPTY_SNAPSHOT_GRACE_MS, 0, 2_000);
        GRID_SEARCH_CANDIDATE_INDEX_MAX_PERCENT = cl
                .comment("倒排候选占比上限（%）",
                        "倒排索引候选占当前可搜索条目的比例上限。",
                        "超过该比例时直接分片扫描，避免为接近全集的候选额外复制和求交。范围：10-100。")
                .defineInRange("candidateIndexMaxPercent",
                        DEFAULT_GRID_SEARCH_CANDIDATE_INDEX_MAX_PERCENT, 10, 100);
        GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS = cl
                .comment("候选索引重建间隔（毫秒）",
                        "搜索文本开始成批就绪后，按多长间隔在后台重建倒排索引。",
                        "连续预热期间会合并更新，同时保留动态结果，避免为每个物品重复发布。范围：0-2000 毫秒。")
                .defineInRange("candidateRebuildDelayMs",
                        DEFAULT_GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS, 0, 2_000);
        GRID_SEARCH_PINYIN_WORKERS = cl
                .comment("拼音索引后台线程数",
                        "用于生成 # 搜索拼音索引的后台线程数。",
                        "Minecraft tooltip 本身仍在客户端线程采集；只有纯文本转拼音在后台运行。",
                        "范围：1-4。")
                .defineInRange("pinyinWorkers", DEFAULT_GRID_SEARCH_PINYIN_WORKERS, 1, 4);
        GRID_SEARCH_DISK_CACHE_ENABLED = cl
                .comment("启用磁盘搜索缓存",
                        "将已经完成的 RS tooltip 与拼音搜索文本保存到本地磁盘。",
                        "再次启动客户端时可直接复用，避免每次进入世界都重新冷预热。")
                .define("diskCacheEnabled", true);
        GRID_SEARCH_DISK_CACHE_ENTRIES = cl
                .comment("磁盘搜索缓存条目上限",
                        "磁盘搜索缓存最多保留的物品/流体变体数量。",
                        "缓存键包含类型、注册名和 NBT，不包含数量。范围：1000-100000。")
                .defineInRange("diskCacheEntries", DEFAULT_GRID_SEARCH_DISK_CACHE_ENTRIES,
                        1_000, 100_000);
        GRID_SEARCH_DISK_CACHE_MAX_MIB = cl
                .comment("磁盘搜索缓存容量（MiB）",
                        "压缩搜索缓存允许使用的最大磁盘空间。范围：4-256 MiB。")
                .defineInRange("diskCacheMaxMiB", DEFAULT_GRID_SEARCH_DISK_CACHE_MAX_MIB,
                        4, 256);
        GRID_SEARCH_DISK_CACHE_SAVE_DELAY_MS = cl
                .comment("磁盘搜索缓存保存等待（毫秒）",
                        "最后一次补全 tooltip 后延迟多久异步写回磁盘。",
                        "较长的延迟可以合并连续写入。范围：500-30000 毫秒。")
                .defineInRange("diskCacheSaveDelayMs",
                        DEFAULT_GRID_SEARCH_DISK_CACHE_SAVE_DELAY_MS, 500, 30_000);
        LIGHTWEIGHT_SLASHBLADE_LIST_RENDERING = cl
                .comment("轻量显示拔刀剑图标",
                        "在 RS 网格和 JEI 物品列表中使用轻量的 SlashBlade 图标渲染。",
                        "保留刀身与纹理，但省略发光、3D 耐久装饰和附魔的重复模型绘制。")
                .define("lightweightSlashBladeRendering", false);
        cl.pop();
        cl.comment("搜索历史").push("recentSearch");
        RS_RECENT_SEARCH_ENABLED = cl
                .comment("显示最近搜索记录",
                        "在 RS 网格搜索框下方显示按世界或服务器隔离的最近搜索记录。")
                .define("enabled", true);
        RS_RECENT_SEARCH_MAX_VISIBLE_ENTRIES = cl
                .comment("搜索记录显示条数",
                        "搜索浮层最多显示的历史记录数量。范围：1-20。")
                .defineInRange("maxVisibleEntries",
                        DEFAULT_RECENT_SEARCH_MAX_VISIBLE_ENTRIES, 1, 20);
        RS_RECENT_SEARCH_MAX_STORED_ENTRIES = cl
                .comment("搜索记录保存条数",
                        "每个玩家在每个世界或服务器最多保存的搜索记录数量。范围：10-1000。",
                        "达到上限时优先删除最旧的非收藏记录。")
                .defineInRange("maxStoredEntries",
                        DEFAULT_RECENT_SEARCH_MAX_STORED_ENTRIES, 10, 1_000);
        RS_RECENT_SEARCH_FAVORITES_ENABLED = cl
                .comment("允许收藏搜索记录",
                        "允许收藏搜索记录并将收藏项显示在普通记录之前。")
                .define("favoritesEnabled", true);
        RS_RECENT_SEARCH_DELETE_BUTTONS_ENABLED = cl
                .comment("显示搜索记录删除按钮",
                        "在搜索历史浮层中显示单条删除和清空按钮。")
                .define("deleteButtonsEnabled", true);
        cl.pop();
        cl.comment("遥远世界").push("distantWorlds");
        ENABLE_DISTANT_WORLDS_HUD = cl
                .comment("显示祭坛状态",
                        "看向核心时显示祭坛状态。")
                .define("enableHud", true);
        cl.pop();
        CLIENT_SPEC = cl.build();
    }

    private RSIntegrationConfig() {}

    public static int migrateRepeatCountMax(int schema, int currentValue) {
        return schema < 2 && currentValue == 64
                ? REPEAT_COUNT_DEFAULT : currentValue;
    }

    public static List<? extends String> migrateFreeWaterMachines(int schema, List<? extends String> currentValue) {
        List<String> oldDefaults = DEFAULT_FREE_WATER_MACHINES.subList(0, DEFAULT_FREE_WATER_MACHINES.size() - 1);
        return schema < 7 && currentValue.equals(oldDefaults) ? DEFAULT_FREE_WATER_MACHINES : currentValue;
    }

    public static List<? extends String> migrateAnvilMemoryAdapters(
            int schema, List<? extends String> currentValue) {
        return schema < 3 && currentValue.equals(List.of("minecraft_anvil"))
                ? DEFAULT_ANVIL_MEMORY_ADAPTERS : currentValue;
    }

    public static List<? extends String> migratePassiveTickItems(
            List<? extends String> currentValue) {
        return currentValue.equals(LEGACY_DEFAULT_PASSIVE_TICK_ITEMS)
                ? DEFAULT_PASSIVE_TICK_ITEMS : currentValue;
    }

    public static int migrateTypedPreviewTimeoutMs(int schema, int currentValue) {
        if (schema < 4 && currentValue == 50) {
            return DEFAULT_CRAFTING_TYPED_PREVIEW_TIMEOUT_MS;
        }
        return schema < 6 && currentValue == 400
                ? DEFAULT_CRAFTING_TYPED_PREVIEW_TIMEOUT_MS : currentValue;
    }

    public static int migratePurePlanningTimeoutMs(int schema, int currentValue) {
        return schema < 6 && currentValue == 500
                ? DEFAULT_CRAFTING_PURE_PLANNING_TIMEOUT_MS : currentValue;
    }

    public static int migratePlanCacheTtlMs(int schema, int currentValue) {
        return schema < 5 && currentValue == 500
                ? DEFAULT_CRAFTING_PLAN_CACHE_TTL_MS : currentValue;
    }

    public static void saveClientConfig() {
        if (clientModConfig != null) clientModConfig.save();
    }

    /**
     * Returns the minimum reserve count for items matching the given ingredient,
     * or 0 if the item is not in the protected list.
     */
    public static int getProtectedReserve(Ingredient ingredient) {
        List<? extends String> items = PROTECTED_ITEMS.get();
        if (items.isEmpty()) return 0;
        int reserve = PROTECTED_RESERVE.get();
        for (String itemId : items) {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null) continue;
            if (ingredient.test(new ItemStack(item))) {
                return reserve;
            }
        }
        return 0;
    }

    public static void register() {
        var container = ModLoadingContext.get().getActiveContainer();
        container.addConfig(new ModConfig(ModConfig.Type.COMMON, COMMON_SPEC, container,
                "rs_integration/common.toml"));
        container.addConfig(new ModConfig(ModConfig.Type.SERVER, SERVER_SPEC, container,
                "rs_integration/server.toml"));
        clientModConfig = new ModConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, container,
                "rs_integration/client.toml");
        container.addConfig(clientModConfig);
    }
}
