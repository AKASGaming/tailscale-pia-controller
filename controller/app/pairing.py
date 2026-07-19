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
            "Device registration requires the pairing secret shown below. "
            "Enter the same value in the mobile app or CLI when registering."
        )
    return (
        "No pairing secret is configured. Any device on your network can register. "
        "Set CONTROLLER_SECRET in your .env file to restrict registration."
    )
