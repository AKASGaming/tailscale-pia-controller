"""Region configuration loader."""

from dataclasses import dataclass
from pathlib import Path

import yaml

from app.config import get_settings


@dataclass(frozen=True)
class RegionConfig:
    id: str
    display_name: str
    server_region: str
    hostname: str


def load_regions(path: Path | None = None) -> dict[str, RegionConfig]:
    settings = get_settings()
    regions_path = path or settings.regions_file
    with regions_path.open(encoding="utf-8") as handle:
        raw = yaml.safe_load(handle)

    regions: dict[str, RegionConfig] = {}
    for region_id, data in raw.get("regions", {}).items():
        regions[region_id] = RegionConfig(
            id=region_id,
            display_name=data["display_name"],
            server_region=data["server_region"],
            hostname=data["hostname"],
        )
    return regions


def get_region(region_id: str) -> RegionConfig:
    regions = load_regions()
    if region_id not in regions:
        raise KeyError(f"Unknown region: {region_id}")
    return regions[region_id]
