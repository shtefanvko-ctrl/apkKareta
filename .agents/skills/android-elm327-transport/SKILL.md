---
name: android-elm327-transport
description: Work on ELM327 Classic SPP, BLE UART or Wi-Fi TCP transport/state handling.
version: 1.0.0
---
Separate adapter connected from ECU ready. Keep transport-specific discovery/connect code behind one stable bridge contract. Enforce connect timeouts, cleanup, read-only safety where required, and durable accepted-only sync. Never fake ECU readiness.
