import unittest

from run_retrieval_eval import score_case, summarize


class RetrievalEvaluationMetricsTest(unittest.TestCase):

    def test_positive_metrics(self):
        case = {"category": "出处精确查询", "expected_audio_ids": [1, 7]}
        metrics = score_case(case, [4, 7, 1], 3)
        self.assertTrue(metrics["passed"])
        self.assertEqual(1.0, metrics["recall_at_k"])
        self.assertEqual(0.5, metrics["reciprocal_rank"])
        self.assertFalse(metrics["top1_correct"])

    def test_negative_rejection(self):
        case = {"category": "否定事实", "expected_audio_ids": []}
        metrics = score_case(case, [], 5)
        self.assertTrue(metrics["passed"])
        self.assertTrue(metrics["negative"])

    def test_summary(self):
        cases = [
            {"category": "A", "metrics": score_case({"category": "A", "expected_audio_ids": [1]}, [1], 5)},
            {"category": "A", "metrics": score_case({"category": "A", "expected_audio_ids": [2]}, [3], 5)},
        ]
        summary, categories = summarize(cases, 5)
        self.assertEqual(0.5, summary["overall_case_accuracy"])
        self.assertEqual(0.5, categories["A"]["accuracy"])


if __name__ == "__main__":
    unittest.main()
