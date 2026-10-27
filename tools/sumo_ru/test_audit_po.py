import tempfile
import unittest
from pathlib import Path

from audit_po import audit, parse_po


class PoAuditTests(unittest.TestCase):
    def test_multiline_and_missing_translation(self):
        content = '''msgid ""
msgstr ""
"Language: ru\\n"

msgid "Open"
msgstr "Открыть"

msgid "Missing"
msgstr ""

msgid "Long"
msgstr ""
"Длинный"
'''
        with tempfile.TemporaryDirectory() as directory:
            po = Path(directory) / "sample.po"
            po.write_text(content, encoding="utf-8")
            entries, missing_count, missing = audit(po)
        self.assertEqual(entries, 3)
        self.assertEqual(missing_count, 1)
        self.assertEqual(missing, ["Missing"])

    def test_plural_missing_form_is_reported(self):
        content = '''msgid "vehicle"
msgid_plural "vehicles"
msgstr[0] "машина"
msgstr[1] ""
'''
        with tempfile.TemporaryDirectory() as directory:
            po = Path(directory) / "plural.po"
            po.write_text(content, encoding="utf-8")
            _, missing_count, missing = audit(po)
        self.assertEqual(missing_count, 1)
        self.assertEqual(missing, ["vehicle"])


if __name__ == "__main__":
    unittest.main()
