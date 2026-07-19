"""Shared VPN session logic for API and admin UI."""

from __future__ import annotations

from datetime import datetime

from fastapi import BackgroundTasks, HTTPException, status
from sqlalchemy.orm import Session

from app.docker_manager import ensure_region_stack, get_stack_status, release_region_stack, stop_idle_stacks
from app.database import SessionLocal
from app.models import Device, VpnSession
from app.regions import load_regions, region_display_label
from app.schemas import VpnStatusResponse, VpnUpdateRequest


def get_or_create_session(db: Session, device: Device) -> VpnSession:
    session = db.query(VpnSession).filter(VpnSession.device_id == device.id).first()
    if session is None:
        session = VpnSession(device_id=device.id, enabled=False)
        db.add(session)
        db.commit()
        db.refresh(session)
    return session


def build_vpn_status(device: Device, session: VpnSession, db: Session) -> VpnStatusResponse:
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


def apply_vpn_update(
    device: Device,
    payload: VpnUpdateRequest,
    db: Session,
    background_tasks: BackgroundTasks | None = None,
) -> VpnStatusResponse:
    session = get_or_create_session(db, device)
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
        if background_tasks is not None:
            background_tasks.add_task(idle_cleanup)

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


def delete_device(device: Device, db: Session, background_tasks: BackgroundTasks | None = None) -> None:
    session = db.query(VpnSession).filter(VpnSession.device_id == device.id).first()
    if session and session.enabled and session.region:
        release_region_stack(db, session.region)
        if background_tasks is not None:
            background_tasks.add_task(idle_cleanup)
    db.delete(device)
    db.commit()


def idle_cleanup() -> None:
    db = SessionLocal()
    try:
        stop_idle_stacks(db)
    except Exception:
        pass
    finally:
        db.close()


def list_device_summaries(db: Session) -> list[dict]:
    regions = load_regions()
    devices = db.query(Device).order_by(Device.created_at.desc()).all()
    summaries = []
    for device in devices:
        session = db.query(VpnSession).filter(VpnSession.device_id == device.id).first()
        region_id = session.region if session else None
        summaries.append(
            {
                "id": device.id,
                "name": device.name,
                "platform": device.platform,
                "created_at": device.created_at.strftime("%Y-%m-%d %H:%M UTC"),
                "vpn_enabled": bool(session and session.enabled),
                "region": region_id,
                "region_display_name": region_display_label(region_id, regions),
                "exit_node_hostname": session.exit_node_hostname if session else None,
                "stack_status": get_stack_status(db, region_id),
            }
        )
    return summaries
