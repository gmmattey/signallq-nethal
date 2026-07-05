# Contributing to NETHAL

NETHAL is a network hardware abstraction project. Contributions must be safe, documented and respectful of user authorization.

## Core rules

- Do not bypass authentication.
- Do not include exploits.
- Do not brute-force credentials.
- Do not store router passwords.
- Do not collect sensitive personal data.
- Do not send credentials to cloud services.
- Do not add write actions without a safety review.

## Driver contributions

Every driver must document:

- Vendor
- Device family
- Tested models
- Tested firmware versions
- Protocol used
- Authentication method
- Supported capabilities
- Known limitations
- Safety risks

## Driver stages

```text
DRAFT
DISCOVERY_ONLY
READ_ONLY_ALPHA
READ_ONLY_BETA
WRITE_BETA
STABLE
DEPRECATED
BLOCKED
```
