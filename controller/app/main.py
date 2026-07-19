"""FastAPI application entrypoint."""

from __future__ import annotations

import asyncio
import io
import logging
from contextlib import asynccontextmanager, suppress
from pathlib import Path
from typing import Annotated
from urllib.parse import quote

from fastapi import BackgroundTasks, Depends, FastAPI, Form, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

from app import __version__
from app.auth import get_current_device, verify_admin_secret, verify_pairing_credentials
from app.config import get_settings
from app.dashboard import render_dashboard
from app.database import Base, SessionLocal, engine, get_db
from app.docker_manager import build_region_info_dict, reconcile_ref_counts, stop_idle_stacks, stop_region_stack
from app.host_paths import resolve_host_path
from app.models import Device, RegionStack, VpnSession
from app.pairing import pairing_instructions, pairing_required
from app.pairing_codes import build_pairing_payload, get_or_create_active_code
from app.regions import load_regions
from app.schemas import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    DeviceUpdateRequest,
    DeviceUpdateResponse,
    DeviceSummary,
    DeviceListResponse,
    DashboardStateResponse,
    HealthResponse,
    PairingInfoResponse,
    RegionInfo,
    RegionListResponse,
    VpnStatusResponse,
    VpnUpdateRequest,
)
from app.vpn_service import (
    apply_vpn_update,
    build_vpn_status,
    delete_device,
    get_or_create_session,
    idle_cleanup,
    list_device_summaries,
    shutdown_all_devices,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


async def _periodic_idle_cleanup() -> None:
    while True:
        await asyncio.sleep(60)
        db = SessionLocal()
        try:
            stopped = stop_idle_stacks(db)
            if stopped:
                logger.info("Periodic idle cleanup stopped %s regional stack(s)", stopped)
        except Exception as exc:
            logger.error("Periodic idle cleanup failed: %s", exc)
        finally:
            db.close()


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)
    settings = get_settings()
    startup_db = SessionLocal()
    try:
        reconcile_ref_counts(startup_db)
    finally:
        startup_db.close()
    host_runtime = resolve_host_path(settings.runtime_dir)
    logger.info("Resolved host runtime directory: %s", host_runtime)
    try:
        import subprocess

        result = subprocess.run(["docker", "version", "--format", "{{.Server.Version}}"], capture_output=True, text=True)
        if result.returncode == 0:
            logger.info("Docker daemon reachable (server %s)", result.stdout.strip())
        else:
            logger.error("Docker daemon not reachable: %s", (result.stderr or result.stdout).strip())
    except Exception as exc:
        logger.error("Docker check failed: %s", exc)
    if pairing_required():
        logger.info("Pairing secret is ENABLED — view it at http://0.0.0.0:8090/")
    else:
        logger.info("Pairing secret is disabled — device registration is open")
    cleanup_task = asyncio.create_task(_periodic_idle_cleanup())
    try:
        yield
    finally:
        cleanup_task.cancel()
        with suppress(asyncio.CancelledError):
            await cleanup_task
        logger.info("Controller shutting down — disabling VPN for all devices and stopping stacks")
        db = SessionLocal()
        try:
            disabled = shutdown_all_devices(db)
            logger.info("Shutdown complete — disabled %s device VPN session(s)", disabled)
        except Exception as exc:
            logger.error("Shutdown cleanup failed: %s", exc)
        finally:
            db.close()


app = FastAPI(
    title="Tailscale PIA Controller",
    description="Per-device PIA region control via Tailscale exit nodes",
    version=__version__,
    lifespan=lifespan,
)

static_dir = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=static_dir), name="static")


def _region_dicts(db: Session) -> list[dict]:
    regions = [
        build_region_info_dict(db, region_id, region)
        for region_id, region in load_regions().items()
    ]
    return sorted(regions, key=lambda item: item["display_name"])


def _region_infos(db: Session) -> list[RegionInfo]:
    return [RegionInfo(**item) for item in _region_dicts(db)]


@app.get("/", response_class=HTMLResponse)
def dashboard(
    db: Session = Depends(get_db),
    msg: Annotated[str | None, Query()] = None,
) -> HTMLResponse:
    active = db.query(RegionStack).filter(RegionStack.status == "running").count()
    devices_count = db.query(Device).count()
    code_row = get_or_create_active_code(db)
    html = render_dashboard(
        status="ok",
        active_stacks=active,
        registered_devices=devices_count,
        idle_shutdown_minutes=get_settings().idle_shutdown_minutes,
        regions=_region_dicts(db),
        devices=list_device_summaries(db),
        message=msg,
        pairing_code=code_row.code if code_row else None,
        pairing_code_expires_at=code_row.expires_at.strftime("%Y-%m-%d %H:%M") if code_row else None,
    )
    return HTMLResponse(content=html)


@app.get("/dashboard/state", response_model=DashboardStateResponse)
def dashboard_state(db: Session = Depends(get_db)) -> DashboardStateResponse:
    active = db.query(RegionStack).filter(RegionStack.status == "running").count()
    devices_count = db.query(Device).count()
    code_row = get_or_create_active_code(db)
    return DashboardStateResponse(
        active_stacks=active,
        registered_devices=devices_count,
        idle_shutdown_minutes=get_settings().idle_shutdown_minutes,
        regions=_region_infos(db),
        devices=[
            DeviceSummary(
                id=item["id"],
                name=item["name"],
                platform=item["platform"],
                created_at=item["created_at"],
                vpn_enabled=item["vpn_enabled"],
                region=item["region"],
                region_display_name=item["region_display_name"],
                exit_node_hostname=item["exit_node_hostname"],
                stack_status=item["stack_status"],
            )
            for item in list_device_summaries(db)
        ],
        pairing_required=pairing_required(),
        pairing_code=code_row.code if code_row else None,
        pairing_code_expires_at=code_row.expires_at.strftime("%Y-%m-%d %H:%M") if code_row else None,
    )


@app.get("/pairing", response_model=PairingInfoResponse)
def pairing_info(db: Session = Depends(get_db)) -> PairingInfoResponse:
    required = pairing_required()
    code_row = get_or_create_active_code(db) if required else None
    return PairingInfoResponse(
        required=required,
        instructions=pairing_instructions(),
        pairing_code=code_row.code if code_row else None,
        pairing_code_expires_at=code_row.expires_at.isoformat() + "Z" if code_row else None,
    )


@app.get("/pairing/qr")
def pairing_qr(request: Request, db: Session = Depends(get_db)) -> Response:
    import qrcode

    base_url = str(request.base_url).rstrip("/")
    code_row = get_or_create_active_code(db)
    payload = build_pairing_payload(base_url, code_row.code if code_row else None)
    image = qrcode.make(payload)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return Response(content=buffer.getvalue(), media_type="image/png")


@app.get("/health", response_model=HealthResponse)
def health(db: Session = Depends(get_db)) -> HealthResponse:
    active = db.query(RegionStack).filter(RegionStack.status == "running").count()
    devices = db.query(Device).count()
    return HealthResponse(status="ok", version=__version__, active_stacks=active, registered_devices=devices)


@app.get("/regions", response_model=RegionListResponse)
def list_regions(db: Session = Depends(get_db)) -> RegionListResponse:
    return RegionListResponse(regions=_region_infos(db))


@app.get("/admin/devices", response_model=DeviceListResponse)
def admin_list_devices(db: Session = Depends(get_db)) -> DeviceListResponse:
    summaries = [
        DeviceSummary(
            id=item["id"],
            name=item["name"],
            platform=item["platform"],
            created_at=item["created_at"],
            vpn_enabled=item["vpn_enabled"],
            region=item["region"],
            region_display_name=item["region_display_name"],
            exit_node_hostname=item["exit_node_hostname"],
            stack_status=item["stack_status"],
        )
        for item in list_device_summaries(db)
    ]
    return DeviceListResponse(devices=summaries)


@app.post("/devices/register", response_model=DeviceRegisterResponse)
def register_device(payload: DeviceRegisterRequest, db: Session = Depends(get_db)) -> DeviceRegisterResponse:
    verify_pairing_credentials(
        db,
        pairing_secret=payload.pairing_secret,
        pairing_code=payload.pairing_code,
    )

    device = Device(
        name=payload.name,
        platform=payload.platform,
        tailscale_ip=payload.tailscale_ip,
    )
    device.session = VpnSession(enabled=False)
    db.add(device)
    db.commit()
    db.refresh(device)

    return DeviceRegisterResponse(device_id=device.id, api_token=device.api_token, name=device.name)


@app.patch("/devices/me", response_model=DeviceUpdateResponse)
def update_device(
    payload: DeviceUpdateRequest,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> DeviceUpdateResponse:
    device.name = payload.name
    db.commit()
    db.refresh(device)
    return DeviceUpdateResponse(device_id=device.id, name=device.name)


@app.get("/devices/me/vpn", response_model=VpnStatusResponse)
def get_vpn_status(device: Device = Depends(get_current_device), db: Session = Depends(get_db)) -> VpnStatusResponse:
    session = get_or_create_session(db, device)
    return build_vpn_status(device, session, db)


@app.put("/devices/me/vpn", response_model=VpnStatusResponse)
def update_vpn_status(
    payload: VpnUpdateRequest,
    background_tasks: BackgroundTasks,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> VpnStatusResponse:
    return apply_vpn_update(device, payload, db, background_tasks)


@app.post("/admin/devices/{device_id}/vpn")
def admin_update_device_vpn(
    device_id: str,
    background_tasks: BackgroundTasks,
    enabled: Annotated[str, Form()],
    db: Session = Depends(get_db),
    region: Annotated[str | None, Form()] = None,
    secret: Annotated[str | None, Form()] = None,
):
    verify_admin_secret(secret)
    device = db.get(Device, device_id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    is_enabled = enabled.lower() in {"true", "1", "on", "yes"}
    payload = VpnUpdateRequest(enabled=is_enabled, region=region or None)
    result = apply_vpn_update(device, payload, db, background_tasks)
    msg = result.message or ("VPN updated" if is_enabled else "VPN disabled")
    return RedirectResponse(url=f"/?msg={quote(msg)}", status_code=303)


@app.post("/admin/devices/{device_id}/delete")
def admin_delete_device(
    device_id: str,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    secret: Annotated[str | None, Form()] = None,
):
    verify_admin_secret(secret)
    device = db.get(Device, device_id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
    name = device.name
    delete_device(device, db, background_tasks)
    return RedirectResponse(url=f"/?msg=Removed device {name}", status_code=303)


@app.post("/admin/cleanup-idle")
def cleanup_idle(db: Session = Depends(get_db)) -> dict:
    stopped = stop_idle_stacks(db)
    return {"stopped": stopped}


@app.post("/admin/regions/{region_id}/stop")
def admin_stop_region(
    region_id: str,
    db: Session = Depends(get_db),
    secret: Annotated[str | None, Form()] = None,
):
    verify_admin_secret(secret)
    regions = load_regions()
    if region_id not in regions:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown region")

    try:
        stopped = stop_region_stack(db, region_id)
    except RuntimeError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    if not stopped:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Regional stack is not running",
        )

    label = regions[region_id].display_name
    return RedirectResponse(url=f"/?msg={quote(f'Stopped {label} stack')}", status_code=303)
