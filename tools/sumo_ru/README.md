# SUMO Russian-language starter kit

This folder adds a safe, separate workflow around upstream Eclipse SUMO. It does not replace SUMO's source code and does not pretend to redesign the native FOX GUI.

## Launch SUMO GUI in Russian

1. Install the official Windows 64-bit SUMO package: https://eclipse.dev/sumo/docs/Downloads.html
2. Either add SUMO's `bin` directory to `PATH`, or set `SUMO_HOME` to the SUMO installation/source directory.
3. Run `launch_sumo_ru.bat`. You can pass normal SUMO GUI arguments to it.
4. The launcher passes `--language ru` explicitly, so it does not depend on the Windows system locale.

The visible Russian text depends on the translations included in the installed build. An untranslated string in upstream SUMO cannot be fixed by a launcher alone.

## Audit translation coverage

Use Python 3.10 or newer; no third-party packages are needed.

```powershell
python tools/sumo_ru/audit_po.py path\to\ru_gui.po path\to\ru_tools.po --report build\sumo-ru-missing.md
python -m unittest discover -s tools/sumo_ru -p "test_*.py" -v
```

The audit counts an entry as incomplete if its singular translation or any plural form is empty. The report lists source message IDs that need human translation review; it does not machine-translate them or overwrite upstream translation files.

## Completing the native interface translation

SUMO uses gettext PO files and compiles them into MO catalogs. Its official translation workflow is documented at https://eclipse.dev/sumo/docs/Developer/Translating.html. Translation files should be reviewed in Weblate/upstream workflow to avoid losing updates. After updating PO files, build/compile the catalogs using the upstream i18n tooling and test the GUI, Netedit, CLI, and Python tools with `--language ru`.

## UI improvement roadmap

A full native UI redesign requires changes to SUMO's C++/FOX GUI and a build against the required libraries. This starter kit deliberately avoids brittle runtime hacks. Recommended implementation order:

- establish a baseline with screenshots and a reproducible SUMO build;
- improve Russian terminology and missing translations first;
- standardize labels, shortcuts, tooltips, dialogs, and error messages;
- improve layout/navigation in the native GUI and Netedit, with usability tests;
- run upstream test suites and verify scenario load/save, network editing, and simulation controls.

The full upstream build requires CMake, Visual Studio C++ tools, Python, and SUMOLibraries on Windows; see https://eclipse.dev/sumo/docs/Installing/Windows_Build.html.
