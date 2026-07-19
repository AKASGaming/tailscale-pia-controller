"""Docker Compose lifecycle for regional Gluetun + Tailscale stacks."""

from __future__ import annotations

import logging
import subprocess
import textwrap
import time
from datetime import datetime, timedelta
from pathlib import Path

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import RegionStack
from app.regions import RegionConfig, load_regions

logger = logging.getLogger(__name__)


def _region_port(region_index: int) -> int:
    settings = get_settings()
    return settings.ts_base_port + region_index


def _compose_path(region_id: str) -> Path:
    settings = get_settings()
    settings.runtime_dir.mkdir(parents=True, exist_ok=True)
    return settings.runtime_dir / f"compose.{region_id}.yml"


def _render_compose(region: RegionConfig, ts_port: int) -> str:
    settings = get_settings()
    gluetun_name = f"gluetun-{region.id}"
    tailscale_name = f"tailscale-exit-{region.id}"
    data_dir = f"{settings.host_runtime_dir.rstrip('/')}/data/{region.id}"

    return textwrap.dedent(
        f"""
        services:
          {gluetun_name}:
            image: qmcgaw/gluetun:latest
            container_name: {gluetun_name}
            cap_add:
              - NET_ADMIN
            devices:
              - /dev/net/tun:/dev/net/tun
            environment:
              VPN_SERVICE_PROVIDER: private internet access
              VPN_TYPE: openvpn
              OPENVPN_USER: ${{PIA_USER}}
              OPENVPN_PASSWORD: ${{PIA_PASS}}
              SERVER_REGIONS: {region.server_region}
              FIREWALL_OUTBOUND_SUBNETS: ${{LAN_CIDR}},100.64.0.0/10
              FIREWALL_INPUT_PORTS: {ts_port}
            volumes:
              - {data_dir}/gluetun:/gluetun
            ports:
              - "{ts_port}:{ts_port}/udp"
            healthcheck:
              test: ["CMD", "/gluetun-entrypoint", "healthcheck"]
              interval: 30s
              timeout: 5s
              retries: 5
              start_period: 60s
            restart: unless-stopped
            labels:
              vpn.region: "{region.id}"
              vpn.managed-by: vpn-controller

          {tailscale_name}:
            image: tailscale/tailscale:latest
            container_name: {tailscale_name}
            network_mode: service:{gluetun_name}
            environment:
              TS_AUTHKEY: ${{TS_AUTHKEY}}
              TS_HOSTNAME: {region.hostname}
              TS_STATE_DIR: /var/lib/tailscale
              TS_USERSPACE: "true"
              TS_ACCEPT_DNS: "false"
              TS_EXTRA_ARGS: --advertise-exit-node
            volumes:
              - {data_dir}/tailscale:/var/lib/tailscale
            depends_on:
              {gluetun_name}:
                condition: service_healthy
            restart: unless-stopped
            labels:
              vpn.region: "{region.id}"
              vpn.managed-by: vpn-controller
        """
    ).strip() + "\n"


def _run_compose(compose_file: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    settings = get_settings()
    env = {
        "PIA_USER": settings.pia_user,
        "PIA_PASS": settings.pia_pass,
        "TS_AUTHKEY": settings.ts_authkey,
        "LAN_CIDR": settings.lan_cidr,
        "COMPOSE_PROJECT_NAME": f"{settings.compose_project_name}-{compose_file.stem.replace('compose.', '')}",
    }
    cmd = [
        "docker",
        "compose",
        "-f",
        str(compose_file),
        "-p",
        env["COMPOSE_PROJECT_NAME"],
        *args,
    ]
    logger.info("Running: %s", " ".join(cmd))
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        check=check,
        cwd=settings.runtime_dir,
        env={**subprocess.os.environ, **env},
    )


def _container_healthy(container_name: str) -> bool:
    result = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Health.Status}}", container_name],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return False
    status = result.stdout.strip()
    return status in {"healthy", ""}


def ensure_region_stack(db: Session, region_id: str) -> RegionStack:
    regions = load_regions()
    if region_id not in regions:
        raise KeyError(f"Unknown region: {region_id}")

    region = regions[region_id]
    stack = db.get(RegionStack, region_id)
    region_ids = list(regions.keys())
    ts_port = _region_port(region_ids.index(region_id))

    if stack is None:
        stack = RegionStack(region=region_id, ts_port=ts_port, status="stopped", ref_count=0)
        db.add(stack)

    stack.ref_count += 1
    stack.last_used_at = datetime.utcnow()

    if stack.status in {"running", "starting"}:
        db.commit()
        db.refresh(stack)
        return stack

    stack.status = "starting"
    stack.error_message = None
    db.commit()

    compose_file = _compose_path(region_id)
    compose_file.write_text(_render_compose(region, ts_port), encoding="utf-8")
    settings = get_settings()
    data_root = settings.runtime_dir / "data" / region_id
    (data_root / "gluetun").mkdir(parents=True, exist_ok=True)
    (data_root / "tailscale").mkdir(parents=True, exist_ok=True)

    try:
        _run_compose(compose_file, "up", "-d")
        gluetun_name = f"gluetun-{region_id}"
        for _ in range(60):
            if _container_healthy(gluetun_name):
                stack.status = "running"
                break
            time.sleep(2)
        else:
            stack.status = "error"
            stack.error_message = "Gluetun failed to become healthy within timeout"
    except subprocess.CalledProcessError as exc:
        stack.status = "error"
        stack.error_message = (exc.stderr or exc.stdout or str(exc))[:2000]
        logger.exception("Failed to start region %s", region_id)

    db.commit()
    db.refresh(stack)
    return stack


def release_region_stack(db: Session, region_id: str | None) -> None:
    if not region_id:
        return

    stack = db.get(RegionStack, region_id)
    if stack is None:
        return

    stack.ref_count = max(0, stack.ref_count - 1)
    stack.last_used_at = datetime.utcnow()
    db.commit()


def stop_idle_stacks(db: Session) -> int:
    settings = get_settings()
    cutoff = datetime.utcnow() - timedelta(minutes=settings.idle_shutdown_minutes)
    stopped = 0

    stacks = db.query(RegionStack).filter(RegionStack.status == "running").all()
    for stack in stacks:
        if stack.ref_count > 0:
            continue
        if stack.last_used_at and stack.last_used_at > cutoff:
            continue

        compose_file = _compose_path(stack.region)
        if compose_file.exists():
            try:
                _run_compose(compose_file, "down", check=False)
            except Exception:
                logger.exception("Failed to stop region %s", stack.region)

        stack.status = "stopped"
        stopped += 1

    db.commit()
    return stopped


def get_stack_status(db: Session, region_id: str | None) -> str | None:
    if not region_id:
        return None
    stack = db.get(RegionStack, region_id)
    return stack.status if stack else None
