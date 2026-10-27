import pytest

from app.simulation.lane_routes import Lane, LaneConnection, LaneRouteGraph, Point


def lane(lane_id, approach, index=0, movements=("straight", "left", "right")):
    return Lane(lane_id, f"road-{approach}", approach, index, frozenset(movements))


def connection(source, target, movement="straight", points=((0, 0), (10, 0))):
    return LaneConnection(source, target, movement, tuple(Point(x, y) for x, y in points))


def test_graph_finds_route_through_explicit_lane_connections():
    graph = LaneRouteGraph([lane("north-in", "north"), lane("middle", "junction"), lane("east-out", "east")])
    graph.connect(connection("north-in", "middle", "left", ((0, 0), (3, 2), (5, 5))))
    graph.connect(connection("middle", "east-out", "straight", ((5, 5), (10, 8))))
    route = graph.route("north-in", "east-out")
    assert route.lane_ids == ("north-in", "middle", "east-out")
    assert len(route.connections) == 2
    assert route.length > 0
    assert graph.validate() == []


def test_route_rejects_missing_lane_and_disconnected_network():
    graph = LaneRouteGraph([lane("a", "north"), lane("b", "south")])
    with pytest.raises(ValueError, match="должны существовать"):
        graph.route("missing", "b")
    with pytest.raises(ValueError, match="Не найден маршрут"):
        graph.route("a", "b")


def test_turn_must_be_allowed_on_source_lane():
    graph = LaneRouteGraph([
        lane("in", "north", movements=("straight",)),
        lane("out", "east"),
    ])
    with pytest.raises(ValueError, match="запрещён"):
        graph.connect(connection("in", "out", "left"))


def test_duplicate_lanes_and_connections_are_rejected():
    graph = LaneRouteGraph([lane("a", "north"), lane("b", "south")])
    with pytest.raises(ValueError, match="уже существует"):
        graph.add_lane(lane("a", "north"))
    edge = connection("a", "b")
    graph.connect(edge)
    with pytest.raises(ValueError, match="уже существует"):
        graph.connect(edge)


def test_invalid_geometry_and_non_finite_coordinates_are_rejected():
    with pytest.raises(ValueError, match="минимум две точки"):
        LaneConnection("a", "b", "straight", (Point(0, 0),))
    with pytest.raises(ValueError, match="конечными"):
        Point(float("inf"), 0)


def test_shortest_route_is_deterministic():
    graph = LaneRouteGraph([lane(x, x) for x in ("a", "b", "c", "d")])
    graph.connect(connection("a", "c"))
    graph.connect(connection("a", "b"))
    graph.connect(connection("b", "d"))
    graph.connect(connection("c", "d"))
    assert graph.route("a", "d").lane_ids == ("a", "b", "d")


def test_graph_serialization_round_trip_is_stable():
    graph = LaneRouteGraph([lane("a", "north", movements=("straight", "left")), lane("b", "east")])
    graph.connect(connection("a", "b", "left", ((0, 0), (2, 3), (5, 5))))
    encoded = graph.to_dict()
    restored = LaneRouteGraph.from_dict(encoded)
    assert restored.to_dict() == encoded
    assert restored.route("a", "b").length == pytest.approx(graph.route("a", "b").length)


def test_graph_loader_rejects_unknown_schema_and_missing_lane_reference():
    with pytest.raises(ValueError, match="Неподдерживаемая версия"):
        LaneRouteGraph.from_dict({"schema_version": 2, "lanes": [], "connections": []})
    malformed = {
        "schema_version": 1,
        "lanes": [{"lane_id": "a", "road_id": "r", "approach": "north", "index": 0}],
        "connections": [{
            "from_lane": "a", "to_lane": "missing", "movement": "straight",
            "geometry": [[0, 0], [1, 1]],
        }],
    }
    with pytest.raises(ValueError, match="не найдена"):
        LaneRouteGraph.from_dict(malformed)
