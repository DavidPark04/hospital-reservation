import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('repository_policy', Path(__file__).parents[1] / 'check_repository.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class PullRequestPolicyTest(unittest.TestCase):
    def test_feature_and_emergency_changes_target_main_once(self):
        for head in ('feat/frontend/49-example', 'fix/backend/50-example', 'docs/51-example', 'hotfix/shared/52-example'):
            with self.subTest(head=head):
                self.assertEqual([], policy.check_pr({'head': {'ref': head}, 'base': {'ref': 'main'}, 'title': 'fix: 설명'}))

    def test_develop_and_reverse_release_pull_requests_are_rejected(self):
        for head, base in [('feat/frontend/49-example', 'develop'), ('main', 'develop'), ('develop', 'main'), ('unexpected', 'main')]:
            with self.subTest(head=head, base=base):
                self.assertTrue(policy.check_pr({'head': {'ref': head}, 'base': {'ref': base}, 'title': 'fix: 설명'}))

    def test_title_validation_still_applies(self):
        self.assertTrue(policy.check_pr({'head': {'ref': 'fix/frontend/49-example'}, 'base': {'ref': 'main'}, 'title': '잘못된 제목'}))
