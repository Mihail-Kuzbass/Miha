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
