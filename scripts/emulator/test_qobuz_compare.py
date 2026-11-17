import unittest

import qobuz_compare as compare


class IntervalTest(unittest.TestCase):
    def test_newcombes_own_example(self):
        # Newcombe 1998, method 10: 56/70 against 48/80 is 0.2, from 0.0524 to 0.3339.
        diff, low, high = compare.newcombe(56, 70, 48, 80)
        self.assertAlmostEqual(diff, 0.2, places=4)
        self.assertAlmostEqual(low, 0.0524, places=4)
        self.assertAlmostEqual(high, 0.3339, places=4)

    def test_forty_of_forty_each_cant_yet_rule_out_five_points_worse_but_eighty_can(self):
        self.assertLess(compare.newcombe(40, 40, 40, 40)[1], -0.05)
        self.assertGreater(compare.newcombe(80, 80, 80, 80)[1], -0.05)


class TriesTest(unittest.TestCase):
    def test_pairs_take_turns_going_first(self):
        self.assertEqual([compare.arm(i) for i in range(8)], ["base", "head", "head", "base", "base", "head", "head", "base"])

    def test_server_errors_and_broken_setups_dont_count(self):
        self.assertFalse(compare.counted(dict(server_500=2)))
        self.assertFalse(compare.counted(dict(pre_failed=True, server_500=99)))
        self.assertFalse(compare.counted(dict(error="timeout")))
        self.assertTrue(compare.counted(dict(server_500=1, right_at=None)))

    def test_a_song_never_reached_is_a_miss(self):
        self.assertTrue(compare.reached(dict(right_at=5.1)))
        self.assertFalse(compare.reached(dict(right_at=None)))

    def test_the_older_song_back_after_a_newer_one_is_a_miss(self):
        newer = dict(then_right_at=6.0, then_want="Blinding Lights", reopened=False, final=dict(song="Blinding Lights, The Weeknd, null"))
        self.assertTrue(compare.reached(newer))
        self.assertFalse(compare.reached({**newer, "reopened": True}))
        self.assertFalse(compare.reached({**newer, "then_right_at": None}))
        self.assertFalse(compare.reached({**newer, "final": dict(song="Expresso Love, Dire Straits, null")}))
        self.assertFalse(compare.reached({**newer, "final": None}))

    def test_it_stops_at_the_pairs_when_head_is_close_enough_or_else_at_the_most(self):
        def tries(n, base_hits, head_hits):
            return [dict(arm="base", right_at=1.0 if i < base_hits else None) for i in range(n)] + [dict(arm="head", right_at=1.0 if i < head_hits else None) for i in range(n)]
        self.assertFalse(compare.enough(compare.summary(tries(40, 40, 40)), 40, 80, 0.05))
        self.assertTrue(compare.enough(compare.summary(tries(40, 20, 40)), 40, 80, 0.05))
        self.assertTrue(compare.enough(compare.summary(tries(80, 80, 60)), 40, 80, 0.05))
        self.assertFalse(compare.enough(compare.summary(tries(39, 20, 39)), 40, 80, 0.05))

    def test_the_summary_says_what_each_build_did_and_keeps_out_what_doesnt_count(self):
        tries = [dict(arm="base", right_at=4.0, blocked=0), dict(arm="head", right_at=2.0, blocked=1), dict(arm="head", right_at=None, blocked=0), dict(arm="head", error="timeout")]
        report = compare.summary(tries)
        self.assertEqual(report["uncounted"], 1)
        self.assertEqual({k: report["base"][k] for k in ("tries", "reached", "blocked", "median_s")}, dict(tries=1, reached=1, blocked=0, median_s=4.0))
        self.assertEqual({k: report["head"][k] for k in ("tries", "reached", "blocked", "median_s")}, dict(tries=2, reached=1, blocked=1, median_s=2.0))
        self.assertEqual(report["head_first_20"], dict(tries=2, reached=1, blocked=1))

    def test_the_first_launch_is_the_first_qobuz_start_not_crosstunes_own(self):
        crosstune = [0.0, "START", "", "com.astrovm.crosstune.dev/com.astrovm.crosstune.MainActivity"]
        self.assertEqual(compare.first_launch(dict(events=[crosstune, [1.0, "START", "https://play.qobuz.com/...", ""], [9.0, "START", "https://play.qobuz.com/...", ""]])), 1.0)
        self.assertIsNone(compare.first_launch(dict(events=[crosstune, [2.0, "BLOCKED", "qobuz"]])))
        self.assertIsNone(compare.first_launch({}))

    def test_a_gap_is_taken_pair_by_pair_and_a_pair_missing_a_time_is_left_out(self):
        def tried(pair, base, head):
            return [dict(arm="base", index=pair * 2, right_at=base), dict(arm="head", index=pair * 2 + 1, right_at=head)]
        tries = tried(0, 10.0, 11.0) + tried(1, 20.0, 21.5) + tried(2, 30.0, 30.5) + tried(3, 10.0, None)
        gap = compare.paired(tries, lambda t: t["right_at"])
        self.assertEqual((gap["pairs"], gap["median"], gap["mean"]), (3, 1.0, 1.0))
        self.assertIsNone(compare.paired(tried(0, 1.0, 2.0), lambda t: t["right_at"]))

    def test_a_large_paired_gap_has_a_tight_interval(self):
        tries = []
        for pair in range(40):
            tries += [dict(arm="base", index=pair * 2, right_at=10.0), dict(arm="head", index=pair * 2 + 1, right_at=11.0 + (pair % 2) / 10)]
        gap = compare.paired(tries, lambda t: t["right_at"])
        # t at 39 degrees of freedom is 2.0227; the gaps' spread is 0.05064.
        self.assertAlmostEqual(gap["high"] - gap["mean"], 2.0227 * 0.05064 / 40 ** 0.5, places=3)
        self.assertGreater(gap["low"], 1.0)


if __name__ == "__main__":
    unittest.main()
