#!/usr/bin/env python3
"""Apply reviewed Russian translations to currently empty entries in a SUMO GUI PO file.

Only msgstr values that are empty are changed. Existing translations are preserved.
The source file is never overwritten unless input and output paths are identical
and --in-place is explicitly supplied.
"""
from __future__ import annotations

import argparse
import ast
from pathlib import Path
import re
import sys

# Reviewed starter translations for blank entries in Eclipse SUMO data/po/ru_gui.po.
# Keep source message IDs and format placeholders unchanged.
TRANSLATIONS: dict[str, str] = {
    "Original coordinate (before coordinate transformation in netconvert). Enter 'lat, lon' or 'lat:... lon:...' to center view": "Исходные координаты (до преобразования координат в netconvert). Введите «lat, lon» или «lat:... lon:...», чтобы центрировать вид",
    "Enter 'lat, lon' to center view": "Введите «lat, lon», чтобы центрировать вид",
    "Network coordinate. Enter 'x, y' to center view": "Координаты сети. Введите «x, y», чтобы центрировать вид",
    "Enter 'x, y' to center view": "Введите «x, y», чтобы центрировать вид",
    "number of running vehicles": "количество движущихся транспортных средств",
    "number of insertion backlogged vehicles": "количество транспортных средств в очереди на ввод в сеть",
    "percentage of teleports per departed vehicles": "доля телепортаций относительно выехавших транспортных средств",
    "number of active persons": "количество активных пешеходов",
    "number of active containers": "количество активных контейнеров",
    "'%' loaded.": "Загружено: «%».",
    "lane": "полоса движения",
    "reservations [#]": "бронирования [шт.]",
    "reservation ids": "идентификаторы бронирований",
    "Show polygon symbols": "Показывать обозначения полигонов",
    "Draw filled walkingareas": "Заливать области пешеходного движения",
    "Quit the app if the initial net/config loading fails": "Завершить работу приложения, если не удалось загрузить исходную сеть или конфигурацию",
    "The name of a routingType": "Название типа маршрутизации",
    "Height of polygon in meters": "Высота полигона в метрах",
    "Show polygon symbols.": "Показывать обозначения полигонов.",
    "Draw filled walking areas": "Заливать области пешеходного движения",
    "Draw filled walking areas.": "Заливать области пешеходного движения.",
    "add dest prob reroute to '": "добавить вероятность перенаправления к пункту назначения '",
    "A stop requires only either stoppingPlace, edge or lane": "Для остановки требуется указать только один из параметров: stoppingPlace, edge или lane",
    "Show lane Parameter": "Показывать параметр полосы",
    "Show edge Parameter": "Показывать параметр ребра",
    "Copy test coordinates to clipboard": "Копировать тестовые координаты в буфер обмена",
    "   to inspect next element": "   для проверки следующего элемента",
    "   position to inspect": "   позиция для проверки",
    "   previous element": "   предыдущий элемент",
    "  according to the current 'Modification Mode'.": "  в соответствии с текущим режимом изменения.",
    "     1. Select an object type from the first input box": "     1. Выберите тип объекта в первом поле ввода",
    "     2. Select an attribute from the second input box": "     2. Выберите атрибут во втором поле ввода",
    "     3. Enter a 'match expression' in the third input box and press <return>": "     3. Введите «выражение поиска» в третьем поле и нажмите <return>",
    "     '' (no operator) matches if string is a substring of that object's attribute.": "     '' (без оператора) — совпадение, если строка является частью значения атрибута объекта.",
    "     '=' matches if string is an exact match.": "     '=' — совпадение при точном равенстве строк.",
    "     '!' matches if string is not a substring.": "     '!' — совпадение, если строка не является частью значения.",
    "     '^' matches if string is not an exact match.": "     '^' — совпадение, если строки не равны точно.",
    "     junction; id; 'foo' -> match all junctions that have 'foo' in their id": "     junction; id; 'foo' -> выбрать все узлы, в идентификаторе которых есть 'foo'",
    "     junction; type; '=priority' -> match all junctions of type 'priority', but not of type 'priority_stop'": "     junction; type; '=priority' -> выбрать узлы типа 'priority', но не 'priority_stop'",
    "     edge; speed; '>10' -> match all edges with a speed above 10": "     edge; speed; '>10' -> выбрать все рёбра со скоростью выше 10",
    "Show in inspect mode": "Показывать в режиме проверки",
    "Hide other sources": "Скрыть другие источники",
    "Inspect connections": "Проверять соединения",
    "  Select Sources/Sinks.": "  Выберите источники/приёмники.",
    "Write selection files for created, deleted and changed elements": "Записывать файлы выборки для созданных, удалённых и изменённых элементов",
    "Write shape files for created, deleted and changed elements": "Записывать shape-файлы для созданных, удалённых и изменённых элементов",
    "Write geo coordinates instead of network coordinates": "Записывать географические координаты вместо координат сети",
    "Give more output": "Выводить больше информации",
    "Interpret source and dest as plain-xml prefix instead of network names": "Считать source и dest префиксами plain-xml, а не именами сетей",
    "Compare source and dest files directly": "Сравнивать файлы source и dest напрямую",
    "Generate patch that can be applied during initial network import (exports additional connection elements)": "Создать патч для применения при первоначальном импорте сети (экспортирует дополнительные элементы соединений)",
    "Avoid saving plain xml files of source and destination networks": "Не сохранять plain XML-файлы исходной сети и сети назначения",
    "Comma-separated list of element names to copy (if they are unchanged)": "Разделённый запятыми список имён элементов для копирования (если они не изменились)",
    "Path to binaries": "Путь к исполняемым файлам",
    "Copy selection to clipboard": "Копировать выделение в буфер обмена",
    "Select all text": "Выделить весь текст",
    "Clear message window": "Очистить окно сообщений",
    "parking area": "парковочная зона",
    "parking space": "парковочное место",
    "No projection defined": "Проекция не задана",
    "Centered view on %. Geo coordinates: lat %, lon %": "Вид центрирован на %. Географические координаты: широта %, долгота %",
    "Invalid geo coordinate. Use 'lat, lon' or 'lat:... lon:...'": "Недопустимые географические координаты. Используйте 'lat, lon' или 'lat:... lon:...'",
    "Centered view on x: %, y: %": "Вид центрирован на x: %, y: %",
    "Invalid cartesian coordinate. Use 'x, y' or 'x:... y:...'": "Недопустимые декартовы координаты. Используйте 'x, y' или 'x:... y:...'",
}


def decode_field(block: str, field: str) -> str | None:
    """Decode a PO field, including its continued quoted lines."""
    lines = block.splitlines()
    prefix = field + " "
    active = False
    value = ""
    for line in lines:
        stripped = line.strip()
        if stripped.startswith(prefix):
            active = True
            value = ast.literal_eval(stripped[len(prefix):])
        elif active and stripped.startswith('"'):
            value += ast.literal_eval(stripped)
        elif active:
            break
    return value if active else None


def apply(path: Path, output: Path) -> tuple[int, int]:
    source = path.read_text(encoding="utf-8-sig")
    blocks = re.split(r"(\n[ \t]*\n)", source)
    applied = 0
    found = set()
    for index in range(0, len(blocks), 2):
        block = blocks[index]
        msgid = decode_field(block, "msgid")
        if not msgid or msgid not in TRANSLATIONS:
            continue
        found.add(msgid)
        msgstr = decode_field(block, "msgstr")
        if msgstr is not None and msgstr.strip():
            continue
        translated = TRANSLATIONS[msgid]
        encoded = repr(translated)
        # PO uses C-style double-quoted strings, not Python single-quoted repr.
        import json
        encoded = json.dumps(translated, ensure_ascii=False)
        updated, count = re.subn(r'(?m)^(\s*)msgstr ""\s*$', lambda m: m.group(1) + "msgstr " + encoded, block, count=1)
        if count:
            blocks[index] = updated
            applied += 1
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(blocks), encoding="utf-8")
    return applied, len(set(TRANSLATIONS) - found)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="upstream ru_gui.po")
    parser.add_argument("output", type=Path, help="destination PO file")
    parser.add_argument("--in-place", action="store_true", help="allow input and output to be the same file")
    args = parser.parse_args()
    if not args.input.is_file():
        print(f"ERROR: input file not found: {args.input}", file=sys.stderr)
        return 2
    if args.input.resolve() == args.output.resolve() and not args.in_place:
        print("ERROR: refusing to overwrite input; pass --in-place explicitly", file=sys.stderr)
        return 2
    try:
        applied, not_found = apply(args.input, args.output)
    except (UnicodeError, SyntaxError, ValueError) as exc:
        print(f"ERROR: could not process PO file: {exc}", file=sys.stderr)
        return 2
    print(f"Applied {applied} translations; {not_found} source IDs were not found (may indicate upstream version drift).")
    print(f"Output: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
