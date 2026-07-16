# RS Integration

Deep integration between [Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) and various modded machines, enabling on-demand remote crafting, machine GUI access, and container transfer.

## Features

- **Machine Binding** — Shift-right-click any supported machine with an RS Network Linker to bind it; Shift-right-click again to unbind. Stale bindings are auto-cleaned when a bound block is broken.
- **Remote Crafting** — Click the `+` button in JEI to craft directly through a bound machine, with recursive ingredient resolution and OR-tree alternatives
- **Crafting Plan Screen** — Visual crafting step tree: card view and zoomable/pannable node graph view with collapsible subtrees, alternative recipe dropdowns, demand annotations, and material-availability color coding
- **Load Balancing** — When multiple machines of the same type are bound, craft tasks are automatically distributed across all idle machines for parallel execution
- **Craft Chain Control** — `/rsi cancel` to abort active crafting chains; clickable `[Cancel]` button in chain-started chat messages
- **JEI Marquee & Quick Actions** — Drag-select multiple items in JEI to batch bookmark/hide (A/H/Escape); Alt+left-click to filter by mod; Alt+middle-click to clear search; Ctrl+T to transfer recipe to RS crafting grid
- **RS Grid Swipe Extract** — Hold Ctrl and drag across RS Grid slots to extract one of each item swiped over, delivered directly to the player
- **Auto-Eat** — Automatically consume food from the RS network to maintain hunger. Three modes: Diversity (prefer uneaten foods), Stack (focus on one food item), Diet (balance nutrition groups). Blacklist configuration UI with pinyin search
- **Remote GUI** — Open bound machine GUIs directly from the RS terminal side panel. Close the GUI to automatically return to the RS terminal (configurable).
- **Side Panel** — Foldable, draggable overlay showing RS network contents on any screen, with quick machine tabs
- **Container Transfer** — Press `F` in any container GUI to instantly dump all container contents into the RS network; `G` toggles the transfer target (RS Network / Sophisticated Backpack). Automatically suppressed when JEI/EMI/REI search boxes are focused
- **Dimensional Upgrades** — Existing Sophisticated Backpacks upgrades (Magnet, Pickup, Feeding, Refill, Restock, Deposit) redirected to interact with the RS network instead of the backpack's own inventory
- **Binding Tooltips** — Hold Shift on any bound item to see its full bound-machine list with dimension names (supports resource-pack translations via `dimension.<namespace>.<path>`)
- **Machine Management Center** — When the number of bound machines exceeds the config threshold, a hub button appears on the RS Grid side. Click to open a draggable, searchable (pinyin) grid overlay showing real-time machine status (Idle / Working / Output Ready), with left-click to collect/insert/open GUI, number keys 1-9 for quick selection
- **Aetherworks HUD** — In-world overlay showing heat, temperature, ember level, lever binding, and auto-hammer status when looking at an Aetherium Anvil
- **Resonance Disk** — A special RS storage disk whose contents contribute passive item effects (attribute modifiers, inventoryTick simulation, event-driven redirects) as if the items were in the player's inventory
- **Resonance Backpack** — A portable GUI for browsing and managing the resonance disk contents, accessible via a button in the RS terminal side panel

## Dependencies

| Mod | Required | Notes |
|---|---|---|
| Forge | Yes | 47+ (Minecraft 1.20.1) |
| Refined Storage | Yes | 1.12+ |
| JEI | Recommended | Enables `+` buttons in recipe views and remote crafting plans |
| Curios | Optional | Backpack detection in Curios slots |
| Sophisticated Backpacks | Optional | Backpack transfer, RS-custom upgrades integration |
| Sophisticated Core | Optional | Required with Sophisticated Backpacks |
| Enigmatic Legacy | Optional | Resonance disk passive item effects support |
| Enigmatic Addons | Optional | Resonance disk passive item effects support |
| Moonstone | Optional | Resonance disk Nine Sword Book support |
| Chapter of Yuusha | Optional | Resonance disk Nine Sword Book compat support |
| Reliquary | Optional | Resonance disk consumption redirect support |

## Supported Mods

| Mod | Machines | Binding | Crafting | Remote GUI |
|---|---|---|---|---|
| **Vanilla** | Furnace, Blast Furnace, Smoker, Stonecutter, Smithing Table, Anvil, Enchanting Table | ✅ | ✅ | ✅ |
| **Goety** | Necro Brazier, Dark Altar, Cursed Cage, Soul Candlestick | ✅ | ✅ | ❌ |
| **Malum** | Spirit Altar, Spirit Crucible (core + components) | ✅ | ✅ | ❌ |
| **Eidolon** | Worktable, Crucible, Brazier | ✅ | ✅ | ⚠️¹ |
| **Forbidden & Arcanus** | Hephaestus Forge, Smithing Table² | ✅ | ✅ | ❌ |
| **Wizards Reborn** | Wissen Crystallizer, Arcane Iterator, Arcane Workbench, Crystal Block | ✅ | ✅ | ❌ |
| **Touhou Little Maid** | Maid Altar | ✅ | ✅ | ❌ |
| **Embers Rekindled** | Alchemy Tablet | ✅ | ✅ | ❌ |
| **Aetherworks** | Aetherium Anvil (in-world hammer crafting) | ✅ | ✅ | ❌ |
| **Aether** | Freezer, Incubator, Altar | ✅ | ✅ | ✅ |
| **CrockPot** | Crock Pot, Portable Crock Pot | ✅ | ✅ | ✅ |
| **TACZ** | Gun Smith Table A/B/C | ✅ | ✅ | ✅ |
| **FarmingForBlockheads** | Market | ✅ | ✅ | ✅ |
| **SlashBlade** | Crafting table recipes with NBT | ➖ | ✅ | ➖ |
| **Avaritia** | Dire Crafting Table, Neutron Compressor, Extreme Smithing Table | ✅ | ✅ | ✅ |
| **Metal Barrels** | Iron, Gold, Diamond, and upgraded barrels | ✅ | ❌ | ✅ |
| **Crabber's Delight** | Crab traps and seafood processing blocks | ✅ | ✅ | ✅ |
| **Farmer's Delight** | Cooking Pot, Skillet | ✅ | ✅ | ❌ |
| **Farmer's Respite** | Kettle | ✅ | ✅ | ❌ |
| **Youkais Homecoming** | Cooking Pot, Steamer, Moka Pot, Kettle, Fermentation Tank, Cuisine Board | ✅ | ✅ | ❌ |
| **Immortaler's Delight** | Enchantal Cooler | ✅ | ✅ | ❌ |
| **Confluence** | Workshop | ✅ | ✅ | ✅ |
| **Apotheosis** | Reforging Table | ✅ | ❌ | ✅ |
| **Ancient Reforging** | Ancient Reforging Table | ✅ | ❌ | ✅ |
| **PGP** | PGP Gun Workbench | ✅ | ❌ | ✅ |
| **EMX Arms** | EMX Weapon Workbench | ✅ | ❌ | ✅ |
| **Enigmatic Legacy** | Passive item effects (via resonance disk) | ➖ | ➖ | ➖ |
| **Enigmatic Addons** | Passive item effects (via resonance disk) | ➖ | ➖ | ➖ |
| **Moonstone** | Nine Sword Books (via resonance disk) | ➖ | ➖ | ➖ |
| **Chapter of Yuusha** | Nine Sword Books compat (via resonance disk) | ➖ | ➖ | ➖ |
| **Reliquary** | Pyromancer Staff, coin consumption (via resonance disk) | ➖ | ➖ | ➖ |
| **Forbidden & Arcanus** | Spectral Eye Amulet (via resonance disk) | ➖ | ➖ | ➖ |

> ¹ Eidolon remote GUI: worktable only.  
> ² Forbidden & Arcanus `apply_*_modifier` recipes require a bound Smithing Table; JEI `+` opens the Smithing Table GUI with template and materials auto-filled.

**Custom GUI Machines** — Any mod with container-based machines (e.g. Metal Barrels, PGP, EMX Arms, Apotheosis, TerraCurio) can be added via `customGuiMachineMods` in config for remote GUI access, without writing any Java code. The default list includes `crabbersdelight`, `metalbarrels`, `pgp`, `emxarms`, `apotheosis`, `ancientreforging`. You must manually bind each machine by shift-right-clicking it with a Network Linker.

## Quick Start

1. Set up an RS network (Controller, Disk Drive, Terminal)
2. Craft a **Network Linker** from RS Addons
3. Shift-right-click a supported machine to bind it (Shift-right-click again to unbind)
4. Open the RS Terminal — bound machines appear as tabs in the side panel
5. In JEI, click `+` on any recipe to send it to a bound machine
6. Use `/rsi cancel` to abort a running craft chain, or click `[Cancel]` in the chat message
7. In any container GUI, press `F` to dump items into RS, `G` to toggle transfer target mode

## Crafting Flow

When you click the JEI `+` button:

1. **Preview Phase** — Recursively resolve all intermediate ingredients, producing a full crafting step tree displayed in the `Crafting Plan` panel with OR alternative paths
2. **Execution Phase** — Deduct materials from RS network → physically insert into machine → wait for processing → extract output back into RS

For GUI-less machines (altars, braziers, etc.), crafting resolves via the mod's recipe API directly. For GUI-based machines (furnaces, crock pots, etc.), items are physically inserted into machine input slots and output is collected after processing completes.

## Key Bindings

| Key | Context | Action |
|---|---|---|
| `F` (default) | Any container GUI | Instantly dump all container contents into RS network |
| `G` (default) | Any container GUI | Toggle transfer target mode (RS Network / Sophisticated Backpack) |
| Side Panel toggle | Any screen | Show/hide the RS side panel |
| **Alt + Left-click** | JEI ingredient list | Filter by that item's mod (equivalent to `@modid` search) |
| **Alt + Middle-click** | JEI ingredient list | Clear JEI search box |
| **Ctrl + T** | JEI recipe view | Transfer current recipe to RS crafting grid |
| **Ctrl + Left-drag** | RS crafting grid | Swipe extract: extract one of each item dragged over |
| Left-drag marquee | JEI ingredient list / bookmarks | Select multiple items |
| `A` | After marquee | Batch bookmark all selected items |
| `H` | After marquee | Batch hide all selected items |
| `Escape` | After marquee | Clear selection |

## Auto-Eat

Auto-Eat automatically consumes food from the RS network to maintain player hunger. Requires the potion effect specified in config (default: `crockpot:gnaws_gift`; set to empty string to disable this requirement).

### Three Modes

| Mode | Behavior |
|---|---|
| **Diversity** | Prefer foods not yet eaten (requires SolCarrot) |
| **Stack** | Focus on a single selected food item |
| **Diet** | Balance nutrition groups automatically (requires Diet) |

### Blacklist Configuration

The `Auto-Eat Config` screen provides per-mode blacklisting:
- Searchable, paginated food grid with **pinyin / pinyin-initial** search support
- Drag marquee for batch-selecting multiple foods
- Blacklists automatically sync between client and server

## JEI Marquee Selection

In the JEI ingredient list or bookmark panel, simply **drag with the left mouse button** to select multiple items:

- After selection, press `A` — batch bookmark all selected items
- After selection, press `H` — batch hide all selected items
- After selection, press `Escape` — clear the selection
- Marquee is automatically disabled when the JEI search box is focused to avoid key conflicts

## Crafting Plan Screen

The preview phase after clicking JEI `+` shows the full crafting step tree:

- **Card View** — Linear step list clearly showing materials and outputs for each step
- **Tree View** — Zoomable, pannable node graph (DAG) displaying recipe dependency relationships
- **Collapsible subtrees**, **alternative recipe dropdowns**, **demand annotations**
- Material availability **color coding**: green (sufficient) / orange (partial) / red (missing)
- Press Ctrl+T on any subtree node to transfer its JEI recipe to the crafting grid

## Load Balancing

When multiple machines of the same mod type are bound, remote craft dispatch automatically uses all idle machines for parallel execution:

- Each dispatch round checks whether machine chunks are loaded and BEs are present
- Material pre-reservation happens once at the chain level, ensuring intermediate outputs (virtual inventory) are visible to subsequent steps
- Partial child-machine failures do not affect already-started siblings

## Machine Management Center

When the number of bound machines exceeds the config threshold, a hub button appears on the RS Grid side. Clicking it opens a draggable overlay:

- **Real-time Status** — Each machine shows its current state: Idle (green), Working (yellow), Output Ready (blue)
- **Quick Actions** — Left-click to collect output / insert items / open GUI; Shift+left-click to send output to RS network; right-click always opens the machine GUI
- **Number keys 1-9** for instant machine selection
- **Pinyin search** — filter machines by pinyin or initials
- **Scroll wheel** to page, **drag** to reposition the overlay
- `Escape` first clears the filter text, second press dismisses the overlay

## Dimensional Upgrades

Existing Sophisticated Backpacks upgrades that, when bound to an RS Grid (via NBT), redirect their behavior to use the RS network instead of the backpack's own inventory:

| Upgrade | Function |
|---|---|
| **Magnet Upgrade (RS)** | Pulls picked-up items into the RS network |
| **Pickup Upgrade (RS)** | Auto-picks up items in range into the RS network |
| **Feeding Upgrade (RS)** | Auto-consumes food from the RS network |
| **Refill Upgrade (RS)** | Refills hotbar items from the RS network |
| **Restock Upgrade (RS)** | Restocks player inventory on interact from the RS network |
| **Deposit Upgrade (RS)** | When targeting an RS Grid, pushes backpack contents into the RS network (config `depositUpgradeRS`, default off) |
| **Compacting Upgrade (RS)** | Auto-compresses compactable items from the RS network; also auto-combines Majrusz's Accessories matching the whitelist filter |

## Embers Alchemy

Embers Alchemy Tablet recipes support both inferred and deterministic pedestal layouts:

- Places the tablet ingredient on the Alchemy Tablet, aspect catalysts in pedestal bottoms, and recipe inputs in pedestal tops
- Reserves tablet/input materials through the crafting graph while reserving hidden aspect catalysts directly from the RS network, preserving the exact placement order
- Reuses inferred alchemical codes for later deterministic crafts, including recipes with a single repeated aspect
- Configurable max attempts, zero-black-pin abort limit, lock timeout, and progress timeout

## Debug Commands

`/rsi_debug` (permission level 2) provides these subcommands:

| Subcommand | Function |
|---|---|
| `dump chain` | Dump current craft chain state |
| `dump ledger` | Dump extraction ledger details |
| `dump index` | Dump recipe index statistics |
| `dump handlers` | List all registered mod recipe handlers |
| `dump bindings` | Dump player inventory binding info |
| `trace <item>` | Candidate-decision and resolution trace (supports `--no-inventory` flag) |
| `audit` | Check mod recipe coverage |
| `embers_clearcache` | Clear Embers alchemy infer cache |
| `embers_clearlocks` | Clear Embers alchemy tablet locks |
| `perf` | Dump performance monitoring snapshot |

## Configuration

All configs are TOML-based and sync from server to client where applicable.

### Common (`config/rs_integration-common.toml`)

```toml
[general]
enableBinding = true            # Master switch for block binding
enableAutoCrafting = true       # Master switch for recursive auto-crafting

[integrations]
enableGoety = true
enableMalum = true
enableWizardsReborn = true
enableForbiddenArcanus = true
enableEidolon = true
enableTouhouLittleMaid = true
enableSlashblade = true
enableAvaritia = true
enableAetherworks = true
enableAether = true
enableCrockPot = true
enableTacz = true
enableEmbersAlchemy = true
enableEmbersAlchemyCalculate = false   # Deterministic pedestal layout mode
enableVanillaMachines = true
enableSophisticatedBackpacks = true
enableJeiIntegration = true
enableFarmingForBlockheads = true
enableFarmersDelight = true
enableFarmersRespite = true
enableYoukaisHomecoming = true
enableImmortalsDelight = true
enableConfluence = true

[autoCrafting]
enableMultiblockAutoCrafting = true    # Allow multi-block machines as intermediate steps

[sophisticated_backpacks]
depositUpgradeRS = false               # Deposit upgrade pushes items into RS network

[passiveEffects]
enableRSPassiveEffects = true           # Master switch for RS passive effects system
passiveTickItems = [...]                # Items whose inventoryTick is simulated from disk
nineSwordMaxCount = 9                   # Max effective count of Nine Sword Books

[containerTransfer]
enableContainerTransfer = true

[autoEat]
requiredEffect = "crockpot:gnaws_gift"     # Potion effect required for auto-eat (empty = no requirement)
perItemCost = 1                            # Items consumed from network per eating action

[sidePanel]
enableRSSidePanel = true

[remoteMachineGui]
enableMachineGuiTabs = true
customGuiMachineMods = ["crabbersdelight", "metalbarrels", "pgp", "emxarms", "apotheosis", "ancientreforging"]

[advanced]
diagnosticVerboseLogging = false
containerTransferKey = 70                   # Container transfer hotkey (default F = 70)
```

### Server (`saves/<world>/serverconfig/rs_integration-server.toml`)

```toml
[integrations]
crockpotFillerItem = "minecraft:stick"    # Filler item for empty Crock Pot slots
crockpotFuelPriority = ["minecraft:coal", "minecraft:charcoal", "minecraft:coal_block"] # Crock Pot fuel order
vanillaFurnaceFuelPriority = ["minecraft:coal", "minecraft:charcoal"] # Furnace/blast furnace/smoker fuel order; other safe fuels are fallback
embersInferMaxAttempts = 20               # Embers infer mode max attempts
embersInferZeroBlackLimit = 5             # Consecutive zero-black-pin abort limit
embersLockTimeoutMinutes = 10             # Alchemy tablet lock timeout (minutes)
embersProgressTimeoutTicks = 600          # Alchemy tablet progress wait timeout (ticks)
embersCalculateSafeMode = false           # Safe mode: only use known aspects, never infer from empty pins
embersCalculateMaxLayouts = 256           # Max layout count for calculate mode

[autoCrafting]
preferredRecipes = []                  # Recipe IDs with +10000 scoring bonus
multiblockCraftTimeoutSeconds = 300    # Multi-block craft timeout (seconds)
multiblockRecipeBlacklist = []         # Multi-block recipe blacklist
multiblockRecipeAllowlist = []         # Multi-block recipe allowlist (empty = all allowed)
repeatCountMax = 64                    # Max repeat count for crafting plans
craftingMaxDepth = 8                   # Max recursive crafting depth
craftingMaxSteps = 4096                # Max steps per resolution
protectedItems = []                    # Items kept in reserve during crafting
protectedReserve = 2                   # Minimum reserve quantity
```

### Client (`config/rs_integration-client.toml`)

```toml
[sidePanel]
rsSidePanelX = 0
rsSidePanelY = 0
rsSidePanelWidth = 200
rsSidePanelHeight = 160
rsSidePanelHidden = false
```

## License

All rights reserved.
