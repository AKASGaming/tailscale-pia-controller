"""Ephemeral pairing codes for device registration."""

from __future__ import annotations

import json
import secrets
from datetime import datetime, timedelta

from sqlalchemy.orm import Session

from app.models import PairingCode
from app.pairing import pairing_required

CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 6
CODE_TTL_MINUTES = 30


def generate_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


def get_or_create_active_code(db: Session) -> PairingCode | None:
    if not pairing_required():
        return None

    now = datetime.utcnow()
    active = (
        db.query(PairingCode)
        .filter(PairingCode.expires_at > now)
        .order_by(PairingCode.created_at.desc())
        .first()
    )
    if active is not None:
        return active

    code = PairingCode(
        code=generate_code(),
        expires_at=now + timedelta(minutes=CODE_TTL_MINUTES),
    )
    db.add(code)
    db.commit()
    db.refresh(code)
    return code


def validate_pairing_code(db: Session, code: str | None) -> bool:
    if not pairing_required():
        return True
    if not code or not code.strip():
        return False

    normalized = code.strip().upper()
    now = datetime.utcnow()
    row = (
        db.query(PairingCode)
        .filter(
            PairingCode.code == normalized,
            PairingCode.expires_at > now,
        )
        .first()
    )
    return row is not None


def build_pairing_payload(base_url: str, code: str | None) -> str:
    payload: dict[str, object] = {"v": 1, "url": base_url.rstrip("/")}
    if code:
        payload["code"] = code
    return json.dumps(payload, separators=(",", ":"))
