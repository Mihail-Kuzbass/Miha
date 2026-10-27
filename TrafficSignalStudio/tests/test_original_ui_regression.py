"""Regression checks for the maintained original-project UI source."""
import ast
from pathlib import Path


def test_main_window_has_single_phase_apply_handler():
    source = Path(__file__).resolve().parents[1] / "app" / "ui" / "main_window.py"
    tree = ast.parse(source.read_text(encoding="utf-8"))
    window = next(
        node for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == "MainWindow"
    )
    handlers = [
        node for node in window.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == "_apply_phases"
    ]
    assert len(handlers) == 1, "A later duplicate method can silently override full phase-plan validation."


def test_phase_editor_reads_all_six_columns():
    source = Path(__file__).resolve().parents[1] / "app" / "ui" / "main_window.py"
    text = source.read_text(encoding="utf-8")
    assert 'self.phase_table = QTableWidget(0, 6)' in text
    assert "self._read_phase_table()" in text


def test_intersection_schema_accepts_valid_v1_and_legacy_files():
    from app.ui.main_window import _validated_lane_counts

    approaches = ["North", "South", "East", "West"]
    assert _validated_lane_counts(
        {"schema_version": 1, "lane_counts": {"North": 3, "South": 1, "East": 2, "West": 4}},
        approaches,
    ) == {"North": 3, "South": 1, "East": 2, "West": 4}
    assert _validated_lane_counts({"lane_counts": {"North": 1}}, approaches) == {
        "North": 1, "South": 2, "East": 2, "West": 2
    }


def test_intersection_schema_rejects_bad_version_and_lane_counts():
    import pytest
    from app.ui.main_window import _validated_lane_counts

    approaches = ["North", "South", "East", "West"]
    for data in (
        {"schema_version": 2, "lane_counts": {}},
        {"schema_version": True, "lane_counts": {}},
        {"lane_counts": {"North": 0}},
        {"lane_counts": {"North": 9}},
        {"lane_counts": {"North": "3"}},
        {"lane_counts": []},
        [],
    ):
        with pytest.raises(ValueError):
            _validated_lane_counts(data, approaches)
