"""Application configuration."""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "sqlite:////data/vpn-controller.db"
    regions_file: Path = Path("/project/regions/regions.yaml")
    runtime_dir: Path = Path("/project/runtime")
    compose_project_name: str = "tailscale-pia"
    docker_project_name: str = "tailscale-pia-controller"
    ts_exit_node_tag: str = "tag:pia-exit"

    pia_user: str = ""
    pia_pass: str = ""
    ts_authkey: str = ""
    lan_cidr: str = "192.168.1.0/24"
    ts_base_port: int = 41641

    controller_secret: str = ""
    idle_shutdown_minutes: int = 30
    host_runtime_dir: str = "/project/runtime"


@lru_cache
def get_settings() -> Settings:
    return Settings()
