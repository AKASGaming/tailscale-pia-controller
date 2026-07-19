<p align="center">
  <img src="controller/app/static/app-icon.png" alt="PIA Control" width="128" />
</p>

<h1 align="center">Tailscale PIA Controller</h1>

<p align="center">
  Per-device <a href="https://www.privateinternetaccess.com">Private Internet Access</a> (PIA) region control for devices that already use <strong>Tailscale</strong> as their only VPN.
</p>

This solves the Android "one VPN slot" problem: keep Tailscale connected for home services and Pi-hole DNS, then route **only your device's internet traffic** through PIA when you need it — without disconnecting Tailscale.

## How it works

```
Phone (Tailscale only)
  ├─ Normal: home LAN + Pi-hole DNS, direct internet
  └─ PIA on:  Tailscale exit node → home Docker host → Gluetun (PIA) → internet
```

Each device picks its own region (e.g. Mexico, Australia). Other devices are unaffected. Regional Docker stacks start on demand and stop after idle timeout.

### Components

| Component | Purpose |
|---|---|
| **vpn-controller** | REST API + web dashboard — device registration, per-device on/off, on-demand regional stacks |
| **Gluetun + Tailscale exit nodes** | One Docker stack per region (`gluetun-<region>` + `tailscale-exit-<region>`) |
| **Android app** | Region picker, VPN toggle, Tailscale exit node automation, QR pairing, background sync |
| **Windows client** | PowerShell/Python CLI + `tailscale set --exit-node` |

---

## Prerequisites

- Linux Docker host on your LAN (same machine as other containers is fine)
- [Tailscale](https://tailscale.com) tailnet with subnet routing to home
- Pi-hole DNS configured in Tailscale admin (optional but typical)
- PIA account
- Tailscale **reusable auth key** with `tag:pia-exit` for exit nodes — [create here](https://login.tailscale.com/admin/settings/keys)
- Docker + Docker Compose v2 on the host

---

## Server installation (Docker)

### 1. Clone and configure

```bash
git clone https://github.com/AKASGaming/tailscale-pia-controller.git
cd tailscale-pia-controller
cp .env.example .env
```

Edit `.env`:

```env
PIA_USER=your_pia_username
PIA_PASS=your_pia_password
TS_AUTHKEY=tskey-auth-xxxxxxxx?tags=tag:pia-exit
LAN_CIDR=192.168.1.0/24
CONTROLLER_SECRET=choose-a-long-random-string
CONTROLLER_PORT=8090
IDLE_SHUTDOWN_MINUTES=30
HOST_RUNTIME_DIR=/absolute/path/to/tailscale-pia-controller/runtime
DOCKER_PROJECT_NAME=tailscale-pia-controller
TS_EXIT_NODE_TAG=tag:pia-exit
```

| Variable | Description |
|---|---|
| `PIA_USER` / `PIA_PASS` | PIA credentials used by Gluetun |
| `TS_AUTHKEY` | Reusable Tailscale key with `tag:pia-exit` (see Tailscale setup below) |
| `LAN_CIDR` | Your home LAN — allowed through Gluetun firewall |
| `CONTROLLER_SECRET` | Protects device registration and admin actions. Leave empty for open LAN registration |
| `HOST_RUNTIME_DIR` | Absolute host path to `runtime/` if auto-detection fails |
| `DOCKER_PROJECT_NAME` | Labels regional containers for Portainer grouping |
| `IDLE_SHUTDOWN_MINUTES` | Stop unused regional stacks after this many minutes |

### 2. Start the controller

```bash
docker compose up -d --build
```

Verify:

```bash
curl http://localhost:8090/health
curl http://localhost:8090/regions
```

Open **http://your-docker-host:8090/** in a browser.

After code or config changes:

```bash
docker compose up -d --build vpn-controller
```

> **Note:** On controller shutdown/restart, VPN is disabled for all registered devices and regional stacks are stopped. Android clients clear their exit node on the next refresh.

### 3. Tailscale admin setup

#### Auto-approve exit nodes (recommended)

1. Open [Tailscale admin → Access controls](https://login.tailscale.com/admin/acls) and merge in [`docs/tailscale-acl.example.json`](docs/tailscale-acl.example.json):

```json
{
  "tagOwners": {
    "tag:pia-exit": ["your-email@example.com"]
  },
  "autoApprovers": {
    "exitNode": ["tag:pia-exit"]
  }
}
```

2. Create a new **auth key** with tag `tag:pia-exit` and set it as `TS_AUTHKEY` in `.env`.

3. Regional exit nodes (`pia-mexico`, `pia-au`, etc.) join the tailnet already approved.

> If a node was created before autoApprovers were configured, delete its Tailscale state (`runtime/data/<region>/tailscale/`) and re-enable the region.

#### DNS

Confirm Tailscale DNS still points to Pi-hole (Admin → DNS) if you use it.

### 4. Regions

Default regions are in [`regions/regions.yaml`](regions/regions.yaml). The `server_region` value must match [Gluetun PIA naming](https://github.com/qdm12/gluetun-wiki/blob/main/setup/providers/private-internet-access.md) exactly (city-level names like `CA Toronto`, not `Canada`).

| Region ID | Display name | Exit node hostname | PIA server region |
|---|---|---|---|
| `mexico` | Mexico | `pia-mexico` | Mexico |
| `us-east` | US East | `pia-us-east` | US East |
| `us-west` | US West | `pia-us-west` | US West |
| `uk` | UK London | `pia-uk` | UK London |
| `netherlands` | Netherlands | `pia-nl` | Netherlands |
| `canada` | Canada (Toronto) | `pia-ca` | CA Toronto |
| `japan` | Japan (Tokyo) | `pia-jp` | JP Tokyo |
| `australia` | Australia (Sydney) | `pia-au` | AU Sydney |

---

## Web dashboard

The dashboard at **http://your-docker-host:8090/** provides:

- **Live status** — server status (`Idle` / `Active`), device VPN state, and stats auto-refresh every 5 seconds
- **Stack status** — shows `idle` when a regional stack is running but no devices are connected
- **Idle shutdown** — live countdown per region, plus a **Stop now** button to shut down idle stacks early
- **Pair a device** — QR code + 6-character pairing code (when `CONTROLLER_SECRET` is set)
- **Per-device controls** — enable/disable VPN, change region, remove devices
- **Region overview** — PIA server region names, stack status, and idle shutdown state
- **In-app feedback** — admin actions show confirmation banners without reloading the page

If `CONTROLLER_SECRET` is empty, registration is open and the QR code only encodes the controller URL.

---

## Android app

**Requirements:** [Tailscale for Android](https://play.google.com/store/apps/details?id=com.tailscale.ipn) installed and **connected** before enabling PIA.

### Install

**Option A — Download APK (recommended)**

Download the latest APK from [GitHub Releases](https://github.com/AKASGaming/tailscale-pia-controller/releases) (currently `pia-control-v1.0.17-debug.apk` from [v1.0.23](https://github.com/AKASGaming/tailscale-pia-controller/releases/tag/v1.0.23)).

Enable "Install unknown apps" for your browser or files app if prompted.

**Option B — Build from source**

1. Open `apps/android` in Android Studio
2. Build → Build APK(s)
3. Install on your phone

### First-time setup

1. Open the **PIA Control** app
2. Enter your controller URL (e.g. `http://192.168.68.53:8090`)
3. Either:
   - Tap **Scan pairing QR code** on the web dashboard, or
   - Tap **Check connection**, then enter the 6-character pairing code shown on the dashboard
4. Enter a device name → tap **Register device**
5. Allow notifications when prompted (Android 13+) — needed for alerts when the WebUI changes VPN settings while the app is in the background

### Daily usage

1. **Tailscale** must stay connected (always)
2. Open **PIA Control**
3. Select a region from the dropdown
4. Toggle **Route traffic through PIA**
5. Wait for the stack to start (15–45 seconds on first use per region). The app applies the Tailscale exit node automatically when ready
6. Browse — your IP reflects the PIA region; home LAN and Pi-hole DNS still work via Tailscale
7. To switch regions: pick a new region from the dropdown while VPN is on
8. Toggle off when done — exit node clears, Tailscale stays connected

### App features

| Feature | Description |
|---|---|
| **Remote sync** | Polls the controller every 3 seconds while registered — WebUI changes apply even when the app is in the background |
| **Background notifications** | Alerts when VPN is enabled, disabled, or region-changed from the WebUI while the app is not open |
| **Exit node automation** | Applies and clears the Tailscale exit node automatically; clears it when VPN is disabled remotely |
| **Device settings** | Edit device name, re-register, or reset all app data from the setup screen |
| **Refresh status** | Reloads VPN state and region list from the controller |
| **Open Tailscale** | Launch Tailscale to manually pick an exit node if needed |
| **Test IP & location** | Check your public IP through the active route |
| **Export logs** | Save a diagnostic log to Downloads and share it for troubleshooting |

**Tips:**

- Enable **Allow direct access to local network** in the Tailscale app when using an exit node
- If the exit node doesn't apply automatically, tap **Refresh status** or select it manually in Tailscale (`pia-mexico`, `pia-au`, etc.)
- After a controller update/restart, open the app once to clear a stale exit node

---

## Windows client

**Requirements:** Tailscale for Windows with CLI enabled.

```powershell
cd apps/windows

# Register once (use pairing secret from .env, or pairing code from dashboard)
./vpn-control.ps1 register -ControllerUrl http://192.168.1.10:8090 -Name "My PC" -PairingSecret "your-secret"

# List regions
./vpn-control.ps1 regions

# Enable Mexico
./vpn-control.ps1 enable -Region mexico

# Check status
./vpn-control.ps1 status

# Disable
./vpn-control.ps1 disable
```

Cross-platform Python CLI:

```bash
python apps/windows/vpn-control.py register --controller-url http://192.168.1.10:8090 --name "My PC" --pairing-secret your-secret
python apps/windows/vpn-control.py enable --region mexico
python apps/windows/vpn-control.py disable
```

---

## API reference

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/` | No | Web dashboard |
| `GET` | `/health` | No | Service health |
| `GET` | `/regions` | No | List available regions |
| `GET` | `/pairing` | No | Pairing requirements + 6-char code |
| `GET` | `/dashboard/state` | No | Live dashboard JSON |
| `POST` | `/devices/register` | Pairing code or secret | Register device, returns API token |
| `PATCH` | `/devices/me` | Bearer token | Update device name |
| `GET` | `/devices/me/vpn` | Bearer token | Current device VPN state |
| `PUT` | `/devices/me/vpn` | Bearer token | `{ "enabled": true, "region": "mexico" }` |

Example:

```bash
# Register with pairing code from dashboard
curl -X POST http://localhost:8090/devices/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Phone","platform":"curl","pairing_code":"ABC123"}'

# Enable VPN
curl -X PUT http://localhost:8090/devices/me/vpn \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"region":"mexico"}'
```

Full interactive docs: **http://your-docker-host:8090/docs**

---

## Architecture

```mermaid
flowchart LR
  Phone[Android App]
  Web[Web Dashboard]
  API[vpn-controller]
  MX[gluetun_mexico + tailscale_exit]
  AU[gluetun_australia + tailscale_exit]
  PiHole[Pi-hole via Tailscale DNS]

  Phone -->|register / enable| API
  Web -->|live status| API
  API -->|start on demand| MX
  API -->|start on demand| AU
  Phone -->|Tailscale exit node| MX
  Phone -->|DNS| PiHole
```

- Regional stacks start **on demand** when a device enables a region
- Stacks stop after `IDLE_SHUTDOWN_MINUTES` with no active users (checked every 60 seconds)
- The WebUI shows a live countdown per region when a stack is idle and waiting to shut down
- Each Gluetun container uses one PIA connection

---

## Troubleshooting

| Issue | Fix |
|---|---|
| Exit node not in Tailscale app | Approve it in Tailscale admin, or set up ACL autoApprovers; wait for `pia-*` machine to appear |
| Stack stuck on `starting` | `docker logs gluetun-<region>` — check PIA credentials and `server_region` in `regions.yaml` |
| Gluetun "region not valid" | Use city-level PIA names (e.g. `CA Toronto`, not `Canada`) — see `regions/regions.yaml` |
| Home LAN unreachable with exit on | Enable **Allow LAN access** in Tailscale app |
| Pi-hole blocking stops | Verify Tailscale DNS still points to Pi-hole in admin console |
| App says "Connect Tailscale first" | Open Tailscale and ensure VPN is connected before enabling PIA |
| Stale exit node after controller restart | Open PIA Control and tap **Refresh status** — exit node clears automatically |
| WebUI change not reflected on phone | Ensure the app is registered and notifications are allowed; the app polls every 3 seconds in the background |
| Regional stack won't start | Check controller logs for `Resolved host runtime directory` and Docker socket access |
| Portainer grouping wrong | Set `DOCKER_PROJECT_NAME` in `.env` to match your stack name |

**Controller logs:**

```bash
docker logs -f vpn-controller
```

**Regional stack logs:**

```bash
docker logs gluetun-mexico
docker logs tailscale-exit-mexico
```

**Android diagnostic logs:** Tap **Export logs** in the app → share the file from Downloads.

**Manual idle cleanup:**

```bash
curl -X POST http://localhost:8090/admin/cleanup-idle
```

---

## Security notes

- Set `CONTROLLER_SECRET` before exposing the API beyond your LAN
- Prefer accessing the controller over Tailscale (`http://your-docker-host:8090`)
- Do not commit `.env` — it contains PIA credentials
- Pairing codes expire after 30 minutes; the full `CONTROLLER_SECRET` is never shown on the dashboard
- Restrict exit node ACLs in Tailscale to trusted devices

---

## Project structure

```
tailscale-pia-controller/
├── docker-compose.yml          # Controller service
├── regions/regions.yaml        # Region definitions
├── controller/                 # FastAPI API + web dashboard
│   └── app/static/             # Dashboard CSS, JS, and app icon
├── apps/android/               # Android control app
├── apps/windows/               # PowerShell + Python CLI
├── releases/                   # Prebuilt Android APKs
└── docs/                       # Tailscale ACL example
```

---

## License

MIT
