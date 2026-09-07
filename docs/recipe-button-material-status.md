# Recipe button material status

JEI and EMI custom craft buttons use PNG backgrounds, symbols and monitor parts in
`assets/rs_integration/textures/gui/recipe_buttons`. Geometry preserves the original
one-GUI-pixel border and original colors, with a fixed-size glyph centered using the
original default-font advance. The Unicode check and its shadow retain half-pixel
detail. This works for JEI's usual 13 x 13 buttons and EMI's 10 x 10 buttons without
stretching their borders or symbols. Resource-pack font overrides no longer alter
these texture-backed symbols.

The common batch channel registers material request/result packets independently of
Goety. Protocol 45 retires Goety packet IDs 30/31 and introduces IDs 137/138. Both
client and server must update together. The old Goety checker/cache/network classes
are removed; its ritual material adapter remains.

All custom recipe craft buttons request server-authoritative item material status.
Gray means pending, unavailable or unsupported; red means insufficient direct item
materials; green means sufficient item materials for one execution. This is not a
recursive-craftability or machine-readiness indicator. Fluids, energy, research,
ritual environment and permissions beyond material access still follow the existing
craft execution validation. Quest submission is not a recipe-material check.

Recipe resolution shares the execution catalog, including synthetic recipe IDs.
Ingredient extraction uses the existing handlers and counted ingredient specs.
DirectMaterialAllocator handles quantities, overlapping alternatives, repeated inputs
and NBT. Goety/Ars dynamic outputs and smithing bases identify separate cache entries.
Dynamic food-value recipes and unresolvable inputs stay gray rather than guessing.

Client results expire after two seconds, with at most 32 requests per second and 256
entries. Screen, container, world, hand or inventory changes invalidate results.
Tickets reject delayed replies after invalidation or superseding requests. The
server independently caps requests. Storage is resolved against the current player
context and selected bound machine on each check; unavailable storage snapshots
remain gray. Checks never extract or reserve items.
