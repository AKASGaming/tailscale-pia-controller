"""Docker lifecycle for regional Gluetun + Tailscale stacks."""

from __future__ import annotations

import logging
import subprocess
import time
from datetime import datetime, timedelta
from pathlib import Path

from sqlalchemy.orm import Session

from app.config import get_settings
from app.host_paths import resolve_host_path
from app.models import RegionStack
from app.regions import RegionConfig, load_regions

logger = logging.getLogger(__name__)


def _region_port(region_index: int) -> int:
    settings = get_settings()
    return settings.ts_base_port + region_index


def _host_runtime_dir() -> str:
    settings = get_settings()
    configured = settings.host_runtime_dir.strip()
    if configured and configured != "/project/runtime":
        return configured.rstrip("/")
    return resolve_host_path(settings.runtime_dir).rstrip("/")


def _host_data_dir(region_id: str) -> str:
    return f"{_host_runtime_dir()}/data/{region_id}"


def _run_docker(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    cmd = ["docker", *args]
    logger.info("Running: %s", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout or f"exit code {result.returncode}").strip()
        raise subprocess.CalledProcessError(
            result.returncode,
            cmd,
            output=result.stdout,
            stderr=detail,
        )
    return result


def _remove_container(name: str) -> None:
    _run_docker("rm", "-f", name, check=False)


def _container_exists(name: str) -> bool:
    result = _run_docker("inspect", "-f", "{{.Id}}", name, check=False)
    return result.returncode == 0


def _container_healthy(name: str) -> bool:
    result = _run_docker("inspect", "-f", "{{.State.Health.Status}}", name, check=False)
    if result.returncode != 0:
        return False
    status = result.stdout.strip()
    return status in {"healthy", ""}


def _wait_for_healthy(name: str, timeout_seconds: int = 120) -> bool:
    for _ in range(timeout_seconds // 2):
        if _container_healthy(name):
            return True
        time.sleep(2)
    return False


def _stack_labels(region_id: str, service: str) -> list[str]:
    settings = get_settings()
    project = settings.docker_project_name
    host_project = resolve_host_path("/project")
    return [
        "--label",
        f"com.docker.compose.project={project}",
        "--label",
        f"com.docker.compose.service={service}",
        "--label",
        f"com.docker.compose.project.working_dir={host_project}",
        "--label",
        "com.docker.compose.project.config_files=docker-compose.yml",
        "--label",
        f"vpn.region={region_id}",
        "--label",
        "vpn.managed-by=vpn-controller",
    ]


def _tailscale_extra_args() -> str:
    settings = get_settings()
    tag = settings.ts_exit_node_tag.strip()
    if tag:
        return f"--advertise-exit-node --advertise-tags={tag}"
    return "--advertise-exit-node"


def _start_gluetun(region: RegionConfig, ts_port: int, data_dir: str) -> None:
    settings = get_settings()
    name = f"gluetun-{region.id}"

    _remove_container(name)
    _run_docker(
        "run",
        "-d",
        "--name",
        name,
        "--cap-add",
        "NET_ADMIN",
        "--device",
        "/dev/net/tun:/dev/net/tun",
        "-e",
        "VPN_SERVICE_PROVIDER=private internet access",
        "-e",
        "VPN_TYPE=openvpn",
        "-e",
        f"OPENVPN_USER={settings.pia_user}",
        "-e",
        f"OPENVPN_PASSWORD={settings.pia_pass}",
        "-e",
        f"SERVER_REGIONS={region.server_region}",
        "-e",
        f"FIREWALL_OUTBOUND_SUBNETS={settings.lan_cidr},100.64.0.0/10",
        "-e",
        f"FIREWALL_INPUT_PORTS={ts_port}",
        "-v",
        f"{data_dir}/gluetun:/gluetun",
        "-p",
        f"{ts_port}:{ts_port}/udp",
        *_stack_labels(region.id, f"gluetun-{region.id}"),
        "--restart",
        "unless-stopped",
        "qmcgaw/gluetun:latest",
    )


def _start_tailscale(region: RegionConfig, data_dir: str) -> None:
    settings = get_settings()
    gluetun_name = f"gluetun-{region.id}"
    name = f"tailscale-exit-{region.id}"

    _remove_container(name)
    _run_docker(
        "run",
        "-d",
        "--name",
        name,
        "--network",
        f"container:{gluetun_name}",
        "-e",
        f"TS_AUTHKEY={settings.ts_authkey}",
        "-e",
        f"TS_HOSTNAME={region.hostname}",
        "-e",
        "TS_STATE_DIR=/var/lib/tailscale",
        "-e",
        "TS_USERSPACE=true",
        "-e",
        "TS_ACCEPT_DNS=false",
        "-e",
        f"TS_EXTRA_ARGS={_tailscale_extra_args()}",
        "-v",
        f"{data_dir}/tailscale:/var/lib/tailscale",
        *_stack_labels(region.id, f"tailscale-exit-{region.id}"),
        "--restart",
        "unless-stopped",
        "tailscale/tailscale:latest",
    )


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

    gluetun_name = f"gluetun-{region_id}"
    if stack.status == "running" and _container_exists(gluetun_name) and _container_healthy(gluetun_name):
        db.commit()
        db.refresh(stack)
        return stack

    stack.status = "starting"
    stack.error_message = None
    db.commit()

    settings = get_settings()
    data_dir = _host_data_dir(region_id)
    container_data_root = settings.runtime_dir / "data" / region_id
    (container_data_root / "gluetun").mkdir(parents=True, exist_ok=True)
    (container_data_root / "tailscale").mkdir(parents=True, exist_ok=True)

    logger.info("Using host data directory: %s", data_dir)

    try:
        if not settings.pia_user or not settings.pia_pass:
            raise RuntimeError("PIA_USER and PIA_PASS must be set in the controller environment")
        if not settings.ts_authkey:
            raise RuntimeError("TS_AUTHKEY must be set in the controller environment")

        _start_gluetun(region, ts_port, data_dir)
        if not _wait_for_healthy(gluetun_name):
            logs = _run_docker("logs", "--tail", "40", gluetun_name, check=False)
            snippet = (logs.stderr or logs.stdout or "no logs").strip()[-1500:]
            raise RuntimeError(f"Gluetun failed to become healthy. Recent logs:\n{snippet}")

        _start_tailscale(region, data_dir)
        stack.status = "running"
    except (subprocess.CalledProcessError, RuntimeError) as exc:
        stack.status = "error"
        if isinstance(exc, subprocess.CalledProcessError):
            stack.error_message = (exc.stderr or exc.stdout or str(exc))[:2000]
            logger.error("Failed to start region %s: %s", region_id, stack.error_message)
        else:
            stack.error_message = str(exc)[:2000]
            logger.error("Failed to start region %s: %s", region_id, stack.error_message)

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

        _remove_container(f"tailscale-exit-{stack.region}")
        _remove_container(f"gluetun-{stack.region}")
        stack.status = "stopped"
        stopped += 1

    db.commit()
    return stopped


def get_stack_status(db: Session, region_id: str | None) -> str | None:
    if not region_id:
        return None
    stack = db.get(RegionStack, region_id)
    return stack.status if stack else None
