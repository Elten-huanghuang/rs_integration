package com.huanghuang.rsintegration.config;

import com.huanghuang.rsintegration.sidepanel.PlayerLockManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

public final class RSIntegrationConfig {

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    // ── master switches ──────────────────────────────────────────
    public static ForgeConfigSpec.BooleanValue ENABLE_BINDING;
    public static ForgeConfigSpec.BooleanValue ENABLE_AUTO_CRAFTING;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PREFERRED_RECIPES;
    public static ForgeConfigSpec.BooleanValue ENABLE_MULTIBLOCK_AUTO_CRAFTING;
    public static ForgeConfigSpec.IntValue MULTIBLOCK_CRAFT_TIMEOUT_SECONDS;
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
    public static ForgeConfigSpec.BooleanValue ENABLE_EMBERS_ALCHEMY_CALC;
    public static ForgeConfigSpec.BooleanValue ENABLE_VANILLA_MACHINES;
    public static ForgeConfigSpec.IntValue EMBERS_INFER_MAX_ATTEMPTS;
    public static ForgeConfigSpec.IntValue EMBERS_INFER_ZERO_BLACK_LIMIT;
    public static ForgeConfigSpec.IntValue EMBERS_LOCK_TIMEOUT_MINUTES;
    public static ForgeConfigSpec.IntValue EMBERS_PROGRESS_TIMEOUT_TICKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI;
    public static ForgeConfigSpec.BooleanValue DEPOSIT_UPGRADE_RS;
    public static ForgeConfigSpec.BooleanValue ENABLE_MACHINE_GUI_TABS;
    public static ForgeConfigSpec.IntValue MACHINE_TAB_THRESHOLD;
    public static ForgeConfigSpec.IntValue MACHINE_HUB_TOGGLE_KEY;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> MACHINE_GUI_WHITELIST;
    public static ForgeConfigSpec.IntValue MACHINE_GUI_MAX_DISTANCE;
    public static ForgeConfigSpec.IntValue REPEAT_COUNT_MAX;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> PROTECTED_ITEMS;
    public static ForgeConfigSpec.IntValue PROTECTED_RESERVE;
    public static ForgeConfigSpec.BooleanValue ENABLE_CONTAINER_TRANSFER;
    public static ForgeConfigSpec.IntValue CONTAINER_TRANSFER_KEY;

    // ── side panel ─────────────────────────────────────────────────
    public static ForgeConfigSpec.BooleanValue ENABLE_RS_SIDE_PANEL;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_KEY;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_MAX_SLOTS;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_X;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_Y;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_WIDTH;
    public static ForgeConfigSpec.IntValue RS_SIDE_PANEL_HEIGHT;
    public static ForgeConfigSpec.BooleanValue RS_SIDE_PANEL_HIDDEN;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_SYNC_INTERVAL;
    public static ForgeConfigSpec.IntValue SIDE_PANEL_EXTRACTION_TIMEOUT;
    public static ForgeConfigSpec.BooleanValue DIAGNOSTIC_VERBOSE_LOGGING;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        ENABLE_BINDING = b
                .comment("Master switch for all block-binding features (Shift+Right-click to bind RS networks to machines).",
                        "Disabling this turns off all binding-related functionality regardless of per-mod settings.")
                .define("enableBinding", true);
        ENABLE_AUTO_CRAFTING = b
                .comment("Allow the mod to automatically craft intermediate items via the RS network when direct materials are missing.",
                        "Disabling this means recipes will simply fail if all items are not already present.")
                .define("enableAutoCrafting", true);
        b.pop();

        b.push("integrations");
        ENABLE_GOETY = b
                .comment("Enable RS integration with Goety (Dark Altar remote crafting).")
                .define("enableGoety", true);
        ENABLE_MALUM = b
                .comment("Enable RS integration with Malum (Spirit Altar remote crafting).")
                .define("enableMalum", true);
        ENABLE_WIZARDS_REBORN = b
                .comment("Enable RS integration with Wizards Reborn (Wissen Crystallizer, Arcane Iterator, etc.).")
                .define("enableWizardsReborn", true);
        ENABLE_FORBIDDEN_ARCANUS = b
                .comment("Enable RS integration with Forbidden & Arcanus (Hephaestus Forge remote crafting).")
                .define("enableForbiddenArcanus", true);
        ENABLE_EIDOLON = b
                .comment("Enable RS integration with Eidolon Repraised (Crucible remote crafting).")
                .define("enableEidolon", true);
        ENABLE_TOUHOU_LITTLE_MAID = b
                .comment("Enable RS integration with Touhou Little Maid (Maid Altar remote crafting).")
                .define("enableTouhouLittleMaid", true);
        ENABLE_AETHERWORKS = b
                .comment("Enable RS integration with Embers Aetherworks Addon",
                        "(Aetherium Anvil remote crafting with auto-hammer support).")
                .define("enableAetherworks", true);
        ENABLE_EMBERS_ALCHEMY = b
                .comment("Enable RS integration with Embers Rekindled (Alchemy Tablet remote crafting).")
                .define("enableEmbersAlchemy", true);
        ENABLE_EMBERS_ALCHEMY_CALC = b
                .comment("Enable Calculate mode for Embers Alchemy — shows the deterministic pedestal layout.",
                        "When disabled, only Infer (trial-and-error) mode is available.",
                        "Requires enableEmbersAlchemy=true.")
                .define("enableEmbersAlchemyCalculate", false);
        ENABLE_VANILLA_MACHINES = b
                .comment("Enable RS integration with vanilla machines (Furnace, Blast Furnace, Smoker,",
                        "Campfire, Stonecutter, Smithing Table). Allows binding and remote crafting",
                        "via these blocks.")
                .define("enableVanillaMachines", true);
        EMBERS_INFER_MAX_ATTEMPTS = b
                .comment("Maximum trial-and-error attempts for Embers Alchemy inference mode.",
                        "Each failed attempt consumes some materials (per Embers' failure mechanics).",
                        "Range: 5-200.")
                .defineInRange("embersInferMaxAttempts", 20, 5, 200);
        EMBERS_INFER_ZERO_BLACK_LIMIT = b
                .comment("Consecutive zero-black-pin attempts before aborting Embers Alchemy inference.",
                        "A zero-black-pin result means no aspect is in the correct position.",
                        "After this many consecutive such failures, inference aborts early.",
                        "Range: 3-50.")
                .defineInRange("embersInferZeroBlackLimit", 5, 3, 50);
        EMBERS_LOCK_TIMEOUT_MINUTES = b
                .comment("Minutes before an Embers Alchemy tablet lock auto-expires.",
                        "Prevents permanent lock-up when a player disconnects mid-craft.",
                        "Range: 1-60.")
                .defineInRange("embersLockTimeoutMinutes", 10, 1, 60);
        EMBERS_PROGRESS_TIMEOUT_TICKS = b
                .comment("Maximum ticks (1 tick = 1/20 second) to wait for an Embers Alchemy",
                        "tablet to finish processing before timing out.",
                        "Range: 100-2400 (5s to 120s).")
                .defineInRange("embersProgressTimeoutTicks", 600, 100, 2400);
        ENABLE_SOPHISTICATED_BACKPACKS = b
                .comment("Enable RS integration with Sophisticated Backpacks (RS-based upgrade items).")
                .define("enableSophisticatedBackpacks", true);
        ENABLE_JEI = b
                .comment("Show '+' buttons in JEI recipe views for remote crafting.",
                        "Client-side; the server value is synced to the client.")
                .define("enableJeiIntegration", true);
        REPEAT_COUNT_MAX = b
                .comment("Maximum repeat count allowed in the CraftingPlanScreen.",
                        "Higher values allow more concurrent crafts but increase server load.",
                        "Range: 1-1024.")
                .defineInRange("repeatCountMax", 64, 1, 1024);
        PROTECTED_ITEMS = b
                .comment("Items that should be kept in reserve during recursive auto-crafting.",
                        "When a recipe would consume these items, the system first crafts extra",
                        "copies so you always keep at least 'protectedReserve' copies after crafting.",
                        "Format: \"modid:item_id\" per line. Example: \"bossmod:boss_drop\".")
                .defineList("protectedItems", List.of(),
                        obj -> obj instanceof String s && ResourceLocation.tryParse(s) != null);
        PROTECTED_RESERVE = b
                .comment("Minimum copies to retain for each item in 'protectedItems'.",
                        "Range: 1-1024.")
                .defineInRange("protectedReserve", 2, 1, 1024);
        b.pop();

        b.push("autoCrafting");
        PREFERRED_RECIPES = b
                .comment("Preferred recipe IDs for auto-crafting resolution.",
                        "When multiple recipes produce the same item, the preferred recipe gets a +10000 scoring bonus.",
                        "Format: one recipe ID per line, e.g. \"minecraft:oak_planks\".",
                        "Example: prefer 4-plank-from-log over 1-plank-from-log when crafting.")
                .defineList("preferredRecipes", List.of(), obj -> obj instanceof String s && ResourceLocation.tryParse(s) != null);
        ENABLE_MULTIBLOCK_AUTO_CRAFTING = b
                .comment("Allow multi-block machines (altars, forges, crucibles, etc.) to be used as intermediate",
                        "steps during recursive auto-crafting. When disabled, only vanilla crafting-table recipes",
                        "are used for intermediate items.")
                .define("enableMultiblockAutoCrafting", true);
        MULTIBLOCK_CRAFT_TIMEOUT_SECONDS = b
                .comment("Maximum time (in seconds) to wait for a multi-block craft to complete.",
                        "If exceeded, the crafting chain is aborted and items are refunded.",
                        "Range: 10–600.")
                .defineInRange("multiblockCraftTimeoutSeconds", 300, 10, 600);
        MULTIBLOCK_RECIPE_BLACKLIST = b
                .comment("Multi-block recipe IDs that should NEVER be used for auto-crafting.",
                        "Format: \"modid:recipe_id\".")
                .defineList("multiblockRecipeBlacklist", List.of(),
                        obj -> obj instanceof String s && ResourceLocation.tryParse(s) != null);
        MULTIBLOCK_RECIPE_ALLOWLIST = b
                .comment("If non-empty, ONLY these multi-block recipe IDs may be used for auto-crafting.",
                        "Format: \"modid:recipe_id\". Empty list = all recipes allowed (subject to blacklist).")
                .defineList("multiblockRecipeAllowlist", List.of(),
                        obj -> obj instanceof String s && ResourceLocation.tryParse(s) != null);
        b.pop();

        b.push("sophisticated_backpacks");
        DEPOSIT_UPGRADE_RS = b
                .comment("Whether the backpack's Deposit Upgrade can push items into the RS network.",
                        "Enabled (true): deposit upgrade interacts with RS grids.",
                        "Disabled (false): deposit upgrade works as vanilla Sophisticated Backpacks.")
                .define("depositUpgradeRS", false);
        b.pop();

        b.push("containerTransfer");
        ENABLE_CONTAINER_TRANSFER = b
                .comment("Enable one-key container-to-RS transfer.",
                        "When any container GUI is open, press the configured key to transfer",
                        "all container items into the bound RS network.",
                        "Requires: a dimensional accessor and a bound RS network.")
                .define("enableContainerTransfer", true);
        CONTAINER_TRANSFER_KEY = b
                .comment("Key code for container-to-RS transfer (default F = 70).",
                        "See GLFW key codes: https://www.glfw.org/docs/latest/group__keys.html")
                .defineInRange("containerTransferKey", 70, 32, 348);
        b.pop();

        b.push("sidePanel");
        ENABLE_RS_SIDE_PANEL = b
                .comment("Enable the RS Side Panel — a foldable, draggable overlay showing RS network items on any screen.",
                        "Toggle with the configured hotkey while in-game.")
                .define("enableRSSidePanel", true);
        RS_SIDE_PANEL_KEY = b
                .comment("Key code for toggling the RS Side Panel (default Y = 89).",
                        "See GLFW key codes: https://www.glfw.org/docs/latest/group__keys.html")
                .defineInRange("rsSidePanelKey", 89, 32, 348);
        RS_SIDE_PANEL_MAX_SLOTS = b
                .comment("Maximum number of RS storage slots to show in the side panel.",
                        "Lower values reduce network traffic and client memory usage.",
                        "Range: 36-1024.")
                .defineInRange("rsSidePanelMaxSlots", 256, 36, 1024);
        b.pop();

        b.push("remoteMachineGui");
        ENABLE_MACHINE_GUI_TABS = b
                .comment("Enable remote machine shortcut tabs on RS GridScreen.",
                        "When disabled, players cannot open remote machine GUIs from terminals.")
                .define("enableMachineGuiTabs", true);
        MACHINE_GUI_WHITELIST = b
                .comment("Whitelist of mod type IDs that support the \"Open Machine\" GUI button.",
                        "Only mod types in this list will show the button in crafting plan screens.",
                        "Valid values: vanilla_machine, goety, malum, forbidden_arcanus, eidolon, wizards_reborn")
                .defineList("machineGuiWhitelist",
                        List.of("vanilla_machine"),
                        obj -> obj instanceof String);
        MACHINE_TAB_THRESHOLD = b
                .comment("Maximum number of machine shortcut tabs displayed before auto-collapsing",
                        "into a single Hub control-panel button. Set to 0 to always use Hub mode.")
                .defineInRange("machineTabThreshold", 0, 0, 64);
        MACHINE_HUB_TOGGLE_KEY = b
                .comment("Key code for toggling the Machine Hub overlay on the RS Grid screen.",
                        "Default Y = 89. See GLFW key codes: https://www.glfw.org/docs/latest/group__keys.html")
                .defineInRange("machineHubToggleKey", 89, 32, 348);
        MACHINE_GUI_MAX_DISTANCE = b
                .comment("Maximum distance (in blocks) for wirelessly opening a bound machine's GUI.",
                        "Set to 0 to disable distance checks (unlimited range).",
                        "This is a server-side security control to prevent cross-dimension GUI abuse.")
                .defineInRange("machineGuiMaxDistance", 0, 0, Integer.MAX_VALUE);
        b.pop();

        b.push("advanced");
        SIDE_PANEL_SYNC_INTERVAL = b
                .comment("Interval in ticks for full side-panel sync (default 300 = 15s).",
                        "Lower values = more responsive but higher network traffic.")
                .defineInRange("sidePanelSyncInterval", 300, 20, 1200);
        SIDE_PANEL_EXTRACTION_TIMEOUT = b
                .comment("Timeout in milliseconds for side-panel extraction operations.")
                .defineInRange("sidePanelExtractionTimeout", 2000, 500, 10000);
        DIAGNOSTIC_VERBOSE_LOGGING = b
                .comment("Enable verbose diagnostic logging for debugging.",
                        "WARNING: This may spam the server log. Only enable for troubleshooting.")
                .define("diagnosticVerboseLogging", false);
        b.pop();

        SERVER_SPEC = b.build();

        // ── client-only config ──────────────────────────────────────
        ForgeConfigSpec.Builder cb = new ForgeConfigSpec.Builder();
        cb.push("sidePanel");
        RS_SIDE_PANEL_X = cb.defineInRange("x", 100, 0, 4000);
        RS_SIDE_PANEL_Y = cb.defineInRange("y", 100, 0, 4000);
        RS_SIDE_PANEL_WIDTH = cb
                .comment("Custom panel width in pixels. 0 = use RS default (247px).",
                        "Range: 0-600.")
                .defineInRange("width", 0, 0, 600);
        RS_SIDE_PANEL_HEIGHT = cb
                .comment("Custom panel height in pixels. 0 = auto-calculate from grid rows.",
                        "Range: 0-600.")
                .defineInRange("height", 0, 0, 600);
        RS_SIDE_PANEL_HIDDEN = cb
                .comment("Collapse the side panel to a small bar.")
                .define("hidden", false);
        cb.pop();
        CLIENT_SPEC = cb.build();
    }

    private RSIntegrationConfig() {}

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

    /**
     * Returns the minimum reserve count, checking both config-protected items
     * and player-locked items. Player locks use the same reserve count from config.
     */
    public static int getProtectedReserve(Ingredient ingredient, @Nullable ServerPlayer player) {
        int reserve = getProtectedReserve(ingredient);
        if (reserve > 0) return reserve;
        if (player == null) return 0;
        Set<ResourceLocation> locked = PlayerLockManager.getLockedItems(player);
        if (locked.isEmpty()) return 0;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (rl != null && locked.contains(rl)) {
                return PROTECTED_RESERVE.get();
            }
        }
        return 0;
    }

    public static void register() {
        ForgeConfigSpec.Builder commonBuilder = new ForgeConfigSpec.Builder();
        commonBuilder.push("common");
        // Reserve for future common config entries (synced from server to client)
        commonBuilder.pop();
        ForgeConfigSpec commonSpec = commonBuilder.build();
        var container = ModLoadingContext.get().getActiveContainer();
        container.addConfig(new ModConfig(ModConfig.Type.COMMON, commonSpec, container,
                "rs_integration/common.toml"));
        container.addConfig(new ModConfig(ModConfig.Type.SERVER, SERVER_SPEC, container,
                "rs_integration/server.toml"));
        container.addConfig(new ModConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, container,
                "rs_integration/client.toml"));
    }
}
