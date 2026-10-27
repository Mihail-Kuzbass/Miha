import tempfile
import unittest
from pathlib import Path

from apply_ru_overrides import TRANSLATIONS, apply


class OverrideTests(unittest.TestCase):
    def test_only_empty_translation_is_changed(self):
        msgid = "Enter 'lat, lon' to center view"
        content = f'''msgid ""
msgstr ""
"Language: ru\\n"

msgid "{msgid}"
msgstr ""

msgid "Already translated"
msgstr "Уже переведено"
'''
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "ru_gui.po"
            output = Path(directory) / "patched.po"
            source.write_text(content, encoding="utf-8")
            applied, not_found = apply(source, output)
            result = output.read_text(encoding="utf-8")
        self.assertEqual(applied, 1)
        self.assertGreater(not_found, 0)
        self.assertIn(f'msgstr "{TRANSLATIONS[msgid]}"', result)
        self.assertIn('msgstr "Уже переведено"', result)

    def test_refuses_to_change_nonempty_entries(self):
        msgid = "Enter 'lat, lon' to center view"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "ru_gui.po"
            source.write_text(f'msgid "{msgid}"\nmsgstr "Свой перевод"\n', encoding="utf-8")
            applied, _ = apply(source, source)
            result = source.read_text(encoding="utf-8")
        self.assertEqual(applied, 0)
        self.assertIn('msgstr "Свой перевод"', result)


if __name__ == "__main__":
    unittest.main()
