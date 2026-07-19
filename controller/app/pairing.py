"""Pairing secret helpers."""

from app.config import get_settings


def pairing_required() -> bool:
    return bool(get_settings().controller_secret.strip())


def pairing_secret_value() -> str | None:
    secret = get_settings().controller_secret.strip()
    return secret or None


def pairing_instructions() -> str:
    if pairing_required():
        return (
            "Scan the QR code with the PIA Control app, or enter the 6-character pairing code below. "
            "Codes expire after 30 minutes and refresh automatically."
        )
    return (
        "No pairing secret is configured. Any device on your network can register. "
        "Set CONTROLLER_SECRET in your .env file to restrict registration."
    )
