import unittest

from prune_caches import to_delete


def cache(id, ref, key, created):
    return {
        "id": id,
        "ref": ref,
        "key": key,
        "created_at": f"2026-11-23T{created}:00Z",
        "size_in_bytes": 1,
    }


class ToDeleteTest(unittest.TestCase):
    def ids(self, caches, open_pulls=frozenset({1205})):
        return sorted(c["id"] for c in to_delete(caches, set(open_pulls)))

    def test_only_the_newest_codeql_dependencies_of_each_branch_stays(self):
        caches = [
            cache(
                1, "refs/pull/1205/head", "codeql-dependencies-1-Linux-java-aa", "10:00"
            ),
            cache(
                2, "refs/pull/1205/head", "codeql-dependencies-1-Linux-java-bb", "12:00"
            ),
            cache(
                3, "refs/pull/1205/head", "codeql-dependencies-1-Linux-java-cc", "11:00"
            ),
            cache(4, "refs/heads/main", "codeql-dependencies-1-Linux-java-dd", "09:00"),
        ]
        self.assertEqual(self.ids(caches), [1, 3])

    def test_a_closed_pull_requests_caches_go_and_an_open_ones_stay(self):
        caches = [
            cache(1, "refs/pull/1193/merge", "apt-ffmpeg-ubuntu26", "10:00"),
            cache(2, "refs/pull/1205/merge", "apt-ffmpeg-ubuntu26", "10:00"),
            cache(
                3, "refs/pull/1193/head", "codeql-dependencies-1-Linux-java-aa", "10:00"
            ),
        ]
        self.assertEqual(self.ids(caches), [1, 3])

    def test_other_caches_are_left_to_github(self):
        caches = [
            cache(1, "refs/heads/main", "gradle-build-cache-v2-aa", "10:00"),
            cache(2, "refs/heads/main", "gradle-build-cache-v2-bb", "11:00"),
            cache(
                3,
                "refs/heads/fix/lyrics-timing",
                "Linux-jdk21-temurin-gradle-aa",
                "11:00",
            ),
        ]
        self.assertEqual(self.ids(caches), [])


if __name__ == "__main__":
    unittest.main()
