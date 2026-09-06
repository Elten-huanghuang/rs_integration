# JEI Cheat Shortcuts

With JEI cheat mode enabled, hover an item in the ingredient list or bookmarks:

| Default shortcut | Action |
| --- | --- |
| Ctrl + left mouse button | Give one item to inventory through JEI |
| Ctrl + right mouse button | Give one maximum-size stack to inventory through JEI |
| Ctrl + Q | Spawn and throw the displayed stack, capped at the item's stack limit |

All three bindings appear under Options > Controls > Key Binds > RS Integration.
Each can be rebound to a keyboard key or mouse button, with Forge modifiers,
or unbound. Modifier matching follows Forge's key-binding rules. If these three
bindings overlap, only one action runs: give one, then give stack, then drop.

The give actions retain JEI's server permission checks and require JEI on the
server. The drop action requires server permission level 2. It does not consume
items from player or storage inventories. Item NBT is preserved.

Shortcuts are inactive when JEI integration, the overlay, or cheat mode is
disabled, or a text field/JEI search field has keyboard focus. Avoid assigning
the same input as another JEI action; normal key-binding conflict rules apply.

These gestures are adapted from the user-supplied `jei_cheat_mod-source.tar.xz`.
The standalone shortcut mod should not be installed alongside this integration.
Client and server must use this same RSI build (network protocol 44), even though
the requested mod version remains 1.4.2. The drop packet does not require JEI on
the dedicated server and does not load client classes there.
