"""HTML dashboard for the controller."""

from __future__ import annotations

from html import escape

from app import __version__
from app.pairing import pairing_instructions, pairing_required


def _region_options(regions: list[dict], selected: str | None = None) -> str:
    options = ['<option value="">— select region —</option>']
    for region in regions:
        sel = ' selected' if selected == region["id"] else ""
        options.append(
            f'<option value="{escape(region["id"])}"{sel}>{escape(region["display_name"])}</option>'
        )
    return "".join(options)


def render_dashboard(
    *,
    status: str,
    active_stacks: int,
    registered_devices: int,
    regions: list[dict],
    devices: list[dict],
    message: str | None = None,
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
          <p>{escape(pairing_instructions())}</p>
          <div class="pairing-grid">
            <div>
              <img src="/pairing/qr" width="220" height="220" alt="Pairing QR code" class="qr" />
            </div>
            <div>
              <p class="muted">Pairing code</p>
              <div class="secret pairing-code">{code}</div>
              <p class="muted">Expires at {expires} UTC</p>
              <p class="muted">Scan with the PIA Control app, or enter the code manually if the device has no camera.</p>
            </div>
          </div>
        </section>
        """
    else:
        pairing_block = f"""
        <section class="card">
          <h2>Pair a device</h2>
          <p>{escape(pairing_instructions())}</p>
          <div class="pairing-grid">
            <div>
              <img src="/pairing/qr" width="220" height="220" alt="Controller URL QR code" class="qr" />
            </div>
            <div>
              <p class="muted">Scan to fill the controller URL in the app. No pairing code is required.</p>
            </div>
          </div>
        </section>
        """

    region_rows = "".join(
        f"<tr><td>{escape(r['display_name'])}</td><td><code>{escape(r['hostname'])}</code></td>"
        f"<td>{escape(r['stack_status'])}</td></tr>"
        for r in regions
    )

    admin_secret_field = ""
    if pairing_required():
        admin_secret_field = """
        <label>Admin secret
          <input type="password" name="secret" placeholder="Pairing secret" required />
        </label>
        """

    device_rows = ""
    for device in devices:
        region_select = _region_options(regions, device.get("region"))
        vpn_label = "Yes" if device.get("vpn_enabled") else "No"
        device_rows += f"""
        <tr>
          <td><strong>{escape(device['name'])}</strong><br><span class="muted">{escape(device['platform'])}</span></td>
          <td><code>{escape(device['id'][:8])}…</code></td>
          <td>{vpn_label}</td>
          <td>{escape(device.get('region') or '—')}</td>
          <td><code>{escape(device.get('exit_node_hostname') or '—')}</code></td>
          <td>{escape(device.get('stack_status') or '—')}</td>
          <td>
            <form method="post" action="/admin/devices/{escape(device['id'])}/vpn" class="inline-form">
              {admin_secret_field}
              <label><input type="hidden" name="enabled" value="true" />
              <select name="region">{region_select}</select></label>
              <button type="submit">Enable</button>
            </form>
            <form method="post" action="/admin/devices/{escape(device['id'])}/vpn" class="inline-form">
              {admin_secret_field}
              <input type="hidden" name="enabled" value="false" />
              <button type="submit" class="danger">Disable</button>
            </form>
            <form method="post" action="/admin/devices/{escape(device['id'])}/delete" class="inline-form"
                  onsubmit="return confirm('Remove this device? The app will need to register again.');">
              {admin_secret_field}
              <button type="submit" class="danger">Remove</button>
            </form>
          </td>
        </tr>
        """

    flash = f'<div class="flash">{escape(message)}</div>' if message else ""

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Tailscale PIA Controller</title>
  <style>
    :root {{
      color-scheme: light dark;
      font-family: system-ui, -apple-system, Segoe UI, sans-serif;
      line-height: 1.5;
    }}
    body {{ max-width: 1100px; margin: 0 auto; padding: 24px; }}
    h1 {{ margin-bottom: 0.25rem; }}
    .muted {{ color: #666; font-size: 0.9rem; }}
    .card {{
      border: 1px solid #ccc;
      border-radius: 12px;
      padding: 16px 20px;
      margin: 16px 0;
    }}
    .highlight {{ border-color: #6750a4; background: rgba(103, 80, 164, 0.08); }}
    .secret {{
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 1.25rem;
      padding: 12px 16px;
      border-radius: 8px;
      background: rgba(0,0,0,0.06);
      word-break: break-all;
      user-select: all;
    }}
    table {{ width: 100%; border-collapse: collapse; font-size: 0.95rem; }}
    th, td {{ text-align: left; padding: 10px 8px; border-bottom: 1px solid #ddd; vertical-align: top; }}
    code {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.85rem; }}
    a {{ color: #6750a4; }}
    .stats {{ display: flex; gap: 16px; flex-wrap: wrap; }}
    .stat {{ min-width: 140px; }}
    .flash {{
      background: rgba(103, 80, 164, 0.15);
      border: 1px solid #6750a4;
      border-radius: 8px;
      padding: 12px 16px;
      margin-bottom: 16px;
    }}
    .inline-form {{
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      align-items: center;
      margin-bottom: 6px;
    }}
    .inline-form label {{ display: flex; gap: 4px; align-items: center; }}
    .pairing-grid {{
      display: flex;
      gap: 24px;
      flex-wrap: wrap;
      align-items: center;
      margin-top: 12px;
    }}
    .pairing-code {{
      letter-spacing: 0.35em;
      text-align: center;
      font-size: 1.75rem;
    }}
    .qr {{
      border-radius: 12px;
      background: white;
      padding: 8px;
    }}
  </style>
</head>
<body>
  <h1>Tailscale PIA Controller</h1>
  <p class="muted">Version {escape(__version__)} · Status: <strong>{escape(status)}</strong></p>
  {flash}
  {pairing_block}

  <section class="card">
    <h2>Overview</h2>
    <div class="stats">
      <div class="stat"><strong>{active_stacks}</strong><br><span class="muted">Active stacks</span></div>
      <div class="stat"><strong>{registered_devices}</strong><br><span class="muted">Registered devices</span></div>
    </div>
    <p><a href="/docs">API documentation</a> · <a href="/health">Health JSON</a> · <a href="/pairing">Pairing JSON</a> · <a href="/regions">Regions JSON</a></p>
  </section>

  <section class="card">
    <h2>Registered devices</h2>
    <p class="muted">Control VPN per device from here if the client app is not available. Removing a device invalidates its API token — the mobile app must register again.</p>
    <table>
      <thead>
        <tr>
          <th>Device</th><th>ID</th><th>VPN</th><th>Region</th><th>Exit node</th><th>Stack</th><th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {device_rows or '<tr><td colspan="7">No devices registered yet</td></tr>'}
      </tbody>
    </table>
  </section>

  <section class="card">
    <h2>Available regions</h2>
    <table>
      <thead><tr><th>Region</th><th>Exit node hostname</th><th>Stack status</th></tr></thead>
      <tbody>{region_rows or '<tr><td colspan="3">No regions configured</td></tr>'}</tbody>
    </table>
    <p class="muted">After enabling a region, select the matching exit node in the Tailscale app on the device (e.g. <code>pia-mexico</code>).</p>
  </section>
</body>
</html>"""
