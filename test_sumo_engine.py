import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.integrations.sumo_engine import (
    SumoError, convert_osm_to_network, discover_sumo, generate_routes,
    inspect_traffic_lights, run_sumo,
)


class SumoEngineTests(unittest.TestCase):
    def test_discover_sumo_fails_with_clear_message(self):
        with patch("app.integrations.sumo_engine.shutil.which", return_value=None), patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(SumoError, "Eclipse SUMO"):
                discover_sumo()

    def test_convert_requires_existing_osm(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(FileNotFoundError):
                convert_osm_to_network(str(Path(temp) / "missing.osm"), str(Path(temp) / "net.net.xml"))

    def test_route_demand_validation(self):
        with self.assertRaises(ValueError):
            generate_routes("network.net.xml", "routes.rou.xml", 60, -1)

    def test_run_requires_existing_network(self):
        with self.assertRaises(FileNotFoundError):
            run_sumo("missing.net.xml", "missing.rou.xml", "out", 60)

    def test_inspect_tls_programs(self):
        with tempfile.TemporaryDirectory() as temp:
            network = Path(temp) / "network.net.xml"
            network.write_text(
                '<net><tlLogic id="J1" programID="0" type="static">'
                '<phase duration="20" state="Gr"/><phase duration="3" state="yr"/>'
                '</tlLogic></net>', encoding="utf-8"
            )
            lights = inspect_traffic_lights(str(network))
            self.assertEqual(lights[0]["id"], "J1")
            self.assertEqual(lights[0]["phase_count"], 2)
            self.assertEqual(lights[0]["cycle_seconds"], 23.0)


if __name__ == "__main__":
    unittest.main()
