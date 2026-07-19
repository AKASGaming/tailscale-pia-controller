#!/usr/bin/env python3
"""Cross-platform CLI client for the Tailscale PIA controller."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

try:
    import urllib.request
except ImportError:  # pragma: no cover
    print("Python 3 is required", file=sys.stderr)
    sys.exit(1)

CONFIG_PATH = Path(__file__).with_name(".vpn-control.json")


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        return {}
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def save_config(data: dict) -> None:
    CONFIG_PATH.write_text(json.dumps(data, indent=2), encoding="utf-8")


def request(method: str, path: str, body: dict | None = None, token: str | None = None, base_url: str | None = None):
    config = load_config()
    base = (base_url or config.get("controller_url", "")).rstrip("/")
    if not base:
        raise SystemExit("Controller URL required. Register first or pass --controller-url.")

    payload = None
    headers = {"Accept": "application/json"}
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(f"{base}{path}", data=payload, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def tailscale_exit_node(hostname: str | None) -> None:
    cmd = ["tailscale", "set", f"--exit-node={hostname or ''}", "--exit-node-allow-lan-access=true"]
    try:
        subprocess.run(cmd, check=False)
    except FileNotFoundError:
        print("tailscale CLI not found. Install Tailscale and run:", " ".join(cmd))


def main() -> None:
    parser = argparse.ArgumentParser(description="Tailscale PIA controller client")
    parser.add_argument("--controller-url", help="Controller base URL")
    sub = parser.add_subparsers(dest="command", required=True)

    register = sub.add_parser("register", help="Register this device")
    register.add_argument("--name", required=True)
    register.add_argument("--platform", default="cli")
    register.add_argument("--pairing-secret")

    sub.add_parser("regions", help="List regions")
    sub.add_parser("status", help="Show VPN status")

    enable = sub.add_parser("enable", help="Enable PIA for this device")
    enable.add_argument("--region", required=True)

    sub.add_parser("disable", help="Disable PIA for this device")

    args = parser.parse_args()
    config = load_config()

    if args.command == "register":
        data = request(
            "POST",
            "/devices/register",
            {
                "name": args.name,
                "platform": args.platform,
                "pairing_secret": args.pairing_secret,
            },
            base_url=args.controller_url,
        )
        save_config(
            {
                "controller_url": args.controller_url.rstrip("/"),
                "api_token": data["api_token"],
                "device_id": data["device_id"],
            }
        )
        print(json.dumps(data, indent=2))
        return

    token = config.get("api_token")
    if not token:
        raise SystemExit("Not registered. Run: vpn-control.py register --controller-url ... --name ...")

    if args.command == "regions":
        print(json.dumps(request("GET", "/regions", token=token, base_url=args.controller_url), indent=2))
        return

    if args.command == "status":
        print(json.dumps(request("GET", "/devices/me/vpn", token=token, base_url=args.controller_url), indent=2))
        return

    if args.command == "enable":
        data = request(
            "PUT",
            "/devices/me/vpn",
            {"enabled": True, "region": args.region},
            token=token,
            base_url=args.controller_url,
        )
        print(json.dumps(data, indent=2))
        if data.get("stack_status") == "running" and data.get("exit_node_hostname"):
            tailscale_exit_node(data["exit_node_hostname"])
        return

    if args.command == "disable":
        data = request(
            "PUT",
            "/devices/me/vpn",
            {"enabled": False, "region": None},
            token=token,
            base_url=args.controller_url,
        )
        print(json.dumps(data, indent=2))
        tailscale_exit_node(None)


if __name__ == "__main__":
    main()
