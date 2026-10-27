import pytest

from app.simulation.microsim import MicroSimulationConfig, run_microsimulation


def run(config=None):
    config = config or MicroSimulationConfig(900, 700, 10, 600, seed=42)
    return run_microsimulation(config, [30, 30], [25, 25], ["NS", "EW"])


def test_micro_simulation_is_reproducible_for_same_seed():
    a, b = run(), run()
    assert a.north_south.arrived == b.north_south.arrived
    assert a.east_west.departed == b.east_west.departed
    assert a.north_south.max_queue_vehicles == b.north_south.max_queue_vehicles


def test_zero_demand_produces_no_vehicles_or_queue():
    result = run(MicroSimulationConfig(0, 0, 0, 120))
    assert result.departed_total == 0
    assert result.north_south.arrived == 0
    assert result.east_west.remaining == 0


def test_invalid_phase_assignment_is_rejected():
    with pytest.raises(ValueError, match="длительность, зелёный"):
        run_microsimulation(MicroSimulationConfig(100, 100, 0, 120), [30], [20, 20], ["NS", "EW"])


def test_phase_axes_must_be_explicit_and_supported():
    with pytest.raises(ValueError, match="NS или EW"):
        run_microsimulation(MicroSimulationConfig(100, 100, 0, 120), [30], [20], ["оба"])


def test_green_must_fit_inside_full_phase_duration():
    with pytest.raises(ValueError, match="от 0 до полной"):
        run_microsimulation(MicroSimulationConfig(100, 100, 0, 120), [20], [25], ["NS"])


def test_vehicle_demand_is_respected_and_metrics_are_bounded():
    result = run(MicroSimulationConfig(1200, 600, 20, 300, seed=5))
    assert result.north_south.arrived > result.east_west.arrived
    assert result.north_south.max_queue_vehicles >= 0
    assert result.east_west.mean_speed_kmh >= 0
    assert result.north_south.remaining >= 0

def test_saturation_flow_limits_stop_line_discharges():
    base = dict(north_south_vehicles_per_hour=3000, east_west_vehicles_per_hour=0,
                heavy_vehicle_percent=0, duration_seconds=600, seed=17)
    low = run_microsimulation(MicroSimulationConfig(**base, saturation_flow_vehicles_per_hour=900),
                              [60], [50], ["NS"])
    high = run_microsimulation(MicroSimulationConfig(**base, saturation_flow_vehicles_per_hour=2400),
                               [60], [50], ["NS"])
    assert high.north_south.departed > low.north_south.departed


def test_invalid_seed_and_non_finite_vehicle_parameters_are_rejected():
    with pytest.raises(ValueError, match="от 1 до 999999"):
        MicroSimulationConfig(100, 100, 0, 120, seed=0)
    with pytest.raises(ValueError, match="конечным положительным"):
        MicroSimulationConfig(100, 100, 0, 120, free_speed_kmh=float("nan"))
