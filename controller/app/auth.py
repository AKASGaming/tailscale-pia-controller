"""Authentication helpers."""

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.models import Device
from app.pairing_codes import validate_pairing_code

security = HTTPBearer(auto_error=False)


def get_current_device(
    credentials: HTTPAuthorizationCredentials | None = Depends(security),
    db: Session = Depends(get_db),
) -> Device:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")

    device = db.query(Device).filter(Device.api_token == credentials.credentials).first()
    if device is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid API token")
    return device


def verify_pairing_secret(provided: str | None) -> None:
    settings = get_settings()
    expected = settings.controller_secret.strip()
    if not expected:
        return
    if not provided or not provided.strip():
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Pairing secret required. Open the controller dashboard in your browser to view it.",
        )
    if provided.strip() != expected:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid pairing secret")


def verify_pairing_credentials(
    db: Session,
    *,
    pairing_secret: str | None,
    pairing_code: str | None,
) -> None:
    settings = get_settings()
    expected = settings.controller_secret.strip()
    if not expected:
        return

    if validate_pairing_code(db, pairing_code):
        return
    if pairing_secret and pairing_secret.strip() == expected:
        return

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Valid pairing code required. Scan the QR code on the controller dashboard or enter the 6-character code.",
    )


def verify_admin_secret(provided: str | None) -> None:
    """Protect admin web actions when CONTROLLER_SECRET is configured."""
    verify_pairing_secret(provided)
