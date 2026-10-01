package com.huanghuang.rsintegration.network.packet;

/**
 * Stable, explicit network-packet discriminator IDs for the single unified channel.
 *
 * <p>Every packet has a FIXED id here instead of drawing from a shared
 * auto-incrementing counter. This guarantees that adding or removing a packet —
 * or a mod-/config-gated packet being registered on one side but not the other —
 * never renumbers the remaining packets. Previously the shared counter made the
 * ids depend on registration order, so removing a mid-sequence packet (or a mod
 * being absent on one end) silently desynced the client/server id tables and
 * every following packet decoded as the wrong type.</p>
 *
 * <p>Ids are grouped by subsystem with gaps for future growth. Keep every value
 * unique and below 256 (Forge writes the discriminator as a single byte).</p>
 */
public final class NetworkPacketIds {

    private NetworkPacketIds() {}

    // ── Batch crafting (0-9) ───────────────────────────────────────
    public static final int GENERIC_CRAFT = 0;
    public static final int PLAN_RESPONSE = 1;
    public static final int CRAFT_STARTED = 2;
    public static final int CRAFT_PROGRESS = 3;
    public static final int CRAFT_CANCEL = 4;
    public static final int CRAFT_STATUS_REQUEST = 5;
    public static final int CRAFT_STATUS_SYNC = 6;
    public static final int CRAFT_PROGRESS_DELTA = 7;
    public static final int PREPARE_INTERMEDIATE_MATERIALS = 8;
    public static final int PLANNING_PROGRESS = 9;

    // ── Container transfer (10-19) ─────────────────────────────────
    public static final int STORE_ALL = 10;

    // ── Mod craft dispatch (20-29) ─────────────────────────────────
    public static final int MALUM_CRAFT = 20;
    public static final int FA_CRAFT = 21;
    public static final int EIDOLON_CRAFT = 22;
    public static final int WR_WAND_CRAFT = 23;
    // 24: formerly ARS_NOUVEAU_CRAFT (now uses GenericCraftPacket via IBatchDelegate)

    // ── Goety ritual GUI (30-39) ───────────────────────────────────
    // 30-31: retired Goety-only ingredient check packets; do not reuse.
    // 32 formerly GOETY_SELECT_RITUAL (companion-mod ritual GUI, removed) — do not reuse.

    // ── Side panel (40-69) ─────────────────────────────────────────
    public static final int SIDE_PANEL_REQUEST = 40;
    public static final int SIDE_PANEL_SYNC = 41;
    public static final int SIDE_PANEL_CLICK = 42;
    public static final int SIDE_PANEL_DELTA = 43;
    public static final int INVENTORY_TRANSFER = 44;
    public static final int OPEN_BOUND_MACHINE_GUI = 45;
    public static final int MACHINE_STATUS_DELTA = 46;
    public static final int MACHINE_COLLECT = 47;
    public static final int MACHINE_INSERT = 48;
    public static final int RS_BINDING_SYNC = 49;
    public static final int CONFIG_SYNC = 50;
    public static final int RETURN_TO_RS = 51;
    // 52-53 formerly RSItemLockPacket / RSItemLockSyncPacket (removed in 1.1.1) — do not reuse.
    public static final int SIDE_PANEL_OPERATION_RESULT = 54;
    public static final int UNBIND_MACHINE = 55;
    public static final int MACHINE_FAVORITE_TOGGLE = 56;
    public static final int MACHINE_FAVORITES_SYNC = 57;
    public static final int PLACEBO_REMOTE_MENU_SNAPSHOT = 58;
    public static final int RS_BINDING_SYNC_REQUEST = 59;

    // ── Resonance backpack (70-79) ──────────────────────────────────
    public static final int OPEN_RESONANCE_BACKPACK = 70;
    public static final int RESONANCE_SYNC = 71;

    // ── Auto-eat (80-89) ─────────────────────────────────────────────
    public static final int AUTO_EAT_REQUEST = 80;
    public static final int AUTO_EAT_STOP = 81;
    public static final int AUTO_EAT_SYNC = 82;
    public static final int AUTO_EAT_BLACKLIST_UPDATE = 83;
    public static final int AUTO_EAT_BLACKLIST_REQUEST = 84;
    public static final int AUTO_EAT_BLACKLIST_SYNC = 85;
    public static final int AUTO_EAT_PREFERENCES_UPDATE = 86;

    // ── FTB Quests submission (90-99) ────────────────────────────────
    public static final int FTB_QUEST_SUBMISSION_REQUEST = 90;
    public static final int FTB_QUEST_MISSING_BOOKMARK = 91;
    public static final int FTB_QUEST_CONFIRM_CHECKMARKS = 92;
    public static final int FTB_QUEST_STORAGE_SCAN = 93;

    // ── Apotheosis library (100-109) ─────────────────────────────────
    public static final int APOTHEOSIS_LIBRARY_LEVEL = 100;
    public static final int APOTHEOSIS_LIBRARY_SCAN_REQUEST = 101;
    public static final int APOTHEOSIS_LIBRARY_SCAN_RESPONSE = 102;
    public static final int APOTHEOSIS_LIBRARY_IMPORT_REQUEST = 103;
    public static final int APOTHEOSIS_LIBRARY_IMPORT_RESULT = 104;
    public static final int APOTHEOSIS_SPAWNER_STATE = 105;
    public static final int APOTHEOSIS_SPAWNER_EXECUTE = 106;
    public static final int APOTHEOSIS_SPAWNER_REFRESH = 107;

    // ── Distant Worlds Lithum Altar (110-119) ──────────────────────
    public static final int LITHUM_ALTAR_STATUS_REQUEST = 110;
    public static final int LITHUM_ALTAR_STATUS_SYNC = 111;

    // Villager trade restock (120-129)
    public static final int VILLAGER_RESTOCK_REQUEST = 120;
    public static final int VILLAGER_RESTOCK_RESULT = 121;
    public static final int REFORGING_RESTOCK_REQUEST = 122;
    public static final int REFORGING_RESTOCK_RESULT = 123;
    public static final int ANVIL_MEMORY_REQUEST = 124;
    public static final int ANVIL_MEMORY_SYNC = 125;

    // Nearby machine binding (126-129)
    public static final int NEARBY_BINDING_REQUEST = 126;
    public static final int VILLAGER_TRADE_LOCK_SNAPSHOT = 127;
    public static final int ENCHANTING_RESTOCK_REQUEST = 128;
    public static final int ENCHANTING_RESTOCK_RESULT = 129;
    public static final int EXPLICIT_MACHINE_BINDING = 130;
    /** BD-only machine GUI open request; kept separate from the RS side-panel packet. */
    public static final int BD_OPEN_BOUND_MACHINE_GUI = 131;
    public static final int BD_MACHINE_COLLECT = 132;
    public static final int BD_MACHINE_INSERT = 133;
    public static final int BD_UNBIND_MACHINE = 134;
    public static final int BD_BINDING_SYNC = 135;
    public static final int JEI_CHEAT_DROP = 136;
    public static final int RECIPE_AVAILABILITY_REQUEST = 137;
    public static final int RECIPE_AVAILABILITY_RESULT = 138;
    public static final int VOID_UPGRADE_CONFIG = 139;
    /** Server-originated storage-terminal search text, scoped by container id. */
    public static final int STORAGE_SEARCH_TEXT = 140;
    public static final int JEI_NETWORK_INVENTORY = 141;
    public static final int JEI_NETWORK_INVENTORY_RESYNC = 142;
    public static final int JEI_STORAGE_PULL = 143;
    public static final int SMITHING_MODE = 144;
    public static final int SMITHING_JEI_TRANSFER = 145;
    public static final int CRAFTING_STATION_MODE = 146;
    public static final int STONECUTTER_RECIPE_SELECT = 147;
    public static final int CRAFTING_STATION_JEI_TRANSFER = 148;
    public static final int ANVIL_NAME = 149;
    public static final int UNIFIED_GRID_UPDATE = 150;
    public static final int UNIFIED_GRID_ACTION = 151;
}
