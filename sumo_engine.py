"""Eclipse SUMO backend for TrafficSignalStudio.

SUMO is launched as an external process. No SUMO code is copied into this project.
The user installs SUMO separately and supplies its directory or SUMO_HOME.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


class SumoError(RuntimeError):
    """Actionable error from the SUMO backend."""


def discover_sumo(sumo_home: str | None = None) -> dict[str, str]:
    candidates: list[Path] = []
    if sumo_home and sumo_home.strip():
        candidates.append(Path(sumo_home.strip()).expanduser())
    env_home = os.environ.get("SUMO_HOME")
    if env_home:
        candidates.append(Path(env_home).expanduser())
    suffix = ".exe" if os.name == "nt" else ""
    for base in candidates:
        roots = [base, base / "bin"] if base.name.lower() != "bin" else [base, base.parent]
        for root in roots:
            paths = {
                "sumo": root / f"sumo{suffix}",
                "sumo-gui": root / f"sumo-gui{suffix}",
                "netconvert": root / f"netconvert{suffix}",
            }
            if paths["sumo"].is_file() and paths["netconvert"].is_file():
                result = {key: str(value) for key, value in paths.items() if value.is_file()}
                tools = root.parent / "tools" if root.name.lower() == "bin" else root / "tools"
                random_trips = tools / "randomTrips.py"
                if random_trips.is_file():
                    result["randomTrips.py"] = str(random_trips)
                return result
    sumo = shutil.which("sumo")
    netconvert = shutil.which("netconvert")
    if sumo and netconvert:
        result = {"sumo": sumo, "netconvert": netconvert}
        gui = shutil.which("sumo-gui")
        if gui:
            result["sumo-gui"] = gui
        return result
    raise SumoError(
        "Не найден Eclipse SUMO. Установите SUMO и укажите каталог установки "
        "или задайте переменную SUMO_HOME."
    )


def _run(command: list[str], timeout: int = 180) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    except FileNotFoundError as exc:
        raise SumoError(f"Не найдена команда: {command[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise SumoError(f"Команда превысила лимит времени ({timeout} с): {command[0]}") from exc
    if result.returncode:
        details = (result.stderr or result.stdout or "без диагностического сообщения").strip()
        raise SumoError(f"SUMO завершился с кодом {result.returncode}.\n{details[-5000:]}")
    return result


def convert_osm_to_network(osm_path: str, output_network: str, sumo_home: str | None = None) -> str:
    source = Path(osm_path).expanduser().resolve()
    target = Path(output_network).expanduser().resolve()
    if not source.is_file():
        raise FileNotFoundError(f"OSM-файл не найден: {source}")
    if source.suffix.lower() not in {".osm", ".xml", ".pbf"}:
        raise ValueError("Ожидается файл OSM .osm, .xml или .pbf.")
    tools = discover_sumo(sumo_home)
    target.parent.mkdir(parents=True, exist_ok=True)
    command = [
        tools["netconvert"], "--osm-files", str(source), "--output-file", str(target),
        "--tls.guess", "true", "--tls.default-type", "static",
        "--junctions.join", "true",
    ]
    _run(command, timeout=300)
    if not target.is_file() or target.stat().st_size == 0:
        raise SumoError("netconvert не создал файл сети.")
    return str(target)


def generate_routes(
    network_file: str,
    output_routes: str,
    duration_seconds: int,
    demand_vph: int,
    seed: int = 42,
    sumo_home: str | None = None,
) -> str:
    network = Path(network_file).expanduser().resolve()
    target = Path(output_routes).expanduser().resolve()
    if not network.is_file():
        raise FileNotFoundError(f"Файл сети SUMO не найден: {network}")
    if duration_seconds <= 0:
        raise ValueError("Длительность должна быть положительной.")
    if demand_vph < 0:
        raise ValueError("Интенсивность не может быть отрицательной.")
    tools = discover_sumo(sumo_home)
    trips_script = tools.get("randomTrips.py")
    if not trips_script:
        raise SumoError("Не найден SUMO/tools/randomTrips.py. Укажите полный каталог установки SUMO.")
    target.parent.mkdir(parents=True, exist_ok=True)
    period = max(0.1, 3600.0 / max(1, demand_vph))
    command = [
        sys.executable, trips_script, "-n", str(network), "-r", str(target),
        "-e", str(duration_seconds), "--period", f"{period:.6f}", "--seed", str(seed),
        "--validate",
    ]
    _run(command, timeout=300)
    if not target.is_file() or target.stat().st_size == 0:
        raise SumoError("randomTrips.py не создал файл маршрутов.")
    return str(target)


def inspect_traffic_lights(network_file: str) -> list[dict[str, Any]]:
    """Read traffic-light IDs and controlled-link counts from a SUMO net file."""
    path = Path(network_file).expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"Файл сети SUMO не найден: {path}")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise SumoError(f"Некорректный XML сети SUMO: {exc}") from exc
    lights = []
    for node in root.findall("tlLogic"):
        tls_id = node.get("id", "")
        phases = node.findall("phase")
        lights.append({
            "id": tls_id,
            "program_id": node.get("programID", "0"),
            "type": node.get("type", "static"),
            "phase_count": len(phases),
            "cycle_seconds": sum(float(p.get("duration", "0")) for p in phases),
            "states": [p.get("state", "") for p in phases],
        })
    return lights


def run_sumo(
    network_file: str,
    routes_file: str,
    output_dir: str,
    duration_seconds: int,
    seed: int = 42,
    sumo_home: str | None = None,
    use_gui: bool = False,
) -> dict[str, Any]:
    network = Path(network_file).expanduser().resolve()
    routes = Path(routes_file).expanduser().resolve()
    if not network.is_file():
        raise FileNotFoundError(f"Файл сети SUMO не найден: {network}")
    if not routes.is_file():
        raise FileNotFoundError(f"Файл маршрутов SUMO не найден: {routes}")
    if duration_seconds <= 0:
        raise ValueError("Длительность должна быть положительной.")
    tools = discover_sumo(sumo_home)
    executable = tools.get("sumo-gui") if use_gui else tools["sumo"]
    if not executable:
        raise SumoError("В установке SUMO не найден SUMO-GUI.")
    out = Path(output_dir).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    tripinfo = out / "tripinfo.xml"
    summary = out / "summary.xml"
    command = [
        executable, "-n", str(network), "-r", str(routes),
        "--end", str(duration_seconds), "--seed", str(seed),
        "--tripinfo-output", str(tripinfo), "--summary-output", str(summary),
        "--no-step-log", "true", "--duration-log.disable", "true",
    ]
    _run(command, timeout=max(180, duration_seconds * 2))
    arrived = departed = 0
    duration_sum = waiting_sum = time_loss_sum = 0.0
    if tripinfo.is_file():
        for _, element in ET.iterparse(tripinfo, events=("end",)):
            if element.tag == "tripinfo":
                arrived += 1
                duration_sum += float(element.get("duration", 0))
                waiting_sum += float(element.get("waitingTime", 0))
                time_loss_sum += float(element.get("timeLoss", 0))
                element.clear()
    if summary.is_file():
        try:
            last = None
            for _, element in ET.iterparse(summary, events=("end",)):
                if element.tag == "step":
                    last = element.attrib.copy()
            if last:
                departed = int(float(last.get("inserted", arrived)))
        except (ET.ParseError, ValueError):
            departed = arrived
    if departed == 0:
        departed = arrived
    return {
        "engine": "Eclipse SUMO",
        "duration_seconds": duration_seconds,
        "seed": seed,
        "departed": departed,
        "arrived": arrived,
        "average_trip_duration_s": duration_sum / arrived if arrived else 0.0,
        "average_waiting_time_s": waiting_sum / arrived if arrived else 0.0,
        "average_time_loss_s": time_loss_sum / arrived if arrived else 0.0,
        "tripinfo_file": str(tripinfo),
        "summary_file": str(summary),
    }
