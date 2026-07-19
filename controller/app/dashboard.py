"""Simple HTML dashboard for the controller."""

from __future__ import annotations

from html import escape

from app import __version__
from app.pairing import pairing_instructions, pairing_required, pairing_secret_value


def render_dashboard(
    *,
    status: str,
    active_stacks: int,
    registered_devices: int,
    regions: list[dict],
) -> str:
    pairing_block = ""
    if pairing_required():
        secret = escape(pairing_secret_value() or "")
        pairing_block = f"""
        <section class="card highlight">
          <h2>Pairing secret</h2>
          <p>{escape(pairing_instructions())}</p>
          <div class="secret">{secret}</div>
          <p class="muted">Copy this into the Android app or CLI when registering a device.</p>
        </section>
        """
    else:
        pairing_block = f"""
        <section class="card">
          <h2>Pairing secret</h2>
          <p>{escape(pairing_instructions())}</p>
        </section>
        """

    region_rows = "".join(
        f"<tr><td>{escape(r['display_name'])}</td><td><code>{escape(r['hostname'])}</code></td>"
        f"<td>{escape(r['stack_status'])}</td></tr>"
        for r in regions
    )

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
    body {{ max-width: 900px; margin: 0 auto; padding: 24px; }}
    h1 {{ margin-bottom: 0.25rem; }}
    .muted {{ color: #666; }}
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
    table {{ width: 100%; border-collapse: collapse; }}
    th, td {{ text-align: left; padding: 8px; border-bottom: 1px solid #ddd; }}
    code {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }}
    a {{ color: #6750a4; }}
    .stats {{ display: flex; gap: 16px; flex-wrap: wrap; }}
    .stat {{ min-width: 140px; }}
  </style>
</head>
<body>
  <h1>Tailscale PIA Controller</h1>
  <p class="muted">Version {escape(__version__)} · Status: <strong>{escape(status)}</strong></p>

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
    <h2>Available regions</h2>
    <table>
      <thead><tr><th>Region</th><th>Exit node hostname</th><th>Stack status</th></tr></thead>
      <tbody>{region_rows or '<tr><td colspan="3">No regions configured</td></tr>'}</tbody>
    </table>
  </section>

  <section class="card">
    <h2>Register a device</h2>
    <ol>
      <li>Install the PIA Control Android app or use the Windows CLI.</li>
      <li>Enter this server's URL (e.g. <code>http://your-host:8090</code>).</li>
      <li>{"Enter the pairing secret shown above." if pairing_required() else "No pairing secret is needed."}</li>
      <li>Approve new <code>pia-*</code> exit nodes in the Tailscale admin console after enabling a region.</li>
    </ol>
  </section>
</body>
</html>"""
