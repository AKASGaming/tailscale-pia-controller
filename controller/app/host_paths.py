"""Resolve container paths to host paths for Docker bind mounts."""

from __future__ import annotations

import logging
from pathlib import Path, PurePosixPath

logger = logging.getLogger(__name__)


def resolve_host_path(container_path: str | Path) -> str:
    """Map a path inside this container to the corresponding host path."""
    target = PurePosixPath(str(container_path).replace("\\", "/")).as_posix()
    if target != "/":
        target = target.rstrip("/")

    best_mountpoint = ""
    best_host_root = ""

    try:
        with open("/proc/self/mountinfo", encoding="utf-8") as handle:
            for line in handle:
                parts = line.split()
                if "-" not in parts:
                    continue

                root = parts[3]
                mountpoint = parts[4]
                dash = parts.index("-")
                source = parts[dash + 2]

                if mountpoint != target and not target.startswith(mountpoint.rstrip("/") + "/"):
                    continue
                if len(mountpoint) < len(best_mountpoint):
                    continue

                if root in ("/", "."):
                    host_root = source
                else:
                    host_root = f"{source.rstrip('/')}/{root.lstrip('/')}"

                best_mountpoint = mountpoint
                best_host_root = host_root
    except OSError as exc:
        logger.warning("Could not read mountinfo: %s", exc)
        return target

    if not best_mountpoint:
        logger.warning("No mountinfo match for %s; using path as-is", target)
        return target

    suffix = target[len(best_mountpoint) :].lstrip("/")
    if suffix:
        return f"{best_host_root.rstrip('/')}/{suffix}"
    return best_host_root
