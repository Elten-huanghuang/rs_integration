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

    // ── Container transfer (10-19) ─────────────────────────────────
    public static final int STORE_ALL = 10;

    // ── Mod craft dispatch (20-29) ─────────────────────────────────
    public static final int MALUM_CRAFT = 20;
    public static final int FA_CRAFT = 21;
    public static final int EIDOLON_CRAFT = 22;
    public static final int WR_WAND_CRAFT = 23;

    // ── Goety ritual GUI (30-39) ───────────────────────────────────
    public static final int GOETY_CHECK_RS = 30;
    public static final int GOETY_RS_RESULT = 31;
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

    // ── FTB Quests submission (90-99) ────────────────────────────────
    public static final int FTB_QUEST_SUBMISSION_REQUEST = 90;

    // ── Apotheosis library (100-109) ─────────────────────────────────
    public static final int APOTHEOSIS_LIBRARY_LEVEL = 100;
    public static final int APOTHEOSIS_LIBRARY_SCAN_REQUEST = 101;
    public static final int APOTHEOSIS_LIBRARY_SCAN_RESPONSE = 102;
    public static final int APOTHEOSIS_LIBRARY_IMPORT_REQUEST = 103;
    public static final int APOTHEOSIS_LIBRARY_IMPORT_RESULT = 104;
}
