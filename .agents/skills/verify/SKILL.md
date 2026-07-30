---
name: verify
summary: Verify async crafting lifecycle changes in a real Forge instance
---

# Runtime verification

1. Launch the Forge client with `./gradlew runClient` in a graphical desktop session.
2. Open or create a world containing Refined Storage and at least one supported asynchronous machine binding.
3. Start an asynchronous craft through the normal plan confirmation UI.
4. Observe the server log for the generated craft ID and manager submission/removal messages.
5. Exercise completion, disconnect, controller removal, and graceful server stop while a craft has committed inputs.
6. Compare RS storage, player inventory, machine slots, and world drops before and after each termination path.

This repository's unit-test runtime does not include the compile-only Refined Storage API. Lifecycle/refund verification therefore requires the configured Forge run with the integration mods installed; `gradlew test` cannot provide runtime evidence for these paths.
