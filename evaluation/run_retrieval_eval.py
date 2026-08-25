import argparse
import getpass
import json
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def post_json(url, payload, token=None, timeout=60):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = token
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {body}") from error
    except URLError as error:
        raise RuntimeError(f"无法连接后端：{error.reason}") from error


def load_cases(path):
    cases = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                cases.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise ValueError(f"测试集第{line_number}行不是有效JSON：{error}") from error
    return cases


def is_negative_case(case):
    return case.get("category") == "否定事实" and not case.get("expected_audio_ids")


def score_case(case, retrieved_ids, top_k):
    expected_ids = list(dict.fromkeys(int(item) for item in case.get("expected_audio_ids", [])))
    retrieved_ids = [int(item) for item in retrieved_ids[:top_k]]
    negative = is_negative_case(case)
    if not expected_ids and not negative:
        return {
            "scorable": False,
            "negative": False,
            "passed": None,
            "hit_at_k": None,
            "recall_at_k": None,
            "reciprocal_rank": None,
            "top1_correct": None,
        }
    if negative:
        passed = len(retrieved_ids) == 0
        return {
            "scorable": True,
            "negative": True,
            "passed": passed,
            "hit_at_k": None,
            "recall_at_k": None,
            "reciprocal_rank": None,
            "top1_correct": None,
        }

    ranks = [index + 1 for index, audio_id in enumerate(retrieved_ids) if audio_id in expected_ids]
    unique_hits = len(set(retrieved_ids).intersection(expected_ids))
    hit = bool(ranks)
    return {
        "scorable": True,
        "negative": False,
        "passed": hit,
        "hit_at_k": 1.0 if hit else 0.0,
        "recall_at_k": unique_hits / len(expected_ids),
        "reciprocal_rank": 1.0 / min(ranks) if ranks else 0.0,
        "top1_correct": bool(retrieved_ids and retrieved_ids[0] in expected_ids),
    }


def average(values):
    values = [value for value in values if value is not None]
    return sum(values) / len(values) if values else None


def summarize(case_results, top_k):
    scorable = [item for item in case_results if item["metrics"]["scorable"]]
    positive = [item for item in scorable if not item["metrics"]["negative"]]
    negative = [item for item in scorable if item["metrics"]["negative"]]
    summary = {
        "total_cases": len(case_results),
        "scorable_cases": len(scorable),
        "positive_cases": len(positive),
        "negative_cases": len(negative),
        f"hit_at_{top_k}": average([item["metrics"]["hit_at_k"] for item in positive]),
        f"recall_at_{top_k}": average([item["metrics"]["recall_at_k"] for item in positive]),
        "mrr": average([item["metrics"]["reciprocal_rank"] for item in positive]),
        "top1_accuracy": average([1.0 if item["metrics"]["top1_correct"] else 0.0 for item in positive]),
        "negative_rejection_accuracy": average([1.0 if item["metrics"]["passed"] else 0.0 for item in negative]),
        "overall_case_accuracy": average([1.0 if item["metrics"]["passed"] else 0.0 for item in scorable]),
    }

    grouped = defaultdict(list)
    for item in scorable:
        grouped[item["category"]].append(item)
    category_metrics = {}
    for category, items in sorted(grouped.items()):
        category_metrics[category] = {
            "count": len(items),
            "accuracy": average([1.0 if item["metrics"]["passed"] else 0.0 for item in items]),
            f"recall_at_{top_k}": average([item["metrics"]["recall_at_k"] for item in items]),
            "mrr": average([item["metrics"]["reciprocal_rank"] for item in items]),
        }
    return summary, category_metrics


def percent(value):
    return "N/A" if value is None else f"{value * 100:.2f}%"


def markdown_report(report):
    summary = report["summary"]
    top_k = report["config"]["top_k"]
    lines = [
        "# MusicHub 检索效果评测报告",
        "",
        f"- 生成时间：{report['generated_at']}",
        f"- 后端地址：`{report['config']['base_url']}`",
        f"- 测试集：`{report['config']['dataset']}`",
        f"- Top-K：{top_k}",
        "",
        "## 总体指标",
        "",
        "| 指标 | 结果 |",
        "|---|---:|",
        f"| 总题数 | {summary['total_cases']} |",
        f"| 可检索评分题数 | {summary['scorable_cases']} |",
        f"| Hit@{top_k} | {percent(summary[f'hit_at_{top_k}'])} |",
        f"| Recall@{top_k} | {percent(summary[f'recall_at_{top_k}'])} |",
        f"| MRR | {summary['mrr'] if summary['mrr'] is not None else 'N/A'} |",
        f"| Top-1准确率 | {percent(summary['top1_accuracy'])} |",
        f"| 无结果拒答准确率 | {percent(summary['negative_rejection_accuracy'])} |",
        f"| 总体案例准确率 | {percent(summary['overall_case_accuracy'])} |",
        "",
        "## 分类指标",
        "",
        "| 类别 | 题数 | 准确率 | Recall@K | MRR |",
        "|---|---:|---:|---:|---:|",
    ]
    for category, metrics in report["category_metrics"].items():
        mrr = "N/A" if metrics["mrr"] is None else f"{metrics['mrr']:.4f}"
        lines.append(
            f"| {category} | {metrics['count']} | {percent(metrics['accuracy'])} | "
            f"{percent(metrics[f'recall_at_{top_k}'])} | {mrr} |"
        )

    failures = [item for item in report["cases"] if item["metrics"]["scorable"] and not item["metrics"]["passed"]]
    lines.extend(["", "## 失败案例", ""])
    if not failures:
        lines.append("没有失败案例。")
    else:
        lines.extend(["| ID | 类别 | 问题 | 期望ID | 实际Top-K |", "|---|---|---|---|---|"])
        for item in failures:
            question = item["question"].replace("|", "\\|")
            lines.append(
                f"| {item['id']} | {item['category']} | {question} | "
                f"{item['expected_audio_ids']} | {item['retrieved_audio_ids']} |"
            )

    lines.extend([
        "",
        "## 指标说明",
        "",
        f"- `Hit@{top_k}`：前{top_k}名是否至少命中一个标准歌曲。",
        f"- `Recall@{top_k}`：前{top_k}名命中的标准歌曲数量占全部标准歌曲数量的比例。",
        "- `MRR`：第一个正确结果排名的倒数平均值。",
        "- `Top-1准确率`：第一名是否属于标准歌曲集合。",
        "- `无结果拒答准确率`：未收录作品是否返回空检索结果。",
        "- `总体案例准确率`：正例至少命中一个，负例正确返回空结果的案例比例。",
        "",
        "本报告评估检索链路，不调用DeepSeek，因此不会产生模型费用，也不会评估回答措辞。",
    ])
    return "\n".join(lines) + "\n"


def login(base_url, username, password):
    response = post_json(f"{base_url}/api/login", {"username": username, "password": password})
    if response.get("code") != 200 or not response.get("data", {}).get("token"):
        raise RuntimeError(f"登录失败：{response.get('msg', 'unknown error')}")
    return response["data"]["token"]


def run(args):
    dataset = Path(args.dataset).resolve()
    cases = load_cases(dataset)
    base_url = args.base_url.rstrip("/")
    if args.token:
        token = args.token
    else:
        password = args.password or getpass.getpass(f"请输入管理员 {args.username} 的密码：")
        token = login(base_url, args.username, password)
    case_results = []

    for index, case in enumerate(cases, 1):
        payload = {
            "question": case["question"],
            "history": case.get("history", []),
            "topK": args.top_k,
        }
        response = post_json(f"{base_url}/api/admin/evaluation/retrieve", payload, token=token)
        if response.get("code") != 200:
            raise RuntimeError(f"{case['id']}评测失败：{response.get('msg', 'unknown error')}")
        results = response.get("data", {}).get("results", [])
        retrieved_ids = [item["audioId"] for item in results if item.get("audioId") is not None]
        metrics = score_case(case, retrieved_ids, args.top_k)
        case_results.append({
            "id": case["id"],
            "category": case.get("category", "未分类"),
            "question": case["question"],
            "expected_audio_ids": case.get("expected_audio_ids", []),
            "retrieved_audio_ids": retrieved_ids,
            "metrics": metrics,
            "results": results,
        })
        status = "PASS" if metrics["passed"] is True else "FAIL" if metrics["passed"] is False else "SKIP"
        print(f"[{index:02d}/{len(cases)}] {case['id']} {status} expected={case.get('expected_audio_ids', [])} actual={retrieved_ids}")

    summary, category_metrics = summarize(case_results, args.top_k)
    generated_at = datetime.now().astimezone().isoformat(timespec="seconds")
    report = {
        "generated_at": generated_at,
        "config": {
            "base_url": base_url,
            "dataset": str(dataset),
            "top_k": args.top_k,
        },
        "summary": summary,
        "category_metrics": category_metrics,
        "cases": case_results,
    }

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = output_dir / f"retrieval-eval-{stamp}.json"
    markdown_path = output_dir / f"retrieval-eval-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    markdown_path.write_text(markdown_report(report), encoding="utf-8")
    print(f"\nJSON报告：{json_path}")
    print(f"Markdown报告：{markdown_path}")
    print(f"总体案例准确率：{percent(summary['overall_case_accuracy'])}")
    return 0


def parse_args():
    base_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="运行MusicHub 50题检索效果评测")
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    parser.add_argument("--dataset", default=str(base_dir / "rag-eval-50.jsonl"))
    parser.add_argument("--output-dir", default=str(base_dir / "reports"))
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default=None)
    parser.add_argument("--token", default=None, help="已有管理员JWT，可跳过登录")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(run(parse_args()))
    except (RuntimeError, ValueError) as error:
        print(f"评测失败：{error}", file=sys.stderr)
        sys.exit(1)
