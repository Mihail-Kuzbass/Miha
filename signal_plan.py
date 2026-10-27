"""Signal-plan model compatible with the SUMO-first UI and legacy simulation.

Phase positional order is kept compatible with main_window.py:
(name, green, yellow, intergreen, flashing_green, red).
"""
from dataclasses import asdict, dataclass
import json
from pathlib import Path


@dataclass
class Phase:
    name: str
    green_seconds: int
    yellow_seconds: int
    intergreen_seconds: int
    flashing_green_seconds: int = 0
    red_seconds: int = 0


class SignalPlan:
    def __init__(self, phases):
        if len(phases) < 2:
            raise ValueError("Нужно минимум две фазы.")
        self.phases = list(phases)
        self.validate()

    def validate(self):
        if len(self.phases) < 2:
            raise ValueError("Нужно минимум две фазы.")
        for i, phase in enumerate(self.phases, 1):
            if not phase.name or not phase.name.strip():
                raise ValueError(f"Фаза {i}: укажите название.")
            for label, value, minimum in (
                ("зелёный", phase.green_seconds, 5),
                ("жёлтый", phase.yellow_seconds, 1),
                ("межфазный интервал", phase.intergreen_seconds, 0),
                ("мигающий зелёный", phase.flashing_green_seconds, 0),
                ("красный", phase.red_seconds, 0),
            ):
                if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
                    raise ValueError(f"Фаза {i}: время «{label}» должно быть целым числом не менее {minimum} с.")
        if self.cycle_seconds > 300:
            raise ValueError("Длина цикла превышает 300 с; проверьте введённые интервалы.")

    @classmethod
    def default(cls):
        return cls([
            Phase("Север—Юг", 35, 3, 2, 3, 0),
            Phase("Восток—Запад", 30, 3, 2, 3, 0),
        ])

    @property
    def cycle_seconds(self):
        return sum(
            p.green_seconds + p.flashing_green_seconds + p.yellow_seconds
            + p.red_seconds + p.intergreen_seconds
            for p in self.phases
        )

    def to_dict(self):
        return {"schema_version": 2, "phases": [asdict(p) for p in self.phases]}

    def save(self, path):
        Path(path).write_text(
            json.dumps(self.to_dict(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    @classmethod
    def load(cls, path):
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
            if data.get("schema_version") not in (1, 2):
                raise ValueError("Неизвестная версия формата плана.")
            phases = []
            for item in data["phases"]:
                item = dict(item)
                item.setdefault("flashing_green_seconds", 0)
                item.setdefault("red_seconds", 0)
                phases.append(Phase(**item))
            return cls(phases)
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
            raise ValueError(f"Не удалось загрузить план: {exc}") from exc
