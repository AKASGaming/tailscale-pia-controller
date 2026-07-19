"""Authentication helpers."""

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.models import Device

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
