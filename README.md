<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">Simplified Chinese</a>
</p>

RS Integration connects JEI recipes, Refined Storage inventories, and modded machines into one remote crafting workflow for Minecraft 1.20.1. It can resolve recursive dependencies, dispatch independent graph nodes concurrently, operate bound machines, and return results to the RS network or the player.

## Requirements

| Dependency | Requirement |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47+ |
| Refined Storage | 1.12+; required |
| JEI | Required on the client for recipe actions and plan previews |
| Other integrations | Optional; modules load only when their target mod is present |

## Main Features

### Recursive remote crafting

- Click the RSI action in JEI to preview and launch a recipe through a bound machine.
- Recursively resolves crafting-table, machine, virtual-exchange, and multiblock intermediates.
- Displays both a step list and a zoomable dependency graph with alternative recipes, repeat counts, and missing-material states.
- Executes independent DAG nodes concurrently while keeping exclusive or serial machines safe.
- Tracks material provenance and performs transactional reservation, rollback, refund, and output delivery.
- Supports inventory-only recursive crafting when no RS network is available for ordinary crafting recipes.
- Keeps one physical machine input slot on one concrete item variant, while crafting-grid slots may still mix tag-compatible materials such as different planks.
- Running chains can be viewed with `P`, cancelled from the progress screen/chat action, or cancelled with `/rsi cancel`.

### Machine binding and remote access

- Shift-right-click a supported block with the Network Linker to bind or unbind it.
- Bindings retain dimension and position information and remove stale entries when blocks are broken.
- Open compatible machine GUIs remotely from RS screens and return to the terminal after closing them.
- Use the searchable Machine Management Center for status, output collection, GUI access, and number-key selection.
- Multiple compatible machines are load-balanced; operation groups can distribute repeated work where the machine contract permits it.

### JEI and RS interface tools

- Drag-select JEI ingredients for batch bookmark/hide actions.
- Alt-click an ingredient to filter JEI by mod; Alt-middle-click clears search.
- `Ctrl+T` transfers the visible JEI recipe to an RS crafting grid.
- `Ctrl` + left-drag across RS slots extracts one item from each visited slot.
- A draggable side panel exposes RS contents and machine tabs from other screens, with pinyin-aware search.
- Press `F` in a container to deposit its contents; `G` switches between RS and Sophisticated Backpack targets.

### Resonance Disk

The Resonance Disk is an RS storage disk whose contents can act as if carried by the player.

- Supplies compatible attributes, inventory-tick effects, event-driven passives, and item/resource lookups.
- Persists mutation for configured items instead of ticking disposable copies.
- Includes default passive support for Apotheosis potion charms, Muyimeng fused potion charms, Reliquary's Pyromancer Staff, Enigmatic Addons' Artificial Flower, and Forbidden & Arcanus' Spectral Eye Amulet.
- Supports Chapter of Yuusha/Moonstone Nine Sword Books, Wizard Terra Curios `BuffItem` items, and Terra Equipment stacked infinite potions.
- Integrates with RSI auto-eat, magnet/pickup behavior, and compatible crafting/resource consumers.

### Sophisticated Backpacks and quests

- Redirects supported Magnet, Pickup, Feeding, Refill, Restock, Deposit, and Compacting upgrade workflows to RS where configured.
- Allows recursive ordinary crafting from the player's inventory even without an RS wireless terminal.
- Tracks external item movement for FTB Quests and can submit eligible item tasks through the native quest path.

## Quick Start

1. Build and power a Refined Storage network.
2. Obtain an RSI Network Linker.
3. Shift-right-click a supported machine to bind it.
4. Open a recipe in JEI and use the RSI craft action.
5. Review the generated plan, choose alternatives or a repeat count, then start the chain.
6. Watch progress with `P`; completed outputs return to RS, or to the player when no network exists.

## Default Controls

All key mappings can be changed in Minecraft Controls. Configurable numeric key codes use GLFW values.

| Control | Context | Action |
|---|---|---|
| `F` | Container screen | Deposit container contents |
| `G` | Any/container screen | Toggle RS / Sophisticated Backpack transfer target |
| `Y` | Any screen | Toggle the RS side panel |
| `H` | Any screen | Toggle the Machine Management Center |
| `P` | Any screen | Open/close active crafting progress |
| `Alt` + left-click | JEI ingredient | Filter by mod |
| `Alt` + middle-click | JEI ingredient list | Clear search |
| `Ctrl+T` | JEI recipe | Transfer to RS crafting grid |
| `Ctrl` + left-drag | RS grid | Swipe-extract one of each item |
| Left-drag, then `A` / `H` / `Esc` | JEI list | Bookmark / hide / clear marquee selection |
| `U` / `R` | Hovered side-panel item | Show uses / recipes in JEI |

## Crafting Integrations

The table lists dedicated recipe integrations registered by the current code. Remote GUI-only blocks can also be added through `customGuiMachineMods`.

| Mod | Supported recipes or machines |
|---|---|
| Vanilla / Brick Furnace | Furnace, blast furnace, smoker, campfire, stonecutter, smithing, anvil, enchanting |
| Iron Furnaces | Furnace tiers in furnace, blasting, and smoking modes |
| Botania | Mana Pool/catalysts, Petal Apothecary, Runic Altar, Botanical Brewery, Alfheim trade, Terra Plate, Pure Daisy |
| Ars Nouveau | Imbuement Chamber and Enchanting Apparatus |
| Goety | Necro Brazier, Dark Altar, Cursed Cage, Soul Candlestick and supported ritual recipes |
| Malum | Spirit Altar, Spirit Crucible, Runic Workbench, Spirit Infusion and related recipes |
| Eidolon | Worktable, Crucible, and Brazier |
| Forbidden & Arcanus | Hephaestus Forge, Clibano, smithing/apply-modifier flow |
| Wizards Reborn | Wissen Crystallizer, Arcane Iterator, Arcane Workbench, Crystal rituals |
| Touhou Little Maid | Maid Altar |
| Embers Rekindled | Alchemy Tablet, including inferred and deterministic layouts |
| Aetherworks | Aetherium Anvil and Tool Station |
| The Aether | Freezer, Incubator, and Altar |
| Crock Pot | Crock Pot and Portable Crock Pot |
| Farmer's Delight | Cooking Pot and Skillet |
| Farmer's Respite | Kettle |
| Youkai's Homecoming | Moka Pot, Fermentation Tank, Steamer, Kettle, cooking pots, Cuisine Board |
| Immortaler's Delight | Enchantal Cooler |
| Apotheosis | Fletching, Gem Cutting, Enchantment Library utilities; reforging GUI access |
| TACZ and compatible gun packs | Gun Smith Table recipes, including NBT-bearing guns/ammo |
| Avaritia | Extreme crafting, Neutron Compressor, Extreme Smithing |
| SlashBlade | NBT-sensitive crafting recipes |
| Confluence | Workshop |
| Distant Worlds | Lithum Altar and related interaction/HUD support |
| Lychee | Virtual item-inside/soaking recipes |
| Farming for Blockheads | Market exchanges as virtual recipes |
| Crabber's Delight | Crab Trap processing |

Custom GUI defaults include Crabber's Delight, Metal Barrels, PGP, EMX Arms, Apotheosis, and Ancient Reforging. They provide binding and remote GUI access even when no automatic recipe delegate exists.

## Configuration

Configuration files are documented in-place with comments and validation ranges:

- `config/rs_integration/common.toml`: feature switches, optional integrations, passive effects, auto-eat, side panel, GUI-machine allowlist.
- `saves/<world>/serverconfig/rs_integration/server.toml`: recipe selection, timeouts, recursive depth/step limits, DAG concurrency, protected reserves, machine-specific policies.
- `config/rs_integration/client.toml`: side-panel position, size, visibility, and HUD preferences.

Important server controls include recipe preference/blacklists, repeat limits, resolution budgets, concurrent graph nodes/operations, per-mod parallel policy, and global chain timeout. Existing config files are not overwritten when new defaults are added; merge new list entries manually or regenerate the file.

## Diagnostics

- `/rsi cancel` cancels the player's active chain.
- `/rsi_debug` exposes chain, ledger, recipe-index, handler, binding, trace, audit, Embers cache/lock, and performance diagnostics to permission-level 2 operators.
- `diagnosticVerboseLogging` enables detailed resolver and dispatcher logging when troubleshooting.

## Building

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat build --no-daemon
```

The release JAR is written to `build/libs/` and verified by the build's `verifyReleaseJar` task.

## License

All rights reserved.
