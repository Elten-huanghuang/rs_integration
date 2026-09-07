# Craft Failure Diagnostics

## Implemented in 1.4.2

- Reports use cyan bold section headings, red failures, yellow suggestions and
  truncation notices, and gray technical/environment details. Section spacing and
  explicit label/value line breaks complement the existing width-aware wrapping.
  Minecraft's splitter drops empty components, so the screen explicitly retains
  an empty rendered row for paragraph spacing rather than relying on wrapping.
  Chat shows the red failure text and green underlined report link on separate
  lines within one message, preserving the existing per-task deduplication.
- Export remains UTF-8 plain text with paragraph breaks. Component colors and
  legacy localization formatting codes do not enter the exported text. Existing
  Chinese log-path highlighting is preserved in the screen. Export success is
  green; export errors are red. The presentation change does not touch execution,
  planning, report identity, data collection or the client click bridge.
- The task center opens a read-only failure report for a failed execution.
- Execution failures also post one green, underlined `[Click to view report]`
  link per task UUID. `/rsi_failure_report <uuid>` is a Forge client command;
  both entry points resolve only the local history, without OP or server actions.
  Forge 47.3.22 intercepts typed commands in `sendCommand`, but vanilla component
  clicks use `sendUnsignedCommand` and bypass that interception. A client-only
  `Screen.handleComponentClicked` HEAD injection (required target count 1) routes
  only RSI report RUN_COMMAND links through `ClientCommandHandler`. Other links
  and Shift-click handling retain vanilla behavior. No mouse hitbox is replaced.
- Opening is deferred past one END client tick and rechecks the retained UUID
  before displaying the exact report. Chat closure after submitting a command
  cannot immediately close the report. Dismissed, evicted, disconnected and
  malformed IDs produce an explicit expired-record message. Pending navigation
  is cleared on logout and guarded by a session generation.
- First receipt time is captured on the client with its timezone, together with
  graph/flat mode and client environment versions. Reopening/exporting/resyncing
  does not replace that context. Actual server start/failure times and duration
  are unknown: a CraftStartedPacket can also be sent by status reconciliation.
- Reports include snapshot sequence, display item registry IDs/counts/NBT presence,
  recorded node state/reason histograms, running operations and draining counts.
  Partial node observations are explicitly distinguished from the declared total.
  Minecraft, Forge, RSI and sampled related namespace/adapter versions are client
  observations, not evidence of the server environment or a full dependency list.
- Reports include task ID, client RSI version, target, progress, reason category,
  failed recipe/node IDs, integration type, recorded machine location and raw detail.
- Suggested checks are separate from observed facts. A timeout does not prove
  missing energy or blocked output. Unrecorded locations stay explicitly unknown.
- Failed nodes precede secondary blocked-node diagnostics. At most 32 diagnostic
  nodes are expanded; omitted nodes are counted.
- Raw fields are bounded to 1024 code points and have control characters removed.
  Text output is bounded to 65536 characters and explicitly marks truncation.
- File export requires a player click. UTF-8 text files are saved under the current
  game instance's `rs_integration/reports/` directory. Filenames include the local
  timestamp, task ID prefix and a unique suffix; earlier exports are not overwritten.
  The details page displays the full absolute destination after success, includes
  an Open Folder button, and reports errors without chat messages. No report is uploaded automatically;
  export does not inspect player inventory. Item NBT contents are excluded by
  default. Raw server-provided
  error text and machine coordinates are included, so review before sharing.
- Eight recent failures are retained in client memory, separately from the live
  HUD, until dismissed or disconnected. Exported files remain on disk; dismissing
  a record or disconnecting does not delete them. Active-status reconciliation does not
  remove retained failures. Dismissing a report cannot cancel a running task.
- Session-local UUID tombstones prevent repeated full/delta/start packets from
  recreating removed or evicted failures or posting another chat notification.
  Only eight full reports are kept; lightweight UUID tombstones grow with the
  number of failed tasks in this connection and are all cleared on disconnect.
- Existing progress packets are reused. No new packet, protocol change, world
  mutation, retry, refund, chunk load or server permission change is introduced.
- Task and node classifications share the same rules. Explicit output shortage
  is classified before generic missing-material wording; timeouts retain their
  own category rather than guessing a more specific cause.

## Current Boundaries

The Detailed NBT checkbox is off by default and affects both the displayed and
exported report. It includes only the target and abnormal step display outputs
already present in the progress snapshot. It does not obtain actual consumed
inputs, actual produced stacks, or capability state. Reports explicitly identify
these missing observations; display stacks must not be used as proof of them.

NBT excerpts are bounded to 8 entries, 2048 UTF-16 characters per entry and 8192
in total, depth 8 and 256 visited tags per entry. At most 256 compound keys are
scanned at each compound; known matching fields are also checked directly.
Spell, enchantment, Damage, Unbreakable and food fields are prioritized, with
RepairCost/itemModifier retained when present. Nested values, numeric types and
NaN are not repaired or normalized. Arrays, strings and compounds are traversed
with limits rather than fully serialized before truncation. `[truncated]` and
omitted-entry notices identify incomplete excerpts, which are not importable SNBT.
Neither optional diagnostics nor export mutate items or upload data. Raw server
error text can itself contain adapter-provided NBT and is preserved with existing
field bounds; review it and machine coordinates before sharing.

No additional server sampling or packet fields were added, so this enhancement
adds zero network payload bytes. Future actual-input/capability sampling needs
separate ownership, privacy, payload limits and protocol compatibility design.

Both the in-game report and exported file ask for logs/latest.log from the same
session and logs/debug.log when available. Multiplayer reports should include
server logs when obtainable. The task summary cannot replace full logs and the
export action does not automatically collect or copy log files.

This release covers failures after a craft task has started. Planning-stage
missing-material and prerequisite diagnostics remain in the existing plan UI.
Reason classification still interprets adapter-provided text in some paths;
raw detail is retained, and unknown causes are not converted to confident claims.
The report does not establish that all materials were refunded.

## Recommended Follow-on Work

1. Extend adapter observations with typed failures and resource amounts, starting
   with frequently used machines. Carry confirmed facts such as blocked output,
   required/available fluid or energy, and invalid recipe conditions separately
   from free-form debug text. Negotiate any future packet schema change.
2. Give planning failures and runtime failures one shared diagnostic model, while
   preserving planning request ownership and stale-response rejection.
3. Capture bounded server-side before/after observations keyed by task ID, only
   for the requesting player or authorized administrators. Do not collect full
   inventories or scan unloaded machines just to enrich a diagnostic.
4. Offer retry only after checking execution settlement and actual outputs;
   a failure report alone must never authorize automatic retry or item refund.

## Verification

Automated coverage includes classification precedence, report bounds and NBT
exclusion, failed-node ordering, translations, bounded history, active-status
reconciliation, dismissal safety and disconnect cleanup. Progress codecs,
runtime settlement and client/server packet isolation are regression-tested.
Live Minecraft GUI rendering and multiplayer failure scenarios require in-game
validation; headless tests are not a substitute for those checks.

New regression coverage exercises the green RUN_COMMAND component, Brigadier
client route, delayed exact-task navigation, stale navigation at disconnect,
full/delta/start deduplication, tombstones, immutable failure context, unknown
server timing/input evidence, statistics, translations, optional NBT exclusion,
field priority, Unicode/depth/array limits, and per-entry/total export bounds.
The click bridge's client-only registration and narrow command scope are tested;
actual Mixin application and rendering still require launching Minecraft.

In-game acceptance: fail two different real execution tasks, click each green
link, and verify the UUID and receipt timestamp. Repeat full status sync, remove
and evict records, disconnect/reconnect, and test old links. Test both typed
client commands and chat clicks, with no OP, and verify that neither emits a
server command. Toggle detailed NBT and export both modes at multiple GUI scales.
Keep same-session latest.log/debug.log and, for multiplayer, server logs.
No automatic commit, installation, or deployment is part of this workflow.

## Verified Build: 2026-09-07

- Final targeted run: 94 tests passed, including failure/progress diagnostics,
  packet codecs and side isolation, client command scope, translation parity,
  server-side translation isolation, failure classification and GUI/JEI contracts.
- Full run: 1494 tests across 317 suites; 1493 passed, zero failures/errors,
  one skipped. The skipped IronSpellConfigMixinContractTest runtime-jar check
  requires RSI_IRON_COMPAT_JAR, which was not set.
- `gradlew.bat test build reobfJar verifyReleaseJar --no-daemon` succeeded.
  The initial full run exposed a missing client-only marker on the report
  formatter; this was corrected with @OnlyIn(Dist.CLIENT), not by weakening
  the server translation test. The final full run includes that correction.
- Release: `D:/sd/rs-integration/build/libs/rs_integration-1.4.2.jar`
  (4,407,936 bytes).
- SHA-256: `686E205748317111BA4908DFD7736E25A49A509C1F422559F02F1707676B8220`.
- HTML test report: `D:/sd/rs-integration/build/reports/tests/test/index.html`.
- Reobfuscation generated the click bridge mapping to
  `Screen.m_5561_(Style)Z`. This is build verification, not proof of successful
  runtime transformation alongside every mod in the pack.
- Minecraft was not launched: click navigation, layout at different GUI scales,
  and compatibility with chat-replacing mods remain in-game acceptance items.
- No commit, push, modpack installation or automatic log collection occurred.
  Existing pending edits and all localization files were preserved.

## Follow-up Change List

Paths below are relative to the project source root
`src/main/java/com/huanghuang/rsintegration/` unless noted otherwise.

- `crafting/CraftFailureContext.java`: frozen client receipt/mode/version context.
- `crafting/CraftFailureHistory.java`, `crafting/CraftProgressTracker.java`:
  first-failure retention, session UUID deduplication, mode and navigation epoch.
- `crafting/CraftFailureLinks.java`, `crafting/CraftFailureClientCommands.java`:
  green UUID link, local Brigadier command, deferred navigation and expiry checks.
- `crafting/batch/CraftProgressClientPacketHandler.java`,
  `client/ClientEventBootstrap.java`: notify on the first retained failure only,
  and register client command/tick/logout events.
- `mixin/minecraft/CraftFailureReportClickMixin.java`,
  `src/main/resources/rs_integration.mixins.json`: narrow client-only component
  click bridge, without replacing other commands or mouse handling.
- `crafting/CraftFailureReport.java`, `crafting/CraftFailureNbt.java`:
  provenance, statistics, unknown evidence, bounded optional NBT and client-only
  text formatting.
- `crafting/CraftFailureScreen.java`, `crafting/CraftProgressScreen.java`:
  read retained context and provide the detailed NBT checkbox.
- `src/main/resources/assets/rs_integration/lang/en_us.json` and `zh_cn.json`:
  matching translations for all added diagnostics and link states.
- `src/test/java/com/huanghuang/rsintegration/crafting/CraftFailureLinksTest.java`
  and `CraftFailureMetadataTest.java`: 14 new regression tests.
- `src/test/java/com/huanghuang/rsintegration/crafting/CraftFailurePresentationTest.java`:
  five presentation regressions covering semantic colors, section spacing,
  two-line chat links, style-preserving wrapping with deterministic glyph widths,
  both translations, and formatting-free UTF-8 exports. These do not replace
  in-game visual acceptance.
- This document and the generated release SHA-256 sidecar record verification.

Preexisting exporter, progress publisher/protocol and execution/refund/cancel
logic were not redesigned in this follow-up. Their pending user changes remain
included in the built project; this list distinguishes the new follow-up work.

## Mana Pool Amplification Reservation Fix (2026-09-07)

The supplied report for task `04f4e1dc-ed4c-4b9d-9467-1db772e8cb37`
and the same session's `debug.log` at 16:02:51 identify recipe
`botania:kjs/65yk66gm62mbg9o9c86lt9552`, adapter `botania_mana_pool`.
The modpack's `kubejs/server_scripts/botania.js` declares one
`malum:earthen_spirit` input, two of the same item as output, a conjuration
catalyst and 2000 mana per operation.

The preview and direct launch use `SelfAmplifyingRecipePolicy`: the self-input
is a seed, not the total number of inputs across all future operations.
However, flat execution previously reserved 32 operations at once, independent
of seed availability. `reserveFromInventory` includes the main inventory and
supported backpacks but returns empty unless its entire requested amount is
available. With 20-plus seeds, this explains why the log said "need 32 more":
it measured an unsuccessful reservation, not the actual inventory shortage.
The termination audit (`preStart=0 inFlight=0 settled=0`) indicates that this
failure happened before a physical machine operation started.

The shared flat-machine executor now retries unsuccessful, uncommitted batches
at successively smaller sizes (32, 16, 8, 4, 2, 1). Before each retry it restores
the virtual inventory and cancels only ledger entries created by that attempt,
using reservation marks instead of releasing entries by item identity. It
reconfigures the delegate's batch before reserving and records the admitted
operation count before committing or starting. Successful real outputs remain
in the existing chain inventory and can seed subsequent batches. Affordable
batches still take one reservation attempt; an absent seed fails at one
operation instead of looping indefinitely. This changes admission only: it
does not synthesize outputs, refund items, change permissions or bypass mana.

An irrecoverable reservation failure is now reported as
`MATERIAL_EXTRACTION_FAILED`, with recipe, input, required/reserved/unreserved
quantities and an explicit warning that unreserved is not a measured shortage.
Intermediate failed probes do not produce warning spam. The INFO message
`Flat material batch reduced` records remaining operations and requested/admitted
batch sizes. The GUI's repeat field remains **operation count**, not gross
output count: 100 repetitions of 1-to-2 yield a net gain of 100, assuming every
operation succeeds. The target display stack of two is not an order total.

Regression coverage in `FlatMaterialBatchTest` exercises the actual batch
selection helper with simulated stock for 1/2/20/23 seeds and 100 operations,
full batches, absent seeds, delegate batch limits, preview/physical scaling and
failure classification. It does not start a Forge server, perform real storage
extractions, or operate a Botania pool. Real inventory/backpack extraction,
mana consumption, cancellation during processing and final delivery still need
in-game acceptance. The graph executor and unrelated machine adapters were not
redesigned. No commit, push or modpack deployment is performed.

Verification for this fix:

- Directed run: 97 tests passed, including eight new batch regressions.
- Final `gradlew.bat test build reobfJar verifyReleaseJar --no-daemon`:
  BUILD SUCCESSFUL; 1507 tests discovered, 1506 passed, one skipped, no failures
  or errors. The skipped test is the existing real-runtime Iron's Spellbooks
  config contract, not a Mana Pool test.
- Test report: `build/reports/tests/test/index.html`; XML results:
  `build/test-results/test/`.
- Release: `build/libs/rs_integration-1.4.2.jar` (4,413,292 bytes), reobfuscated
  and release-verified. SHA-256:
  `5408685a8894b94b74796f85e1e3e5462efb9f80f498ee3974cc91374c767fb0`.
- Existing Mixin target/deprecated API and dependency deobfuscation warnings
  remain. Minecraft/modpack execution has not been tested in this run.
