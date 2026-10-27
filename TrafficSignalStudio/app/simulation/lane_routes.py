"""Lane-level road routing primitives for TrafficSignalStudio.

This module is deliberately independent of the UI and simulation clock. It models
explicit lane-to-lane connections and validated route geometry; it does not infer
legal turns from a schematic or claim normative compliance.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from math import hypot
from typing import Iterable

MOVEMENTS = frozenset({"straight", "left", "right", "u_turn"})


@dataclass(frozen=True)
class Point:
    x: float
    y: float

    def __post_init__(self) -> None:
        if not (self.x == self.x and self.y == self.y):
            raise ValueError("Координаты должны быть конечными числами.")
        if abs(self.x) == float("inf") or abs(self.y) == float("inf"):
            raise ValueError("Координаты должны быть конечными числами.")


@dataclass(frozen=True)
class Lane:
    lane_id: str
    road_id: str
    approach: str
    index: int
    allowed_movements: frozenset[str] = field(
        default_factory=lambda: frozenset({"straight", "left", "right"})
    )

    def __post_init__(self) -> None:
        if not self.lane_id.strip() or not self.road_id.strip() or not self.approach.strip():
            raise ValueError("У полосы должны быть заданы ID дороги, ID полосы и подход.")
        if isinstance(self.index, bool) or not isinstance(self.index, int) or self.index < 0:
            raise ValueError("Индекс полосы должен быть неотрицательным целым числом.")
        unknown = set(self.allowed_movements) - MOVEMENTS
        if unknown:
            raise ValueError(f"Неизвестные манёвры: {', '.join(sorted(unknown))}.")


@dataclass(frozen=True)
class LaneConnection:
    from_lane: str
    to_lane: str
    movement: str
    geometry: tuple[Point, ...]

    def __post_init__(self) -> None:
        if self.movement not in MOVEMENTS:
            raise ValueError(f"Неизвестный манёвр: {self.movement}.")
        if self.from_lane == self.to_lane:
            raise ValueError("Соединение полосы с самой собой не допускается.")
        if len(self.geometry) < 2:
            raise ValueError("Траектория соединения должна содержать минимум две точки.")


@dataclass(frozen=True)
class Route:
    lane_ids: tuple[str, ...]
    connections: tuple[LaneConnection, ...]

    @property
    def length(self) -> float:
        """Length of route geometry, in the coordinate system's units."""
        return sum(
            hypot(b.x - a.x, b.y - a.y)
            for connection in self.connections
            for a, b in zip(connection.geometry, connection.geometry[1:])
        )


class LaneRouteGraph:
    """Directed lane graph with explicit, non-inferred turning trajectories."""

    def __init__(self, lanes: Iterable[Lane] = ()) -> None:
        self._lanes: dict[str, Lane] = {}
        self._outgoing: dict[str, list[LaneConnection]] = {}
        for lane in lanes:
            self.add_lane(lane)

    @property
    def lanes(self) -> dict[str, Lane]:
        return dict(self._lanes)

    @property
    def connections(self) -> tuple[LaneConnection, ...]:
        return tuple(edge for edges in self._outgoing.values() for edge in edges)

    def add_lane(self, lane: Lane) -> None:
        if lane.lane_id in self._lanes:
            raise ValueError(f"Полоса с ID «{lane.lane_id}» уже существует.")
        self._lanes[lane.lane_id] = lane
        self._outgoing[lane.lane_id] = []

    def connect(self, connection: LaneConnection) -> None:
        source = self._lanes.get(connection.from_lane)
        target = self._lanes.get(connection.to_lane)
        if source is None or target is None:
            missing = connection.from_lane if source is None else connection.to_lane
            raise ValueError(f"Полоса «{missing}» не найдена в дорожной сети.")
        if connection.movement not in source.allowed_movements:
            raise ValueError(
                f"Манёвр «{connection.movement}» запрещён для полосы «{source.lane_id}»."
            )
        if any(
            edge.to_lane == connection.to_lane and edge.movement == connection.movement
            for edge in self._outgoing[connection.from_lane]
        ):
            raise ValueError("Такое соединение полос уже существует.")
        self._outgoing[connection.from_lane].append(connection)

    def route(self, start_lane: str, end_lane: str) -> Route:
        """Return a deterministic route with the fewest lane connections."""
        if start_lane not in self._lanes or end_lane not in self._lanes:
            raise ValueError("Начальная и конечная полосы должны существовать.")
        if start_lane == end_lane:
            return Route((start_lane,), ())
        queue = deque([start_lane])
        previous: dict[str, tuple[str, LaneConnection] | None] = {start_lane: None}
        while queue:
            current = queue.popleft()
            for edge in sorted(self._outgoing[current], key=lambda item: (item.to_lane, item.movement)):
                if edge.to_lane in previous:
                    continue
                previous[edge.to_lane] = (current, edge)
                if edge.to_lane == end_lane:
                    queue.clear()
                    break
                queue.append(edge.to_lane)
        if end_lane not in previous:
            raise ValueError(f"Не найден маршрут от полосы «{start_lane}» к «{end_lane}».")
        lane_ids = [end_lane]
        connections: list[LaneConnection] = []
        cursor = end_lane
        while cursor != start_lane:
            prior = previous[cursor]
            assert prior is not None
            parent, edge = prior
            connections.append(edge)
            lane_ids.append(parent)
            cursor = parent
        lane_ids.reverse()
        connections.reverse()
        return Route(tuple(lane_ids), tuple(connections))

    def to_dict(self) -> dict:
        """Serialize the graph in a stable, versioned JSON-compatible format."""
        return {
            "schema_version": 1,
            "lanes": [
                {
                    "lane_id": lane.lane_id,
                    "road_id": lane.road_id,
                    "approach": lane.approach,
                    "index": lane.index,
                    "allowed_movements": sorted(lane.allowed_movements),
                }
                for lane in self._lanes.values()
            ],
            "connections": [
                {
                    "from_lane": edge.from_lane,
                    "to_lane": edge.to_lane,
                    "movement": edge.movement,
                    "geometry": [[point.x, point.y] for point in edge.geometry],
                }
                for edge in sorted(
                    self.connections,
                    key=lambda item: (item.from_lane, item.to_lane, item.movement),
                )
            ],
        }

    @classmethod
    def from_dict(cls, data: dict) -> "LaneRouteGraph":
        """Load a graph without mutating any existing project or editor state."""
        if not isinstance(data, dict):
            raise ValueError("Дорожный граф должен быть JSON-объектом.")
        version = data.get("schema_version")
        if isinstance(version, bool) or version != 1:
            raise ValueError(f"Неподдерживаемая версия графа полос: {version!r}.")
        lane_data = data.get("lanes")
        connection_data = data.get("connections")
        if not isinstance(lane_data, list) or not isinstance(connection_data, list):
            raise ValueError("Граф должен содержать списки lanes и connections.")
        lanes = []
        for index, item in enumerate(lane_data, 1):
            if not isinstance(item, dict):
                raise ValueError(f"Описание полосы {index} должно быть объектом.")
            movements = item.get("allowed_movements", ["straight", "left", "right"])
            if not isinstance(movements, list) or any(not isinstance(value, str) for value in movements):
                raise ValueError(f"Список манёвров полосы {index} имеет неверный формат.")
            try:
                lanes.append(Lane(
                    lane_id=item["lane_id"],
                    road_id=item["road_id"],
                    approach=item["approach"],
                    index=item["index"],
                    allowed_movements=frozenset(movements),
                ))
            except (KeyError, TypeError) as exc:
                raise ValueError(f"Некорректные данные полосы {index}: {exc}.") from exc
        graph = cls(lanes)
        for index, item in enumerate(connection_data, 1):
            if not isinstance(item, dict):
                raise ValueError(f"Описание соединения {index} должно быть объектом.")
            geometry_data = item.get("geometry")
            if not isinstance(geometry_data, list):
                raise ValueError(f"Траектория соединения {index} должна быть списком точек.")
            points = []
            for point_index, pair in enumerate(geometry_data, 1):
                if not isinstance(pair, (list, tuple)) or len(pair) != 2:
                    raise ValueError(
                        f"Точка {point_index} соединения {index} должна содержать координаты X и Y."
                    )
                if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in pair):
                    raise ValueError(f"Координаты точки {point_index} должны быть числами.")
                points.append(Point(float(pair[0]), float(pair[1])))
            try:
                graph.connect(LaneConnection(
                    from_lane=item["from_lane"],
                    to_lane=item["to_lane"],
                    movement=item["movement"],
                    geometry=tuple(points),
                ))
            except (KeyError, TypeError) as exc:
                raise ValueError(f"Некорректные данные соединения {index}: {exc}.") from exc
        errors = graph.validate()
        if errors:
            raise ValueError("Ошибки структуры дорожного графа: " + "; ".join(errors))
        return graph

    def validate(self) -> list[str]:
        """Return structural errors without changing the graph."""
        errors: list[str] = []
        for source_id, edges in self._outgoing.items():
            for edge in edges:
                if edge.from_lane != source_id:
                    errors.append(f"Соединение «{edge.from_lane}» хранится у другой полосы.")
                if edge.to_lane not in self._lanes:
                    errors.append(f"Соединение ведёт к отсутствующей полосе «{edge.to_lane}».")
                if edge.movement not in self._lanes[source_id].allowed_movements:
                    errors.append(f"Для полосы «{source_id}» не разрешён манёвр «{edge.movement}».")
        return errors
