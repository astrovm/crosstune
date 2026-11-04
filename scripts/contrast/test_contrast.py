import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ElementTree

spec = importlib.util.spec_from_file_location("contrast", Path(__file__).with_name("contrast.py"))
contrast = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contrast)

SAVED = b"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="theme">DARK</string>
    <string name="kept">a song</string>
    <float name="visuals_shade" value="0.7" />
    <set name="visuals_off">
        <string>old</string>
    </set>
    <long name="visuals_change_ms" value="30000" />
</map>
"""


def preferences(xml):
    return {(node.tag, node.get("name")): node for node in ElementTree.fromstring(xml)}


class PrefsTest(unittest.TestCase):
    def test_only_the_preset_stays_on_for_good(self):
        got = preferences(contrast.prefs_for(SAVED, ["old", "A & B's", "kept on"], "kept on"))
        off = [node.text for node in got[("set", "visuals_off")]]
        self.assertEqual(off, ["old", "A & B's"])
        self.assertEqual(got[("long", "visuals_change_ms")].get("value"), str(2**63 - 1))
        self.assertEqual(got[("boolean", "visuals_skip_heavy")].get("value"), "false")

    def test_brightness_and_theme_are_the_apps_own_unless_asked_for_so_runs_compare(self):
        xml = contrast.prefs_for(SAVED, ["a"], "a")
        self.assertNotIn(b'name="theme"', xml)
        self.assertNotIn(b'name="visuals_shade"', xml)

    def test_the_rest_stays_as_it_was(self):
        self.assertEqual(preferences(contrast.prefs_for(SAVED, ["a"], "a"))[("string", "kept")].text, "a song")

    def test_asked_for_settings_replace_the_saved_ones_and_leave_no_second_copy(self):
        xml = contrast.prefs_for(SAVED, ["a"], "a", shade=0.0, theme="LIGHT", pure_black=True)
        got = preferences(xml)
        self.assertEqual(got[("string", "theme")].text, "LIGHT")
        self.assertEqual(got[("float", "visuals_shade")].get("value"), "0.0")
        self.assertEqual(got[("boolean", "pure_black")].get("value"), "true")
        self.assertEqual(xml.count(b'name="theme"'), 1)
        self.assertEqual(xml.count(b'name="visuals_shade"'), 1)


if __name__ == "__main__":
    unittest.main()
