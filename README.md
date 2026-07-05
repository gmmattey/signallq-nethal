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

## Status

Early foundation. No production driver is stable yet.

## License

License to be defined before accepting external contributions.
