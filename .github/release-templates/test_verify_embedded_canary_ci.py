import unittest
from unittest.mock import patch

from verify_embedded_canary_ci import REPOSITORY, verified_run


class EmbeddedCanaryCiTest(unittest.TestCase):
    def test_accepts_only_exact_successful_upstream_main_ci(self):
        sha = "a" * 40
        run = {"id": 7, "path": ".github/workflows/canary-build.yml", "head_branch": "main",
               "head_sha": sha, "status": "completed", "conclusion": "success", "event": "push",
               "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY}}
        with patch("verify_embedded_canary_ci.api_json", return_value={"workflow_runs": [run]}):
            self.assertEqual(verified_run(sha)["id"], 7)
        for changes in ({"head_sha": "b" * 40}, {"conclusion": "failure"}, {"event": "pull_request"},
                        {"head_repository": {"full_name": "untrusted/fork"}}):
            with self.subTest(changes=changes), patch("verify_embedded_canary_ci.api_json",
                    return_value={"workflow_runs": [dict(run, **changes)]}), self.assertRaises(ValueError):
                verified_run(sha)

    def test_missing_or_unbounded_history_fails_closed(self):
        for result in ({"workflow_runs": []}, {"workflow_runs": [], "total_count": 101}):
            with patch("verify_embedded_canary_ci.api_json", return_value=result), self.assertRaises(ValueError):
                verified_run("a" * 40)


if __name__ == "__main__":
    unittest.main()
