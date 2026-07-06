# NETHAL

**Network Hardware Abstraction Layer**

NETHAL is an experimental network hardware abstraction layer for discovering, identifying, reading and safely controlling local network equipment such as routers, ONTs, ONUs, access points, mesh systems and gateways.

The project is independent from SignallQ. If validated, stable NETHAL drivers may later be integrated into SignallQ.

## What NETHAL does

NETHAL provides a common interface over different devices, vendors, firmware families and local protocols.

Applications should not ask "is this Huawei?" or "is this TP-Link?". They should ask what the detected device can safely do.

## MVP focus

The first usable version should be Android-first, because local network discovery and device probing are heavily restricted in browsers and PWAs.

Initial scope:

- Android-first lab app
- local network discovery
- read-only drivers
- sanitized telemetry
- compatibility reporting
- no stored router credentials
- no remote access outside the local network

## Documentation

Start here:

- [Product Specification](docs/product/specification.md)
- [Architecture Overview](docs/architecture/overview.md)
- [Driver Model](docs/drivers/driver-model.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## Project structure

Gradle multi-module setup:

- `core/` — **NetHAL Core**, a pure Kotlin (JVM) SDK module with no Android UI dependency, so it can be reused by SignallQ later. Package: `com.nethal.core`.
- `app/` — **NetHAL Lab**, the Android/Compose app that consumes `core`. `applicationId`/namespace: `com.nethal.lab`.

Both names are implementation details, not brand decisions — flag to Rafael/Luiz if a different package convention is preferred before this ships further.

Toolchain: Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01, `compileSdk`/`targetSdk` 35, `minSdk` 26 (required for reliable Wi-Fi scan behavior under `ACCESS_FINE_LOCATION`, per `/regras-android-nethal`).

## Status

Early foundation. Onboarding and consent flow (SIG-307) implemented. No production driver is stable yet.

## License

License to be defined before accepting external contributions.
