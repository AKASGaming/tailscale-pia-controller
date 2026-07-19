"""HTML dashboard for the controller."""

from __future__ import annotations

from html import escape

from app import __version__
from app.docker_manager import display_stack_status
from app.pairing import pairing_instructions, pairing_required


def _display_stack_status(region: dict) -> str:
    return display_stack_status(region.get("stack_status"), region.get("idle_status"))


def _device_display_stack_status(device: dict, regions: list[dict]) -> str:
    region_id = device.get("region")
    if not region_id:
        return device.get("stack_status") or "—"
    region = next((item for item in regions if item["id"] == region_id), None)
    if region:
        return _display_stack_status(region)
    return device.get("stack_status") or "—"


def _idle_label(region: dict) -> str:
    status = region.get("idle_status", "stopped")
    stack = region.get("stack_status", "stopped")
    if status == "in_use":
        count = region.get("ref_count", 0)
        noun = "device" if count == 1 else "devices"
        return f"In use ({count} {noun})"
    if stack not in {"running", "starting"}:
        return "—"
    if status == "eligible":
        return "Shutdown pending"
    if not region.get("shutdown_at"):
        minutes = region.get("idle_shutdown_minutes", 30)
        return f"Idle ({minutes}m timeout)"
    return "Counting down…"


def _idle_cell_attrs(region: dict) -> str:
    return (
        f'class="region-idle" data-idle-status="{escape(region.get("idle_status", "stopped"))}" '
        f'data-stack-status="{escape(region["stack_status"])}" '
        f'data-shutdown-at="{escape(region.get("shutdown_at") or "")}" '
        f'data-idle-minutes="{region.get("idle_shutdown_minutes", 30)}" '
        f'data-ref-count="{region.get("ref_count", 0)}"'
    )


def _stack_badge(status: str) -> str:
    normalized = (status or "stopped").lower()
    badge_class = (
        normalized
        if normalized in {"running", "starting", "stopped", "error", "idle"}
        else "stopped"
    )
    return f'<span class="badge badge-{badge_class}">{escape(status or "stopped")}</span>'


def _region_stop_button(region: dict) -> str:
    if region.get("idle_status") not in {"idle", "eligible"}:
        return ""
    if region.get("stack_status") not in {"running", "starting"}:
        return ""
    region_id = escape(region["id"])
    return (
        f'<form method="post" action="/admin/regions/{region_id}/stop" class="inline-form" '
        f'onsubmit="return confirm(\'Stop this regional stack now?\');">'
        f'<button type="submit" class="danger">Stop now</button>'
        f"</form>"
    )


def _region_options(regions: list[dict], selected: str | None = None) -> str:
    options = ['<option value="">— select region —</option>']
    for region in regions:
        sel = ' selected' if selected == region["id"] else ""
        label = f'{region["display_name"]} — {region["server_region"]}'
        options.append(
            f'<option value="{escape(region["id"])}"{sel}>{escape(label)}</option>'
        )
    return "".join(options)


def render_dashboard(
    *,
    status: str,
    active_stacks: int,
    registered_devices: int,
    idle_shutdown_minutes: int,
    regions: list[dict],
    devices: list[dict],
    pairing_code: str | None = None,
    pairing_code_expires_at: str | None = None,
) -> str:
    pairing_block = ""
    if pairing_required():
        code = escape(pairing_code or "—")
        expires = escape(pairing_code_expires_at or "")
        pairing_block = f"""
        <section class="card highlight">
          <h2>Pair a device</h2>
          <p class="muted">{escape(pairing_instructions())}</p>
          <div class="pairing-grid">
            <div>
              <img id="pairing-qr" src="/pairing/qr" width="220" height="220" alt="Pairing QR code" class="qr" />
            </div>
            <div>
              <p class="muted">Pairing code</p>
              <div id="pairing-code" class="secret pairing-code">{code}</div>
              <p class="muted">Expires at <span id="pairing-expires">{expires}</span> UTC</p>
              <p class="muted">Scan with the PIA Control app, or enter the 6-character code manually.</p>
            </div>
          </div>
        </section>
        """
    else:
        pairing_block = f"""
        <section class="card">
          <h2>Pair a device</h2>
          <p class="muted">{escape(pairing_instructions())}</p>
          <div class="pairing-grid">
            <div>
              <img id="pairing-qr" src="/pairing/qr" width="220" height="220" alt="Controller URL QR code" class="qr" />
            </div>
            <div>
              <p class="muted">Scan to fill the controller URL in the app. No pairing code is required.</p>
            </div>
          </div>
        </section>
        """

    admin_secret_bar = ""
    if pairing_required():
        admin_secret_bar = """
        <div class="admin-bar">
          <label>Admin secret
            <input type="password" id="admin-secret" placeholder="Pairing secret" autocomplete="current-password" />
          </label>
          <p class="muted">Used for device and region actions below.</p>
        </div>
        """

    region_rows = "".join(
        f"<tr data-region-id=\"{escape(r['id'])}\">"
        f"<td>{escape(r['display_name'])}</td>"
        f"<td><code>{escape(r['server_region'])}</code></td>"
        f"<td><code>{escape(r['hostname'])}</code></td>"
        f"<td class=\"region-stack\">{_stack_badge(_display_stack_status(r))}</td>"
        f"<td {_idle_cell_attrs(r)}>{escape(_idle_label(r))}</td>"
        f"<td class=\"region-actions\">{_region_stop_button(r)}</td></tr>"
        for r in regions
    )

    device_rows = ""
    for device in devices:
        region_select = _region_options(regions, device.get("region"))
        vpn_label = "Yes" if device.get("vpn_enabled") else "No"
        stack_label = _device_display_stack_status(device, regions)
        device_rows += f"""
        <tr data-device-id="{escape(device['id'])}">
          <td><strong>{escape(device['name'])}</strong><br><span class="muted">{escape(device['platform'])}</span></td>
          <td><code>{escape(device['id'][:8])}…</code></td>
          <td class="device-vpn">{vpn_label}</td>
          <td class="device-region">{escape(device.get('region_display_name') or '—')}</td>
          <td class="device-exit"><code>{escape(device.get('exit_node_hostname') or '—')}</code></td>
          <td class="device-stack">{_stack_badge(stack_label)}</td>
          <td>
            <form method="post" action="/admin/devices/{escape(device['id'])}/vpn" class="inline-form">
              <label><input type="hidden" name="enabled" value="true" />
              <select name="region">{region_select}</select></label>
              <button type="submit">Enable</button>
            </form>
            <form method="post" action="/admin/devices/{escape(device['id'])}/vpn" class="inline-form">
              <input type="hidden" name="enabled" value="false" />
              <button type="submit" class="danger">Disable</button>
            </form>
            <form method="post" action="/admin/devices/{escape(device['id'])}/delete" class="inline-form"
                  onsubmit="return confirm('Remove this device? The app will need to register again.');">
              <button type="submit" class="danger">Remove</button>
            </form>
          </td>
        </tr>
        """

    flash = '<div id="flash-banner" class="flash hidden" role="status" aria-live="polite"></div>'
    asset_version = escape(__version__)

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Tailscale PIA Controller</title>
  <link rel="icon" href="/static/favicon.png?v={asset_version}" type="image/png" />
  <link rel="apple-touch-icon" href="/static/apple-touch-icon.png?v={asset_version}" />
  <link rel="stylesheet" href="/static/dashboard.css?v={asset_version}" />
</head>
<body data-pairing-required="{"true" if pairing_required() else "false"}">
  <div class="shell">
    <header class="topbar">
      <div class="topbar-brand">
        <img src="/static/app-icon.png?v={asset_version}" alt="" class="brand-icon" width="44" height="44" />
        <div>
          <h1>Tailscale PIA Controller</h1>
          <p class="meta">Version {escape(__version__)} · Status: <strong id="server-status" class="server-status server-status-{escape(status.lower())}">{escape(status)}</strong></p>
        </div>
      </div>
      <div id="live-indicator" class="live-pill">Live updates starting…</div>
    </header>

    {flash}
    {pairing_block}

    <section class="card">
      <h2>Overview</h2>
      <div class="grid-stats">
        <div class="stat-card"><strong id="stat-active-stacks">{active_stacks}</strong><span>Active stacks</span></div>
        <div class="stat-card"><strong id="stat-registered-devices">{registered_devices}</strong><span>Registered devices</span></div>
        <div class="stat-card"><strong id="stat-idle-timeout">{idle_shutdown_minutes}m</strong><span>Idle shutdown timeout</span></div>
      </div>
      <div class="links">
        <a href="/docs">API documentation</a>
        <a href="/health">Health JSON</a>
        <a href="/pairing">Pairing JSON</a>
        <a href="/regions">Regions JSON</a>
      </div>
    </section>

    <section class="card">
      <h2>Registered devices</h2>
      <p class="muted">Control VPN per device from here if the client app is not available.</p>
      {admin_secret_bar}
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Device</th><th>ID</th><th>VPN</th><th>Region</th><th>Exit node</th><th>Stack</th><th>Actions</th>
            </tr>
          </thead>
          <tbody id="devices-tbody">
            {device_rows or '<tr id="devices-empty"><td colspan="7">No devices registered yet</td></tr>'}
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <h2>Available regions</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>Region</th><th>PIA server region</th><th>Exit node hostname</th><th>Stack status</th><th>Idle shutdown</th><th>Actions</th></tr>
          </thead>
          <tbody id="regions-tbody">{region_rows or '<tr><td colspan="6">No regions configured</td></tr>'}</tbody>
        </table>
      </div>
      <p class="muted">After enabling a region, select the matching exit node in the Tailscale app on the device.</p>
    </section>
  </div>
  <script src="/static/dashboard.js?v={asset_version}" defer></script>
</body>
</html>"""
