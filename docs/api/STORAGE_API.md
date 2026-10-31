# RSI storage API

`com.huanghuang.rsintegration.api.storage.RSIStorageApi` is the public, server-side boundary for integrations that need the player's bound Resonance Storage network. Obtain it with `RSIStorageApi.get()` after RSI common setup; the optional result is empty while RSI is unavailable or stopped.

All methods require a `ServerPlayer` and must be called on that player's server thread. `defaultStorage` resolves exactly one available default network with VIEW permission. `resolve` accepts an explicit backend id and network id. Both return an opaque `StorageLink` owned by that player; links cannot be reused by another player and become invalid when RSI services stop or restart.

`countExact` and `extractExact` compare the complete item stack, including tags. `hasTool` intentionally matches by item identity and ignores stack NBT, which allows named ladles and other damaged/named tools. `snapshot` creates a filtered, read-only count view for one server-thread operation. A failed or incomplete extraction returns recovered physical stacks to the player's inventory (or drops them when full) and returns an empty list.

The API does not expose RSI backend/session classes. Feature code should use this interface together with `RSICraftConsumerApi` for recursive crafting. API version: `1.0`.
