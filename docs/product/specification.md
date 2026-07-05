# NETHAL Product Specification

## Product name

NETHAL

## Meaning

Network Hardware Abstraction Layer

## Product type

Experimental Android-first network hardware abstraction platform.

## Vision

Create a reusable abstraction layer that allows software to discover, identify, read and safely control local network equipment through a common capability-based interface.

## Relationship with SignallQ

NETHAL is independent from SignallQ.

SignallQ may consume stable NETHAL drivers in the future, but beta or unverified drivers must remain outside the main SignallQ product.

## Principles

1. Safety before compatibility.
2. Read-only before write actions.
3. Capability-based, not vendor-based.
4. No stored router credentials.
5. Driver maturity by model and firmware.
6. Fail safely when uncertain.
7. Experimental drivers stay in NETHAL Lab.
