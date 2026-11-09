# HassBle

A system that turns an Android smartphone into a **BLE → Home Assistant gateway**.
It consists of two parts: a **Smart Android app (similar to the Companion app)** and a **Home Assistant bridge component**.

Instead of forwarding all raw packets (like ESPHome Bluetooth Proxy does), it parses and filters packets on the phone, sending **only changed sensor values** to Home Assistant, where they are exposed as **native entities**. Configuration (parsing rules) is **loaded by the app from Git**, and users **select which sensors** to apply on their phones.

It **does not use MQTT**, relying solely on the existing Home Assistant URL + access token (no additional ports required).

## Screenshots

| Gateway | Sensors | Logs |
|:---:|:---:|:---:|
| ![Gateway tab](docs/screenshots/gateway.png) | ![Sensors tab](docs/screenshots/sensors.png) | ![Logs tab](docs/screenshots/logs.png) |
| HA URL, OAuth, start/stop gateway | Git config, devices, sensor toggles | Live BLE/WS event log with filters |

## Architecture

\`\`\`
[Android App: The Brain]             WebSocket          [HA Component ws_bridge: Generic Bridge]
  Load config from Git + presets  ◀──(HA WS API)──▶     Create entities upon receiving declaration
  Scan Ads / GATT / OBD polling                         Update entities upon receiving state
  Raw -> Decode -> Value filter                         Relay commands for switch/number/select/button
  Only declare/send selected sensors                    (No BLE/format knowledge, generic)
\`\`\`

- Since only the phone has BLE, the **BLE I/O + config/decoding/filtering/selection is handled by the app**, while **Home Assistant only handles entity creation and updates**.
- \`ws_bridge\` is not BLE-specific but **generic** — any authenticated WebSocket client can create entities if it matches the protocol. This follows the same pattern as the Home Assistant Companion App's sensor register/update.

## Features

### BLE data paths

1. **Advertisement Parsing** — Decodes raw passive scan advertisements into Home Assistant sensors.
2. **OBD (ELM327) Polling** — Polls BLE OBD adapters like vLinker. Compatible with the preset/formula model of ESPHome [\`ble_elm327\`](https://github.com/eigger/espcomponents/tree/master/components/ble_elm327).

   | App (Sensors tab) | Home Assistant |
   |:---:|:---:|
   | ![OBD device in HassBle app](docs/screenshots/obd-app.png) | ![OBD entities in Home Assistant](docs/screenshots/obd-home-assistant.png) |
   | Bind an ELM327 adapter, select PIDs, and connect manually or through vehicle presence | Native sensors (fuel, odometer, battery, …) via ws_bridge |

   OBD profiles do not connect just because the gateway starts when \`auto_connect\` is disabled (the default). A matching, enabled vehicle-presence profile can trigger the connection independently.

3. **GATT notify + Bi-directional Control** — Receives data from push-based BLE devices and allows bi-directional control from Home Assistant to BLE devices via switch/number controls.

### Multi-vehicle OBD profiles

HassBle can manage multiple vehicle profiles in one configuration. Each vehicle has its own identity, and each OBD device is assigned to a vehicle using \`vehicle_id\`. This allows the app to select the matching OBD profile based on the connected vehicle instead of connecting to every configured adapter.

The vehicle Bluetooth address belongs to the car's infotainment / hands-free or audio connection. It is **not** the Bluetooth address of the OBD adapter.

Example:

\`\`\`yaml
vehicle_presence:
  enabled: true
  disconnect_delay_seconds: 30
  vehicles:
    - id: vw_id7
      name: "VW ID.7"
      bluetooth_mac: "AA:BB:CC:DD:EE:01"
    - id: fiat_500e
      name: "Fiat 500e"
      bluetooth_mac: "AA:BB:CC:DD:EE:02"

devices:
  - id: vw_id7_obd
    name: "VW ID.7 OBD"
    source: obd
    vehicle_id: vw_id7
    obd:
      mac: "11:22:33:44:55:66" # OBD adapter address, not the car address
    sensors:
      # Vehicle-specific PIDs and formulas
      - key: hv_soc
        mode: "22"
        pid: "028C"
        formula: "b/2.5"
        unit: "%"
        update_interval: 10s

  - id: fiat_500e_obd
    name: "Fiat 500e OBD"
    source: obd
    vehicle_id: fiat_500e
    obd:
      mac: "22:33:44:55:66:77"
    sensors:
      # Add the Fiat-specific PIDs and formulas here
\`\`\`

Add one entry under \`vehicle_presence.vehicles\` for each car and assign its OBD device to the corresponding \`vehicle_id\`. Each OBD profile can have its own adapter settings, initialization commands, PIDs, and formulas. The optional \`wifi_ssid\` field can be used as a fallback where supported by the vehicle-presence configuration.

The configured \`disconnect_delay_seconds\` controls the vehicle-presence disconnect delay used for OBD profile selection. The separate automatic gateway stop delay described below is currently 15 seconds.

Example vehicle configurations and the Fiat 500e (2020) template are maintained in the separate [hassble-config repository](https://github.com/Goddi8/hassble-config).

### Automatic gateway lifecycle (experimental)

The optional **Vehicle Automation** setting in the Gateway tab can start and stop the gateway based on the presence of paired vehicle Bluetooth devices.

- Pair each configured vehicle once from the Gateway tab using Android's system **Companion Device Manager**.
- When an associated vehicle appears, HassBle starts the gateway service.
- When the last associated vehicle disappears, HassBle schedules the gateway to stop after **15 seconds**.
- If a vehicle reappears during that delay, the pending stop is cancelled.
- When Android restarts, HassBle restores the presence observation; the gateway is not started at boot unless a vehicle-presence event is received.

This feature requires **Android 12 (API 31) or later** and is experimental until validated with different phones, Android versions, and vehicle Bluetooth systems. Android may not deliver presence callbacks while the app is force-stopped; reopen the app to resume normal operation.

This app-level gateway automation is separate from \`vehicle_presence\` in \`config.yaml\`: the YAML setting selects the matching OBD profile while the gateway is running; Vehicle Automation controls whether the gateway service itself starts or stops.

### Home Assistant state retention

When HassBle disconnects, it asks \`ws_bridge\` to **keep the last known entity states** instead of immediately marking them unavailable. This avoids losing the most recent readings when the gateway stops or the phone temporarily disconnects.

Those retained values are the **last reported values**, not live readings. They can therefore become stale while the gateway is offline.

### HTTP and HTTPS support

HassBle supports both HTTP and HTTPS connections to Home Assistant. HTTP support is enabled through Android's \`usesCleartextTraffic\` setting.

**Important security requirement:** Use HTTP **only when Home Assistant is reachable through a VPN connection**. HTTP traffic is not encrypted by itself; the VPN must provide the encrypted tunnel between the Android device and Home Assistant. Do not expose the HTTP endpoint directly to the public internet or use it over untrusted networks.

Use HTTPS whenever a VPN-protected connection is not available.

### App UI (three tabs)

| Tab | Purpose |
|-----|---------|
| **Gateway** | HA URL, OAuth or long-lived token, start/stop gateway, battery & scan settings, and optional Vehicle Automation pairing/toggle. Shows a **readiness banner** when HA/Git/sensors are not ready. |
| **Sensors** | Git repo (\`owner/repo\` + branch), device list, sensor toggles, bind/connect. **Import template**, **Add OBD**, **Add BLE** (wizard), **Export YAML**. |
| **Logs** | Live event log (LINK/TX/RX/NOTIF/ADV), filter, copy, save/share. Tap MAC to filter or jump to Sensors tab. |

### Config workflow

- **Git config**: \`config.yaml\` at repo root (fixed filename). Default repo: [eigger/hassble-config](https://github.com/eigger/hassble-config).
- **Templates**: \`templates.yaml\` at repo root — import from Sensors tab. Bundled fallback in the app when offline.
- **Local draft devices**: Add OBD/BLE/template in the app → stored locally → **Export YAML** (merged or local-only) → commit to your Git repo manually.

## Repository Structure

\`\`\`
app/                        Android App (Kotlin/Compose)
app/src/main/assets/        Bundled obd_presets.yaml, templates.yaml (offline fallback)
config.example.yaml         Example device config (advertisement)
templates.example.yaml      Example templates for Git repos
docs/DESIGN.md              Architecture & UX flow
docs/PROTOCOL.md            App ↔ HA WebSocket Protocol (App perspective)
docs/CONFIG_SCHEMA.md       Git config & templates YAML schema
\`\`\`

> **Home Assistant component is in a separate repository**: \`ws_bridge\` (Generic WebSocket Bridge) is maintained at [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge). The protocol specification is also hosted there in \`PROTOCOL.md\`.

## Quick Start

1. **Home Assistant**: Install \`ws_bridge\` via HACS or manually from [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge) → Add integration "WebSocket Bridge" (no configuration needed).

2. **App — Gateway tab**
   - Enter **HA URL**.
   - Sign in with **HA OAuth** or paste a **long-lived access token**.
   - Fix any items shown in the readiness banner (red = blocking, orange = advisory).

3. **App — Sensors tab**
   - Enter **GitHub repo** as \`owner/repo\` (e.g. \`eigger/hassble-config\`) and branch (default \`main\`).
   - Optional: GitHub token for private repos.
   - Wait for config to load, **enable sensors** on each device.
   - Optional: import a template, add OBD/BLE devices, or export YAML for local drafts.

4. **App — Gateway tab**
   - Tap **Start Gateway** to run the gateway manually. The app runs as a foreground service with a persistent notification.
   - Optional: enable **Vehicle Automation** and pair each configured car to let Android presence events start and stop the gateway automatically. This requires Android 12+ and is experimental.

5. **Debugging**: Open the **Logs** tab while the gateway is running. Filter by MAC or message; long-press a MAC to search in Sensors.

No MQTT broker is required.

## Status

Implemented: advertisement parsing, GATT notify and control, OBD polling, multi-vehicle OBD profile selection, Git config loading, sensor selection, value filtering, OAuth, local draft devices, YAML export, HTTP/HTTPS connectivity, Home Assistant last-state retention, and the foreground gateway service.

Experimental / requires device validation: Companion Device Manager-based automatic gateway start/stop and vehicle presence behavior across different Android versions and vehicle Bluetooth systems.

Planned / partial: mDNS HA discovery, bulk GATT bind by service UUID.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
