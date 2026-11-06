"""Prompt ảnh biến thể trang phục nhắc ảnh gốc bằng ID trần: phải thành @ID để Flow dùng ảnh đó."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from drivers.flow_browser import FlowBrowserDriver  # noqa: E402

fix = FlowBrowserDriver._mention_unmentioned_refs


class MentionRefsTest(unittest.TestCase):
    def test_bare_id_becomes_a_mention_once(self):
        out = fix('same person as the supplied reference image CHAR-CLARA-1, in the outfit "2". Keep CHAR-CLARA-1 look.', ["CHAR-CLARA-1"])
        self.assertEqual(out, 'same person as the supplied reference image @CHAR-CLARA-1, in the outfit "2". Keep CHAR-CLARA-1 look.')

    def test_already_mentioned_is_untouched(self):
        p = "use @CHAR-CLARA-1 as identity"
        self.assertEqual(fix(p, ["CHAR-CLARA-1"]), p)

    def test_id_not_in_prompt_is_appended(self):
        self.assertEqual(fix("make a portrait", ["CHAR-CLARA-1"]), "make a portrait @CHAR-CLARA-1")

    def test_longer_id_is_not_confused_with_prefix(self):
        out = fix("image CHAR-CLARA-10 only", ["CHAR-CLARA-1"])
        self.assertEqual(out, "image CHAR-CLARA-10 only @CHAR-CLARA-1")


if __name__ == "__main__":
    unittest.main()
