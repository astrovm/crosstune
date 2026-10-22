import unittest
from align import correction, lrc_lines, normalize, observations


class AlignmentTest(unittest.TestCase):
    def test_only_matching_confident_vocals_supply_evidence(self):
        lines = lrc_lines('[00:10.00]First imaginary sentence\n[00:20.00]Another invented sentence\n[00:30.00]Last fictional sentence')
        segments = [dict(start=line['start'] + 10, text=line['text'], avg_logprob=-0.3, no_speech_prob=0.01) for line in lines]
        points = observations(lines, segments)
        self.assertEqual(dict(offset_seconds=10, matching_lines=3), correction(points))
        self.assertIsNone(correction(points[:2]))
        self.assertIsNone(correction([points[0]] * 5))
        self.assertEqual([], observations(lines, [dict(segments[0], avg_logprob=-1.2)]))
        self.assertEqual([], observations(lines, [dict(segments[0], no_speech_prob=0.9)]))
        self.assertEqual([], observations(lines, [dict(segments[0], text='hallucinated unrelated train station announcement')]))
        self.assertEqual([], observations(lines, [dict(segments[0], text='hi')]))
        self.assertEqual([], observations(lines, [dict(segments[0], start=1000)]))
        self.assertEqual([], observations([lines[0], dict(lines[0], start=11)], [segments[0]]))
        self.assertIsNone(correction([points[0], dict(points[1], shift=30), dict(points[2], shift=50)]))
        self.assertIsNone(correction([dict(p, line=i) for i, p in enumerate(points)]))
        self.assertEqual('fictionalsentence', normalize('Ｆｉｃｔｉｏｎａｌ sentence!'))
        self.assertEqual([], lrc_lines('[ar:Band]\n[00:01.00]'))


if __name__ == '__main__':
    unittest.main()
