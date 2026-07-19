"""HTML dashboard for the controller."""

from __future__ import annotations

from html import escape

from app import __version__
from app.pairing import pairing_instructions, pairing_required


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
              <img id="pairing-qr" src="/pairing/qr" width="220" height="220" alt="Pairing QR code" class="qr" />
            </div>
            <div>
              <p class="muted">Pairing code</p>
              <div id="pairing-code" class="secret pairing-code">{code}</div>
              <p class="muted">Expires at <span id="pairing-expires">{expires}</span> UTC</p>
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
              <img id="pairing-qr" src="/pairing/qr" width="220" height="220" alt="Controller URL QR code" class="qr" />
            </div>
            <div>
              <p class="muted">Scan to fill the controller URL in the app. No pairing code is required.</p>
            </div>
          </div>
        </section>
        """

    region_rows = "".join(
        f"<tr data-region-id=\"{escape(r['id'])}\"><td>{escape(r['display_name'])}</td>"
        f"<td><code>{escape(r['server_region'])}</code></td>"
        f"<td><code>{escape(r['hostname'])}</code></td>"
        f"<td class=\"region-stack\">{escape(r['stack_status'])}</td>"
        f"<td {_idle_cell_attrs(r)}>{escape(_idle_label(r))}</td></tr>"
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
        <tr data-device-id="{escape(device['id'])}">
          <td><strong>{escape(device['name'])}</strong><br><span class="muted">{escape(device['platform'])}</span></td>
          <td><code>{escape(device['id'][:8])}…</code></td>
          <td class="device-vpn">{vpn_label}</td>
          <td class="device-region">{escape(device.get('region_display_name') or '—')}</td>
          <td class="device-exit"><code>{escape(device.get('exit_node_hostname') or '—')}</code></td>
          <td class="device-stack">{escape(device.get('stack_status') or '—')}</td>
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
    .live-indicator {{
      font-size: 0.85rem;
      color: #666;
    }}
    .live-indicator.live {{ color: #2e7d32; }}
  </style>
</head>
<body data-pairing-required="{"true" if pairing_required() else "false"}">
  <h1>Tailscale PIA Controller</h1>
  <p class="muted">
    Version {escape(__version__)} · Status: <strong>{escape(status)}</strong>
    · <span id="live-indicator" class="live-indicator">Live updates starting…</span>
  </p>
  {flash}
  {pairing_block}

  <section class="card">
    <h2>Overview</h2>
    <div class="stats">
      <div class="stat"><strong id="stat-active-stacks">{active_stacks}</strong><br><span class="muted">Active stacks</span></div>
      <div class="stat"><strong id="stat-registered-devices">{registered_devices}</strong><br><span class="muted">Registered devices</span></div>
      <div class="stat"><strong id="stat-idle-timeout">{idle_shutdown_minutes}m</strong><br><span class="muted">Idle shutdown timeout</span></div>
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
      <tbody id="devices-tbody">
        {device_rows or '<tr id="devices-empty"><td colspan="7">No devices registered yet</td></tr>'}
      </tbody>
    </table>
  </section>

  <section class="card">
    <h2>Available regions</h2>
    <table>
      <thead><tr><th>Region</th><th>PIA server region</th><th>Exit node hostname</th><th>Stack status</th><th>Idle shutdown</th></tr></thead>
      <tbody id="regions-tbody">{region_rows or '<tr><td colspan="5">No regions configured</td></tr>'}</tbody>
    </table>
    <p class="muted">After enabling a region, select the matching exit node in the Tailscale app on the device (e.g. <code>pia-mexico</code>).</p>
  </section>
  <script>
    const POLL_INTERVAL_MS = 3000;
    let lastPairingCode = document.getElementById("pairing-code")?.textContent?.trim() || "";

    function escapeHtml(value) {{
      const node = document.createElement("div");
      node.textContent = value ?? "";
      return node.innerHTML;
    }}

    function regionOptions(regions, selected) {{
      const options = ['<option value="">— select region —</option>'];
      for (const region of regions) {{
        const sel = selected === region.id ? " selected" : "";
        const label = `${{region.display_name}} — ${{region.server_region}}`;
        options.push(`<option value="${{escapeHtml(region.id)}}"${{sel}}>${{escapeHtml(label)}}</option>`);
      }}
      return options.join("");
    }}

    function adminSecretField(pairingRequired) {{
      if (!pairingRequired) return "";
      return `
        <label>Admin secret
          <input type="password" name="secret" placeholder="Pairing secret" required />
        </label>`;
    }}

    function collectAdminSecrets() {{
      const secrets = {{}};
      document.querySelectorAll("#devices-tbody tr[data-device-id]").forEach((row) => {{
        const secret = row.querySelector('input[name="secret"]')?.value;
        if (secret) secrets[row.dataset.deviceId] = secret;
      }});
      return secrets;
    }}

    function renderDeviceRow(device, regions, pairingRequired, savedSecrets) {{
      const vpnLabel = device.vpn_enabled ? "Yes" : "No";
      const secretValue = savedSecrets[device.id] ? ` value="${{escapeHtml(savedSecrets[device.id])}}"` : "";
      const secretField = pairingRequired
        ? `<label>Admin secret
            <input type="password" name="secret" placeholder="Pairing secret" required${{secretValue}} />
          </label>`
        : "";
      return `
        <tr data-device-id="${{escapeHtml(device.id)}}">
          <td><strong>${{escapeHtml(device.name)}}</strong><br><span class="muted">${{escapeHtml(device.platform)}}</span></td>
          <td><code>${{escapeHtml(device.id.slice(0, 8))}}…</code></td>
          <td class="device-vpn">${{vpnLabel}}</td>
          <td class="device-region">${{escapeHtml(device.region_display_name || "—")}}</td>
          <td class="device-exit"><code>${{escapeHtml(device.exit_node_hostname || "—")}}</code></td>
          <td class="device-stack">${{escapeHtml(device.stack_status || "—")}}</td>
          <td>
            <form method="post" action="/admin/devices/${{escapeHtml(device.id)}}/vpn" class="inline-form">
              ${{secretField}}
              <label><input type="hidden" name="enabled" value="true" />
              <select name="region">${{regionOptions(regions, device.region)}}</select></label>
              <button type="submit">Enable</button>
            </form>
            <form method="post" action="/admin/devices/${{escapeHtml(device.id)}}/vpn" class="inline-form">
              ${{secretField}}
              <input type="hidden" name="enabled" value="false" />
              <button type="submit" class="danger">Disable</button>
            </form>
            <form method="post" action="/admin/devices/${{escapeHtml(device.id)}}/delete" class="inline-form"
                  onsubmit="return confirm('Remove this device? The app will need to register again.');">
              ${{secretField}}
              <button type="submit" class="danger">Remove</button>
            </form>
          </td>
        </tr>`;
    }}

    function updateDevices(devices, regions, pairingRequired) {{
      const tbody = document.getElementById("devices-tbody");
      if (!tbody) return;

      const savedSecrets = collectAdminSecrets();
      const currentIds = [...tbody.querySelectorAll("tr[data-device-id]")].map((row) => row.dataset.deviceId).join(",");
      const nextIds = devices.map((device) => device.id).join(",");

      if (currentIds === nextIds) {{
        for (const device of devices) {{
          const row = tbody.querySelector(`tr[data-device-id="${{device.id}}"]`);
          if (!row) continue;
          row.querySelector(".device-vpn").textContent = device.vpn_enabled ? "Yes" : "No";
          row.querySelector(".device-region").textContent = device.region_display_name || "—";
          row.querySelector(".device-exit code").textContent = device.exit_node_hostname || "—";
          row.querySelector(".device-stack").textContent = device.stack_status || "—";
        }}
        return;
      }}

      if (!devices.length) {{
        tbody.innerHTML = '<tr id="devices-empty"><td colspan="7">No devices registered yet</td></tr>';
        return;
      }}

      tbody.innerHTML = devices
        .map((device) => renderDeviceRow(device, regions, pairingRequired, savedSecrets))
        .join("");
    }}

    function formatIdleCountdown(region) {{
      if (region.idle_status === "in_use") {{
        const noun = region.ref_count === 1 ? "device" : "devices";
        return `In use (${{region.ref_count}} ${{noun}})`;
      }}
      if (region.stack_status !== "running" && region.stack_status !== "starting") {{
        return "—";
      }}
      if (region.idle_status === "eligible") {{
        return "Shutdown pending";
      }}
      if (!region.shutdown_at) {{
        return `Idle (${{region.idle_shutdown_minutes}}m timeout)`;
      }}
      const remaining = new Date(region.shutdown_at) - Date.now();
      if (remaining <= 0) return "Shutdown pending";
      const mins = Math.floor(remaining / 60000);
      const secs = Math.floor((remaining % 60000) / 1000);
      return `Stops in ${{mins}}m ${{secs}}s`;
    }}

    function regionIdleCellAttrs(region) {{
      return `class="region-idle" data-idle-status="${{escapeHtml(region.idle_status)}}" data-stack-status="${{escapeHtml(region.stack_status)}}" data-shutdown-at="${{escapeHtml(region.shutdown_at || "")}}" data-idle-minutes="${{region.idle_shutdown_minutes}}" data-ref-count="${{region.ref_count}}"`;
    }}

    function tickIdleCountdowns() {{
      document.querySelectorAll(".region-idle").forEach((cell) => {{
        const region = {{
          idle_status: cell.dataset.idleStatus,
          stack_status: cell.dataset.stackStatus,
          shutdown_at: cell.dataset.shutdownAt || null,
          idle_shutdown_minutes: Number(cell.dataset.idleMinutes || 30),
          ref_count: Number(cell.dataset.refCount || 0),
        }};
        cell.textContent = formatIdleCountdown(region);
      }});
    }}

    function updateRegions(regions) {{
      const tbody = document.getElementById("regions-tbody");
      if (!tbody) return;

      const currentIds = [...tbody.querySelectorAll("tr[data-region-id]")].map((row) => row.dataset.regionId).join(",");
      const nextIds = regions.map((region) => region.id).join(",");

      if (currentIds === nextIds) {{
        for (const region of regions) {{
          const row = tbody.querySelector(`tr[data-region-id="${{region.id}}"]`);
          if (!row) continue;
          row.querySelector(".region-stack").textContent = region.stack_status;
          const idleCell = row.querySelector(".region-idle");
          idleCell.dataset.idleStatus = region.idle_status;
          idleCell.dataset.stackStatus = region.stack_status;
          idleCell.dataset.shutdownAt = region.shutdown_at || "";
          idleCell.dataset.idleMinutes = region.idle_shutdown_minutes;
          idleCell.dataset.refCount = region.ref_count;
          idleCell.textContent = formatIdleCountdown(region);
        }}
        return;
      }}

      tbody.innerHTML = regions.map((region) => `
        <tr data-region-id="${{escapeHtml(region.id)}}">
          <td>${{escapeHtml(region.display_name)}}</td>
          <td><code>${{escapeHtml(region.server_region)}}</code></td>
          <td><code>${{escapeHtml(region.hostname)}}</code></td>
          <td class="region-stack">${{escapeHtml(region.stack_status)}}</td>
          <td ${{regionIdleCellAttrs(region)}}>${{escapeHtml(formatIdleCountdown(region))}}</td>
        </tr>`).join("");
    }}

    function updatePairing(state) {{
      const codeEl = document.getElementById("pairing-code");
      const expiresEl = document.getElementById("pairing-expires");
      const qrEl = document.getElementById("pairing-qr");

      if (codeEl && state.pairing_code) {{
        codeEl.textContent = state.pairing_code;
      }}
      if (expiresEl && state.pairing_code_expires_at) {{
        expiresEl.textContent = state.pairing_code_expires_at;
      }}
      if (qrEl && state.pairing_code && state.pairing_code !== lastPairingCode) {{
        lastPairingCode = state.pairing_code;
        qrEl.src = `/pairing/qr?t=${{Date.now()}}`;
      }}
    }}

    async function refreshDashboard() {{
      if (document.hidden) return;

      try {{
        const response = await fetch("/dashboard/state", {{ headers: {{ Accept: "application/json" }} }});
        if (!response.ok) throw new Error(`HTTP ${{response.status}}`);
        const state = await response.json();

        document.getElementById("stat-active-stacks").textContent = state.active_stacks;
        document.getElementById("stat-registered-devices").textContent = state.registered_devices;
        document.getElementById("stat-idle-timeout").textContent = `${{state.idle_shutdown_minutes}}m`;
        updateRegions(state.regions);
        updateDevices(state.devices, state.regions, state.pairing_required);
        updatePairing(state);

        const indicator = document.getElementById("live-indicator");
        const updatedAt = new Date().toLocaleTimeString();
        indicator.textContent = `Live · updated ${{updatedAt}}`;
        indicator.classList.add("live");
      }} catch (error) {{
        const indicator = document.getElementById("live-indicator");
        indicator.textContent = `Live update failed: ${{error.message}}`;
        indicator.classList.remove("live");
      }}
    }}

    refreshDashboard();
    setInterval(refreshDashboard, POLL_INTERVAL_MS);
    setInterval(tickIdleCountdowns, 1000);
    tickIdleCountdowns();
    document.addEventListener("visibilitychange", () => {{
      if (!document.hidden) refreshDashboard();
    }});
  </script>
</body>
</html>"""
