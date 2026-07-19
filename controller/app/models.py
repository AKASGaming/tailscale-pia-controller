"""Database models."""

import secrets
import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


def new_token() -> str:
    return secrets.token_urlsafe(32)


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name: Mapped[str] = mapped_column(String(128))
    platform: Mapped[str] = mapped_column(String(32), default="unknown")
    api_token: Mapped[str] = mapped_column(String(64), default=new_token, unique=True, index=True)
    tailscale_ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    session: Mapped["VpnSession | None"] = relationship(back_populates="device", uselist=False)


class VpnSession(Base):
    __tablename__ = "vpn_sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), unique=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    region: Mapped[str | None] = mapped_column(String(64), nullable=True)
    exit_node_hostname: Mapped[str | None] = mapped_column(String(128), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    device: Mapped[Device] = relationship(back_populates="session")


class RegionStack(Base):
    __tablename__ = "region_stacks"

    region: Mapped[str] = mapped_column(String(64), primary_key=True)
    status: Mapped[str] = mapped_column(String(32), default="stopped")  # starting, running, stopping, stopped, error
    ref_count: Mapped[int] = mapped_column(Integer, default=0)
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    ts_port: Mapped[int] = mapped_column(Integer, default=0)
