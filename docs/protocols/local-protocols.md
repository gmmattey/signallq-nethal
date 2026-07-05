# Local Protocols

## HTTP/HTTPS local APIs

Most consumer routers expose a local web interface. Some use private REST-like APIs, CGI endpoints, form posts, cookies and anti-CSRF tokens.

## UPnP / SSDP

Useful for discovery and limited read-only metadata. Not suitable for changing Wi-Fi password or advanced settings.

## TR-064

SOAP-based local management protocol. Powerful when available, but not universally exposed in Brazilian ISP hardware.

## SNMP

Useful for read-only diagnostics where enabled. Usually disabled or restricted in consumer routers.

## TR-069 / TR-369

Not part of NETHAL MVP. These are operator/controller protocols and should not be treated as local user app protocols.
