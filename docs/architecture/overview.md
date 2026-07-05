# Architecture Overview

```text
NETHAL SDK
    ├── Discovery Engine
    ├── Fingerprint Engine
    ├── Protocol Detector
    ├── Authentication Manager
    ├── Driver Registry
    ├── Capability Engine
    ├── Command Executor
    ├── Safety Guard
    ├── Telemetry Collector
    └── Compatibility Catalog
```

## Discovery Engine

Finds the local gateway and candidate network devices.

Sources:

- Android network APIs
- default gateway
- DNS servers
- local IP/subnet
- HTTP/HTTPS probes
- SSDP/UPnP
- mDNS when available
- port probes
- HTTP headers
- TLS certificate metadata

## Safety Guard

Mandatory layer before any write action.
