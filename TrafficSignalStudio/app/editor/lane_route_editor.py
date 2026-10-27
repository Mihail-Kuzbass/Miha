"""Manual lane and explicit lane-to-lane trajectory editor."""
from __future__ import annotations

import json

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QTableWidget,
    QTableWidgetItem, QHeaderView, QFileDialog, QMessageBox, QGroupBox, QAbstractItemView,
)
from app.simulation.lane_routes import Lane, LaneConnection, LaneRouteGraph, Point


class LaneRouteEditor(QWidget):
    """Edit lane entities and explicit turning trajectories without inferring turns."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.graph = LaneRouteGraph()
        root = QVBoxLayout(self)
        intro = QLabel(
            "Задайте полосы как отдельные объекты, затем соединения «полоса → полоса» "
            "с явной траекторией. Манёвры не выводятся автоматически из схемы. "
            "Перед инженерным применением сверяйте разрешения с разметкой, знаками и сигналами."
        )
        intro.setWordWrap(True)
        root.addWidget(intro)

        lane_box = QGroupBox("Полосы")
        lane_layout = QVBoxLayout(lane_box)
        self.lanes = QTableWidget(0, 5)
        self.lanes.setHorizontalHeaderLabels(
            ["ID полосы", "ID дороги", "Подход", "Индекс", "Разрешённые манёвры"]
        )
        self.lanes.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        lane_layout.addWidget(self.lanes)
        lane_buttons = QHBoxLayout()
        add_lane = QPushButton("Добавить полосу")
        add_lane.clicked.connect(self.add_lane_row)
        lane_buttons.addWidget(add_lane)
        remove_lane = QPushButton("Удалить полосу")
        remove_lane.clicked.connect(lambda: self._remove_selected(self.lanes))
        lane_buttons.addWidget(remove_lane)
        lane_layout.addLayout(lane_buttons)
        root.addWidget(lane_box, 2)

        connection_box = QGroupBox("Соединения и траектории")
        connection_layout = QVBoxLayout(connection_box)
        self.connections = QTableWidget(0, 4)
        self.connections.setHorizontalHeaderLabels(
            ["Из полосы", "В полосу", "Манёвр", "Точки траектории X,Y"]
        )
        self.connections.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        connection_layout.addWidget(self.connections)
        connection_buttons = QHBoxLayout()
        add_connection = QPushButton("Добавить соединение")
        add_connection.clicked.connect(self.add_connection_row)
        connection_buttons.addWidget(add_connection)
        remove_connection = QPushButton("Удалить соединение")
        remove_connection.clicked.connect(lambda: self._remove_selected(self.connections))
        connection_buttons.addWidget(remove_connection)
        connection_layout.addLayout(connection_buttons)
        root.addWidget(connection_box, 2)

        actions = QHBoxLayout()
        validate = QPushButton("Проверить граф")
        validate.clicked.connect(self.validate_graph)
        actions.addWidget(validate)
        save = QPushButton("Сохранить граф…")
        save.clicked.connect(self.save_graph)
        actions.addWidget(save)
        load = QPushButton("Загрузить граф…")
        load.clicked.connect(self.load_graph)
        actions.addWidget(load)
        root.addLayout(actions)
        self.status = QLabel("Создайте полосы и задайте явные соединения.")
        self.status.setWordWrap(True)
        root.addWidget(self.status)

    @staticmethod
    def _cell(table: QTableWidget, row: int, column: int) -> str:
        item = table.item(row, column)
        if item is None:
            raise ValueError(f"Заполните ячейку {row + 1}, столбец {column + 1}.")
        return item.text().strip()

    @staticmethod
    def _remove_selected(table: QTableWidget) -> None:
        rows = sorted({index.row() for index in table.selectionModel().selectedRows()}, reverse=True)
        for row in rows:
            table.removeRow(row)

    def add_lane_row(self) -> None:
        row = self.lanes.rowCount()
        self.lanes.insertRow(row)
        values = [f"lane-{row + 1}", f"road-{row + 1}", "north", "0", "straight,left,right"]
        for column, value in enumerate(values):
            self.lanes.setItem(row, column, QTableWidgetItem(value))

    def add_connection_row(self) -> None:
        row = self.connections.rowCount()
        self.connections.insertRow(row)
        values = ["lane-1", "lane-2", "straight", "0,0; 5,5; 10,10"]
        for column, value in enumerate(values):
            self.connections.setItem(row, column, QTableWidgetItem(value))

    @staticmethod
    def _parse_geometry(text: str) -> tuple[Point, ...]:
        points = []
        for part in text.split(";"):
            pair = part.strip().split(",")
            if len(pair) != 2:
                raise ValueError("Траектория задаётся как «x,y; x,y; …».")
            try:
                points.append(Point(float(pair[0].strip()), float(pair[1].strip())))
            except ValueError as exc:
                raise ValueError("Координаты траектории должны быть числами.") from exc
        if len(points) < 2:
            raise ValueError("Траектория должна содержать минимум две точки.")
        return tuple(points)

    def _graph_from_tables(self) -> LaneRouteGraph:
        graph = LaneRouteGraph()
        for row in range(self.lanes.rowCount()):
            try:
                lane_id = self._cell(self.lanes, row, 0)
                road_id = self._cell(self.lanes, row, 1)
                approach = self._cell(self.lanes, row, 2)
                index = int(self._cell(self.lanes, row, 3))
                movements_text = self._cell(self.lanes, row, 4)
                movements = frozenset(value.strip() for value in movements_text.split(",") if value.strip())
                graph.add_lane(Lane(lane_id, road_id, approach, index, movements))
            except (ValueError, TypeError) as exc:
                raise ValueError(f"Полоса {row + 1}: {exc}") from exc
        for row in range(self.connections.rowCount()):
            try:
                edge = LaneConnection(
                    self._cell(self.connections, row, 0),
                    self._cell(self.connections, row, 1),
                    self._cell(self.connections, row, 2),
                    self._parse_geometry(self._cell(self.connections, row, 3)),
                )
                graph.connect(edge)
            except (ValueError, TypeError) as exc:
                raise ValueError(f"Соединение {row + 1}: {exc}") from exc
        errors = graph.validate()
        if errors:
            raise ValueError("\n".join(errors))
        return graph

    def validate_graph(self) -> bool:
        try:
            graph = self._graph_from_tables()
            self.graph = graph
            self.status.setText(
                f"Граф корректен: полос — {len(graph.lanes)}, соединений — {len(graph.connections)}. "
                "Это структурная проверка, не подтверждение соответствия ПДД/ГОСТ."
            )
            return True
        except ValueError as exc:
            self.status.setText(f"Ошибка графа: {exc}")
            QMessageBox.warning(self, "Ошибка дорожного графа", str(exc))
            return False

    def save_graph(self) -> None:
        if not self.validate_graph():
            return
        path, _ = QFileDialog.getSaveFileName(self, "Сохранить граф полос", "lane_graph.json", "JSON (*.json)")
        if not path:
            return
        if not path.lower().endswith(".json"):
            path += ".json"
        try:
            with open(path, "w", encoding="utf-8") as stream:
                json.dump(self.graph.to_dict(), stream, ensure_ascii=False, indent=2)
            self.status.setText(f"Граф сохранён: {path}")
        except OSError as exc:
            QMessageBox.critical(self, "Ошибка сохранения", str(exc))

    def load_graph(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Загрузить граф полос", "", "JSON (*.json)")
        if not path:
            return
        try:
            with open(path, "r", encoding="utf-8") as stream:
                loaded = LaneRouteGraph.from_dict(json.load(stream))
            # Populate the UI only after the entire file has passed validation.
            self.lanes.setRowCount(0)
            for lane in loaded.lanes.values():
                row = self.lanes.rowCount()
                self.lanes.insertRow(row)
                values = [
                    lane.lane_id, lane.road_id, lane.approach, str(lane.index),
                    ",".join(sorted(lane.allowed_movements)),
                ]
                for column, value in enumerate(values):
                    self.lanes.setItem(row, column, QTableWidgetItem(value))
            self.connections.setRowCount(0)
            for edge in loaded.connections:
                row = self.connections.rowCount()
                self.connections.insertRow(row)
                geometry = "; ".join(f"{point.x:g},{point.y:g}" for point in edge.geometry)
                values = [edge.from_lane, edge.to_lane, edge.movement, geometry]
                for column, value in enumerate(values):
                    self.connections.setItem(row, column, QTableWidgetItem(value))
            self.graph = loaded
            self.status.setText(f"Загружен граф: полос — {len(loaded.lanes)}, соединений — {len(loaded.connections)}.")
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            QMessageBox.warning(self, "Ошибка загрузки графа", str(exc))
