import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from app.integrations.sumo_adapter import SumoError, find_sumo, run_simulation


class SumoAdapterTests(unittest.TestCase):
    def test_find_sumo_raises_when_missing(self):
        with patch("app.integrations.sumo_adapter.shutil.which", return_value=None), patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(SumoError):
                find_sumo()

    def test_duration_must_be_positive(self):
        with self.assertRaises(ValueError):
            run_simulation("missing.net.xml", "missing.rou.xml", "out", duration=0)

    def test_missing_network_is_reported(self):
        with TemporaryDirectory() as temp:
            route = Path(temp) / "routes.rou.xml"
            route.write_text("<routes/>", encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                run_simulation(str(Path(temp) / "missing.net.xml"), str(route), temp)


if __name__ == "__main__":
    unittest.main()
