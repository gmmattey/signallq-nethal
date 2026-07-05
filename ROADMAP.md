# NETHAL Roadmap

## Phase 0 — Foundation

- Define product scope
- Define architecture
- Define capability model
- Define driver lifecycle
- Define contribution policy
- Create repository structure

## Phase 1 — Android Lab MVP

- Android-first app shell
- Local gateway discovery
- Local IP and DNS detection
- Basic HTTP/HTTPS probing
- UPnP/SSDP discovery
- Fingerprint engine
- Compatibility report export

## Phase 2 — Read-only drivers

Initial candidates:

1. OpenWrt / LuCI
2. ASUSWRT
3. TP-Link Archer
4. Intelbras domestic routers
5. Generic UPnP read-only

## Phase 3 — Beta driver validation

- User tester program
- Sanitized telemetry
- Compatibility catalog
- Model/firmware mapping
- Driver confidence score
- Admin panel for test results

## Phase 4 — Safe actions

Actions only after validation:

- Reboot device
- Restart Wi-Fi
- Change Wi-Fi channel
- Change Wi-Fi bandwidth
- Change DNS

Blocked from MVP:

- Factory reset
- Firmware upgrade
- PPPoE/VLAN changes
- Bridge/router mode changes
- Remote access outside LAN

## Phase 5 — SignallQ integration

Only stable NETHAL drivers may be exposed inside SignallQ.
