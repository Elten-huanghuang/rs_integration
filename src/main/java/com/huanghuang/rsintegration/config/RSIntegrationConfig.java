package com.huanghuang.rsintegration.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

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
    public static ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACKS;
    public static ForgeConfigSpec.BooleanValue ENABLE_JEI;
    public static ForgeConfigSpec.BooleanValue DEPOSIT_UPGRADE_RS;
    public static ForgeConfigSpec.IntValue BATCH_CRAFT_MAX;
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
        ENABLE_SOPHISTICATED_BACKPACKS = b
                .comment("Enable RS integration with Sophisticated Backpacks (RS-based upgrade items).")
                .define("enableSophisticatedBackpacks", true);
        ENABLE_JEI = b
                .comment("Show '+' buttons in JEI recipe views for remote crafting.",
                        "Client-side; the server value is synced to the client.")
                .define("enableJeiIntegration", true);
        b.pop();

        b.push("batchCrafting");
        BATCH_CRAFT_MAX = b
                .comment("Maximum items per batch craft via Shift+click JEI button.",
                        "Range: 1-128.")
                .defineInRange("batchCraftMax", 64, 1, 128);
        b.pop();

        b.push("autoCrafting");
        PREFERRED_RECIPES = b
                .comment("Preferred recipe IDs for auto-crafting resolution.",
                        "When multiple recipes produce the same item, the preferred recipe gets a +1000 scoring bonus.",
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

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
