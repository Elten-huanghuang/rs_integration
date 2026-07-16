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

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;
    private static ModConfig clientModConfig;

    // ── master switches ──────────────────────────────────────────
    public static ForgeConfigSpec.BooleanValue ENABLE_BINDING;
    public static ForgeConfigSpec.BooleanValue ENABLE_AUTO_CRAFTING;
    public static ForgeConfigSpec.BooleanValue ENABLE_MULTIBLOCK_AUTO_CRAFTING;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PREFERRED_RECIPES;
    public static ForgeConfigSpec.IntValue MULTIBLOCK_CRAFT_TIMEOUT_SECONDS;
    public static ForgeConfigSpec.IntValue CRAFTING_CHAIN_GLOBAL_TIMEOUT_SECONDS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> MULTIBLOCK_RECIPE_BLACKLIST;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> MULTIBLOCK_RECIPE_ALLOWLIST;

    // ── per-mod integration ──────────────────────────────────────
    public static ForgeConfigSpec.BooleanValue ENABLE_GOETY;
    public static ForgeConfigSpec.BooleanValue ENABLE_MALUM;
    public static ForgeConfigSpec.BooleanValue ENABLE_WIZARDS_REBORN;
    public static ForgeConfigSpec.BooleanValue ENABLE_FORBIDDEN_ARCANUS;
    public static ForgeConfigSpec.BooleanValue ENABLE_EIDOLON;
    public static ForgeConfigSpec.BooleanValue ENABLE_TOUHOU_LITTLE_MAID;
    public static ForgeConfigSpec.BooleanValue ENABLE_EMBERS_ALCHEMY;
    public static ForgeConfigSpec.BooleanValue ENABLE_AETHERWORKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_AETHER;
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
    public static ForgeConfigSpec.BooleanValue ENABLE_VANILLA_MACHINES;
    public static ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_FTB_QUEST_EXTERNAL_ITEM_PROGRESS;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI_MARQUEE_SELECTION;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION;
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_GRID_SWIPE_EXTRACT;
    public static ForgeConfigSpec.BooleanValue DEPOSIT_UPGRADE_RS;
    public static ForgeConfigSpec.BooleanValue ENABLE_MAJ_ACCESSORY_COMPRESSION;
    public static ForgeConfigSpec.BooleanValue ENABLE_MACHINE_GUI_TABS;
    // ── auto-eat ─────────────────────────────────────────────────
    public static ForgeConfigSpec.BooleanValue ENABLE_AUTO_EAT;
    public static ForgeConfigSpec.ConfigValue<String> AUTO_EAT_REQUIRED_EFFECT;
    public static ForgeConfigSpec.ConfigValue<String> AUTO_EAT_COST_ITEM;
    public static ForgeConfigSpec.IntValue AUTO_EAT_COST_PER_ITEM;
    public static ForgeConfigSpec.IntValue AUTO_EAT_MAX_PER_BATCH;

    public static ForgeConfigSpec.BooleanValue ENABLE_CONTAINER_TRANSFER;
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_SIDE_PANEL;
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_PASSIVE_EFFECTS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PASSIVE_TICK_ITEMS;
    public static ForgeConfigSpec.IntValue NINE_SWORD_MAX_COUNT;
    public static ForgeConfigSpec.BooleanValue DIAGNOSTIC_VERBOSE_LOGGING;

    // ── per-mod tuning (server, per-world) ───────────────────────
    public static ForgeConfigSpec.ConfigValue<String> CROCKPOT_FILLER_ITEM;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CROCKPOT_FUEL_PRIORITY;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> VANILLA_FURNACE_FUEL_PRIORITY;
    public static ForgeConfigSpec.IntValue EMBERS_INFER_MAX_ATTEMPTS;
    public static ForgeConfigSpec.IntValue EMBERS_INFER_ZERO_BLACK_LIMIT;
    public static ForgeConfigSpec.IntValue EMBERS_LOCK_TIMEOUT_MINUTES;
    public static ForgeConfigSpec.IntValue EMBERS_PROGRESS_TIMEOUT_TICKS;

    // ── numeric params ───────────────────────────────────────────
    public static ForgeConfigSpec.IntValue MACHINE_TAB_THRESHOLD;
    public static ForgeConfigSpec.IntValue MACHINE_HUB_TOGGLE_KEY;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CUSTOM_GUI_MACHINE_MODS;
    public static ForgeConfigSpec.IntValue REPEAT_COUNT_MAX;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PROTECTED_ITEMS;
    public static ForgeConfigSpec.IntValue PROTECTED_RESERVE;
    public static ForgeConfigSpec.IntValue CONTAINER_TRANSFER_KEY;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_KEY;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_MAX_SLOTS;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_SYNC_INTERVAL;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_EXTRACTION_TIMEOUT;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_DEPTH;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_STEPS;
    public static ForgeConfigSpec.IntValue CRAFTING_RESOLVE_TIMEOUT_MS;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_ENSURE_CALLS;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_CONCURRENT_GRAPH_NODES;
    public static ForgeConfigSpec.IntValue CRAFTING_GRAPH_DISPATCH_PER_TICK;
    public static ForgeConfigSpec.IntValue CRAFTING_GRAPH_DISPATCH_PER_CRAFT;
    public static ForgeConfigSpec.IntValue CRAFTING_MAX_CONCURRENT_OPERATIONS;
    public static ForgeConfigSpec.IntValue CRAFTING_OPERATION_DISPATCH_PER_CRAFT;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CRAFTING_PARALLEL_DISABLED_MODS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CRAFTING_PARALLEL_DELEGATE_POLICIES;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_MAX_DEPTH;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_MAX_NODES;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_BATCH_DEBOUNCE_MS;
    public static ForgeConfigSpec.IntValue RECIPE_TREE_MAX_CANDIDATES;

    // ── client-only ──────────────────────────────────────────────
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_X;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_Y;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_WIDTH;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_HEIGHT;
    public static ForgeConfigSpec.BooleanValue RS_SIDE_PANEL_HIDDEN;


    static {
        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder s = new ForgeConfigSpec.Builder();

        // ── COMMON: feature toggles ──────────────────────────────

        c.push("general");
        ENABLE_BINDING = c
                .comment("Master switch for all block-binding features (Shift+Right-click to bind RS networks to machines).",
                        "Disabling this turns off all binding-related functionality regardless of per-mod settings.")
                .define("enableBinding", true);
        ENABLE_AUTO_CRAFTING = c
                .comment("Allow the mod to automatically craft intermediate items via the RS network when direct materials are missing.",
                        "Disabling this means recipes will simply fail if all items are not already present.")
                .define("enableAutoCrafting", true);
        c.pop();

        c.push("integrations");
        ENABLE_GOETY = c
                .comment("Enable RS integration with Goety (Dark Altar remote crafting).")
                .define("enableGoety", true);
        ENABLE_MALUM = c
                .comment("Enable RS integration with Malum (Spirit Altar remote crafting).")
                .define("enableMalum", true);
        ENABLE_WIZARDS_REBORN = c
                .comment("Enable RS integration with Wizards Reborn (Wissen Crystallizer, Arcane Iterator, etc.).")
                .define("enableWizardsReborn", true);
        ENABLE_FORBIDDEN_ARCANUS = c
                .comment("Enable RS integration with Forbidden & Arcanus (Hephaestus Forge remote crafting).")
                .define("enableForbiddenArcanus", true);
        ENABLE_EIDOLON = c
                .comment("Enable RS integration with Eidolon Repraised (Crucible remote crafting).")
                .define("enableEidolon", true);
        ENABLE_TOUHOU_LITTLE_MAID = c
                .comment("Enable RS integration with Touhou Little Maid (Maid Altar remote crafting).")
                .define("enableTouhouLittleMaid", true);
        ENABLE_SLASHBLADE = c
                .comment("Enable RS integration with SlashBlade (crafting table recipes with NBT requirements).")
                .define("enableSlashblade", true);
        ENABLE_AVARITIA = c
                .comment("Enable RS integration with Avaritia (Dire Crafting Tables, Neutron Compressor,",
                        "Extreme Smithing Table, Neutron Collector, Chest, Tesseract, Anvil).")
                .define("enableAvaritia", true);
        ENABLE_CONFLUENCE = c
                .comment("Enable RS integration with TerraCurio (Confluence Workshop recursive crafting).")
                .define("enableConfluence", true);
        ENABLE_IMMORTERS_DELIGHT = c
                .comment("Enable RS integration with Immortaler's Delight (Enchantal Cooler remote crafting).")
                .define("enableImmortersDelight", true);
        ENABLE_FARMERSDELIGHT = c
                .comment("Enable RS integration with Farmer's Delight (Cooking Pot + Skillet remote crafting).")
                .define("enableFarmersDelight", true);
        ENABLE_YOUKAISHOMECOMING = c
                .comment("Enable RS integration with Youkai's Homecoming (Moka Pot + Steamer Pot remote crafting).")
                .define("enableYoukaisHomecoming", true);
        ENABLE_FARMERSRESPITE = c
                .comment("Enable RS integration with Farmer's Respite (Kettle fluid brewing).")
                .define("enableFarmersRespite", true);
        ENABLE_AETHERWORKS = c
                .comment("Enable RS integration with Embers Aetherworks Addon",
                        "(Aetherium Anvil remote crafting with auto-hammer support).")
                .define("enableAetherworks", true);
        ENABLE_AETHER = c
                .comment("Enable RS integration with Aether (Freezer, Incubator, Altar).")
                .define("enableAether", true);
        ENABLE_CROCKPOT = c
                .comment("Enable RS integration with CrockPot (Crock Pot and Portable Crock Pot).")
                .define("enableCrockPot", true);
        ENABLE_TACZ = c
                .comment("Enable RS integration with TACZ (Gun Smith Table recursive crafting).")
                .define("enableTacz", true);
        ENABLE_EMBERS_ALCHEMY = c
                .comment("Enable RS integration with Embers Rekindled (Alchemy Tablet remote crafting).")
                .define("enableEmbersAlchemy", true);
        ENABLE_EMBERS_ALCHEMY_CALC = c
                .comment("Enable Calculate mode for Embers Alchemy — shows the deterministic pedestal layout.",
                        "When disabled, only Infer (trial-and-error) mode is available.",
                        "Requires enableEmbersAlchemy=true.")
                .define("enableEmbersAlchemyCalculate", false);
        ENABLE_VANILLA_MACHINES = c
                .comment("Enable RS integration with vanilla machines (Furnace, Blast Furnace, Smoker,",
                        "Campfire, Stonecutter, Smithing Table). Allows binding and remote crafting",
                        "via these blocks.")
                .define("enableVanillaMachines", true);
        ENABLE_SOPHISTICATED_BACKPACKS = c
                .comment("Enable RS integration with Sophisticated Backpacks (RS-based upgrade items).")
                .define("enableSophisticatedBackpacks", true);
        ENABLE_FTB_QUEST_EXTERNAL_ITEM_PROGRESS = c
                .comment("Count items actually inserted by Sophisticated Backpack/RS upgrades toward",
                        "eligible non-consuming FTB Quests item tasks. Simulated and voided items are excluded.")
                .define("enableFtbQuestExternalItemProgress", true);
        ENABLE_JEI = c
                .comment("Show '+' buttons in JEI recipe views for remote crafting.",
                        "Client-side; the server value is synced to the client.")
                .define("enableJeiIntegration", true);
        ENABLE_JEI_MARQUEE_SELECTION = c
                .comment("Enable drag-to-select (marquee) in JEI ingredient list for batch bookmarking/hiding.",
                        "Disable if you find drag gestures interfere with your workflow.",
                        "Client-side; the server value is synced to the client.")
                .define("enableJeiMarqueeSelection", true);
        ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION = c
                .comment("Enable drag-to-select (marquee) in JEI bookmark panel for batch removal/hiding.",
                        "Works the same as ingredient list marquee, but on the left bookmark panel.",
                        "Client-side; the server value is synced to the client.")
                .define("enableJeiBookmarkMarqueeSelection", true);
        ENABLE_RS_GRID_SWIPE_EXTRACT = c
                .comment("Enable mouse swipe-to-extract on the RS grid.",
                        "Hold Ctrl and drag across grid slots to extract one of each item you pass over.",
                        "Disable if you find this gesture interferes with your normal grid usage.",
                        "Client-side; the server value is synced to the client.")
                .define("enableRSGridSwipeExtract", true);
        ENABLE_FARMINGFORBLOCKHEADS = c
                .comment("Enable FarmingForBlockheads Market integration for recursive crafting.",
                        "Allows the Market to participate in JEI-to-RS auto-crafting chains.",
                        "When enabled, you can bind a Market block and use its exchange trades",
                        "as crafting steps in recursive plans.")
                .define("enableFarmingForBlockheads", true);
        c.pop();

        c.push("autoCrafting");
        ENABLE_MULTIBLOCK_AUTO_CRAFTING = c
                .comment("Allow multi-block machines (altars, forges, crucibles, etc.) to be used as intermediate",
                        "steps during recursive auto-crafting. When disabled, only vanilla crafting-table recipes",
                        "are used for intermediate items.")
                .define("enableMultiblockAutoCrafting", true);
        c.pop();

        c.push("sophisticated_backpacks");
        DEPOSIT_UPGRADE_RS = c
                .comment("Whether the backpack's Deposit Upgrade can push items into the RS network.",
                        "Enabled (true): deposit upgrade interacts with RS grids.",
                        "Disabled (false): deposit upgrade works as vanilla Sophisticated Backpacks.")
                .define("depositUpgradeRS", false);
        ENABLE_MAJ_ACCESSORY_COMPRESSION = c
                .comment("Enable Majrusz's Accessories compression via the Compacting Upgrade.",
                        "When enabled, the Compacting Upgrade also auto-combines MAJ accessories",
                        "that match the upgrade's whitelist filter. Accessories are combined",
                        "two at a time (max efficiency + another) until they reach 100%.")
                .define("enableMajAccessoryCompression", true);
        c.pop();

        c.push("passiveEffects");
        ENABLE_RS_PASSIVE_EFFECTS = c
                .comment("Enable RS passive effects system. Items stored in a bound RS network",
                        "that say \"works from inventory/hotbar\" will grant their passive effects",
                        "to the player as if carried in the inventory.",
                        "Three layers:",
                        "  Phase 1 — Attribute modifiers (zero-config, ~50-70% of items),",
                        "  Phase 2 — inventoryTick simulation (JSON whitelist, ~20-35% of items),",
                        "  Phase 3 — event-driven Mixin redirect (per-item, ~5-15% of items).",
                        "Disable this if you prefer vanilla inventory-only passive mechanics.")
                .define("enableRSPassiveEffects", true);
        PASSIVE_TICK_ITEMS = c
                .comment("Items whose inventoryTick should be simulated from the resonance disk.",
                        "Format: \"modid:item_id\" or \"modid:item_id|mutates\".",
                        "Items marked |mutates will use extract→tick→insert to persist NBT changes.",
                        "Items without |mutates will be ticked on a snapshot copy (read-only).")
                .defineList("passiveTickItems",
                        List.of("reliquary:pyromancer_staff|mutates", "enigmaticaddons:artificial_flower|mutates", "forbidden_arcanus:spectral_eye_amulet|mutates", "apotheosis:potion_charm|mutates"),
                        obj -> obj instanceof String && ((String) obj).contains(":"));
        NINE_SWORD_MAX_COUNT = c
                .comment("Maximum effective count of Nine Sword Books across inventory + resonance disk.",
                        "Books beyond this limit are ignored. Hotbar slots (9) is the vanilla maximum,",
                        "so this prevents exceeding it via the resonance disk.",
                        "Range: 1-36.")
                .defineInRange("nineSwordMaxCount", 9, 1, 36);
        c.pop();

        c.push("autoEat");
        ENABLE_AUTO_EAT = c
                .comment("Enable the auto-eat system on the RS Grid Screen.",
                        "Adds three buttons to the Grid Screen for automated eating from the RS network.",
                        "Modes: Diversity (SolCarrot), Stack (bulk eat), Diet (nutrition balance).")
                .define("enableAutoEat", true);
        AUTO_EAT_REQUIRED_EFFECT = c
                .comment("Required potion effect to use auto-eat. Format: \"modid:effect_id\".",
                        "Empty string = no requirement (always available).",
                        "Example: \"crockpot:gnaws_gift\" (requires CrockPot's Gnaw's Gift effect).")
                .define("requiredEffect", "");
        AUTO_EAT_COST_ITEM = c
                .comment("Item consumed from RS network per auto-eat execution. Format: \"modid:item_id\".",
                        "\"minecraft:air\" = no item cost.",
                        "Example: \"crockpot:gnaws_gift\".")
                .define("costItem", "minecraft:air");
        AUTO_EAT_COST_PER_ITEM = c
                .comment("How many cost items to consume per food item eaten.",
                        "0 = no cost. Range: 0-64.")
                .defineInRange("costPerItem", 0, 0, 64);
        AUTO_EAT_MAX_PER_BATCH = c
                .comment("Maximum number of food items eaten in a single batch.",
                        "Range: 1-1024.")
                .defineInRange("maxPerBatch", 128, 1, 1024);
        c.pop();

        c.push("containerTransfer");
        ENABLE_CONTAINER_TRANSFER = c
                .comment("Enable one-key container-to-RS transfer.",
                        "When any container GUI is open, press the configured key to transfer",
                        "all container items into the bound RS network.",
                        "Requires: a dimensional accessor and a bound RS network.")
                .define("enableContainerTransfer", true);
        c.pop();

        c.push("sidePanel");
        ENABLE_RS_SIDE_PANEL = c
                .comment("Enable the RS Side Panel — a foldable, draggable overlay showing RS network items on any screen.",
                        "Toggle with the configured hotkey while in-game.")
                .define("enableRSSidePanel", true);
        c.pop();

        c.push("remoteMachineGui");
        ENABLE_MACHINE_GUI_TABS = c
                .comment("Enable remote machine shortcut tabs on RS GridScreen.",
                        "When disabled, players cannot open remote machine GUIs from terminals.")
                .define("enableMachineGuiTabs", true);
        CUSTOM_GUI_MACHINE_MODS = c
                .comment("Mod IDs to register as GUI-type machines without writing any Java code.",
                        "Machines from these mods appear in the Machine Hub, can be remotely opened,",
                        "and do NOT support batch-crafting — this is purely for remote GUI access.",
                        "Mods that already have full module support (aether, crockpot, tacz, etc.)",
                        "should NOT be listed here — their modules handle binding automatically.",
                        "Example: [\"crabbersdelight\", \"metalbarrels\"]")
                .defineList("customGuiMachineMods", List.of("crabbersdelight", "metalbarrels", "pgp", "emxarms", "apotheosis", "ancientreforging"),
                        obj -> obj instanceof String);
        c.pop();

        c.push("advanced");
        DIAGNOSTIC_VERBOSE_LOGGING = c
                .comment("Enable verbose diagnostic logging for debugging.",
                        "WARNING: This may spam the server log. Only enable for troubleshooting.")
                .define("diagnosticVerboseLogging", false);
        c.pop();

        COMMON_SPEC = c.build();

        // ── SERVER: per-world tuning ─────────────────────────────

        s.push("integrations");
        CROCKPOT_FILLER_ITEM = s
                .comment("Default filler item for CrockPot recipes when input slots are not",
                        "fully occupied by the recipe's must-contain ingredients.",
                        "The pot needs all input slots filled to start cooking; this item",
                        "is used to pad the remaining slots.",
                        "Format: \"modid:item_id\". Default: \"minecraft:stick\".")
                .define("crockpotFillerItem", "minecraft:stick");
        CROCKPOT_FUEL_PRIORITY = s
                .comment("Preferred fuels for auto-refueling a Crock Pot, in priority order.",
                        "The first item the RS network can supply is used. When none of these",
                        "are available, the system falls back to any safe bulk fuel (never tools,",
                        "bows, container fuels like lava buckets, or NBT/enchanted items).",
                        "If the fuel slot already holds a valid fuel, nothing is inserted.",
                        "Format: \"modid:item_id\" per line.")
                .defineList("crockpotFuelPriority",
                        List.of("minecraft:coal", "minecraft:charcoal", "minecraft:coal_block"),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        VANILLA_FURNACE_FUEL_PRIORITY = s
                .comment("Preferred fuels for vanilla furnaces, blast furnaces, and smokers, in priority order.",
                        "When the fuel slot is empty, coal is tried before charcoal, then other safe fuels.",
                        "When the slot already contains fuel, only that same fuel type is topped up.",
                        "Automatic selection skips tools, container-return fuels, and items with NBT.",
                        "Format: \"modid:item_id\" per line.")
                .defineList("vanillaFurnaceFuelPriority",
                        List.of("minecraft:coal", "minecraft:charcoal"),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        EMBERS_INFER_MAX_ATTEMPTS = s
                .comment("Maximum trial-and-error attempts for Embers Alchemy inference mode.",
                        "Each failed attempt consumes some materials (per Embers' failure mechanics).",
                        "Range: 5-200.")
                .defineInRange("embersInferMaxAttempts", 20, 5, 200);
        EMBERS_INFER_ZERO_BLACK_LIMIT = s
                .comment("Consecutive zero-black-pin attempts before aborting Embers Alchemy inference.",
                        "A zero-black-pin result means no aspect is in the correct position.",
                        "After this many consecutive such failures, inference aborts early.",
                        "Range: 3-50.")
                .defineInRange("embersInferZeroBlackLimit", 5, 3, 50);
        EMBERS_LOCK_TIMEOUT_MINUTES = s
                .comment("Minutes before an Embers Alchemy tablet lock auto-expires.",
                        "Prevents permanent lock-up when a player disconnects mid-craft.",
                        "Range: 1-60.")
                .defineInRange("embersLockTimeoutMinutes", 10, 1, 60);
        EMBERS_PROGRESS_TIMEOUT_TICKS = s
                .comment("Maximum ticks (1 tick = 1/20 second) to wait for an Embers Alchemy",
                        "tablet to finish processing before timing out.",
                        "Range: 100-2400 (5s to 120s).")
                .defineInRange("embersProgressTimeoutTicks", 600, 100, 2400);
        s.pop();

        s.push("autoCrafting");
        PREFERRED_RECIPES = s
                .comment("Preferred recipe IDs for auto-crafting resolution.",
                        "When multiple recipes produce the same item, the preferred recipe gets a +10000 scoring bonus.",
                        "Format: one recipe ID per line, e.g. \"minecraft:oak_planks\".",
                        "Example: prefer 4-plank-from-log over 1-plank-from-log when crafting.")
                .defineList("preferredRecipes", List.of(), obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        MULTIBLOCK_CRAFT_TIMEOUT_SECONDS = s
                .comment("Maximum time (in seconds) to wait for a multi-block craft to complete.",
                        "If exceeded, the crafting chain is aborted and items are refunded.",
                        "Range: 10-600.")
                .defineInRange("multiblockCraftTimeoutSeconds", 300, 10, 600);
        CRAFTING_CHAIN_GLOBAL_TIMEOUT_SECONDS = s
                .comment("Maximum total time (in seconds) for an entire graph crafting chain.",
                        "This is a whole-chain ceiling on top of the per-node timeout: even if",
                        "individual nodes keep making progress, the chain is aborted once this",
                        "elapses, so a wedged or livelocked chain cannot run forever.",
                        "Materials already dispatched into a machine are NOT refunded (never duped);",
                        "only undispatched/settled materials are returned. Range: 60-3600.")
                .defineInRange("craftingChainGlobalTimeoutSeconds", 900, 60, 3600);
        MULTIBLOCK_RECIPE_BLACKLIST = s
                .comment("Multi-block recipe IDs that should NEVER be used for auto-crafting.",
                        "Format: \"modid:recipe_id\".")
                .defineList("multiblockRecipeBlacklist", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        MULTIBLOCK_RECIPE_ALLOWLIST = s
                .comment("If non-empty, ONLY these multi-block recipe IDs may be used for auto-crafting.",
                        "Format: \"modid:recipe_id\". Empty list = all recipes allowed (subject to blacklist).")
                .defineList("multiblockRecipeAllowlist", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        REPEAT_COUNT_MAX = s
                .comment("Maximum repeat count allowed in the CraftingPlanScreen.",
                        "Higher values allow more concurrent crafts but increase server load.",
                        "Range: 1-1024.")
                .defineInRange("repeatCountMax", 64, 1, 1024);
        CRAFTING_MAX_DEPTH = s
                .comment("Maximum recursion depth for crafting resolution.",
                        "Limits how many nested sub-recipes the resolver can chain.",
                        "Increase for deep modpack recipe chains; decrease for strict server limits.",
                        "Range: 4-32.")
                .defineInRange("craftingMaxDepth", 8, 4, 32);
        CRAFTING_MAX_STEPS = s
                .comment("Maximum total crafting steps in a single resolution plan.",
                        "Prevents runaway plans from consuming excessive server resources.",
                        "Range: 256-16384.")
                .defineInRange("craftingMaxSteps", 4096, 256, 16384);
        CRAFTING_RESOLVE_TIMEOUT_MS = s
                .comment("Maximum wall-clock time (ms) the crafting resolver may spend on one plan.",
                        "Deep, interdependent modpack recipe trees (e.g. self-referential 'upgrade'",
                        "recipes) can exhaust the default budget and falsely report a reachable",
                        "ingredient as missing. Increase for such packs; the resolver runs on the",
                        "server thread, so very large values can cause a brief hitch. Range: 200-10000.")
                .defineInRange("craftingResolveTimeoutMs", 2000, 200, 10000);
        CRAFTING_MAX_ENSURE_CALLS = s
                .comment("Maximum recursive ingredient-resolution calls per plan.",
                        "Companion cap to craftingResolveTimeoutMs guarding against runaway recursion.",
                        "Increase alongside the timeout for deep recipe trees. Range: 1000-100000.")
                .defineInRange("craftingMaxEnsureCalls", 10000, 1000, 100000);
        CRAFTING_MAX_CONCURRENT_GRAPH_NODES = s
                .comment("Maximum number of independent DAG recipe nodes that may run in parallel.",
                        "Set to 1 for serial execution (safest); increase for multi-machine speedup.",
                        "Only nodes with no material/machine/capture conflicts are dispatched.",
                        "Range: 1-16.")
                .defineInRange("craftingMaxConcurrentGraphNodes", 4, 1, 16);
        CRAFTING_GRAPH_DISPATCH_PER_TICK = s
                .comment("Maximum number of new graph nodes one craft may dispatch in a single tick.",
                        "Admission retries do not consume this budget. Range: 1-16.")
                .defineInRange("craftingGraphDispatchPerTick", 4, 1, 16);
        CRAFTING_GRAPH_DISPATCH_PER_CRAFT = s
                .comment("Maximum total graph-node dispatches in one craft run.",
                        "Prevents retry/callback bugs from dispatching an unbounded number of operations.",
                        "Set above craftingMaxSteps for normal large plans. Range: 16-32768.")
                .defineInRange("craftingGraphDispatchPerCraft", 8192, 16, 32768);
        CRAFTING_MAX_CONCURRENT_OPERATIONS = s
                .comment("Maximum machine-backed operations one craft may own concurrently.",
                        "A normal graph node costs one; a parallel group costs one per running worker.",
                        "Range: 1-64.")
                .defineInRange("craftingMaxConcurrentOperations", 8, 1, 64);
        CRAFTING_OPERATION_DISPATCH_PER_CRAFT = s
                .comment("Maximum machine-operation starts during one craft run.",
                        "Retries before delegate start do not consume this budget. Range: 16-65536.")
                .defineInRange("craftingOperationDispatchPerCraft", 16384, 16, 65536);
        CRAFTING_PARALLEL_DISABLED_MODS = s
                .comment("Mod/delegate type IDs that must always run as exclusive graph nodes.",
                        "This denylist overrides a delegate's concurrency capability declaration.",
                        "Example: [\"malum\", \"goety\"].")
                .defineList("craftingParallelDisabledMods", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str + ":dummy") != null);
        CRAFTING_PARALLEL_DELEGATE_POLICIES = s
                .comment("Optional per-mod or per-delegate graph concurrency policy overrides.",
                        "Format: id=AUTO|OFF|FORCE_WITH_GUARDS. A full or simple delegate class name",
                        "is more specific than a mod type ID. FORCE_WITH_GUARDS never bypasses safety guards.",
                        "Example: [\"avaritia=AUTO\", \"CrabTrapBatchDelegate=OFF\"].")
                .defineList("craftingParallelDelegatePolicies", List.of(),
                        obj -> obj instanceof String str && str.contains("="));
        RECIPE_TREE_MAX_DEPTH = s
                .comment("Maximum depth for the client-side recipe tree view.",
                        "Limits how many nested layers the tree renders.",
                        "Range: 4-32.")
                .defineInRange("recipeTreeMaxDepth", 16, 4, 32);
        RECIPE_TREE_MAX_NODES = s
                .comment("Maximum number of nodes in the recipe tree.",
                        "Prevents the tree renderer from consuming excessive client resources.",
                        "Range: 64-4096.")
                .defineInRange("recipeTreeMaxNodes", 512, 64, 4096);
        RECIPE_TREE_BATCH_DEBOUNCE_MS = s
                .comment("Batch count scroll debounce in milliseconds for the recipe tree.",
                        "Shorter = more responsive; longer = fewer server round-trips.",
                        "Range: 100-2000.")
                .defineInRange("recipeTreeBatchDebounceMs", 300, 100, 2000);
        RECIPE_TREE_MAX_CANDIDATES = s
                .comment("Maximum number of alternative recipes shown in a tree node's dropdown.",
                        "Nodes with more alternatives than this are marked 'limited' and the extras are hidden.",
                        "Range: 2-32.")
                .defineInRange("recipeTreeMaxCandidates", 8, 2, 32);
        PROTECTED_ITEMS = s
                .comment("Items that should be kept in reserve during recursive auto-crafting.",
                        "When a recipe would consume these items, the system first crafts extra",
                        "copies so you always keep at least 'protectedReserve' copies after crafting.",
                        "Format: \"modid:item_id\" per line. Example: \"bossmod:boss_drop\".")
                .defineList("protectedItems", List.of(),
                        obj -> obj instanceof String str && ResourceLocation.tryParse(str) != null);
        PROTECTED_RESERVE = s
                .comment("Minimum copies to retain for each item in 'protectedItems'.",
                        "Range: 1-1024.")
                .defineInRange("protectedReserve", 2, 1, 1024);
        s.pop();

        s.push("containerTransfer");
        CONTAINER_TRANSFER_KEY = s
                .comment("Key code for container-to-RS transfer (default F = 70).",
                        "See GLFW key codes: https://www.glfw.org/docs/latest/group__keys.html")
                .defineInRange("containerTransferKey", 70, 32, 348);
        s.pop();

        s.push("sidePanel");
        RS_SIDE_PANEL_KEY = s
                .comment("Key code for toggling the RS Side Panel (default Y = 89).",
                        "See GLFW key codes: https://www.glfw.org/docs/latest/group__keys.html")
                .defineInRange("rsSidePanelKey", 89, 32, 348);
        RS_SIDE_PANEL_MAX_SLOTS = s
                .comment("Maximum number of RS storage slots to show in the side panel.",
                        "Lower values reduce network traffic and client memory usage.",
                        "Range: 36-1024.")
                .defineInRange("rsSidePanelMaxSlots", 256, 36, 1024);
        SIDE_PANEL_SYNC_INTERVAL = s
                .comment("Interval in ticks for full side-panel sync (default 300 = 15s).",
                        "Lower values = more responsive but higher network traffic.")
                .defineInRange("sidePanelSyncInterval", 300, 20, 1200);
        SIDE_PANEL_EXTRACTION_TIMEOUT = s
                .comment("Timeout in milliseconds for side-panel extraction operations.")
                .defineInRange("sidePanelExtractionTimeout", 2000, 500, 10000);
        s.pop();

        s.push("remoteMachineGui");
        MACHINE_TAB_THRESHOLD = s
                .comment("Maximum number of machine shortcut tabs displayed before auto-collapsing",
                        "into a single Hub control-panel button. Set to 0 to always use Hub mode.")
                .defineInRange("machineTabThreshold", 0, 0, 64);
        MACHINE_HUB_TOGGLE_KEY = s
                .comment("Key code for toggling the Machine Hub overlay on the RS Grid screen.",
                        "Default H = 72. See GLFW key codes: https://www.glfw.org/docs/latest/group__keys.html")
                .defineInRange("machineHubToggleKey", 72, 32, 348);
        s.pop();

        s.push("advanced");
        s.pop();

        SERVER_SPEC = s.build();

        // ── CLIENT ────────────────────────────────────────────────

        ForgeConfigSpec.Builder cl = new ForgeConfigSpec.Builder();
        cl.push("sidePanel");
        RS_SIDE_PANEL_X = cl.defineInRange("x", 100, 0, 4000);
        RS_SIDE_PANEL_Y = cl.defineInRange("y", 100, 0, 4000);
        RS_SIDE_PANEL_WIDTH = cl
                .comment("Custom panel width in pixels. 0 = use RS default (247px).",
                        "Range: 0-600.")
                .defineInRange("width", 0, 0, 600);
        RS_SIDE_PANEL_HEIGHT = cl
                .comment("Custom panel height in pixels. 0 = auto-calculate from grid rows.",
                        "Range: 0-600.")
                .defineInRange("height", 0, 0, 600);
        RS_SIDE_PANEL_HIDDEN = cl
                .comment("Collapse the side panel to a small bar.")
                .define("hidden", false);
        cl.pop();
        CLIENT_SPEC = cl.build();
    }

    private RSIntegrationConfig() {}

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
