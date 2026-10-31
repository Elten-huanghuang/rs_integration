# RSI Machine GUI API

`com.huanghuang.rsintegration.api.machine.gui.RSIMachineGuiApi` is the public
registration point for a machine GUI whose block `use` method can mutate the
world before a menu is opened.

An integration registers once during `FMLCommonSetupEvent` (inside
`event.enqueueWork`) with a `BlockEntityType` and a `MachineGuiOpener`. RSI then
performs its existing loaded-chunk, binding, permission, and remote-menu
authorization checks and invokes the opener on the server thread. The opener
must synchronously call `NetworkHooks.openScreen` (or `ServerPlayer.openMenu`)
and must not authorize a target or force-load a chunk.

The API version is `1.0`. A duplicate registration or a call after the API
registration phase returns `false`; callers should treat that as a startup
configuration error. This extension is independent of the resonance disk API:
the Goety feature uses the RSI storage backend and the public recursive craft
consumer API for a bound network.
