<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">Simplified Chinese</a>
</p>

RS Integration lets Refined Storage operate machines from other mods. Choose an item in JEI and it checks materials, resolves prerequisite recipes, operates bound machines, and returns the result to the RS network.

**Current version: 1.4.1 | Minecraft 1.20.1**

[1.4.1 release notes](docs/RELEASE_NOTES_1.4.1.md) | [MC Encyclopedia guide (Chinese)](https://www.mcmod.cn/class/29199.html)

## Requirements

| Dependency | Requirement |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47+ |
| Refined Storage | 1.12+; required |
| JEI | Required on the client for recipe actions and plan previews |
| Other integrations | Optional; modules load only when their target mod is present |

## Version 1.4.1 Highlights

- **Intermediate preparation mode**: from the plan screen, prepare only independently craftable intermediate products, return them to storage, and leave the final target untouched.
- **Safer recursive execution**: blocked branches are pruned while independent preparation chains continue; explicit preparation packets keep the normal strict crafting path unchanged.
- **Reliable FTB Quests submission**: explicit submit clicks use inventory-first, storage-fallback transactions, suppress only RSI settlement re-entry, and safely fall back to FTB's native handler when no mutation occurs.
- **Clearer diagnostics**: preparation and quest submission now report eligibility, reservation, partial progress, missing materials, and failure reasons in both English and Chinese.

## Main Features

### Recursive remote crafting

**Ordinary recipes can be crafted recursively from the player's inventory even without an RS wireless terminal.**

- Click the RSI action in JEI to preview and start crafting.
- Resolves crafting-table, modded-machine, virtual-exchange, and multiblock prerequisites.
- Shows the full step list, missing materials, alternative recipes, and a zoomable dependency graph before execution.
- Uses the current inventory and RS snapshot to select one planner up front, avoiding duplicate planning work for recipes whose dependencies require special handlers.
- Distinguishes consumed materials, returned containers, and reusable catalysts in cards, the tree, and total requirements. Missing material names can be bookmarked individually in JEI.
- Prunes recursive no-gain conversions between equivalent colors or material variants while preserving exact-output and quantity-increasing recipes.
- Runs independent graph nodes concurrently and load-balances repeated work across compatible machines.
- Splits large vanilla crafting batches across ticks and shares a global operation budget fairly between active players and chains.
- Tracks material provenance and performs transactional reservation, rollback, refund, and output delivery.
- Keeps one physical machine input slot on one concrete item variant, while separate crafting-grid slots may mix tag-compatible materials such as different planks.
- Press `P` to inspect progress; cancel from the progress screen, chat action, or `/rsi cancel`.

### Machine binding and remote access

- Shift-right-click a supported block with the Network Linker to bind or unbind it.
- Press `;` to bind nearby supported machines in loaded chunks. The scan is time-sliced, skips duplicate bindings, accepts a Network Linker from the hand, player inventory, or Curios slots, and reports protected or invalid targets separately.
- Bindings retain dimension and position information and remove stale entries when blocks are broken.
- Open compatible machine GUIs remotely from RS screens and return to the terminal after closing them.
- Use the searchable Machine Management Center for status, output collection, GUI access, and number-key selection.
- `Ctrl` + left-click a machine in the Machine Management Center to unbind it immediately; the list refreshes without requiring another bind or world reload.
- Favorite frequently used machines per player so they remain easy to reach from the Machine Management Center and side panel.
- Machine tabs can insert held items into input slots; shift operations can also replenish fuel.
- Multiple compatible machines are load-balanced and can distribute repeated work where the machine contract permits it.
- Remote operations respect FTB Chunks and Cadmus claims and do not force-load an unloaded target chunk.

### JEI and RS interface tools

- Drag-select JEI ingredients for batch bookmark/hide actions.
- Alt-click an ingredient to filter JEI by mod; Alt-middle-click clears search.
- `Ctrl+T` transfers the visible JEI recipe to an RS crafting grid.
- `Ctrl` + left-drag across RS slots extracts one item from each visited slot.
- A draggable side panel exposes RS contents and machine tabs from other screens, with pinyin-aware search.
- RS grids support progressive `#` tooltip, `$` tag, and `@` mod searches. Search data begins warming when JEI becomes ready, is refreshed without blocking rendering, and is reused from an on-disk cache when possible.
- The search box keeps a bounded, persistent history by search scope; recent entries can be restored, favorited, deleted individually, or cleared from the history overlay.
- Sol Carrot's player-specific Eaten/Not Eaten tooltip state stays dynamic in both RS and JEI instead of leaking into shared search text.
- Missing materials in recipe cards, tree totals, and text lists can be added to JEI bookmarks one at a time or as a group.

### JEI registration and recipe entry points

- RSI registers as a JEI plugin and adds its craft action to supported vanilla and modded recipe layouts. The action validates the bound machine, builds the recursive material plan, and opens its preview; recipes backed by a remotely accessible bound machine also receive an Open Machine button.
- Three dedicated JEI categories are registered: FTB Quests item submission, the Distant Worlds Lithum Altar, and Project MMO probabilistic salvage. Player-specific FTB Quest entries are added dynamically after quest data synchronizes.
- The Lithum Core and PMMO salvage block are registered as catalysts for their categories. PMMO salvage data received after initial registration is also added to JEI at runtime.
- RSI registers a universal RS recipe-transfer handler plus a dedicated 13-recipe-slot transfer handler for the Eidolon Worktable. Categories, catalysts, and entry points load only when their target mod is installed and the corresponding integration is enabled.

### HUDs and in-world prompts

- **Aetherium Anvil / Forge Tool Station HUD**: looking at either machine shows its slotted item, current/required hammer hits, forge temperature, valid recipe temperature range, ember storage and per-hit cost, mistakes, and invalid-recipe warnings. Tool Stations also show target temperature and temperature rate; the footer reports configured temperature bounds, lever binding, Auto Refill, and Auto Hammer state.
- **Distant Worlds Lithum Altar HUD**: looking at a Lithum Core shows idle/working state, the current recipe or output, energy/capacity, recovery rate, and the item/count on all eight surrounding pedestals. It can be disabled with the client option `distantWorlds.enableHud`.
- **Craft progress HUD**: active asynchronous crafts display their target, overall percentage, completed/running node counts, current steps, and machine state in a right-side panel. It can be hidden or restored from the progress screen opened with `P`.
- **Binding hint HUD**: while holding a compatible Network Linker and looking at a supported machine or altar, a hotbar-level prompt indicates whether the current action will bind or unbind it.

### Auto Eat

Open an RS crafting grid to consume food directly from the network:

- **Dietary Diversity** works with SolCarrot and chooses foods not yet recorded.
- **Stack Feast** consumes one selected food until full or the per-use limit is reached.
- Stack Feast recognizes effects such as Gnaw's Gift that legitimately allow eating at full hunger.
- **Balanced Diet** works with Diet and chooses food for lower nutrition groups.
- Food and status-effect blacklists prevent unwanted consumption.
- Bowls, bottles, and other remainders return to the RS network when possible.
- Servers can configure activation requirements, execution cost, and per-use limits.

### Villager trade restock

- Select a villager trade and press `Space` to fill both payment slots for repeated trading.
- Uses matching items from the player inventory first, then extracts the remainder from the accessible RS network.
- Refills each payment slot up to its valid stack limit instead of supplying only one trade at a time.
- Shows how many items came from the inventory and RS, with clear partial, no-network, no-permission, and invalid-trade results.
- Missing payment items and quantities are displayed in the trade screen and added to JEI bookmarks automatically.

### Apotheosis Enchantment Library

- Bind an Apotheosis Library or Ender Library to RS, then open its screen to access the RSI enchanted-book import panel.
- Scans every enchanted book in the bound RS network and groups identical NBT-bearing books with their available counts.
- Search by enchantment or book information, select visible results, and import the filtered selection or every compatible book.
- Clearly marks invalid books, unsupported custom data, and books rejected by the target library before import.
- Imports are validated and executed server-side; rejected or uncommitted books are refunded to RS, or returned safely when RS cannot accept them.
- The native Apotheosis library filter also supports pinyin and registry-name matching.

### Replenishment and item management

- **Reforging restock**: press Space in Apotheosis or Ancient Reforging screens to pull reforging materials from RS; missing items are bookmarked in JEI.
- **Remote side panel**: press `Y` to browse and search RS contents from other screens and open JEI uses or recipes.

### Special automation compatibility

- **Automatic Embers alchemy inference**: after an Embers Rekindled Alchemy Tablet is bound, RSI can place materials, spark the tablet, evaluate black/white pin feedback, and eliminate candidate alchemy codes through repeated trials. Successful codes are saved and reused; Calculate mode can use a deterministic layout without trial and error.
- **Automatic Aetherium Anvil hammering**: while holding a Tinker Hammer, enabling Auto Hammer in the anvil settings makes RSI strike whenever the anvil's hit cooldown permits. The same screen configures temperature bounds, forge-lever temperature control, and automatic material refilling.
- **Dimensional Magnet collection for mutant remains**: Distant Worlds Wither Totems and Charged Wither Totems (`wither_totem` / `charged_wither_totem`) are converted from direct inventory rewards into magnet-compatible drops. A bound Dimensional Magnet can send them straight to RS instead of placing them in the player's inventory.
- **Safe Goety manual rituals**: summoning, sacrifice, and conversion requests prepare pedestal materials and return the activation item to the player, but do not start the ritual automatically. This leaves target and environment confirmation to the player and prevents accidental material loss.
- **Kettle and steamer structures**: Farmer's Respite kettle automation preserves native container/fluid behavior, while Youkai's Homecoming accepts both full-height steamers and a valid single-layer rack with a lid.
- **Youkai fermentation fluids**: pure-fluid and mixed solid/fluid fermentation recipes from Youkai's Homecoming and Gensokyo Delight reserve their filled containers recursively, collect outputs and empty containers, and retain compatibility with legacy water recipes.

### Apotheosis Spawner Upgrades

- Hold a valid RS network item and shift-right-click an Apotheosis spawner; with a BD terminal, use `Alt + right-click` instead.
- The screen lists applicable positive upgrades, current completion, required materials, and available RS stock.
- Select multiple upgrades and preview the complete dependency plan before confirming.
- Available materials are extracted from the linked RS network; missing upgrade materials are crafted recursively when a valid recipe graph exists.
- Every scan and execution is validated server-side against the player, network, dimension, distance, spawner state, and the previewed selection.

### One-key container transfer

- Press `F` in a chest, machine input, or other container to transfer its contents to the current target.
- Press `G` to switch the target between a bound RS network and a Sophisticated Backpack.
- Transfers respect filters, item blacklists, and destination capacity; rejected items remain in the source container.
- Transfer keys are suppressed while typing in chat, search boxes, and other text fields.
- `F` transfer is also disabled in player-inventory and crafting-accessor contexts where Minecraft needs the key for normal offhand swapping.

### RS side panel and Machine Management Center

- Press `Y` to show or hide the side panel without leaving the current screen.
- Browse RS item counts and bound machines.
- Search by name, pinyin, number, or item ID, which remains practical in large modpacks.
- Hover an item and press `U` for uses or `R` for recipes to open JEI directly.
- Open machine screens remotely from machine tabs; stale or destroyed bindings are removed automatically.
- Press `H` for searchable machine status, output collection, GUI access, and number-key selection.
- When the network contains a Resonance Disk, its Resonance Backpack can be opened from the Machine Management Center.

### Mouse and shortcut operations

- Hold `Ctrl` and drag across RS grid slots to extract one item from every visited slot.
- Drag across side-panel items to distribute them evenly to bound machines; left-click extracts a stack and right-click extracts one.
- JEI supports marquee bookmark/hide actions and quick filtering by mod.
- Plan nodes support right-click collapse/expand, right-drag panning, `Ctrl` + wheel zoom, and wheel repeat-count adjustment.

### Resonance Disk

The Resonance Disk is an RS storage disk whose contents can act as if carried by the player.

- Supplies compatible attributes, inventory-tick effects, event-driven passives, and item/resource lookups.
- Persists mutation for configured items instead of ticking disposable copies.
- Includes default passive support for Apotheosis potion charms, Muyimeng fused potion charms, Reliquary's Pyromancer Staff, Enigmatic Addons' Artificial Flower, and Forbidden & Arcanus' Spectral Eye Amulet.
- Supports Chapter of Yuusha/Moonstone Nine Sword Books, Wizard Terra Curios `BuffItem` items, and Terra Equipment stacked infinite potions.
- Supports compatible stored resources such as powder snow buckets, source fluid, and venom during recursive crafting.
- Supports Malum Void Favor acquisition through the well interaction while the disk is available through RS.
- Prevents the Resonance Disk from being dismantled into a 4K storage part and housing by the RS disk `Shift` + right-click shortcut.
- Only one Resonance Disk is active per RS network, preventing duplicated passive effects and resource queries.

### Sophisticated Backpack upgrades

- Provides four RS-specific Sophisticated Backpack upgrades:
- **Dimensional Magnet** sends nearby items that pass its filter directly into RS.
- **Dimensional Pickup** routes picked-up drops through RS while retaining backpack pickup rules.
- **Dimensional Feeding** consumes filtered food from RS to feed the player.
- **Dimensional Restock** supplies items from RS when the backpack needs them.
- Dimensional Magnet keeps accepted drops out of backpack storage; its range and filter-slot count are configurable.
- Dimensional Pickup stores accepted drops directly in RS while preserving Sophisticated Backpack filters.
- Dimensional Feeding and Restock prioritize RS resources and use their filters to restrict eligible items.
- Shift-right-click an RS controller to bind these upgrades; their screens control enablement and filter items.
- **Compacting enhancement**: with Majrusz's Accessories installed, Compacting merges eligible accessories through successive quality tiers until 100% or no further merge is possible.
- Compatible Magnet, Pickup, Feeding, Refill, and Restock upgrades can be redirected to an RS network.
- The Deposit upgrade can send items directly into RS.
- Compacting can automatically merge eligible Majrusz's Accessories items.

### FTB Quests item tracking

- Tracks real RS insertions caused by crafting, container transfer, magnet/pickup flows, and other external movement.
- Simulated insertion, voiding, refunds, and recovery do not advance quest progress.
- Uses FTB Quests item filters and NBT matching rules.
- Explicit quest submission can consume missing items directly from RS; missing requirements are bookmarked in JEI.
- Crafted and externally acquired items are accounted for separately to prevent duplicate progress.
- Requires FTB Quests and FTB Teams; RSI yields to another compatible automatic detector when one is installed.

## Quick Start

1. Build and power a Refined Storage network.
2. Obtain an RSI Network Linker.
3. Shift-right-click one supported machine, or press `;` to scan and bind nearby machines.
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
| `;` | In world | Bind nearby supported machines with a Network Linker in hand, inventory, or Curios |
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
| Vanilla / Brick Furnace | Furnace, blast furnace, smoker, campfire, brewing stand, stonecutter, smithing, anvil, enchanting |
| Iron Furnaces | Furnace tiers in furnace, blasting, and smoking modes, including factory/rainbow stack batches and multi-machine balancing |
| Sophisticated Storage | Grouped iron, gold, diamond, and related chest-upgrade recipes in recursive plans |
| Botania | Mana Pool/catalysts, Petal Apothecary, Runic Altar with automatic wand activation, Botanical Brewery, Alfheim trade, Terra Plate, Pure Daisy |
| Improved Botania Pools | Alfheim, Asgard, Muspelheim, and Nilfheim Mana Pools, using Botania infusion recipes, catalysts, batching, and load balancing |
| MythicBotany | Mana Infuser, including native and KubeJS-defined recipes |
| Ars Nouveau | Imbuement Chamber, Enchanting Apparatus, and recursive Scribes' Table glyph crafting; either physical table half can be bound |
| Iron's Spells 'n Spellbooks | Scroll Forge and Arcane Anvil scroll creation/upgrades, including Refined Mod, T.O Magic 'n Extras, Peyro's Scythe, GTBC's Geomancy Plus, and other compatible addon scrolls |
| Apprentice Codex | Essence Smoker and Spellcaster Workbench |
| ISS CSW | Spell Forge and multi-scroll spell-mixing recipes |
| Goety / Goety Awaken | Cursed Infuser, Grim Infuser, Dark Mender, Necro Brazier, Dark Altar, Cursed Cage, Soul Candlestick and supported ritual recipes; upgraded infusers use their native 64-slot parallel capacity, while summoning, sacrifice, and conversion rituals use manual final confirmation |
| Malum | Spirit Altar, Spirit Crucible, Runic Workbench, Spirit Infusion and related recipes; adjacent altars collect outputs independently and nearby Catalyzers/Runewood Obelisks retain their acceleration effects |
| Eidolon | Worktable, Crucible, and Brazier |
| Forbidden & Arcanus | Hephaestus Forge, Clibano, smithing/apply-modifier flow |
| Wizards Reborn | Wissen Crystallizer, Arcane Iterator, Arcane Workbench, and Crystal Ritual, with recipe-type routing for dynamic/KubeJS recipes and Iterator pedestal-capacity validation |
| Touhou Little Maid | Maid Altar, including automatic P-point replenishment from RS |
| Embers Rekindled | Alchemy Tablet, including automatic trial-and-error code inference, saved inference results, and deterministic layouts |
| Aetherworks | Aetherium Anvil and Forge Tool Station, including automatic hammering, material refilling, forge-lever temperature control, and temperature/ember/hit-progress HUDs |
| The Aether | Freezer, Incubator, and Altar |
| Crock Pot | Crock Pot and Portable Crock Pot |
| Farmer's Delight | Cooking Pot and Skillet |
| Farmer's Respite | Kettle, including native container input, non-water fluids, and safe fluid mismatch handling |
| Youkai's Homecoming | Moka Pot, Fermentation Tank, single- or multi-layer Steamer, Kettle, cooking pots, Cuisine Board |
| Gensokyo Delight | Fermentation Tank, Steamer, Kettle, cooking pots, and Cuisine Board across renamed field layouts |
| Immortaler's Delight | Old and new Enchantal Cooler variants; Hot Spring Bucket resources can participate through Resonance Disks |
| Apotheosis | Fletching, Gem Cutting, Enchantment Library scanning/import, reforging GUI access, and server-authoritative spawner upgrades with recursive material crafting |
| TACZ and compatible gun packs | Gun Smith Table recipes, including NBT-bearing guns/ammo |
| Avaritia | Compressed through Extreme six-tier crafting tables and Extreme Smithing |
| SlashBlade | NBT-sensitive crafting recipes |
| Confluence | Workshop |
| Distant Worlds | Lithum Altar and related interactions; crosshair HUD for recipe, energy, recovery, and all eight pedestal states |
| Lychee | Virtual item-inside/soaking and block-interaction recipes; block interactions can run through a bound Mechanical Press |
| Farming for Blockheads | Market exchanges as virtual recipes and recursive crafting intermediates |
| Project MMO | Probabilistic salvage recipes with recursive material planning, level checks, XP, secondary outputs, and configurable multiblock binding |
| Crabber's Delight | Crab Trap processing |

Custom GUI defaults include Crabber's Delight, Metal Barrels, PGP, EMX Arms, Apotheosis, and Ancient Reforging. They provide binding and remote GUI access even when no automatic recipe delegate exists.

## Configuration

Configuration files are documented in-place with comments and validation ranges:

- `config/rs_integration/common.toml`: feature switches, optional integrations, passive effects, auto-eat, nearby-binding range/budget, side panel, GUI-machine allowlist.
- `saves/<world>/serverconfig/rs_integration/server.toml`: recipe selection, pure/typed planning budgets, preview admission, recursive limits, catalyst preference, variant-conversion guard, vanilla per-tick budgets, DAG concurrency, protected reserves, machine-specific policies.
- `config/rs_integration/client.toml`: side-panel layout, HUD preferences, and RS special-search warm-up, progressive refresh, index, pinyin-worker, and disk-cache settings.

Important server controls include recipe preference/blacklists, repeat limits, resolution budgets, concurrent graph nodes/operations, per-mod parallel policy, and global chain timeout. Versioned migrations update selected legacy default values while preserving custom values; new list entries still need to be merged manually or regenerated.

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
