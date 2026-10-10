<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">Simplified Chinese</a>
</p>

Make items from other mods without shuttling materials between machines by hand. Pick a recipe in JEI, let RSI gather what is available, craft missing ingredients, and send the result back to storage.

**Current version: 1.5.0.5 | Minecraft 1.20.1**

[1.5.0.5 release notes (Chinese)](docs/RELEASE_NOTES_1.5.0.5.md) | [MC Encyclopedia guide (Chinese)](https://www.mcmod.cn/class/29199.html)

## Requirements

| Dependency | Requirement |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47+ |
| Refined Storage | 1.12+; Optional |
| JEI | Required on the client for recipe actions and plan previews |
| Other integrations | Optional; modules load only when their target mod is present |

## Main Features

### Recursive remote crafting

**Ordinary recipes can be crafted recursively from the player's inventory even without an RS wireless terminal.**

- Click the RSI action in JEI to preview and start crafting.
- Automatically follows the recipe chain through crafting tables, supported mod machines, trades, and other compatible recipes.
- Before starting, shows the required steps, available and missing materials, and alternate recipes. You stay in control of whether to begin.
- Uses machines you have linked and can share repeated work between compatible machines. When possible, it also crafts directly from your inventory without a wireless terminal.
- Returns containers and unused materials where supported. Progress is shown with `P`; cancel from the progress screen or run `/rsi cancel`.

### Machine binding and remote access

- Link a supported machine with a Network Linker, use `Alt` + right-click, or press `;` to find nearby machines. Broken links are cleaned up automatically.
- Open linked machines from RSI screens, check their status, collect outputs, or send them items without walking back and forth.
- Keep favourite machines handy. If several machines can do the same job, RSI can share the work between them.
- Machine access follows your FTB Chunks/Cadmus claims; RSI will not load distant chunks just to run a machine.

### JEI and RS interface tools

- Find and bookmark missing ingredients, filter JEI by mod, move a recipe to an RS grid with `Ctrl+T`, or pull a stack from storage with the middle mouse button.
- Use the RSI side panel to search storage and reach linked machines while keeping your current screen open.
- Search RS grids by name, mod (`@`), tag (`$`), or tooltip text (`#`); recent searches can be revisited, favourited, or removed later.
- Press `F` in an RS or Beyond Dimensions terminal to fill its search box from the item currently selected in JEI or EMI.
- Drag across RS slots while holding `Ctrl` to take one item from each slot.

### Tetra JEI integration

- At a Tetra workbench, JEI shows materials that fit the part you are working on. Hold `Ctrl` over a material to preview it, or sort the list by its useful stats.

### JEI registration and recipe entry points

- Supported recipes get an RSI craft button; recipes for linked machines may also get a button to open that machine.
- JEI includes extra views for FTB Quests submissions, the Distant Worlds Lithum Altar, and Project MMO salvage recipes.

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

- Select a villager trade and press `Space` to fill both payment slots from your inventory and connected RS network.
- Missing items are shown in the trade screen and can be bookmarked in JEI.

### Small conveniences

- Press `Space` at an enchanting table to refill lapis from storage.
- Anvil screens can remember materials you use often and refill them later from your inventory or storage. Works with vanilla, Goety, and Iron's Spells anvil screens.
- JEI favourites can protect villager trades from refresh mods that would otherwise reroll them.
- Construction Wands can count matching blocks in connected storage, so you can build more without carrying every block.
- Add an RSI Void Upgrade to a supported RS grid to discard incoming items that match your chosen rules.
- FTB Quests' “claim all” can continue through newly unlocked rewards automatically.

### Apotheosis Enchantment Library

- Connect an Apotheosis library to RS to browse, search, and import enchanted books from storage.
- Use the library's search and filters to choose which books to import; rejected books are returned safely.

### Replenishment and item management

- **Reforging restock**: press `Space` in Apotheosis or Ancient Reforging to refill materials from RS.

### Special automation compatibility

- **Embers Rekindled** can help solve Alchemy Tablet recipes and remember solutions for next time.
- **Aetherworks** can automate hammering and material restocking at its forge, with temperature and progress shown on screen.
- **Goety** can prepare supported rituals, but leaves the final activation to you. Long rituals account for their full soul cost.
- Kettles, steamers, and fermentation machines from Farmer's Respite and Youkai's Homecoming can be included in crafting chains, including recipes that use fluids.

### Apotheosis Spawner Upgrades

- Shift-right-click an Apotheosis spawner while holding an RS network item to view and choose upgrades.
- Check the required materials, select upgrades, and confirm; RSI can craft missing ingredients when recipes are available.

### One-key container transfer

- Press `F` in a chest, machine input, or other container to transfer its contents to the current target.
- Press `G` to switch the target between a bound RS network and a Sophisticated Backpack.
- Transfers respect filters, item blacklists, and destination capacity; rejected items remain in the source container.
- Transfer keys are suppressed while typing in chat, search boxes, and other text fields.
- `F` transfer is also disabled in player-inventory and crafting-accessor contexts where Minecraft needs the key for normal offhand swapping.

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

- Connect compatible backpack upgrades to RS for magnetic pickup, item pickup, feeding, restocking, or depositing items into storage.
- RSI also adds storage-aware upgrades, and can combine eligible Majrusz's Accessories when its compacting upgrade is installed.

### Compatibility highlights

The integration layer also covers Miner's Delight Copper Pots, Terra Curio's renamed workshop, History Stages recipe locks, and native EMI craft-button placement. These modules are optional and are discovered at runtime; installing one does not make it a hard dependency.

### FTB Quests item tracking

- Items crafted through RSI or added to storage can count toward FTB Quests item tasks.
- Submit quest items directly from storage, scan storage for matching tasks, and bookmark anything still missing. Optional sidebar buttons can be disabled.
- Requires FTB Quests and FTB Teams. When another mod already handles automatic item-task progress, RSI steps aside.

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
| `F` | Container / storage terminal | Deposit container contents, or fill the storage search box from the selected JEI/EMI item |
| `G` | Any/container screen | Toggle RS / Sophisticated Backpack transfer target |
| `Y` | Any screen | Toggle the RS side panel |
| `H` | Any screen | Toggle the Machine Management Center |
| `P` | Any screen | Open/close active crafting progress |
| `;` | In world | Bind nearby supported machines with a Network Linker in hand, inventory, or Curios |
| `Alt` + right-click | In world | Bind the held network terminal to the targeted machine |
| `Alt` + left-click | JEI ingredient | Filter by mod |
| `Alt` + middle-click | JEI ingredient list | Clear search |
| `Ctrl+T` | JEI recipe | Transfer to RS crafting grid |
| `Ctrl` + left-drag | RS grid | Swipe-extract one of each item |
| Left-drag, then `A` / `H` / `Esc` | JEI list | Bookmark / hide / clear marquee selection |
| `U` / `R` | Hovered side-panel item | Show uses / recipes in JEI |
| `Space` | Villager / enchantment screen | Refill trade items / lapis from storage |

## Mod compatibility

The list below is a guide to supported machine automation and add-on features. Optional mods only need to be installed when you want their integration.

| Mod | Supported recipes or machines |
|---|---|
| JEI / EMI | Recipe-browser craft and machine buttons, recipe transfer, missing-material bookmarks, Tetra material tools, storage pull, and dedicated FTB/Distant Worlds/PMMO categories |
| Vanilla / Brick Furnace | Furnace, blast furnace, smoker, campfire, brewing stand, stonecutter, smithing, anvil, enchanting |
| Iron Furnaces | Furnace tiers in furnace, blasting, and smoking modes, including factory/rainbow stack batches and multi-machine balancing |
| Sophisticated Storage | Chest upgrades |
| Botania | Mana Pool/catalysts, Petal Apothecary, Runic Altar with automatic wand activation, Botanical Brewery, Alfheim trade, Terra Plate, Pure Daisy |
| Improved Botania Pools | Alfheim, Asgard, Muspelheim, and Nilfheim Mana Pools, using Botania infusion recipes, catalysts, batching, and load balancing |
| MythicBotany | Mana Infuser recipes, including custom recipes supported by the modpack |
| Ars Nouveau | Imbuement Chamber, Enchanting Apparatus, and recursive Scribes' Table glyph crafting; either physical table half can be bound |
| Iron's Spells 'n Spellbooks | Scroll Forge and Arcane Anvil scroll creation/upgrades, including Refined Mod, T.O Magic 'n Extras, Peyro's Scythe, GTBC's Geomancy Plus, and other compatible addon scrolls |
| Apprentice Codex | Essence Smoker and Spellcaster Workbench |
| ISS CSW | Spell Forge and multi-scroll spell-mixing recipes |
| Goety / Goety Awaken | Infusers, brazier, altar, cage, candlestick, and supported rituals |
| Malum | Spirit Altar, Spirit Crucible, Runic Workbench, Spirit Infusion and related recipes; adjacent altars collect outputs independently and nearby Catalyzers/Runewood Obelisks retain their acceleration effects |
| Eidolon | Worktable, Crucible, and Brazier |
| Forbidden & Arcanus | Hephaestus Forge, Clibano, smithing/apply-modifier flow |
| Wizards Reborn | Crystallizer, Arcane Iterator, Workbench, and Crystal Ritual |
| Touhou Little Maid | Maid Altar, including automatic P-point replenishment from RS |
| Wishing Fountain | Built fountain binding and biome/structure map wishes; weather wishes without item outputs remain manual |
| Embers Rekindled | Alchemy Tablet, including automatic trial-and-error code inference, saved inference results, and deterministic layouts |
| Aetherworks | Aetherium Anvil and Forge Tool Station, including automatic hammering, material refilling, forge-lever temperature control, and temperature/ember/hit-progress HUDs |
| The Aether | Freezer, Incubator, and Altar |
| Crock Pot | Crock Pot, Portable Crock Pot, and Birdcage parrot feeding/egg recipes |
| Farmer's Delight | Cooking Pot, Skillet, Cutting Board, and compatible Arcane Stove recipes |
| Farmer's Respite | Kettle |
| Youkai's Homecoming | Moka Pot, Fermentation Tank, single- or multi-layer Steamer, Kettle, cooking pots, Cuisine Board |
| Gensokyo Delight | Fermentation Tank, Steamer, Kettle, cooking pots, and Cuisine Board across renamed field layouts |
| Immortaler's Delight | Old and new Enchantal Cooler variants; Hot Spring Bucket resources can participate through Resonance Disks |
| Apotheosis | Fletching, Gem Cutting, Enchantment Library, Reforging, and Spawner upgrades |
| TACZ and compatible gun packs | Gun Smith Table |
| Avaritia | Compressed through Extreme six-tier crafting tables and Extreme Smithing |
| Until Eternity | Recursive 5×5 End Crafting Table recipes; “I'm Full!” in a Resonance Disk activates the mod's food and thirst protection, and Vibrant Amethyst grants Amethyst Blessing through the mod's native handler |
| SlashBlade | Crafting recipes |
| Confluence | Workshop |
| Distant Worlds | Lithum Altar and related interactions; crosshair HUD for recipe, energy, recovery, and all eight pedestal states |
| Lychee | Virtual item-inside/soaking and block-interaction recipes; block interactions can run through a bound Mechanical Press |
| Farming for Blockheads | Market exchanges that can also be used as crafting steps |
| Project MMO | Salvage recipes |
| Crabber's Delight | Crab Trap processing |
| Tetra | Workbench material filtering, Ctrl-hover result preview, and configurable JEI material sorting |
| Miner's Delight | Copper Pot recipes with container-aware batching |
| Terra Curio | Workshop recipes, binding and container-transfer protection |
| FTB Teams / FTB Quests | Storage-backed submission, item-task progress, bulk claiming, and optional sidebar actions |
| Beyond Dimensions | An alternative storage network for supported crafting, machine access, and JEI extraction |
| Curios | Linker discovery in accessory slots, resonance/passive integrations, and FTB reward-item detection |
| FTB Chunks / Cadmus | Claim-aware machine binding and remote operation checks |
| Sophisticated Backpacks | Remote GUI/transfer support and RS-connected backpack upgrades |
| History Stages | Recipe availability respects stage locks during planning |
| Construction Wand | Blocks in connected storage count toward wand building |
| Easy Villagers / Trade Cycling / Trade Refresh / Retraining | Favourite JEI items can prevent a villager trade from being rerolled |

Custom GUI defaults include Crabber's Delight, Metal Barrels, PGP, EMX Arms, Apotheosis, and Ancient Reforging. They can be bound and opened remotely even when RSI does not automate their recipes.

## Configuration

Configuration files are documented in-place with comments and validation ranges:

- `config/rs_integration/common.toml`: shared feature switches and optional integrations.
- `saves/<world>/serverconfig/rs_integration/server.toml`: server crafting and machine behavior.
- `config/rs_integration/client.toml`: side panel, display options, JEI tools, and Tetra preview settings.

Settings files include descriptions and valid value ranges. Most settings are kept during upgrades; new list entries may need to be added manually.

Remote crafting uses the server-side `autoCrafting.freeWaterMachines` allowlist for automatic water refills. Listed machine types refill for free; omitted types consume water from the selected RS network's fluid storage, not water bucket or bottle items. Set the list to `[]` to disable all free refills.

Defaults cover Farmer's Respite's kettle, Youkai's Homecoming's kettle/fermentation tank/moka pot/steamer, Iron's alchemist cauldron, Eidolon's crucible, and Botania's petal apothecary. The config comments list their exact machine IDs. Existing water remains usable; heat sources, ingredients, and containers are still required. Moka pots, steamers, and petal apothecaries consume 1000 mB when changing from empty to water-filled; other tanks consume only the refill deficit. Eidolon crucibles refill to at least 1000 mB to satisfy their native full-bucket check.

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
