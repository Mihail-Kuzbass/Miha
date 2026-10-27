"""Deterministic, individual-vehicle intersection microsimulation.

Vehicles are represented separately and move along an approach with acceleration,
braking, car-following gaps and signal stop-line constraints. This first integrated
model intentionally represents two conflicting approach groups (N-S and E-W), not
an arbitrary multi-lane network. It is a development model, not a calibrated or
standards-certified traffic model.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import exp, isfinite
import random
from typing import Sequence


@dataclass(frozen=True)
class MicroSimulationConfig:
    north_south_vehicles_per_hour: int
    east_west_vehicles_per_hour: int
    heavy_vehicle_percent: float
    duration_seconds: int
    saturation_flow_vehicles_per_hour: int = 1800
    seed: int = 42
    free_speed_kmh: float = 50.0
    acceleration_mps2: float = 1.4
    comfortable_deceleration_mps2: float = 2.5
    vehicle_gap_m: float = 7.0
    approach_length_m: float = 180.0
    time_step_seconds: float = 0.2

    def __post_init__(self) -> None:
        for name in ("north_south_vehicles_per_hour", "east_west_vehicles_per_hour"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > 5000:
                raise ValueError(f"{name}: интенсивность должна быть целым числом от 0 до 5000.")
        if isinstance(self.heavy_vehicle_percent, bool) or not isinstance(self.heavy_vehicle_percent, (int, float)) or not isfinite(self.heavy_vehicle_percent) or not 0 <= self.heavy_vehicle_percent <= 100:
            raise ValueError("Доля тяжёлых транспортных средств должна быть конечным числом от 0 до 100%.")
        if isinstance(self.duration_seconds, bool) or not isinstance(self.duration_seconds, int) or not 60 <= self.duration_seconds <= 7200:
            raise ValueError("Длительность моделирования должна быть целым числом от 60 до 7200 секунд.")
        if not 900 <= self.saturation_flow_vehicles_per_hour <= 2400:
            raise ValueError("Насыщенная интенсивность должна быть от 900 до 2400 авт./ч.")
        if isinstance(self.seed, bool) or not isinstance(self.seed, int) or not 1 <= self.seed <= 999999:
            raise ValueError("Seed должен быть целым числом от 1 до 999999.")
        for name in ("free_speed_kmh", "acceleration_mps2", "comfortable_deceleration_mps2",
                     "vehicle_gap_m", "approach_length_m", "time_step_seconds"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not isfinite(value) or value <= 0:
                raise ValueError(f"Параметр {name} должен быть конечным положительным числом.")
        if self.vehicle_gap_m < 2 or self.approach_length_m < 30:
            raise ValueError("Дистанция между ТС должна быть не менее 2 м, длина подхода — не менее 30 м.")


@dataclass
class Vehicle:
    vehicle_id: int
    axis: str
    position_m: float
    speed_mps: float
    arrival_time_s: float
    heavy: bool
    stop_delay_s: float = 0.0
    max_speed_mps: float = 0.0


@dataclass(frozen=True)
class AxisMetrics:
    axis: str
    arrived: int
    departed: int
    remaining: int
    mean_travel_time_s: float
    mean_stopped_delay_s: float
    mean_speed_kmh: float
    max_queue_vehicles: int
    mean_queue_vehicles: float
    demand_vehicles_per_hour: float


@dataclass(frozen=True)
class MicroSimulationResult:
    duration_seconds: int
    cycle_seconds: int
    seed: int
    north_south: AxisMetrics
    east_west: AxisMetrics
    notes: tuple[str, ...]

    @property
    def departed_total(self) -> int:
        return self.north_south.departed + self.east_west.departed

    def to_report(self) -> str:
        def block(m: AxisMetrics) -> str:
            return (
                f"{m.axis}:\n"
                f"  Прибыло: {m.arrived} авт.\n"
                f"  Проехало стоп-линию: {m.departed} авт.\n"
                f"  Осталось в системе: {m.remaining} авт.\n"
                f"  Среднее время поездки на участке: {m.mean_travel_time_s:.1f} с\n"
                f"  Средняя остановочная задержка: {m.mean_stopped_delay_s:.1f} с\n"
                f"  Средняя скорость на участке: {m.mean_speed_kmh:.1f} км/ч\n"
                f"  Максимальная очередь: {m.max_queue_vehicles} авт.\n"
                f"  Средняя очередь: {m.mean_queue_vehicles:.2f} авт.\n"
                f"  Интенсивность спроса: {m.demand_vehicles_per_hour:.0f} авт./ч"
            )
        return (
            "МИКРОСИМУЛЯЦИЯ ДВИЖЕНИЯ ОТДЕЛЬНЫХ ТРАНСПОРТНЫХ СРЕДСТВ\n"
            f"Время моделирования: {self.duration_seconds} с | Цикл: {self.cycle_seconds} с | Seed: {self.seed}\n"
            f"Всего пересекло стоп-линию: {self.departed_total} авт.\n\n"
            f"{block(self.north_south)}\n\n{block(self.east_west)}\n\n"
            "ОГРАНИЧЕНИЯ МОДЕЛИ\n" + "\n".join(f"• {note}" for note in self.notes)
        )


def _axis_metrics(axis: str, arrived: int, departed: int, remaining: Sequence[Vehicle],
                  travel_times: list[float], stopped_delays: list[float], speeds: list[float],
                  queue_samples: list[int], demand: int) -> AxisMetrics:
    def mean(values):
        return sum(values) / len(values) if values else 0.0
    return AxisMetrics(
        axis=axis, arrived=arrived, departed=departed, remaining=len(remaining),
        mean_travel_time_s=mean(travel_times),
        mean_stopped_delay_s=mean(stopped_delays),
        mean_speed_kmh=mean(speeds) * 3.6,
        max_queue_vehicles=max(queue_samples, default=0),
        mean_queue_vehicles=mean(queue_samples),
        demand_vehicles_per_hour=demand,
    )


def run_microsimulation(config: MicroSimulationConfig, phase_durations: Sequence[int],
                        phase_green_seconds: Sequence[int], phase_axes: Sequence[str]) -> MicroSimulationResult:
    """Run reproducible individual-vehicle simulation for two conflicting axes.

    phase_durations are full phase durations (green, yellow, red and intergreen);
    phase_green_seconds is the effective permissive interval in each phase.
    """
    if not phase_durations or not (len(phase_durations) == len(phase_axes) == len(phase_green_seconds)):
        raise ValueError("Для каждой фазы необходимо указать длительность, зелёный и направление.")
    if any(isinstance(d, bool) or not isinstance(d, int) or d <= 0 for d in phase_durations):
        raise ValueError("Длительности фаз должны быть положительными целыми числами.")
    if any(isinstance(g, bool) or not isinstance(g, int) or g < 0 or g > d
           for d, g in zip(phase_durations, phase_green_seconds)):
        raise ValueError("Зелёный интервал должен быть от 0 до полной длительности фазы.")
    if any(axis not in ("NS", "EW") for axis in phase_axes):
        raise ValueError("Разрешённое направление фазы должно быть NS или EW.")
    cycle = sum(phase_durations)
    rng = random.Random(config.seed)
    dt = config.time_step_seconds
    free_speed = config.free_speed_kmh / 3.6
    axes = {
        "NS": {"label": "Север—Юг", "demand": config.north_south_vehicles_per_hour,
               "vehicles": [], "arrived": 0, "departed": 0, "travel": [], "delay": [],
               "speeds": [], "queues": [], "next_id": 1, "last_crossing_time": -3600.0},
        "EW": {"label": "Восток—Запад", "demand": config.east_west_vehicles_per_hour,
               "vehicles": [], "arrived": 0, "departed": 0, "travel": [], "delay": [],
               "speeds": [], "queues": [], "next_id": 1, "last_crossing_time": -3600.0},
    }
    phase_ends = []
    total = 0
    for duration in phase_durations:
        total += duration
        phase_ends.append(total)
    t = 0.0
    while t < config.duration_seconds:
        cycle_t = t % cycle
        phase_idx = next((i for i, end in enumerate(phase_ends) if cycle_t < end), len(phase_ends) - 1)
        phase_start = 0 if phase_idx == 0 else phase_ends[phase_idx - 1]
        phase_elapsed = cycle_t - phase_start
        active_axis = phase_axes[phase_idx] if phase_elapsed < phase_green_seconds[phase_idx] else None
        for axis, state in axes.items():
            rate = state["demand"] / 3600.0
            if rate and rng.random() < 1.0 - exp(-rate * dt):
                heavy = rng.random() < config.heavy_vehicle_percent / 100.0
                vmax = free_speed * (0.72 if heavy else 1.0)
                state["vehicles"].append(Vehicle(
                    vehicle_id=state["next_id"], axis=axis,
                    position_m=-config.approach_length_m, speed_mps=0.0,
                    arrival_time_s=t, heavy=heavy, max_speed_mps=vmax,
                ))
                state["next_id"] += 1
                state["arrived"] += 1
            vehicles: list[Vehicle] = state["vehicles"]
            vehicles.sort(key=lambda v: v.position_m, reverse=True)
            departed_now = []
            leader_position = None
            for vehicle in vehicles:
                permitted = active_axis == axis and (t - state["last_crossing_time"] >= 3600.0 / config.saturation_flow_vehicles_per_hour)
                if vehicle.position_m >= 0:
                    target_speed = vehicle.max_speed_mps
                else:
                    distance_to_line = -vehicle.position_m
                    target_speed = vehicle.max_speed_mps
                    stopping_distance = (vehicle.speed_mps ** 2) / (2 * config.comfortable_deceleration_mps2)
                    if not permitted and stopping_distance >= max(0.0, distance_to_line - 0.5):
                        target_speed = 0.0
                if leader_position is not None:
                    gap = leader_position - vehicle.position_m - config.vehicle_gap_m
                    if gap < 0:
                        target_speed = 0.0
                    elif gap < 18.0:
                        target_speed = min(target_speed, max(0.0, gap / 18.0 * vehicle.max_speed_mps))
                if target_speed > vehicle.speed_mps:
                    vehicle.speed_mps = min(target_speed, vehicle.speed_mps + config.acceleration_mps2 * dt)
                else:
                    vehicle.speed_mps = max(target_speed, vehicle.speed_mps - config.comfortable_deceleration_mps2 * dt)
                new_position = vehicle.position_m + vehicle.speed_mps * dt
                if vehicle.position_m < 0 <= new_position and not permitted:
                    new_position = -0.05
                    vehicle.speed_mps = 0.0
                elif vehicle.position_m < 0 <= new_position and permitted:
                    state["last_crossing_time"] = t
                vehicle.position_m = new_position
                if vehicle.speed_mps < 0.5 and vehicle.position_m < 0:
                    vehicle.stop_delay_s += dt
                if vehicle.position_m > 15.0:
                    departed_now.append(vehicle)
                    state["departed"] += 1
                    state["travel"].append(t - vehicle.arrival_time_s)
                    state["delay"].append(vehicle.stop_delay_s)
                    state["speeds"].append(config.approach_length_m / max(dt, t - vehicle.arrival_time_s))
                leader_position = vehicle.position_m
            if departed_now:
                departed_ids = {v.vehicle_id for v in departed_now}
                state["vehicles"] = [v for v in vehicles if v.vehicle_id not in departed_ids]
            state["queues"].append(sum(1 for v in state["vehicles"] if v.position_m < 0 and v.speed_mps < 0.5))
        t += dt
    ns, ew = axes["NS"], axes["EW"]
    notes = (
        "Две конфликтующие группы подходов; полосы, отдельные траектории поворотов и перестроения пока не представлены.",
        "Разрешение движения задаётся назначением оси каждой фазе; конфликтность сигналов не проверяется автоматически.",
        "Параметры движения не откалиброваны по полевым наблюдениям; результаты предназначены для разработки и сравнительного анализа.",
        "Спрос задаётся суммарно по оси, без распределения по отдельным полосам и направлениям поворота.",
    )
    return MicroSimulationResult(
        duration_seconds=config.duration_seconds, cycle_seconds=cycle, seed=config.seed,
        north_south=_axis_metrics(ns["label"], ns["arrived"], ns["departed"], ns["vehicles"],
                                  ns["travel"], ns["delay"], ns["speeds"], ns["queues"], ns["demand"]),
        east_west=_axis_metrics(ew["label"], ew["arrived"], ew["departed"], ew["vehicles"],
                                ew["travel"], ew["delay"], ew["speeds"], ew["queues"], ew["demand"]),
        notes=notes,
    )
