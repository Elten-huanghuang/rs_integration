# RS Integration 1.3.2

**Release Date:** August 11, 2026

This release includes all changes from `1.3.1-fix` to `1.3.2`. It focuses on improving Refined Storage special searches, complex recipe previews, multiplayer batch crafting, recipe-tree material accounting, and JEI integration.

## Fixes

1. Fixed Sol Carrot's "Eaten/Not Eaten" status being incorrectly stored in the shared static tooltip cache.
2. Fixed several issues with Goety sacrifice, conversion, and summoning rituals, and added clearer confirmation messages.
3. Small synchronous crafting jobs now display a completion message instead of finishing without feedback.
4. Fixed several reusable catalyst recipes. Reusable crafting hammers are no longer multiplied by the batch size.
5. Fixed single-layer steamer structures not being recognized.
6. Fixed batch quantities being scaled twice between the recipe tree and the total requirements view.
7. Fixed the Dimensional Feeding Upgrade not working correctly.
8. Fixed recursive crafting slowdowns caused by wool and dye conversion loops.
9. Fixed the Dark Altar being unusable under certain conditions.
10. Stack-based auto-feeding now correctly recognizes Gnaw's Gift.
11. Fixed the recipe transfer key using `F` in certain screens and preventing normal offhand swapping.
12. Fixed potion charm conversion and material recognition. Missing-material names such as Apotheosis Eternal Potion Charms are now formatted correctly instead of displaying `Format error: %s`.
13. Wizards Reborn Crystal Infusion no longer requires exact NBT for crowns unless the recipe explicitly declares it. Durability and unrelated runtime data will no longer prevent matching.
14. Improved SlashBlade smithing compatibility with dynamic blade NBT and assembled outputs. The known empty `rodai` recipe override has also been fixed.
15. Fixed machine panels not closing correctly in some situations.

## New Features and Improvements

1. Reworked Refined Storage searches for `#` tooltips, `$` tags, and `@` mod names. Results now appear progressively instead of freezing for several seconds. Search data is also prewarmed during gameplay and cached for future sessions.
2. Missing-material names can now be clicked directly to add individual items to JEI bookmarks.
3. Added reusable-catalyst recipe preference. For example, compatible ore-processing requests can prefer the Gold Orchid crafting route.
4. Adjusted the total requirements interaction layer so individual JEI bookmark buttons are no longer covered by other UI elements.
5. Added nearby machine binding with the semicolon `;` key:
   - Wireless connectors can remain equipped in Curios slots.
   - Already-bound machines are skipped and never added twice.
   - Clear messages are shown for invalid networks, replaced connectors, multiple equipped connectors, and scans already in progress.
   - FTB Chunks claims are respected. Machines inside claims where the player lacks permission will not be bound.
   - The completion message reports discovered, newly bound, already bound, permission-blocked, and invalid machines separately.
6. Added expanded kettle crafting support, including one-click crafting for additional kettle recipes.
7. Added no-gain conversion protection for wool colors, concrete powder, wood families, and similar materials. This prevents recursive searches from cycling through equivalent variants while preserving exact-output conversions and recipes with a real quantity gain.
8. Added compatibility support for Ars Nouveau add-ons.

## Compatibility

- The recipe preview data format has changed, and the network protocol has been updated from version 29 to 30. Clients and servers must both use RS Integration `1.3.2`.
- Older versions will be rejected during connection instead of continuing with incompatible packet formats.
- Updating will not remove existing Refined Storage networks, machine bindings, or search disk caches.
