"""Optional adapter for running Eclipse SUMO simulations.

SUMO is an external dependency and is not bundled with TrafficSignalStudio.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET


class SumoError(RuntimeError):
    """Raised when SUMO cannot be located or a simulation fails."""


def find_sumo(explicit_path: str | None = None) -> tuple[str, str]:
    """Return paths to sumo and netconvert executables."""
    candidates: list[Path] = []
    if explicit_path:
        candidates.append(Path(explicit_path))
    env_path = os.environ.get("SUMO_HOME")
    if env_path:
        candidates.append(Path(env_path))
    for candidate in candidates:
        roots = [candidate, candidate / "bin"]
        for root in roots:
            exe = "sumo.exe" if os.name == "nt" else "sumo"
            net_exe = "netconvert.exe" if os.name == "nt" else "netconvert"
            sumo = root / exe
            netconvert = root / net_exe
            if sumo.is_file() and netconvert.is_file():
                return str(sumo), str(netconvert)
    sumo = shutil.which("sumo")
    netconvert = shutil.which("netconvert")
    if sumo and netconvert:
        return sumo, netconvert
    raise SumoError("SUMO не найден. Укажите каталог SUMO или настройте SUMO_HOME/PATH.")


def run_simulation(
    network_file: str,
    routes_file: str,
    output_dir: str,
    duration: int = 3600,
    sumo_home: str | None = None,
    gui: bool = False,
    seed: int = 42,
) -> dict[str, Any]:
    """Run SUMO and return a compact summary from tripinfo.xml."""
    if duration <= 0:
        raise ValueError("duration must be positive")
    network = Path(network_file).expanduser().resolve()
    routes = Path(routes_file).expanduser().resolve()
    if not network.is_file():
        raise FileNotFoundError(f"Network file not found: {network}")
    if not routes.is_file():
        raise FileNotFoundError(f"Routes file not found: {routes}")
    sumo, _ = find_sumo(sumo_home)
    out = Path(output_dir).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    tripinfo = out / "tripinfo.xml"
    executable = Path(sumo).with_name(
        ("sumo-gui.exe" if os.name == "nt" else "sumo-gui") if gui else Path(sumo).name
    )
    if gui and not executable.is_file():
        raise SumoError(f"SUMO-GUI не найден: {executable}")
    command = [
        str(executable), "-n", str(network), "-r", str(routes),
        "--end", str(duration), "--seed", str(seed),
        "--tripinfo-output", str(tripinfo), "--no-step-log", "true",
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=max(duration * 2, 120))
    if completed.returncode:
        raise SumoError((completed.stderr or completed.stdout or "SUMO failed").strip())
    metrics = {"arrived": 0, "departed": 0, "duration_sum_s": 0.0, "waiting_sum_s": 0.0}
    if tripinfo.exists():
        root = ET.parse(tripinfo).getroot()
        for trip in root.findall("tripinfo"):
            metrics["arrived"] += 1
            metrics["duration_sum_s"] += float(trip.get("duration", 0))
            metrics["waiting_sum_s"] += float(trip.get("waitingTime", 0))
    metrics["departed"] = metrics["arrived"]
    metrics["average_trip_duration_s"] = (
        metrics["duration_sum_s"] / metrics["arrived"] if metrics["arrived"] else 0.0
    )
    metrics["average_waiting_time_s"] = (
        metrics["waiting_sum_s"] / metrics["arrived"] if metrics["arrived"] else 0.0
    )
    metrics["tripinfo_file"] = str(tripinfo)
    return metrics
