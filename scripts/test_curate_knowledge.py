"""Offline script tests: no actual login, provider requests, secrets or real database."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("curate_knowledge", Path(__file__).with_name("curate-knowledge.py"))
curation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(curation)


class CurationScriptTest(unittest.TestCase):
    def entry(self):
        return {"feedbackId": "a" * 32, "expectedUpdatedAt": 10, "expectedVersion": 0,
                "title": "上传问题", "answer": "人工核对答案", "scope": "TENANT", "confirmGlobal": False, "reviewed": True}

    def test_credentials_and_nonlocal_plain_http_are_rejected(self):
        for url in ("http://example.invalid", "https://u:p@example.invalid", "https://example.invalid?token=abc", "https://example.invalid/#fragment"):
            with self.assertRaises(curation.CurationError):
                curation.base_url(url)
        self.assertEqual("http://127.0.0.1:8080", curation.base_url("http://127.0.0.1:8080/"))
        self.assertEqual("https://example.invalid", curation.base_url("https://example.invalid"))

    def test_unreviewed_global_or_duplicate_entries_cannot_publish(self):
        entry = self.entry()
        curation.validate_review({"schema": 1, "entries": [entry]})
        for invalid in ({**entry, "reviewed": False}, {**entry, "scope": "GLOBAL"}, {**entry, "answer": " "}, {**entry, "expectedVersion": True}):
            with self.assertRaises(curation.CurationError):
                curation.validate_review({"schema": 1, "entries": [invalid]})
        with self.assertRaises(curation.CurationError):
            curation.validate_review({"schema": 1, "entries": [entry, entry]})

    def test_ai_export_keeps_candidate_separate_from_manual_answer_and_never_writes_server(self):
        calls = []

        class Client:
            def request(_self, method, path, data=None):
                calls.append((method, path, data))
                if path.startswith("/admin/ai-feedback?"):
                    return {"items": [{"id": "a" * 32}]}
                if path.startswith("/admin/ai-feedback/"):
                    return {"status": "RESOLVED", "correctedAnswer": "人工答案", "question": "课程问题", "updatedAt": 10, "courseId": "c" * 32}
                if path.startswith("/admin/ai-knowledge/feedback/"):
                    return {"version": 0}
                return {"answer": "AI 润色候选", "requiresReview": True}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            curation.export_review(Client(), path, 5, True)
            entry = json.loads(path.read_text(encoding="utf-8"))["entries"][0]
            self.assertEqual("人工答案", entry["answer"])
            self.assertEqual("AI 润色候选", entry["candidateAnswer"])
            self.assertFalse(entry["reviewed"])
            self.assertEqual("COURSE", entry["scope"])
            self.assertFalse(any(method == "PUT" or path.endswith("/publish") for method, path, _ in calls))

    def test_rejected_publish_confirmation_has_no_writes(self):
        class Client:
            def request(_self, method, path, data=None):
                self.assertEqual("GET", method)
                return {"version": 0} if "/ai-knowledge/" in path else {"status": "RESOLVED", "updatedAt": 10, "correctedAnswer": "人工核对答案"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            curation.private_json(path, {"schema": 1, "entries": [self.entry()]})
            with patch("builtins.input", return_value="CANCEL"):
                curation.publish_review(Client(), path)

    def test_stale_source_is_rejected_before_any_server_write(self):
        class Client:
            def request(_self, method, path, data=None):
                self.assertEqual("GET", method)
                return {"version": 0} if "/ai-knowledge/" in path else {"updatedAt": 11, "correctedAnswer": "新答案"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            curation.private_json(path, {"schema": 1, "entries": [self.entry()]})
            with self.assertRaises(curation.CurationError):
                curation.publish_review(Client(), path)

    def test_existing_output_is_not_overwritten_without_checkpoint_authorization(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            curation.private_json(path, {"schema": 1})
            with self.assertRaises(curation.CurationError):
                curation.private_json(path, {"schema": 2})
            self.assertEqual({"schema": 1}, json.loads(path.read_text()))

    def test_confirmed_batch_saves_edited_answers_then_publishes_exact_saved_versions_without_candidate_fields(self):
        calls = []

        class Client:
            def request(_self, method, path, data=None):
                calls.append((method, path, data))
                if method == "PUT":
                    return {"updatedAt": 11, "correctedAnswer": data["correctedAnswer"]}
                if path.endswith("/publish"):
                    self.assertEqual(11, data["entries"][0]["expectedUpdatedAt"])
                    self.assertNotIn("candidateAnswer", data["entries"][0])
                    return [{"feedbackId": "a" * 32, "version": 1, "state": "PUBLISHED"}]
                if "/ai-knowledge/feedback/" in path:
                    return {"version": 0}
                return {"status": "RESOLVED", "updatedAt": 10, "correctedAnswer": "原人工答案", "note": "原备注"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            curation.private_json(path, {"schema": 1, "entries": [{**self.entry(), "candidateAnswer": "仅作参考"}]})
            with patch("builtins.input", return_value="PUBLISH"):
                curation.publish_review(Client(), path)
            result = json.loads(path.read_text(encoding="utf-8"))["entries"][0]
            self.assertFalse(result["reviewed"])
            self.assertEqual(1, result["expectedVersion"])
            self.assertEqual(1, sum(method == "PUT" for method, _, _ in calls))
            self.assertEqual(1, sum(path.endswith("/publish") for _, path, _ in calls))


if __name__ == "__main__":
    unittest.main()
