import sys
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from drivers import pace  # noqa: E402


class PaceTest(unittest.TestCase):
    def test_factor_from_job_then_default(self):
        self.assertEqual(pace.factor({"humanPace": 2}), 2.0)
        self.assertEqual(pace.factor({"humanPace": -1}), 0.0)
        self.assertEqual(pace.factor({"humanPace": "x"}), 1.0)

    def test_zero_pace_never_waits(self):
        page = mock.MagicMock()
        pace.Pace(0).pause(page, 1, 2)
        page.wait_for_timeout.assert_not_called()

    def test_pause_is_random_within_range_and_scaled(self):
        page = mock.MagicMock()
        p = pace.Pace(2)
        for _ in range(50):
            p.pause(page, 0.5, 1.0)
        waits = [c.args[0] for c in page.wait_for_timeout.call_args_list]
        self.assertTrue(all(1000 <= w <= 2000 for w in waits))
        self.assertGreater(len(set(waits)), 5)   # không đều nhau

    def test_tag_typed_one_char_at_a_time(self):
        page = mock.MagicMock()
        pace.Pace(1).type_slow(page, "CHAR-1")
        typed = [c.args[0] for c in page.keyboard.type.call_args_list]
        self.assertEqual("".join(typed), "CHAR-1")
        self.assertEqual(len(typed), 6)


if __name__ == "__main__":
    unittest.main()
