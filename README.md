<p align="center">
  <img src="src/main/resources/rs_integration_logo.png" width="96" alt="RS Integration logo">
</p>

<h1 align="center">RS Integration</h1>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">简体中文</a>
</p>
<p align="center"><strong>Minecraft 1.20.1 · Forge 47+ </strong></p>
<p align="center">
  <a href="https://modrinth.com/mod/rs-integration"><img src="https://img.shields.io/modrinth/dt/vtvveeqA?style=flat&amp;logo=modrinth&amp;label=Modrinth" alt="Modrinth downloads"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/rs-integration"><img src="https://img.shields.io/curseforge/dt/1630184?style=flat&amp;logo=curseforge&amp;label=CurseForge" alt="CurseForge downloads"></a>
  <a href="https://www.mcmod.cn/class/29199.html"><img src="https://img.shields.io/badge/MC%E7%99%BE%E7%A7%91-29199-2f855a?style=flat" alt="MC百科"></a>
</p>

RS Integration (RSI) connects **Refined Storage** and **Beyond Dimensions** to modded machines: choose a recipe, preview its dependencies, and let RSI supply materials and run supported machines.

**Current version: 1.6.0.1 · Minecraft 1.20.1 · Forge 47+**

1.6.0.1 adds selectable material sources and quick theme switching, with further improvements to large crafting plans. [Release notes (Chinese)](docs/announcements/RELEASE_NOTES_1.6.0.1.md).

## Requirements

| Dependency | Requirement |
|---|---|
| Minecraft / Forge | 1.20.1 / 47+ |
| JEI | Required on the client for recipe actions and previews |
| Refined Storage | 1.12+, optional |
| Beyond Dimensions | Optional alternative storage backend |
| EMI and other integrations | Optional; EMI support still requires JEI |

Install the same RSI version on the client and server. Ordinary crafting-table recipes can be crafted recursively from the player inventory without either storage mod. Optional modules load only when their target mod is installed.

## Features

- **Recursive crafting:** preview the recipe tree, missing materials, alternatives, and machine conditions before starting. Choose inventory, the current RS/BD network, and Sophisticated Backpacks as material sources.
- **Machine access:** bind supported machines, open their GUIs remotely, and share repeated work across compatible machines. Remote actions respect supported claims and do not force-load chunks.
- **Crafting control:** manage plans, active tasks, and bound machines with `P`; inspect steps and materials, then cancel when needed.
- **Storage:** browse items and fluids together in the mixed terminal. The Myriad Realms Guixu Disk stores items, fluids, and magic resources in one RS disk.
- **Resonance:** supported stored items can provide passive effects and resources. Resonant Mana Pools and Source Tanks connect storage with native Botania and Ars Nouveau transfer paths.
- **Modpack tools:** one-key container transfer, food ordering, Auto Eat, trade/reforging replenishment, FTB Quests, and Sophisticated Backpack integrations.
- **Customizable UI:** searchable plans and machine lists, pinyin-aware search, theme presets, and an in-game configuration editor.

RS and BD remain separate storage networks; their balances are never combined automatically. See the [user guide](docs/guide/README.md) for feature details and limits.

## Quick Start

1. Install RSI and JEI; connect an RS or BD network if desired.
2. For machine recipes, hold a compatible network linker or terminal and use `Alt` + right-click to bind a supported machine, or press `;` nearby to scan.
3. Open a recipe in JEI/EMI and click the RSI craft action.
4. Review the plan; choose material sources, recipes, quantity, and output destination, then confirm.
5. Press `P` to view progress and manage machines. Cancel in the screen or with `/rsi cancel`.

## Controls

Default bindings; change them in Minecraft Controls.

| Key | Action |
|---|---|
| `P` | Open the RSI control center: plans, progress, and bound machines |
| `Alt` + right-click / `;` | Bind the targeted machine / scan nearby machines |
| `F` | Transfer container contents; in RS/BD terminals, fill search from JEI/EMI |
| `G` | Switch container-transfer target between RS and Sophisticated Backpack |
| `Y` | Toggle the RS side panel (disabled in config by default) |
| `Space` | Replenish supported trades, lapis, and anvil/reforging materials |

[All controls](docs/guide/README.md#controls)

## Compatibility

Integrations include Botania, Ars Nouveau, Goety, Malum, Embers/Aetherworks, Farmer's Delight and addons, Apotheosis, Iron's Spells 'n Spellbooks, and more. Support is specific to the listed recipes and machines; native heat, structure, fuel, and manual ritual steps may still be required.

[Full compatibility table and machine requirements](docs/guide/README.md#compatibility)

## Developer API

See the [public API guide](docs/api/README.md) for recipe, machine, execution, resonance inventory, and Consumer integrations, including their licensing boundaries.

## Configuration

Use the in-game editor from JEI's config button, or edit the client, common, storage, and world server configs. See [configuration details](docs/guide/README.md#configuration), [plan UI settings](docs/config/plan-ui.md), and the [planning example](docs/config/planning.example.toml).

## Diagnostics

[Commands and recovery tools](docs/guide/README.md#diagnostics) cover cancellation, item/fluid refunds, administrator disk recovery, and troubleshooting.

## Building

Requires JDK 17:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat jarJar verifyReleaseJar --no-daemon
```

Verified release JARs are written to `build/libs/`.

## License

RSI is proprietary software. The [LICENSE](LICENSE) permits official unmodified releases in modpacks, monetized gameplay videos/streams, and use of the documented public API under its interoperability terms.

Contributions require the agreement in [CONTRIBUTING.md](CONTRIBUTING.md). Third-party boundaries are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
