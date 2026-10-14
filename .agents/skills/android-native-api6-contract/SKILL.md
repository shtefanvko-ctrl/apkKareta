---
name: android-native-api6-contract
description: Change or audit the KARETA Native API 6 WebView bridge contract.
version: 1.0.0
---
Map JS method -> native handler -> permission/state -> structured result/error. Preserve backward compatibility, capability discovery and offline acknowledgement semantics. Do not silently rename methods or change payload shape. Version the API only by explicit decision.
