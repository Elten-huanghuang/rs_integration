<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">简体中文</a>
</p>
<p align="center"><strong>Minecraft 1.20.1 · Forge 47+ </strong></p>

RS Integration (RSI) extends Refined Storage and Beyond Dimensions with recursive crafting, remote machine access, storage-aware interactions, and optional magic-resource integration. It is an add-on, not a replacement for either storage mod.

**Current version: 1.5.2 | Minecraft 1.20.1**

[1.5.2 release notes (Chinese)](docs/RELEASE_NOTES_1.5.2.md)

[MC百科介绍](https://www.mcmod.cn/class/29199.html)

## Contents

[Requirements](#requirements) · [Features](#features) · [Quick Start](#quick-start) · [Controls](#controls) · [Compatibility](#compatibility) · [Developer API](#developer-api) · [Configuration](#configuration) · [Diagnostics](#diagnostics) · [Building](#building)

## Requirements

| Dependency | Requirement |
|---|---|
| Minecraft / Forge | 1.20.1 / 47+ |
| Refined Storage | 1.12+, optional; inventory-only recursive crafting remains available without it |
| JEI | Required on the client for recipe actions and plan previews |
| Other integrations | Optional; each module loads only when its target mod is installed |

## Features

### Recursive crafting

- Start from JEI or EMI and preview the graph, required steps, available/missing materials, alternatives, probability estimates, and repeat count before starting.
- Uses the player inventory plus separately resolved RS and BD networks, follows recipes through crafting tables, machines, trades, and inventory-only crafting, and never adds RS and BD balances together.
- Handles alternatives, probabilistic ingredients, multi-output recipes, byproducts, remainders, container transformations, and fluid-container recipes. Input buffering and batch conversion reduce repeated dispatch for supported machines.
- DAG execution, reservations, machine/capture leases, input buffers, and output accounting keep materials conserved across recursion, cancellation, unloaded machines, disconnects, and failures.
- Returns outputs, containers, and unused materials where supported. Unplaceable fluids become refund vouchers; `/rsi_fluid_refund` restores vouchers held after an inventory-full or offline event.
- Shows progress with `P`; cancel from the progress screen or with `/rsi cancel`.

### Workbench-style instant batching

| Category | Current integrations | Execution boundary |
|---|---|---|
| Stable vanilla batches | Shaped/shapeless crafting and stonecutter recipes | Uses real `assemble/getRemainingItems`; one operation represents the remaining stable executions, with results split at `Integer.MAX_VALUE` |
| Bounded instant machines | Apotheosis Fletching/Gem Cutting, Iron's Spellbooks Scroll Forge/Arcane Anvil, Apprentice Codex Spellcaster Workbench, ISS CSW Spell Forge, Eidolon Worktable, Biomancy Bio-Forge, eligible Avaritia compressed/extreme tables, and Malum Runic Workbench | No native processing tick; at most 64 executions per batch |
| Multiplicative pure exchange | Farming for Blockheads Market | One operation can represent every remaining exchange; `craftingVirtualExchangeBatchLimit=0` adds no extra cap, while a positive value bounds each batch |
| Logical recipe families | RSI fluid-container conversion, SlashBlade workbench recipes, Lychee item-inside recipes, Immortaler's Delight Hot Spring, and Malum Void Favor | No continuously running physical machine is required; multiplication depends on the matching delegate and recipe stability |

Products beyond one `ItemStack.getCount()` are emitted as multiple real stacks instead of being silently saturated or discarded.

### Machine access

- Bind machines with a Network Linker, `Alt` + right-click, or `;` to scan nearby machines; broken links are cleaned up automatically.
- Open bound machines remotely, inspect state, collect outputs, send inputs, and use the `H` Machine Center with favourites and compact tabs.
- Multiple compatible machines can share repeated work, while per-machine operation limits and leases prevent overlapping automation.
- Custom-GUI bindings cover machines that can be opened remotely even without recipe automation.
- Respects FTB Chunks/Cadmus claims, never force-loads distant chunks, rate-limits remote GUI requests, and revalidates every server-side action.

### Resonant containers

- The Resonant Mana Pool extends Botania's native `ManaPoolBlockEntity`, preserving dropped-item manual infusion, sparks, receivers, output mode, and native mana rendering.
- The Resonant Source Tank extends Ars Nouveau's native `SourceJarTile`, so relays, splitters, and source consumers use the normal Ars transfer path instead of recipe-specific patches.

### Myriad Realms Guixu Disk

- One disk stores items, fluids, and magic resources in the native RS disk drive. Item and fluid entry limits, per-entry payload size, and total payload size are configurable.
- `MANA`, `SOURCE`, and `SOUL` remain separate resource rows. `MANA` and `SOUL` can feed RSI recursive crafting; `SOURCE` remains on the native Ars transfer path.
- NBT-sensitive variants retain identity. The disk participates in storage caches, automation devices, the mixed terminal, JEI/EMI lookup, and recursive item/fluid extraction.
- Existing disks expand automatically when entry limits are raised. Save verification, crash-safe dirty handling, drop protection, void recovery, and UUID-based administrator recovery reduce data-loss risk.
- Missing items or fluids from uninstalled mods are quarantined without blocking normal stock; their original data remains preserved and counts toward capacity.
- Infinite RS disks can be converted when the matching recipe is available.
- RS and Beyond Dimensions remain separate inventories. Their balances are never added together automatically.

### Resonance Storage Disk

- RS resonance disks satisfy attributes, inventory ticks, event-driven passives, and item/resource lookups as if carried by the player; supported mutations are written back instead of ticking disposable copies.
- The passive engine combines attribute scanning, configurable tick simulation, and event hooks for items such as potion charms, Pyromancer Staff, Artificial Flower, Spectral Eye Amulet, Nine Sword Books, Terra curios, and infinite potions.
- TaCZ loose ammunition participates in reloads with NBT and its real round count; the gun HUD includes matching disk ammunition, and Shift transfers debit only after a successful disk write.
- Stored powder snow, source, venom, hot-spring fluid, and similar resources participate in recursive crafting; Malum Void Favor and its transmutations can be unlocked through the well interaction.
- The disk opens a 36-slot Resonance Backpack view and cannot be dismantled by the RS `Shift` + right-click shortcut. Only one RS resonance disk is active per network.
- The BD Resonance Network Generator provides the corresponding disk-like passive storage. Right-click to bind a BD network, keep it in the inventory, and let generators on the same network merge when space allows.
- BD resonance access follows network permissions and stores its own Void Favor state. The generator remains a separate BD space and never enters BD `UnifiedStorage`.

### Other modpack integrations

- **Mixed terminal:** shows items and fluids in one searchable list, supports filters, JEI transfer, and container filling/draining, with safe rollback when a container cannot accept the result.
- **Fluid-container recipes:** a dedicated category shows filling, draining, and remainder recipes, checks output storage before execution, and supports bookmarking and recipe transfer.
- **RS terminal modes:** switches between crafting, stonecutter, smithing, and anvil workstations; JEI transfer follows the selected mode and falls back to the native 3x3 grid when needed.
- **Recipe actions:** supported recipes receive RSI craft buttons, while linked machines may also receive a button that opens them directly.
- **JEI recipe views:** dedicated pages cover FTB Quests item submission, the Distant Worlds Lithum Altar, and Project MMO salvage.
- **JEI shortcuts:** bookmark missing ingredients, filter by mod, send recipes to an RS grid with `Ctrl+T`, or pull a stack from storage with the middle mouse button.
- **Side panel:** press `Y` to open or close it (disabled by default) and search storage or reach linked machines without leaving the current screen; use `U`/`R` for uses or recipes.
- **Stock overlays:** show stored counts and missing craft amounts; marquee selection can bookmark or hide multiple entries at once.
- **RS search:** supports name, mod (`@`), tag (`$`), tooltip (`#`), and pinyin across item/fluid caches, with background indexing, history, favourites, deletion, and configurable persistent caching.
- **Terminal shortcuts:** press `F` in an RS/BD terminal to fill the search box with the selected JEI/EMI item; hold `Ctrl` and drag across RS slots to take one item from each.
- **Tetra material filtering:** the workbench shows fitting materials, `Ctrl`-hover previews the result, and the list can be sorted by useful stats.
- **Configuration editor:** the JEI config button opens an in-game editor for client, common, server, and storage settings, including search, validation, reset, restart warnings, and saving.
- **Machine automation:** adapters use native recipe and transfer paths where possible and load only with their target mod. Embers solves and remembers Alchemy Tablet codes and runs its Furnace, Mixer Centrifuge, Stamper, and Mini Boiler; Aetherworks automates hammering, refilling, temperature control, and Metal Former item/fluid input.
- **Fluid supply:** water-consuming machines can refill from the selected RS network; `autoCrafting.freeWaterMachines` controls which machines refill without consuming network fluid.
- **Ritual and machine modules:** Goety prepares rituals without activating them; Better Summoning Rituals prepares altars without starting them or collecting world drops; Biomancy supplies Digester, Bio-Lab, Decomposer, and Bio-Forge recipes; Cthulhu Creatures drives counted Flesh Altar batches with multi-altar support.
- **Distant Worlds:** configurable research bypass, Firon failure removal, nearby furnace fuel refill, search radius, per-refill limit, and fuel priority.
- **Cooking automation:** Farmer's Delight, Farmer's Respite, Youkai's Homecoming, Dungeon's Delight, Twilight Delight, BOSSes' Delight, Casualness Delight, Farm & Charm, Candlelight, Bakery, Miner's Delight, Crock Pot, and Gensokyo Delight preserve their native heat, container, batch, fluid, and world-drop rules.
- **HUDs and prompts:** Aetherworks shows hammering, temperature, ember, refill, and automation state; Distant Worlds shows altar, energy, recipe, and pedestal state and can be disabled with `distantWorlds.enableHud`; craft progress and binding hints remain visible in-world.
- **Auto Eat:** SolCarrot diversity, Diet balance, and stack feast support food/effect blacklists, remainder return, activation requirements, execution cost, and per-use limits.
- **Trading and replenishment:** `Space` restocks villager payments, enchanting lapis, anvils, and Apotheosis/Ancient Reforging materials; missing trade items are shown and added to JEI favourites.
- **Apotheosis:** the Enchantment Library scans and merges RS stock with server-side validation and safe returns; spawners support upgrade selection, material planning, recursive supply, and RS/BD access.
- **Container transfer:** `F` moves container contents to the current RS/BD network or to a Sophisticated Backpack selected with `G`, respecting filters, blacklists, capacity, text fields, and vanilla `F` contexts.
- **Sophisticated Backpacks:** magnet, pickup, feeding, refill, deposit, and void upgrades can use RS; the dimensional magnet supports world fluid collection, JEI fluid filtering, ghost-slot controls, and container/upgrade safety checks.
- **FTB Quests:** crafted/stored item progress, storage-backed submission, missing-task scans, bulk claim-all, and optional sidebar buttons; existing authoritative task handlers take priority.
- **Custom GUIs:** Crabber's Delight, Metal Barrels, PGP, EMX Arms, Apotheosis, Ancient Reforging, enchanting tables, Enchanting Infuser variants, and Tetra workbenches can be bound and opened remotely.
- **Small tools:** JEI favourites protect trades, Construction Wands count stored blocks, RSI Void Upgrades discard matching incoming items, retained-item rules protect minimum stock, and Miner's Delight, Terra Curio, History Stages, and native EMI placement remain integrated.

## Quick Start

1. Build or connect an RS network and obtain a Network Linker.
2. Bind a supported machine with `Alt` + right-click, or press `;` nearby.
3. Open a recipe in JEI/EMI and use the RSI craft action.
4. Review the plan, choose alternatives if needed, and start.
5. Press `P` for progress. Without a storage network, outputs return to the player when possible.

## Controls

All keys can be changed in Minecraft Controls.

| Key | Action |
|---|---|
| `F` | Transfer container contents, or fill RS/BD search from the selected JEI/EMI item |
| `G` | Switch transfer target between RS and Sophisticated Backpack |
| `Y` / `H` | Toggle the RS side panel / Machine Management Center |
| `P` | Toggle craft progress |
| `;` | Bind nearby supported machines |
| `Alt` + right-click | Bind the held terminal to the targeted machine |
| `Alt` + left-click | Filter JEI ingredients by mod |
| `Alt` + middle-click | Clear the JEI ingredient search |
| `Ctrl+T` | Send a JEI recipe to the RS crafting grid |
| `Ctrl` + left-drag | Take one item from each RS slot |
| Left-drag, then `A` / `H` / `Esc` | Bookmark / hide / clear a JEI marquee selection |
| `U` / `R` | Show uses / recipes for the hovered side-panel item |
| `Space` | Refill villager trades, lapis, or anvil/reforging materials |

## Compatibility

Integrations are optional and discovered at runtime. JEI/EMI use the target mod's native categories and, where supported, native workstation entries.

<details>
<summary><strong>Full compatibility table</strong></summary>

| Mod | Supported recipes or machines |
|---|---|
| JEI / EMI | Recipe-browser craft and machine buttons, recipe transfer, missing-material bookmarks, Tetra material tools, storage pull, and dedicated FTB/Distant Worlds/PMMO categories |
| The Long Travail | Spring Crown JEI variants use the selected flower's NBT as the recursive target |
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
| Better Summoning Rituals | Remote altar preparation, counted materials, catalysts, rollback, and conflict recovery; final activation and world-drop collection stay manual |
| Biomancy | Digester, Bio-Lab, Decomposer, and Bio-Forge with nutrient/fuel handling, item/fluid input, batching, and native machine rules |
| Cthulhu Creatures | Flesh Altar recipes with counted materials, batch insertion, multi-altar execution, remote GUI, and recursive planning |
| Malum | Spirit Altar, Spirit Crucible, Runic Workbench, Spirit Infusion and related recipes; adjacent altars collect outputs independently and nearby Catalyzers/Runewood Obelisks retain their acceleration effects |
| Eidolon | Worktable, Crucible, and Brazier |
| Forbidden & Arcanus | Hephaestus Forge, Clibano, smithing/apply-modifier flow |
| Wizards Reborn | Crystallizer, Arcane Iterator, Workbench, and Crystal Ritual |
| Touhou Little Maid | Maid Altar, including automatic P-point replenishment from RS |
| Wishing Fountain | Built fountain binding and biome/structure map wishes; weather wishes without item outputs remain manual |
| Embers Rekindled | Alchemy Tablet, Melter, Mixer Centrifuge, Stamper, and Mini Boiler; the Alchemy Tablet supports automatic trial-and-error code inference, saved inference results, and deterministic layouts |
| Aetherworks | Aetherium Anvil, Forge Tool Station, and Metal Former, including automatic hammering, material refilling, forge-lever temperature control, and temperature/ember/hit-progress HUDs. Metal forming supports recursive item and RS fluid inputs; attach it to a complete forge, supply ember, and empty its item slot and fluid tank first |
| The Aether | Freezer, Incubator, and Altar |
| Crock Pot | Crock Pot, Portable Crock Pot, and Birdcage parrot feeding/egg recipes |
| Farmer's Delight | Cooking Pot, Skillet, Cutting Board, and compatible Arcane Stove recipes |
| Dungeon's Delight | Monster Pot recipes with serving-container planning, input buffering, and concurrent pots using native monster heat sources |
| Farmer's Respite | Kettle |
| Youkai's Homecoming | Moka Pot, Fermentation Tank, single- or multi-layer Steamer, Kettle, cooking pots, Cuisine Board |
| Twilight Delight | Fiery Cooking Pot with its native self-heating behavior and Farmer's Delight cooking recipes |
| BOSSes' Delight | Soul Cooking Pot with cold-source validation, container handling, and batch cooking |
| Casualness Delight | Deep Frying Pan with native heat validation and captured world-drop results |
| Farm & Charm | Cooking Pot and Roaster with native heat, container, and batch rules |
| Candlelight | Large Cooking Pot / copper cooking pot using Farm & Charm cooking recipes and Candlelight stoves |
| Bakery | Small Cooking Pot using the shared Farm & Charm cooking recipe and heat rules |
| Gensokyo Delight | Fermentation Tank, Steamer, Kettle, cooking pots, and Cuisine Board across renamed field layouts |
| Immortaler's Delight | Old and new Enchantal Cooler variants and Hot Spring infusion recipes; Hot Spring Bucket resources can participate through Resonance Disks |
| Apotheosis | Fletching, Gem Cutting, Enchantment Library, Reforging, and Spawner upgrades |
| TACZ and compatible gun packs | Gun Smith Table |
| Avaritia | Compressed through Extreme crafting tables, Extreme Smithing, Neutron Collector, Compressed Chest, and related machines |
| Until Eternity | Recursive 5×5 End Crafting Table recipes; “I'm Full!” in a Resonance Disk activates the mod's food and thirst protection, and Vibrant Amethyst grants Amethyst Blessing through the mod's native handler |
| SlashBlade | Crafting recipes with NBT-sensitive blade conditions |
| Confluence | Workshop |
| Distant Worlds | Lithum Altar and related interactions; crosshair HUD for recipe, energy, recovery, and all eight pedestal states |
| Lychee | Virtual item-inside/soaking and block-interaction recipes; block interactions can run through a bound Mechanical Press |
| Farming for Blockheads | Market exchanges that can also be used as crafting steps |
| Project MMO | Salvage recipes |
| Crabber's Delight | Crab Trap processing |
| Tetra | Remote workbench GUI, material filtering, Ctrl-hover result preview, and configurable JEI material sorting |
| Miner's Delight | Copper Pot recipes with container-aware batching |
| Terra Curio | Workshop recipes, binding and container-transfer protection |
| FTB Teams / FTB Quests | Storage-backed submission, item-task progress, bulk claiming, and optional sidebar actions |
| Beyond Dimensions | An alternative storage network for supported crafting, machine access, and JEI extraction |
| Curios | Linker discovery in accessory slots, resonance/passive integrations, and FTB reward-item detection |
| FTB Chunks / Cadmus | Claim-aware machine binding and remote operation checks |
| Sophisticated Backpacks | Remote GUI/transfer, RS-connected magnet/pickup/feeding/refill/deposit/void upgrades, world-fluid collection, and Majrusz compacting |
| Enchanting Infuser | Remote GUI binding for the normal and advanced enchanting infusers |
| History Stages | Recipe availability respects stage locks during planning |
| Construction Wand | Blocks in connected storage count toward wand building |
| Easy Villagers / Trade Cycling / Trade Refresh / Retraining | Favourite JEI items can prevent a villager trade from being rerolled |

</details>

Mini Boiler boiling recipes use RS fluids and batch by input tank, gas tank, and remaining operations. Keep the connected machine supplying waste heat, bind the boiler itself, and disable output extraction pipes during crafting. Built-in conversions are 1 mB water to 5 mB steam and 1 mB dwarven oil to 1 mB dwarven gas.

Custom GUI defaults include Crabber's Delight, Metal Barrels, PGP, EMX Arms, Apotheosis, and Ancient Reforging, so they can be bound and opened remotely even without recipe automation.

## Developer API

RS and BD expose separate APIs. They do not share inventories or merge balances.

RS magic resources are available through [`RsiMagicApi`](src/main/java/com/huanghuang/rsintegration/api/magic/RsiMagicApi.java):

```java
RsiMagicApi.Session session = RsiMagicApi.network(network);
int available = session.available(MagicResourceKind.SOUL);

try (MagicResourceNetworkPool pool =
             session.reserve(MagicResourceKind.SOUL, available)) {
    int extracted = pool.withdraw(available);
    // Use the extracted souls.
}
```

BD magic resources are available through [`BdMagicApi`](src/main/java/com/huanghuang/rsintegration/api/magic/BdMagicApi.java):

```java
BdMagicApi.Session session = BdMagicApi.network(player, networkId);
long souls = session.amount(BdMagicApi.SOUL_KEY);
```

- `RsiMagicApi.Session` provides `amount`, `available`, `insert`, `extract`, and `reserve`.
- `BdMagicApi` provides `MANA_KEY`, `SOURCE_KEY`, `SOUL_KEY`, and matching `Session` methods.
- `BdMagicApi.registerAlias(alias, canonical)` rewrites registered alias keys in BD insert/extract handlers.
- An alias key must already be registered in BD's `StackKeyRegistry`; RSI does not create third-party key types.

## Configuration

- `config/rs_integration/common.toml`: feature switches and optional integrations.
- `config/rs_integration/storage.toml`: mixed terminal, unified-disk limits, disk-manipulator batching, storage save verification, transfer packet limits, and fluid-container checks.
- `saves/<world>/serverconfig/rs_integration/server.toml`: server crafting, machine, and magic-resource behavior.
- `config/rs_integration/client.toml`: UI, JEI, and Tetra display options.

Planning limits, preferred recipes/material variants, retained-item rules, probability-attempt caps, input-buffer limits, graph/operation concurrency, and parallel delegate policies are configurable. The server-side `magicResources.pinnedBackend` only breaks ties when no active scope claims the route and never merges RS with BD.

`integrations.enableGoetySoulReverseRefill` is disabled by default. When enabled, soul consumption from an Arca refills it to capacity from the bound RS network. Attempts are limited to once per player every 20 ticks.

`integrations.goetyBrazierVirtualCandleCount` defaults to `64`. When an RSI recursive Necro Brazier craft has no physical Soul Candlesticks, this many in-memory candles advance the craft; each consumes 1 soul per tick, the recipe total remains unchanged, and manual ignition still follows Goety's original structure requirements.

`autoCrafting.freeWaterMachines` controls free water refills. Listed machines refill freely; omitted machines consume fluid from the selected RS network. `[]` disables all free refills, and the config comments list the exact default machine IDs.

Settings retain existing values during upgrades when possible. New list entries may need to be added manually.

`storage.diskManipulator.enabled` is enabled by default. All disk manipulators share the per-tick `budgetMicros` time slice and split work using `maxOperationsPerTick` and `maxItemsPerOperation`; disabling it restores the original RS behavior. `noProgressCooldownTicks` prevents repeated scans when the network or disk cannot accept more items.

Grid search caches live under the game directory at `rs_integration/cache/grid_search` and are never written into the config files. Legacy files under `config/rs_integration/grid_search_cache` migrate automatically when read.

## Diagnostics

- `/rsi cancel`: cancel the current craft.
- `/rsi_fluid_refund`: claim refund vouchers retained after an inventory-full or offline event.
- `/rsi_debug`: operator diagnostics for chains, bindings, handlers, reservations, and performance (permission level 2).
- `/rsi_unified_disk list [page]` and `recover <uuid>`: list checkpoints and recover a lost unified-disk credential (permission level 2).
- `/rsi_storage_test snapshot|simulate_insert|roundtrip <amount>`: explicit RS storage smoke tests for operators.
- `diagnosticVerboseLogging`: enable detailed resolver and dispatcher logs.

## Building

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat build --no-daemon
```

Release JARs are written to `build/libs/` and checked by `verifyReleaseJar`.

## License

All rights reserved.
