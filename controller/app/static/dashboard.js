const POLL_INTERVAL_MS = 5000;
let lastPairingCode = document.getElementById("pairing-code")?.textContent?.trim() || "";
let refreshController = null;
let lastRegionSnapshot = "";
let lastDeviceSnapshot = "";

function escapeHtml(value) {
  const node = document.createElement("div");
  node.textContent = value ?? "";
  return node.innerHTML;
}

function stackBadge(status) {
  const normalized = (status || "stopped").toLowerCase();
  const cls = ["running", "starting", "stopped", "error"].includes(normalized)
    ? `badge-${normalized}`
    : "badge-stopped";
  return `<span class="badge ${cls}">${escapeHtml(status || "stopped")}</span>`;
}

function adminSecretInput() {
  const pairingRequired = document.body.dataset.pairingRequired === "true";
  if (!pairingRequired) return "";
  const value = document.getElementById("admin-secret")?.value || "";
  const escaped = escapeHtml(value);
  return `<input type="hidden" name="secret" value="${escaped}" />`;
}

function regionOptions(regions, selected) {
  const options = ['<option value="">— select region —</option>'];
  for (const region of regions) {
    const sel = selected === region.id ? " selected" : "";
    const label = `${region.display_name} — ${region.server_region}`;
    options.push(`<option value="${escapeHtml(region.id)}"${sel}>${escapeHtml(label)}</option>`);
  }
  return options.join("");
}

function formatIdleCountdown(region) {
  if (region.idle_status === "in_use") {
    const noun = region.ref_count === 1 ? "device" : "devices";
    return `In use (${region.ref_count} ${noun})`;
  }
  if (region.stack_status !== "running" && region.stack_status !== "starting") {
    return "—";
  }
  if (region.idle_status === "eligible") {
    return "Shutdown pending";
  }
  if (!region.shutdown_at) {
    return `Idle (${region.idle_shutdown_minutes}m timeout)`;
  }
  const remaining = new Date(region.shutdown_at) - Date.now();
  if (remaining <= 0) return "Shutdown pending";
  const mins = Math.floor(remaining / 60000);
  const secs = Math.floor((remaining % 60000) / 1000);
  return `Stops in ${mins}m ${secs}s`;
}

function regionSnapshot(regions) {
  return regions.map((region) => [
    region.id,
    region.stack_status,
    region.idle_status,
    region.shutdown_at || "",
    region.ref_count,
  ].join(":")).join("|");
}

function deviceSnapshot(devices) {
  return devices.map((device) => [
    device.id,
    device.vpn_enabled,
    device.region,
    device.region_display_name,
    device.exit_node_hostname,
    device.stack_status,
  ].join(":")).join("|");
}

function renderRegionStopButton(region) {
  if (!["idle", "eligible"].includes(region.idle_status)) return "";
  if (!["running", "starting"].includes(region.stack_status)) return "";
  return `<form method="post" action="/admin/regions/${escapeHtml(region.id)}/stop" class="inline-form"
    onsubmit="return confirm('Stop this regional stack now?');">
    ${adminSecretInput()}
    <button type="submit" class="danger">Stop now</button>
  </form>`;
}

function renderDeviceRow(device, regions) {
  const vpnLabel = device.vpn_enabled ? "Yes" : "No";
  return `
    <tr data-device-id="${escapeHtml(device.id)}">
      <td><strong>${escapeHtml(device.name)}</strong><br><span class="muted">${escapeHtml(device.platform)}</span></td>
      <td><code>${escapeHtml(device.id.slice(0, 8))}…</code></td>
      <td class="device-vpn">${vpnLabel}</td>
      <td class="device-region">${escapeHtml(device.region_display_name || "—")}</td>
      <td class="device-exit"><code>${escapeHtml(device.exit_node_hostname || "—")}</code></td>
      <td class="device-stack">${stackBadge(device.stack_status || "—")}</td>
      <td>
        <form method="post" action="/admin/devices/${escapeHtml(device.id)}/vpn" class="inline-form">
          ${adminSecretInput()}
          <label><input type="hidden" name="enabled" value="true" />
          <select name="region">${regionOptions(regions, device.region)}</select></label>
          <button type="submit">Enable</button>
        </form>
        <form method="post" action="/admin/devices/${escapeHtml(device.id)}/vpn" class="inline-form">
          ${adminSecretInput()}
          <input type="hidden" name="enabled" value="false" />
          <button type="submit" class="danger">Disable</button>
        </form>
        <form method="post" action="/admin/devices/${escapeHtml(device.id)}/delete" class="inline-form"
              onsubmit="return confirm('Remove this device? The app will need to register again.');">
          ${adminSecretInput()}
          <button type="submit" class="danger">Remove</button>
        </form>
      </td>
    </tr>`;
}

function updateDevices(devices, regions) {
  const tbody = document.getElementById("devices-tbody");
  if (!tbody) return;

  const currentIds = [...tbody.querySelectorAll("tr[data-device-id]")].map((row) => row.dataset.deviceId).join(",");
  const nextIds = devices.map((device) => device.id).join(",");

  if (currentIds === nextIds) {
    for (const device of devices) {
      const row = tbody.querySelector(`tr[data-device-id="${device.id}"]`);
      if (!row) continue;
      row.querySelector(".device-vpn").textContent = device.vpn_enabled ? "Yes" : "No";
      row.querySelector(".device-region").textContent = device.region_display_name || "—";
      row.querySelector(".device-exit code").textContent = device.exit_node_hostname || "—";
      row.querySelector(".device-stack").innerHTML = stackBadge(device.stack_status || "—");
    }
    return;
  }

  if (!devices.length) {
    tbody.innerHTML = '<tr id="devices-empty"><td colspan="7">No devices registered yet</td></tr>';
    return;
  }

  tbody.innerHTML = devices.map((device) => renderDeviceRow(device, regions)).join("");
  lastDeviceSnapshot = deviceSnapshot(devices);
}

function updateRegions(regions) {
  const tbody = document.getElementById("regions-tbody");
  if (!tbody) return;

  const currentIds = [...tbody.querySelectorAll("tr[data-region-id]")].map((row) => row.dataset.regionId).join(",");
  const nextIds = regions.map((region) => region.id).join(",");

  if (currentIds === nextIds) {
    for (const region of regions) {
      const row = tbody.querySelector(`tr[data-region-id="${region.id}"]`);
      if (!row) continue;
      row.querySelector(".region-stack").innerHTML = stackBadge(region.stack_status);
      const idleCell = row.querySelector(".region-idle");
      idleCell.dataset.idleStatus = region.idle_status;
      idleCell.dataset.stackStatus = region.stack_status;
      idleCell.dataset.shutdownAt = region.shutdown_at || "";
      idleCell.dataset.idleMinutes = region.idle_shutdown_minutes;
      idleCell.dataset.refCount = region.ref_count;
      idleCell.textContent = formatIdleCountdown(region);
      row.querySelector(".region-actions").innerHTML = renderRegionStopButton(region);
    }
    lastRegionSnapshot = regionSnapshot(regions);
    return;
  }

  tbody.innerHTML = regions.map((region) => `
    <tr data-region-id="${escapeHtml(region.id)}">
      <td>${escapeHtml(region.display_name)}</td>
      <td><code>${escapeHtml(region.server_region)}</code></td>
      <td><code>${escapeHtml(region.hostname)}</code></td>
      <td class="region-stack">${stackBadge(region.stack_status)}</td>
      <td class="region-idle"
          data-idle-status="${escapeHtml(region.idle_status)}"
          data-stack-status="${escapeHtml(region.stack_status)}"
          data-shutdown-at="${escapeHtml(region.shutdown_at || "")}"
          data-idle-minutes="${region.idle_shutdown_minutes}"
          data-ref-count="${region.ref_count}">${escapeHtml(formatIdleCountdown(region))}</td>
      <td class="region-actions">${renderRegionStopButton(region)}</td>
    </tr>`).join("");
  lastRegionSnapshot = regionSnapshot(regions);
}

function tickIdleCountdowns() {
  document.querySelectorAll(".region-idle").forEach((cell) => {
    const region = {
      idle_status: cell.dataset.idleStatus,
      stack_status: cell.dataset.stackStatus,
      shutdown_at: cell.dataset.shutdownAt || null,
      idle_shutdown_minutes: Number(cell.dataset.idleMinutes || 30),
      ref_count: Number(cell.dataset.refCount || 0),
    };
    cell.textContent = formatIdleCountdown(region);
  });
}

function updatePairing(state) {
  const codeEl = document.getElementById("pairing-code");
  const expiresEl = document.getElementById("pairing-expires");
  const qrEl = document.getElementById("pairing-qr");

  if (codeEl && state.pairing_code) {
    codeEl.textContent = state.pairing_code;
  }
  if (expiresEl && state.pairing_code_expires_at) {
    expiresEl.textContent = state.pairing_code_expires_at;
  }
  if (qrEl && state.pairing_code && state.pairing_code !== lastPairingCode) {
    lastPairingCode = state.pairing_code;
    qrEl.src = `/pairing/qr?t=${Date.now()}`;
  }
}

async function refreshDashboard() {
  if (document.hidden) return;

  refreshController?.abort();
  refreshController = new AbortController();

  try {
    const response = await fetch("/dashboard/state", {
      headers: { Accept: "application/json" },
      signal: refreshController.signal,
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const state = await response.json();

    document.getElementById("stat-active-stacks").textContent = state.active_stacks;
    document.getElementById("stat-registered-devices").textContent = state.registered_devices;
    document.getElementById("stat-idle-timeout").textContent = `${state.idle_shutdown_minutes}m`;

    const statusEl = document.getElementById("server-status");
    if (statusEl && state.status) {
      statusEl.textContent = state.status;
      statusEl.className = `server-status server-status-${state.status.toLowerCase()}`;
    }
    updateRegions(state.regions);
    updateDevices(state.devices, state.regions);
    updatePairing(state);

    const indicator = document.getElementById("live-indicator");
    const updatedAt = new Date().toLocaleTimeString();
    indicator.textContent = `Live · updated ${updatedAt}`;
    indicator.classList.add("live");
  } catch (error) {
    if (error.name === "AbortError") return;
    const indicator = document.getElementById("live-indicator");
    indicator.textContent = `Live update failed: ${error.message}`;
    indicator.classList.remove("live");
  }
}

function showFlash(message, isError = false) {
  const banner = document.getElementById("flash-banner");
  if (!banner || !message) return;
  banner.textContent = message;
  banner.classList.remove("hidden", "error");
  if (isError) banner.classList.add("error");
  clearTimeout(showFlash._timer);
  showFlash._timer = setTimeout(() => banner.classList.add("hidden"), 8000);
}

function consumeLegacyMsgParam() {
  const params = new URLSearchParams(window.location.search);
  const legacyMsg = params.get("msg");
  if (!legacyMsg) return;
  showFlash(legacyMsg);
  params.delete("msg");
  const query = params.toString();
  const cleanUrl = query ? `${window.location.pathname}?${query}` : window.location.pathname;
  history.replaceState({}, "", cleanUrl);
}

async function submitAdminForm(form) {
  if (document.body.dataset.pairingRequired === "true") {
    const secret = document.getElementById("admin-secret")?.value?.trim() || "";
    if (!secret) {
      showFlash("Enter the admin secret first.", true);
      return;
    }
    if (!form.querySelector('input[name="secret"]')) {
      const input = document.createElement("input");
      input.type = "hidden";
      input.name = "secret";
      input.value = secret;
      form.appendChild(input);
    }
  }

  const formData = new FormData(form);
  const response = await fetch(form.action, {
    method: form.method || "POST",
    body: formData,
    headers: {
      Accept: "application/json",
      "X-Dashboard-Submit": "1",
    },
  });

  let data = {};
  try {
    data = await response.json();
  } catch (_) {
    data = {};
  }

  if (!response.ok) {
    const detail = data.detail;
    const message = typeof detail === "string"
      ? detail
      : Array.isArray(detail)
        ? detail.map((item) => item.msg || item).join(", ")
        : `HTTP ${response.status}`;
    throw new Error(message);
  }

  if (data.message) showFlash(data.message);
  await refreshDashboard();
}

consumeLegacyMsgParam();
refreshDashboard();
setInterval(refreshDashboard, POLL_INTERVAL_MS);
setInterval(tickIdleCountdowns, 1000);
tickIdleCountdowns();
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refreshDashboard();
});

document.addEventListener("submit", async (event) => {
  const form = event.target;
  if (!(form instanceof HTMLFormElement)) return;
  if (!form.action.includes("/admin/")) return;

  event.preventDefault();
  try {
    await submitAdminForm(form);
  } catch (error) {
    showFlash(error.message || "Action failed", true);
  }
});
