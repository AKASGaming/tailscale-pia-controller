"""FastAPI application entrypoint."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, status
from fastapi.responses import HTMLResponse
from sqlalchemy.orm import Session

from app import __version__
from app.auth import get_current_device, verify_pairing_secret
from app.dashboard import render_dashboard
from app.database import Base, SessionLocal, engine, get_db
from app.docker_manager import ensure_region_stack, get_stack_status, release_region_stack, stop_idle_stacks
from app.models import Device, RegionStack, VpnSession
from app.pairing import pairing_instructions, pairing_required, pairing_secret_value
from app.regions import load_regions
from app.schemas import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    HealthResponse,
    PairingInfoResponse,
    RegionInfo,
    RegionListResponse,
    VpnStatusResponse,
    VpnUpdateRequest,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)
    if pairing_required():
        logger.info("Pairing secret is ENABLED — view it at http://0.0.0.0:8090/")
    else:
        logger.info("Pairing secret is disabled — device registration is open")
    yield


app = FastAPI(
    title="Tailscale PIA Controller",
    description="Per-device PIA region control via Tailscale exit nodes",
    version=__version__,
    lifespan=lifespan,
)


@app.get("/", response_class=HTMLResponse)
def dashboard(db: Session = Depends(get_db)) -> HTMLResponse:
    active = db.query(RegionStack).filter(RegionStack.status == "running").count()
    devices = db.query(Device).count()
    regions = []
    for region_id, region in load_regions().items():
        regions.append(
            {
                "display_name": region.display_name,
                "hostname": region.hostname,
                "stack_status": get_stack_status(db, region_id) or "stopped",
            }
        )
    html = render_dashboard(
        status="ok",
        active_stacks=active,
        registered_devices=devices,
        regions=sorted(regions, key=lambda item: item["display_name"]),
    )
    return HTMLResponse(content=html)


@app.get("/pairing", response_model=PairingInfoResponse)
def pairing_info() -> PairingInfoResponse:
    required = pairing_required()
    return PairingInfoResponse(
        required=required,
        instructions=pairing_instructions(),
        secret=pairing_secret_value() if required else None,
    )


@app.get("/health", response_model=HealthResponse)
def health(db: Session = Depends(get_db)) -> HealthResponse:
    active = db.query(RegionStack).filter(RegionStack.status == "running").count()
    devices = db.query(Device).count()
    return HealthResponse(status="ok", version=__version__, active_stacks=active, registered_devices=devices)


@app.get("/regions", response_model=RegionListResponse)
def list_regions(db: Session = Depends(get_db)) -> RegionListResponse:
    regions = []
    for region_id, region in load_regions().items():
        stack_status = get_stack_status(db, region_id) or "stopped"
        regions.append(
            RegionInfo(
                id=region_id,
                display_name=region.display_name,
                hostname=region.hostname,
                stack_status=stack_status,
            )
        )
    return RegionListResponse(regions=sorted(regions, key=lambda item: item.display_name))


@app.post("/devices/register", response_model=DeviceRegisterResponse)
def register_device(payload: DeviceRegisterRequest, db: Session = Depends(get_db)) -> DeviceRegisterResponse:
    verify_pairing_secret(payload.pairing_secret)

    device = Device(
        name=payload.name,
        platform=payload.platform,
        tailscale_ip=payload.tailscale_ip,
    )
    db.add(device)
    db.add(VpnSession(device_id=device.id, enabled=False))
    db.commit()
    db.refresh(device)

    return DeviceRegisterResponse(device_id=device.id, api_token=device.api_token, name=device.name)


@app.get("/devices/me/vpn", response_model=VpnStatusResponse)
def get_vpn_status(device: Device = Depends(get_current_device), db: Session = Depends(get_db)) -> VpnStatusResponse:
    session = db.query(VpnSession).filter(VpnSession.device_id == device.id).first()
    if session is None:
        session = VpnSession(device_id=device.id, enabled=False)
        db.add(session)
        db.commit()
        db.refresh(session)

    stack_status = get_stack_status(db, session.region)
    message = None
    if session.enabled and stack_status == "starting":
        message = "Regional VPN stack is starting. Wait 15-45 seconds, then enable the Tailscale exit node."
    elif session.enabled and stack_status == "error":
        message = "Regional VPN stack failed to start. Check controller logs."

    return VpnStatusResponse(
        device_id=device.id,
        enabled=session.enabled,
        region=session.region,
        exit_node_hostname=session.exit_node_hostname,
        allow_lan_access=True,
        stack_status=stack_status,
        message=message,
    )


@app.put("/devices/me/vpn", response_model=VpnStatusResponse)
def update_vpn_status(
    payload: VpnUpdateRequest,
    background_tasks: BackgroundTasks,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
) -> VpnStatusResponse:
    session = db.query(VpnSession).filter(VpnSession.device_id == device.id).first()
    if session is None:
        session = VpnSession(device_id=device.id)
        db.add(session)

    previous_region = session.region

    if payload.enabled:
        if not payload.region:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="region is required when enabling VPN")

        regions = load_regions()
        if payload.region not in regions:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Unknown region: {payload.region}")

        if previous_region and previous_region != payload.region:
            release_region_stack(db, previous_region)

        stack = ensure_region_stack(db, payload.region)
        region = regions[payload.region]

        session.enabled = True
        session.region = payload.region
        session.exit_node_hostname = region.hostname
        session.updated_at = datetime.utcnow()
        db.commit()

        message = "VPN enabled."
        if stack.status == "starting":
            message = (
                f"Starting {region.display_name} exit node ({region.hostname}). "
                "Wait until stack is running, then select this exit node in Tailscale."
            )
        elif stack.status == "error":
            message = f"Failed to start stack: {stack.error_message or 'unknown error'}"

        return VpnStatusResponse(
            device_id=device.id,
            enabled=True,
            region=session.region,
            exit_node_hostname=session.exit_node_hostname,
            allow_lan_access=True,
            stack_status=stack.status,
            message=message,
        )

    if previous_region:
        release_region_stack(db, previous_region)
        background_tasks.add_task(_idle_cleanup)

    session.enabled = False
    session.region = None
    session.exit_node_hostname = None
    session.updated_at = datetime.utcnow()
    db.commit()

    return VpnStatusResponse(
        device_id=device.id,
        enabled=False,
        region=None,
        exit_node_hostname=None,
        allow_lan_access=True,
        stack_status=None,
        message="VPN disabled for this device. Clear your Tailscale exit node.",
    )


def _idle_cleanup() -> None:
    db = SessionLocal()
    try:
        stopped = stop_idle_stacks(db)
        if stopped:
            logger.info("Stopped %s idle regional stacks", stopped)
    except Exception:
        logger.exception("Idle cleanup failed")
    finally:
        db.close()


@app.post("/admin/cleanup-idle")
def cleanup_idle(db: Session = Depends(get_db)) -> dict:
    stopped = stop_idle_stacks(db)
    return {"stopped": stopped}
