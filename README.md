# Tailscale PIA Controller

Per-device Private Internet Access (PIA) region control for devices that already use **Tailscale** as their only VPN.

This solves the Android "one VPN slot" problem: keep Tailscale connected for home services and Pi-hole DNS, then route **only your device's internet traffic** through PIA when you need it — without disconnecting Tailscale.

## How it works

```
Phone (Tailscale only)
  ├─ Normal: home LAN + Pi-hole DNS, direct internet
  └─ PIA on:  Tailscale exit node → home Docker host → Gluetun (PIA) → internet
```

Each device picks its own region (e.g. Mexico, UK). Other devices are unaffected.

### Components

| Component | Purpose |
|---|---|
| **vpn-controller** | REST API — device registration, per-device on/off, on-demand regional stacks |
| **Gluetun + Tailscale exit nodes** | One Docker stack per region; spawned on demand |
| **Android app** | Region picker, enable/disable, Tailscale exit node intent |
| **Windows client** | PowerShell/Python CLI + `tailscale set --exit-node` |

---

## Prerequisites

- Linux Docker host on your LAN (same machine as other containers is fine)
- [Tailscale](https://tailscale.com) tailnet with subnet routing to home (existing setup)
- Pi-hole DNS configured in Tailscale admin (existing setup)
- [PIA](https://www.privateinternetaccess.com) account
- Tailscale **reusable auth key** for exit nodes ([create here](https://login.tailscale.com/admin/settings/keys))
- Docker + Docker Compose v2 on the host
- Approve exit nodes in Tailscale admin after first start

---

## Server installation

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
TS_AUTHKEY=tskey-auth-xxxxxxxx
LAN_CIDR=192.168.1.0/24
CONTROLLER_SECRET=choose-a-long-random-string
CONTROLLER_PORT=8090
IDLE_SHUTDOWN_MINUTES=30
HOST_RUNTIME_DIR=/absolute/path/to/tailscale-pia-controller/runtime
```

> **Important:** `HOST_RUNTIME_DIR` must be the absolute path to the `runtime/` folder on your Linux Docker host (e.g. `/home/you/tailscale-pia-controller/runtime`). Regional Gluetun stacks bind-mount data from this path.

### 2. Start the controller

```bash
docker compose up -d --build
```

Verify:

```bash
curl http://localhost:8090/health
curl http://localhost:8090/regions
```

### 3. Tailscale admin setup

After the first region stack starts (when a device enables VPN):

1. Open [Tailscale admin → Machines](https://login.tailscale.com/admin/machines)
2. Find machines named `pia-mexico`, `pia-uk`, etc.
3. Enable **Use as exit node** for each
4. Confirm DNS still points to Pi-hole (Admin → DNS)
5. Optional ACL to restrict exit node usage:

```json
{
  "grants": [
    {
      "src": ["autogroup:member"],
      "dst": ["tag:exit-node"],
      "ip": ["*"]
    }
  ]
}
```

### 4. Add or customize regions

Edit [`regions/regions.yaml`](regions/regions.yaml). Region `server_region` values must match [Gluetun PIA naming](https://github.com/qdm12/gluetun-wiki/blob/main/setup/providers/private-internet-access.md).

---

## Client installation

### Android app

**Requirements:** Tailscale Android app installed and connected.

1. Open `apps/android` in Android Studio
2. Build → Build APK(s)
3. Install on your phone
4. In the app:
   - Enter controller URL (e.g. `http://192.168.1.10:8090` or Tailscale hostname)
   - Enter pairing secret (if `CONTROLLER_SECRET` is set)
   - Tap **Register device**
   - Select region → toggle **Enable PIA via Tailscale**

The app uses Tailscale's `USE_EXIT_NODE` broadcast intent to select the regional exit node. If that fails, tap **Open Tailscale** and manually select the exit node (e.g. `pia-mexico`).

**Tip:** Enable **Allow direct access to local network** in the Tailscale app when using an exit node.

### Windows client

**Requirements:** Tailscale for Windows with CLI enabled.

```powershell
cd apps/windows

# Register once
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
| `GET` | `/health` | No | Service health |
| `GET` | `/regions` | No | List available regions |
| `POST` | `/devices/register` | Pairing secret (optional) | Register device, returns API token |
| `GET` | `/devices/me/vpn` | Bearer token | Current device VPN state |
| `PUT` | `/devices/me/vpn` | Bearer token | `{ "enabled": true, "region": "mexico" }` |

Example:

```bash
# Register
curl -X POST http://localhost:8090/devices/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Phone","platform":"curl","pairing_secret":"your-secret"}'

# Enable VPN
curl -X PUT http://localhost:8090/devices/me/vpn \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"region":"mexico"}'
```

---

## Daily usage (Android)

1. **Tailscale** stays connected (always)
2. Open **PIA Control** app
3. Select region (e.g. Mexico) → enable
4. Wait ~15–45s for stack to start (first time per region)
5. App selects Tailscale exit node `pia-mexico`
6. Browse — your IP is PIA Mexico; home services and Pi-hole still work
7. Disable in app when done — exit node clears, Tailscale stays on

---

## Architecture

```mermaid
flowchart LR
  Phone[AndroidApp]
  API[vpn-controller]
  MX[gluetun_mexico + tailscale_exit]
  UK[gluetun_uk + tailscale_exit]
  PiHole[PiHole via Tailscale DNS]

  Phone -->|register / enable| API
  API -->|start on demand| MX
  API -->|start on demand| UK
  Phone -->|Tailscale exit node| MX
  Phone -->|DNS| PiHole
```

- Regional stacks start **on demand** when a device enables a region
- Stacks stop after `IDLE_SHUTDOWN_MINUTES` with no active users
- Each Gluetun container uses one PIA connection (~10 max on typical plans)

---

## Troubleshooting

| Issue | Fix |
|---|---|
| Exit node not in Tailscale app | Approve it in Tailscale admin; wait for `pia-*` machine to appear |
| Stack stuck on `starting` | `docker logs gluetun-mexico` — check PIA credentials |
| Home LAN unreachable with exit on | Enable **Allow LAN access** in Tailscale app |
| Pi-hole blocking stops | Verify Tailscale DNS still points to Pi-hole in admin console |
| Android intent unreliable | Manually select exit node in Tailscale app |
| `docker compose` fails from controller | Ensure `/var/run/docker.sock` is mounted and controller has permissions |

View controller logs:

```bash
docker logs -f vpn-controller
```

Manual idle cleanup:

```bash
curl -X POST http://localhost:8090/admin/cleanup-idle
```

---

## Security notes

- Set `CONTROLLER_SECRET` before exposing the API beyond your LAN
- Prefer accessing the controller over Tailscale (`http://your-docker-host.tailnet:8090`)
- Do not commit `.env` — it contains PIA credentials
- Restrict exit node ACLs in Tailscale to trusted devices

---

## Project structure

```
tailscale-pia-controller/
├── docker-compose.yml          # Controller service
├── regions/regions.yaml        # Region definitions
├── controller/                 # FastAPI API + Docker lifecycle
├── apps/android/               # Android control app
└── apps/windows/               # PowerShell + Python CLI
```

---

## License

MIT
