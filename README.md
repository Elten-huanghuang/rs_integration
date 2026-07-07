# RS Integration

Deep integration between [Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) and various modded machines, enabling on-demand remote crafting, machine GUI access, and container transfer.

## Features

- **Machine Binding** — Shift-right-click any supported machine with an RS Network Linker to bind it; Shift-right-click again to unbind. Stale bindings are auto-cleaned when a bound block is broken.
- **Remote Crafting** — Click the `+` button in JEI to craft directly through a bound machine, with recursive ingredient resolution and OR-tree alternatives
- **Craft Chain Control** — `/rsi cancel` to abort active crafting chains; clickable `[Cancel]` button in chain-started chat messages
- **Remote GUI** — Open bound machine GUIs directly from the RS terminal side panel. Close the GUI to automatically return to the RS terminal (configurable).
- **Side Panel** — Foldable, draggable overlay showing RS network contents on any screen, with quick machine tabs
- **Machine Hub** — Collapses individual machine tabs into a searchable grid overlay when you have many bound machines, styled to match the RS terminal aesthetic
- **Container Transfer** — One-key dump container contents into your RS network (supports Sophisticated Backpacks with deposit filter respect)
- **Binding Tooltips** — Hold Shift on any bound item to see its full bound-machine list with dimension names (supports resource-pack translations via `dimension.<namespace>.<path>`)
- **Aetherworks HUD** — In-world overlay showing heat, temperature, ember level, lever binding, and auto-hammer status when looking at an Aetherium Anvil

## Dependencies

| Mod | Required | Notes |
|---|---|---|
| Forge | Yes | 47+ (Minecraft 1.20.1) |
| Refined Storage | Yes | 1.12+ |
| JEI | Recommended | Enables `+` buttons in recipe views and remote crafting plans |
| Curios | Optional | Backpack detection in Curios slots |
| Sophisticated Backpacks | Optional | Backpack transfer, deposit upgrade RS integration |
| Sophisticated Core | Optional | Required with Sophisticated Backpacks |

## Supported Mods

| Mod | Machines | Binding | Crafting | Remote GUI |
|---|---|---|---|---|
| **Vanilla** | Furnace, Blast Furnace, Smoker, Stonecutter, Smithing Table, Anvil, Enchanting Table | Yes | Yes | Yes |
| **Goety** | Necro Brazier, Dark Altar, Cursed Cage, Soul Candlestick | Yes | Yes | No |
| **Malum** | Spirit Altar, Spirit Crucible (core + components) | Yes | Yes | No |
| **Eidolon** | Worktable, Crucible, Brazier | Yes | Yes | Worktable only |
| **Forbidden & Arcanus** | Hephaestus Forge | Yes | Yes | No |
| **Wizards Reborn** | Wissen Crystallizer, Arcane Iterator, Arcane Workbench, Crystal Block | Yes | Yes | No |
| **Touhou Little Maid** | Maid Altar | Yes | Yes | No |
| **Embers Rekindled** | Alchemy Tablet | Yes | Yes | No |
| **Aetherworks** | Aetherium Anvil (in-world hammer crafting) | Yes | Yes | No |
| **Aether** | Freezer, Incubator, Altar | Yes | Yes | Yes |
| **CrockPot** | Crock Pot, Portable Crock Pot | Yes | Yes | Yes |
| **TACZ** | Gun Smith Table A/B/C | Yes | Yes | Yes |
| **FarmingForBlockheads** | Market | Yes | Yes | Yes |
| **SlashBlade** | Crafting table recipes with NBT | N/A | Yes | N/A |
| **Avaritia** | Dire Crafting Table, Neutron Compressor, Extreme Smithing Table | Yes | Yes | Yes |
| **Metal Barrels** | Iron, Gold, Diamond, and upgraded barrels | Yes | No | Yes |
| **Crabber's Delight** | Crab traps and seafood processing blocks | Yes | Yes | Yes |
| **Farmer's Delight** | Cooking Pot, Skillet | Yes | Yes | No |
| **Farmer's Respite** | Kettle | Yes | Yes | No |
| **Youkais Homecoming** | Cooking Pot, Steamer, Moka Pot, Kettle, Fermentation Tank, Cuisine Board | Yes | Yes | No |
| **Immortaler's Delight** | Enchantal Cooler | Yes | Yes | No |
| **Confluence** | Workshop | Yes | Yes | Yes |
| **Apotheosis** | Reforging Table | Yes | No | Yes |
| **Ancient Reforging** | Ancient Reforging Table | Yes | No | Yes |
| **PGP** | PGP Gun Workbench | Yes | No | Yes |
| **EMX Arms** | EMX Weapon Workbench | Yes | No | Yes |

**Custom GUI Machines** — Any mod with container-based machines (e.g. Metal Barrels, PGP, EMX Arms, Apotheosis, TerraCurio) can be added via `customGuiMachineMods` in config for remote GUI access, without writing any Java code. The default list includes `crabbersdelight`, `metalbarrels`, `pgp`, `emxarms`, `apotheosis`, `ancientreforging`. You must manually bind each machine by shift-right-clicking it with a Network Linker.

## Quick Start

1. Set up an RS network (Controller, Disk Drive, Terminal)
2. Craft a **Network Linker** from RS Addons
3. Shift-right-click a supported machine to bind it (Shift-right-click again to unbind)
4. Open the RS Terminal — bound machines appear as tabs in the side panel
5. In JEI, click `+` on any recipe to send it to a bound machine
6. Use `/rsi cancel` to abort a running craft chain, or click `[Cancel]` in the chat message
7. Press the container transfer hotkey (default: `G`) while in any container to dump items into RS

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

[containerTransfer]
enableContainerTransfer = true

[sidePanel]
enableRSSidePanel = true

[remoteMachineGui]
enableMachineGuiTabs = true
customGuiMachineMods = ["crabbersdelight", "metalbarrels", "pgp", "emxarms", "apotheosis", "ancientreforging"]

[advanced]
diagnosticVerboseLogging = false
```

### Server (`saves/<world>/serverconfig/rs_integration-server.toml`)

```toml
[integrations]
crockpotFillerItem = "minecraft:stick"
embersInferMaxAttempts = 20
embersInferZeroBlackLimit = 5
embersLockTimeoutMinutes = 10
embersProgressTimeoutTicks = 600

[autoCrafting]
preferredRecipes = []                  # Recipe IDs with +10000 scoring bonus
multiblockCraftTimeoutSeconds = 300
multiblockRecipeBlacklist = []
multiblockRecipeAllowlist = []
repeatCountMax = 64
craftingMaxDepth = 8
craftingMaxSteps = 4096
protectedItems = []                    # Items kept in reserve during crafting
protectedReserve = 2
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
