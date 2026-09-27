#!/usr/bin/env python3
"""
Background Check API 실측 스크립트.

swagger.yaml 은 "명세"일 뿐이므로, 이 스크립트는 실제 API 를 호출해서
응답 지연 / HTTP 상태코드 / 상태 전이(pending -> clear|flagged) / 503 + Retry-After /
명세 대비 실제 응답 필드를 측정하고, 모든 요청의 원시값을 CSV 에 누적한다.
MEASUREMENTS.md 는 CSV 만 읽어서 생성한다(측정하지 않은 값은 만들지 않는다).

사용법:
    python measure_background_api.py lifecycle   --samples 10
    python measure_background_api.py latency     --samples 100
    python measure_background_api.py duplicate   --employee EMP-001 --count 5
    python measure_background_api.py concurrency --levels 1,5,10 --requests-per-level 20
    python measure_background_api.py errors
    python measure_background_api.py resume      # 중단된 lifecycle checkId 조회 재개
    python measure_background_api.py report

    # POST 동시성은 레코드를 실제로 생성하므로 별도 옵션 + 요청 수 상한
    python measure_background_api.py concurrency --method post --levels 1,3 --requests-per-level 5

수동 관찰은 data/manual_observations.csv 에 별도 기록하며 자동 측정 통계에 섞지 않는다.
요청/응답 본문은 저장하지 않고 필드 '이름'만 기록한다. 이름·생년월일·tenant 류의 실제 값은 남기지 않는다.

인증키는 환경변수 CANDIDATE_KEY 에서만 읽으며, 콘솔/CSV/Markdown 어디에도 기록하지 않는다.

백분위수(percentile) 계산 방식: nearest-rank
    정렬된 표본 x[1..n] 에서 pXX = x[ceil(q * n)]  (q = 0.50, 0.95, 0.99)
    보간을 하지 않으므로 결과는 항상 "실제로 관측된 값" 중 하나다.
    표본이 적으면 p95/p99 가 사실상 max 와 같아지므로, n < 20 인 p95, n < 100 인 p99 에는
    '표본 부족' 표시를 붙인다.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import platform
import re
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

try:
    import requests
except ImportError:  # report 명령은 requests 없이도 동작해야 한다
    requests = None

try:
    import yaml
except ImportError:
    yaml = None


# ---------------------------------------------------------------------------
# 경로 / 상수
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
MEAS_CSV = DATA_DIR / "measurements.csv"
LC_CSV = DATA_DIR / "lifecycles.csv"
RUNS_CSV = DATA_DIR / "runs.csv"
MANUAL_CSV = DATA_DIR / "manual_observations.csv"   # 수동 관찰 기록 (자동 측정 통계와 분리)
REPORT_MD = BASE_DIR / "MEASUREMENTS.md"
DEFAULT_SPEC = BASE_DIR.parent / "swagger.yaml"
# swagger.yaml 을 읽지 못했을 때만 사용하는 fallback (swagger servers[0].url 과 동일)
FALLBACK_BASE_URL = "https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com"

EP_CHECKS = "/background-checks"
EP_CHECK = "/background-checks/{checkId}"
FINAL_STATUSES = {"clear", "flagged"}

# 이전 버전에서 measurements.csv 에 섞여 있던 수동 행의 "미기록" 표시 (하위 호환용으로만 인식)
NOT_RECORDED = "NOT_RECORDED"
# 응답에 필드 자체가 없었음을 나타내는 값 (null 과 구분)
ABSENT = "ABSENT"

# POST 동시성 측정은 레코드를 실제로 생성하므로 별도 옵션 + 요청 수 상한을 둔다 (외부 API 보호용)
POST_CONCURRENCY_DEFAULT_LIMIT = 15
POST_CONCURRENCY_HARD_LIMIT = 30
POST_CONCURRENCY_MAX_LEVEL = 5

# 보고서에서 '표본 부족'을 표시하는 기준. 정책값이 아니라 통계 표시 기준이다.
MIN_N_FOR_PERCENTILE = {0.50: 1, 0.95: 20, 0.99: 100}
MIN_LIFECYCLE_N = 10     # 처리시간 분포를 판단 근거로 쓰기 위한 최소 완료 건수 (요청된 기본 표본 수)
MIN_503_SEQUENCES = 5    # 503 이후 거동(연속 503 횟수 등)을 근거로 쓰기 위한 최소 관측 수

MEAS_COLUMNS = [
    "timestamp", "runId", "source", "testType", "method", "endpoint", "employeeId", "checkId",
    "concurrency", "httpStatus", "latencyMs", "apiStatus", "retryAfterHeader", "retryAfterBody",
    "retryAfter", "processingMs", "createdAt", "completedAt", "serverDurationMs",
    "processingMsDiffMs", "specDefinedStatus", "responseFields", "extraFields", "missingFields",
    "totalCount", "errorMessage", "note",
]
LC_COLUMNS = [
    "runId", "source", "employeeId", "checkId", "postTimestamp", "postHttpStatus", "postApiStatus",
    "postLatencyMs", "estimatedCompletionSeconds", "createdAt", "completedAt", "processingMs",
    "serverDurationMs", "processingMsDiffMs", "lastPendingObservedMs", "firstFinalObservedMs",
    "clientObservedMs", "pollCount", "count503", "retryAfterValues", "statusSequence",
    "finalStatus", "outcome", "note",
]
RUN_COLUMNS = ["runId", "startedAt", "command", "params"]
# 수동 관찰: 관찰 사실만 기록. 요청별 latency / timestamp / 요청 횟수 컬럼은 두지 않는다(기록된 적 없음).
MANUAL_COLUMNS = [
    "observationId", "observedOrder", "method", "endpoint", "employeeId", "httpStatus", "apiStatus",
    "retryAfterBody", "retryAfterHeader", "createdAt", "completedAt", "processingMs",
    "observedExtraFields", "notObservedFields", "note",
]

# 과제 시드 데이터 (duplicate 테스트에서 사용). EMP-007 은 생년월일 미확인.
SEED_EMPLOYEES = {
    "EMP-001": ("김민준", "1990-03-15"),
    "EMP-002": ("김민준", "1994-11-02"),
    "EMP-003": ("남궁서준", "1988-07-21"),
    "EMP-004": ("황보라온", "1995-02-09"),
    "EMP-005": ("김솔", "1992-12-30"),
    "EMP-006": ("선우진", "1991-05-05"),
    "EMP-007": ("이서연", None),
    "EMP-008": ("박민준", "1993-08-17"),
    "EMP-009": ("최지우", "1996-04-03"),
    "EMP-010": ("정하윤", "1989-10-11"),
}
COMPOUND_SURNAMES = ("남궁", "황보", "선우", "제갈", "독고", "사공", "서문", "동방")

_csv_lock = threading.Lock()


# ---------------------------------------------------------------------------
# 공통 유틸
# ---------------------------------------------------------------------------
def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_iso(value):
    """ISO-8601 문자열 -> aware datetime. 파싱 불가 시 None (Python 3.10 호환)."""
    if not value or not isinstance(value, str):
        return None
    s = value.strip().replace("Z", "+00:00")
    # 3.10 의 fromisoformat 은 소수점 3/6자리만 허용 -> 6자리로 맞춘다
    m = re.match(r"^(.*T\d{2}:\d{2}:\d{2})(\.\d+)?(.*)$", s)
    if m:
        frac = (m.group(2) or "")[1:]
        frac = (frac + "000000")[:6] if frac else ""
        s = m.group(1) + (("." + frac) if frac else "") + m.group(3)
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def diff_ms(start, end):
    if start is None or end is None:
        return None
    return int(round((end - start).total_seconds() * 1000))


def parse_int(value):
    if value is None or isinstance(value, bool):
        return None
    s = str(value).strip()
    if not s:
        return None
    try:
        return int(s)
    except ValueError:
        try:
            f = float(s)
            return int(f) if f.is_integer() else None
        except ValueError:
            return None


def to_float(value):
    if value is None:
        return None
    s = str(value).strip()
    if not s or s in (NOT_RECORDED, ABSENT):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def split_list(value):
    if not value or value == NOT_RECORDED:
        return []
    return [x for x in str(value).split(";") if x]


def mask_secret(text, secret):
    """키 문자열이 에러 메시지 등에 섞여 나오는 경우를 대비한 마스킹."""
    if not text:
        return ""
    text = str(text)
    if secret:
        text = text.replace(secret, "***")
    return text


def split_korean_name(name: str):
    """한글 성명 -> (lastName, firstName). 복성은 목록 기준. 측정용 단순 규칙."""
    if len(name) > 2 and name[:2] in COMPOUND_SURNAMES:
        return name[:2], name[2:]
    return name[:1], name[1:]


def append_rows(path: Path, columns, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with _csv_lock:
        new_file = not path.exists() or path.stat().st_size == 0
        # 새 파일은 BOM 포함(엑셀에서 한글 깨짐 방지), 이어쓰기는 BOM 없이
        with path.open("a", newline="", encoding="utf-8-sig" if new_file else "utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
            if new_file:
                writer.writeheader()
            for r in rows:
                writer.writerow({c: ("" if r.get(c) is None else r.get(c)) for c in columns})


def read_rows(path: Path):
    if not path.exists():
        return []
    with path.open("r", newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def fmt_ms(v):
    return "" if v is None else f"{v:.1f}"


def ms(v):
    """서술 문장 안에서 쓰는 ms 표기. 표의 stat_cell 과 같은 자릿수·천단위 구분."""
    return "TBD" if v is None else f"{v:,.0f}"


# ---------------------------------------------------------------------------
# 통계
# ---------------------------------------------------------------------------
def nearest_rank(n, q):
    """nearest-rank 순위(1-based): ceil(q*n). 예) n=100, q=0.99 -> 99번째로 작은 값."""
    return max(1, math.ceil(q * n))


def percentile_nearest_rank(values, q):
    """nearest-rank 백분위수: 정렬 후 x[ceil(q*n) - 1] (0-based). 보간 없음."""
    if not values:
        return None
    s = sorted(values)
    return s[nearest_rank(len(s), q) - 1]


def _binom_cdf(k, n, q):
    """P(B <= k), B ~ Binomial(n, q). 로그 감마로 계산(외부 라이브러리 없이)."""
    if k < 0:
        return 0.0
    if k >= n:
        return 1.0
    total = 0.0
    lq, l1q = math.log(q), math.log1p(-q)
    for i in range(0, k + 1):
        total += math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + i * lq + (n - i) * l1q)
    return min(total, 1.0)


def quantile_ci_ranks(n, q, conf=0.95):
    """분포 가정 없는(order-statistic) 분위수 신뢰구간의 순위 (lower, upper), 1-based.
    P(x_(l) <= ξ_q <= x_(u)) >= conf. 순위가 0 이하/ n 초과이면 None = '관측 범위 밖'.
    예) n=100, q=0.99 -> (97, None): 상한이 관측 최대값을 넘어서므로 p99 의 상한은 데이터로 제한되지 않는다."""
    if n <= 0:
        return None, None
    a = (1 - conf) / 2
    lower = None
    for k in range(n, -1, -1):          # P(B <= l-1) <= a 를 만족하는 가장 큰 l
        if _binom_cdf(k - 1, n, q) <= a:
            lower = k if k >= 1 else None
            break
    upper = None
    for k in range(0, n + 1):           # P(B <= u-1) >= 1-a 를 만족하는 가장 작은 u
        if _binom_cdf(k - 1, n, q) >= 1 - a:
            upper = k if k >= 1 else None
            break
    return lower, upper


def quantile_ci_cell(values, q, conf=0.95):
    n = len(values)
    if n == 0:
        return "TBD (n=0)"
    s = sorted(values)
    lo, hi = quantile_ci_ranks(n, q, conf)
    lo_s = f"{s[lo - 1]:,.0f} ({lo}번째)" if lo else "관측 범위 밖(하한 없음)"
    hi_s = f"{s[hi - 1]:,.0f} ({hi}번째)" if hi else "관측 범위 밖(상한 없음)"
    return f"[{lo_s}, {hi_s}]"


def stat_cell(values, q):
    n = len(values)
    if n == 0:
        return "TBD (n=0)"
    v = percentile_nearest_rank(values, q)
    need = MIN_N_FOR_PERCENTILE.get(q, 1)
    if n < need:
        return f"{v:,.0f} (n={n}, 표본 부족: n≥{need} 필요)"
    return f"{v:,.0f} (n={n})"


def minmax_cell(values, fn):
    if not values:
        return "TBD (n=0)"
    return f"{fn(values):,.0f} (n={len(values)})"


def ratio(count, total):
    if not total:
        return "TBD (n=0)"
    return f"{count} / {total} ({count / total * 100:.1f}%)"


def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c).replace("|", "\\|").replace("\n", " ") for c in r) + " |")
    return out


def latency_stat_row(label, values):
    return [label, len(values), minmax_cell(values, min), stat_cell(values, 0.50),
            stat_cell(values, 0.95), stat_cell(values, 0.99), minmax_cell(values, max)]


LAT_HEADERS = ["구분", "n", "min (ms)", "p50 (ms)", "p95 (ms)", "p99 (ms)", "max (ms)"]


# ---------------------------------------------------------------------------
# swagger.yaml 파싱 (필드 목록을 하드코딩하지 않고 명세에서 읽는다)
# ---------------------------------------------------------------------------
class Spec:
    def __init__(self, path: Path):
        self.path = path
        self.doc = None
        self.error = None
        if yaml is None:
            self.error = "PyYAML 미설치 — 명세 대조 생략"
        elif not path.exists():
            self.error = f"swagger 파일 없음: {path}"
        else:
            with path.open("r", encoding="utf-8") as f:
                self.doc = yaml.safe_load(f)

    def resolve(self, ref):
        if not self.doc or not isinstance(ref, str) or not ref.startswith("#/"):
            return None
        node = self.doc
        for part in ref[2:].split("/"):
            if not isinstance(node, dict) or part not in node:
                return None
            node = node[part]
        return node

    def deref(self, node):
        if isinstance(node, dict) and "$ref" in node:
            return self.resolve(node["$ref"])
        return node

    @property
    def version(self):
        return (self.doc or {}).get("info", {}).get("version", "")

    def server_url(self):
        servers = (self.doc or {}).get("servers") or []
        return servers[0].get("url") if servers else None

    def operation(self, path_tmpl, method):
        return ((self.doc or {}).get("paths", {}).get(path_tmpl, {}) or {}).get(method.lower())

    def defined_statuses(self, path_tmpl, method):
        op = self.operation(path_tmpl, method) or {}
        return sorted(str(k) for k in (op.get("responses") or {}).keys())

    def is_defined(self, path_tmpl, method, status):
        if not self.doc:
            return ""
        return "Y" if str(status) in self.defined_statuses(path_tmpl, method) else "N"

    def response_schema(self, path_tmpl, method, status):
        """(schemaName, schemaDict). 정의되지 않았거나 $ref 가 깨졌으면 schemaDict=None."""
        op = self.operation(path_tmpl, method) or {}
        resp = (op.get("responses") or {}).get(str(status))
        if resp is None:
            resp = (op.get("responses") or {}).get(int(status)) if str(status).isdigit() else None
        resp = self.deref(resp)
        if not isinstance(resp, dict):
            return None, None
        schema = (((resp.get("content") or {}).get("application/json") or {}).get("schema"))
        if isinstance(schema, dict) and "$ref" in schema:
            name = schema["$ref"].split("/")[-1]
            resolved = self.resolve(schema["$ref"])
            return (name if resolved else name + "(unresolved)"), resolved
        return ("inline" if schema else None), schema

    @staticmethod
    def completion_only_fields(schema):
        """설명에 '완료 시에만 존재' / 'null if pending' 이 적힌 필드."""
        out = set()
        for name, prop in (schema.get("properties") or {}).items():
            desc = str((prop or {}).get("description", "")).lower()
            if "only present when completed" in desc or "null if pending" in desc:
                out.add(name)
        return out

    def all_refs(self):
        refs = []

        def walk(node, where):
            if isinstance(node, dict):
                for k, v in node.items():
                    if k == "$ref" and isinstance(v, str):
                        refs.append((where, v))
                    else:
                        walk(v, f"{where}/{k}")
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    walk(v, f"{where}[{i}]")

        walk(self.doc or {}, "#")
        return refs

    def broken_refs(self):
        return [(w, r) for w, r in self.all_refs() if self.resolve(r) is None]

    def unreferenced_components(self, section):
        comps = ((self.doc or {}).get("components", {}) or {}).get(section, {}) or {}
        used = {r for _, r in self.all_refs()}
        return [name for name in comps if f"#/components/{section}/{name}" not in used]


def compare_fields(spec: Spec, path_tmpl, method, status, body):
    """(extraFields, missingFields) — 둘 다 '필드 이름'만 반환한다(값은 다루지 않음).
    missingFields = 명세 스키마에 있으나 '이번 응답에서 관찰되지 않은' 필드. 명세 위반 여부는 여기서 판정하지 않고,
    보고서에서 schema.required 와 대조해 분류한다(classify_missing).
    명세에 스키마가 없거나 body 가 dict 가 아니면 비교 불가 -> ([], [])."""
    if spec.doc is None or status is None or not isinstance(body, dict):
        return [], []
    _, schema = spec.response_schema(path_tmpl, method, status)
    if not isinstance(schema, dict):
        return [], []
    props = schema.get("properties") or {}
    observed = set(body.keys())
    extra = sorted(observed - set(props))
    missing = [field for field in props if field not in observed]
    # 배열 항목(예: BackgroundCheckList.checks[]) 비교
    for field, prop in props.items():
        prop = spec.deref(prop) or {}
        items = body.get(field)
        if prop.get("type") == "array" and isinstance(items, list) and items:
            item_schema = spec.deref(prop.get("items") or {}) or {}
            iprops = set((item_schema.get("properties") or {}).keys())
            if not iprops:
                continue
            seen, miss = set(), set()
            for it in items:
                if isinstance(it, dict):
                    seen |= set(it.keys())
                    miss |= iprops - set(it.keys())
            extra += [f"{field}[].{k}" for k in sorted(seen - iprops)]
            missing += [f"{field}[].{k}" for k in sorted(miss)]
    return extra, missing


def strip_tag(name):
    # 이전 버전이 붙이던 'field(pending)' 태그 제거 (하위 호환)
    return re.sub(r"\(.*\)$", "", name)


def classify_missing(spec: Spec, path_tmpl, method, status, names, api_status=""):
    """관찰되지 않은 필드 이름을 분류한다.
    - required: 명세 schema.required 에 있는데 없음 → '명세 위반 후보' (단정하지 않음)
    - completion_only: pending 응답이고, 명세 설명상 '완료 시에만 존재' 필드 → 명세상 예상된 부재
    - not_observed: required 가 아닌 필드 → '이번 응답에서 관찰되지 않음' (위반 아님)"""
    out = {"required": [], "completion_only": [], "not_observed": []}
    schema = spec.response_schema(path_tmpl, method, status)[1] if spec.doc else None
    required = set((schema or {}).get("required") or [])
    completion_only = spec.completion_only_fields(schema) if isinstance(schema, dict) else set()
    for raw in names:
        name = strip_tag(raw)
        if name in required:
            out["required"].append(name)
        elif api_status == "pending" and name in completion_only:
            out["completion_only"].append(name)
        else:
            out["not_observed"].append(name)
    return out


# ---------------------------------------------------------------------------
# HTTP 클라이언트
# ---------------------------------------------------------------------------
class ApiClient:
    def __init__(self, base_url, key, timeout):
        self.base_url = base_url.rstrip("/")
        self._key = key
        self.timeout = timeout
        self._local = threading.local()

    def _session(self):
        # requests.Session 은 스레드 간 공유를 보장하지 않으므로 스레드별로 둔다
        if not hasattr(self._local, "session"):
            self._local.session = requests.Session()
        return self._local.session

    def call(self, method, path_tmpl, check_id=None, params=None, json_body=None,
             send_key=True, key_override=None):
        path = path_tmpl.replace("{checkId}", quote(str(check_id), safe="")) if check_id else path_tmpl
        headers = {"Accept": "application/json"}
        if send_key:
            headers["X-Candidate-Key"] = key_override if key_override is not None else self._key
        ts = utc_now_iso()
        t0 = time.perf_counter()
        try:
            resp = self._session().request(method, self.base_url + path, headers=headers,
                                           params=params, json=json_body, timeout=self.timeout)
            t1 = time.perf_counter()
        except requests.RequestException as e:
            t1 = time.perf_counter()
            return {"timestamp": ts, "status": None, "exc": type(e).__name__,
                    "latency_ms": (t1 - t0) * 1000, "body": None, "retry_after_header": "",
                    "error": mask_secret(str(e), self._key)[:300], "t_start": t0, "t_end": t1}
        try:
            body = resp.json()
        except ValueError:
            body = None
        error = ""
        if not (200 <= resp.status_code < 300):
            if isinstance(body, dict):
                error = str(body.get("message") or body.get("error") or "")
            else:
                error = (resp.text or "")[:200]
        return {"timestamp": ts, "status": resp.status_code, "exc": None,
                "latency_ms": (t1 - t0) * 1000, "body": body,
                "retry_after_header": resp.headers.get("Retry-After", ""),
                "error": mask_secret(error, self._key)[:300], "t_start": t0, "t_end": t1}


class RunContext:
    def __init__(self, args, spec, client, key):
        self.args = args
        self.spec = spec
        self.client = client
        self._key = key
        self.run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:4]
        # 콘솔/CSV/Markdown 에 남기면 안 되는 실제 값(키, 이름, 생년월일, tenant 류). 에러 메시지 등에 섞이면 가린다.
        self._sensitive = set()
        self._lock = threading.Lock()

    def add_sensitive(self, *values):
        with self._lock:
            for v in values:
                if isinstance(v, str) and len(v.strip()) >= 1:
                    self._sensitive.add(v)

    def mask(self, text):
        text = mask_secret(text, self._key)
        with self._lock:
            # 긴 값부터 치환해야 부분 문자열 충돌이 없다
            for v in sorted(self._sensitive, key=len, reverse=True):
                text = text.replace(v, "***")
        return text


# 응답 body 에서 실제 값을 가려야 하는 필드. 명세에 없는 필드(tenant 등)는 의미를 모르므로 값을 모두 민감 취급한다.
SENSITIVE_RESPONSE_FIELDS = {"firstName", "lastName", "dateOfBirth"}


def collect_sensitive_from_body(ctx, spec_props, body):
    if not isinstance(body, dict):
        return
    for k, v in body.items():
        if k in SENSITIVE_RESPONSE_FIELDS or k not in spec_props:
            if isinstance(v, str):
                ctx.add_sensitive(v)
            elif isinstance(v, dict):
                ctx.add_sensitive(*[x for x in v.values() if isinstance(x, str)])


def build_row(ctx: RunContext, test_type, method, path_tmpl, res, employee_id="", check_id="",
              concurrency="", note="", total_count=""):
    status = res["status"]
    body = res["body"] if isinstance(res["body"], dict) else {}
    is_2xx = status is not None and 200 <= status < 300

    ra_header = res.get("retry_after_header") or ""
    ra_body = body.get("retryAfter", "")
    ra = parse_int(ra_header)
    if ra is None:
        ra = parse_int(ra_body)

    created = body.get("createdAt") if is_2xx else None
    completed = body.get("completedAt") if is_2xx else None
    pms = body.get("processingMs") if is_2xx else None
    sdur = diff_ms(parse_iso(created), parse_iso(completed))
    pdiff = (pms - sdur) if (isinstance(pms, (int, float)) and sdur is not None) else None

    extra, missing = compare_fields(ctx.spec, path_tmpl, method, status, res["body"])
    # 개인정보·tenant 류 값은 정상(2xx) 응답 body 에만 있다. 오류 응답의 message 까지 민감 처리하면
    # 오류 메시지 자체가 '***' 로 가려지므로(명세 스키마가 없는 401 등) 2xx 에서만 수집한다.
    if is_2xx and ctx.spec.doc:
        schema = ctx.spec.response_schema(path_tmpl, method, status)[1] or {}
        collect_sensitive_from_body(ctx, set((schema.get("properties") or {}).keys()), res["body"])

    # 응답 body 자체는 저장하지 않는다. 아래는 식별자/시각/상태/숫자 필드와 '필드 이름' 목록뿐이다.
    return {
        "timestamp": res["timestamp"],
        "runId": ctx.run_id,
        "source": "auto",
        "testType": test_type,
        "method": method,
        "endpoint": path_tmpl,
        "employeeId": (body.get("employeeId") if is_2xx and body.get("employeeId") else employee_id),
        "checkId": (body.get("checkId") if is_2xx and body.get("checkId") else check_id) or "",
        "concurrency": concurrency,
        "httpStatus": str(status) if status is not None else f"EXC:{res['exc']}",
        "latencyMs": fmt_ms(res["latency_ms"]),
        # apiStatus(pending/clear/flagged)는 2xx 응답 body 의 status 만 기록 — HTTP 상태와 섞지 않는다
        "apiStatus": body.get("status", "") if is_2xx else "",
        "retryAfterHeader": ra_header,
        "retryAfterBody": "" if ra_body is None else ra_body,
        "retryAfter": "" if ra is None else ra,
        "processingMs": "" if pms is None else pms,
        "createdAt": created or "",
        "completedAt": completed or "",
        "serverDurationMs": "" if sdur is None else sdur,
        "processingMsDiffMs": "" if pdiff is None else pdiff,
        "specDefinedStatus": ctx.spec.is_defined(path_tmpl, method, status) if status is not None else "",
        "responseFields": ";".join(sorted(res["body"].keys())) if isinstance(res["body"], dict) else "",
        "extraFields": ";".join(extra),
        "missingFields": ";".join(missing),
        "totalCount": total_count,
        "errorMessage": ctx.mask(res.get("error", "")),
        # note 는 스크립트가 만든 고정 문구 + 식별자만 담는다(요청/응답 값 미포함)
        "note": note,
    }


def record(ctx, rows):
    append_rows(MEAS_CSV, MEAS_COLUMNS, rows)
    for r in rows:
        extra = f" apiStatus={r['apiStatus']}" if r["apiStatus"] else ""
        extra += f" retryAfter={r['retryAfter']}" if r["retryAfter"] != "" else ""
        print(f"  [{r['testType']}] {r['method']} {r['endpoint']} -> {r['httpStatus']} "
              f"{r['latencyMs']}ms{extra}")


def retry_wait_seconds(ctx, retry_after):
    """503 수신 시 측정 스크립트가 쉬는 시간. Retry-After 가 있으면 그 값(상한 적용), 없으면 None."""
    if ctx.args.no_wait_on_503 or retry_after in (None, ""):
        return None
    return min(float(retry_after), float(ctx.args.max_retry_wait))


SENSITIVE_ARGS = {"func", "first_name", "last_name", "dob"}


def log_run(ctx, command):
    # 이름/생년월일 인자는 runs.csv 에 남기지 않는다 (키는 애초에 인자로 받지 않음)
    params = {k: v for k, v in vars(ctx.args).items() if k not in SENSITIVE_ARGS}
    params["baseUrl"] = ctx.client.base_url
    params["python"] = platform.python_version()
    params["requests"] = getattr(requests, "__version__", "")
    params["platform"] = platform.platform()
    append_rows(RUNS_CSV, RUN_COLUMNS, [{
        "runId": ctx.run_id, "startedAt": utc_now_iso(), "command": command,
        "params": json.dumps(params, ensure_ascii=False, default=str),
    }])


def compress_sequence(tokens):
    out = []
    for t in tokens:
        if out and out[-1][0] == t:
            out[-1][1] += 1
        else:
            out.append([t, 1])
    return ">".join(t if n == 1 else f"{t}*{n}" for t, n in out)


# ---------------------------------------------------------------------------
# 측정 명령
# ---------------------------------------------------------------------------
def run_lifecycle_once(ctx, employee_id, first_name, last_name, dob, test_type="lifecycle"):
    a = ctx.args
    payload = {"employeeId": employee_id, "firstName": first_name, "lastName": last_name,
               "dateOfBirth": dob}
    ctx.add_sensitive(first_name, last_name, dob)
    res = ctx.client.call("POST", EP_CHECKS, json_body=payload)
    t_post = res["t_start"]
    post_row = build_row(ctx, test_type, "POST", EP_CHECKS, res, employee_id=employee_id)
    record(ctx, [post_row])
    body = res["body"] if isinstance(res["body"], dict) else {}

    lc = {
        "runId": ctx.run_id, "source": "auto", "employeeId": employee_id,
        "checkId": post_row["checkId"], "postTimestamp": post_row["timestamp"],
        "postHttpStatus": post_row["httpStatus"], "postApiStatus": post_row["apiStatus"],
        "postLatencyMs": post_row["latencyMs"],
        "estimatedCompletionSeconds": body.get("estimatedCompletionSeconds", ABSENT) if body else "",
        "createdAt": post_row["createdAt"], "pollCount": 0, "count503": 0,
    }
    seq = [post_row["apiStatus"] or post_row["httpStatus"]]
    check_id = post_row["checkId"]

    if res["status"] != 201 or not check_id:
        lc.update(outcome="error", statusSequence=compress_sequence(seq),
                  note="POST 가 201/checkId 를 반환하지 않아 폴링 생략")
        append_rows(LC_CSV, LC_COLUMNS, [lc])
        return lc

    if post_row["apiStatus"] in FINAL_STATUSES:
        rel = (res["t_end"] - t_post) * 1000
        lc.update(finalStatus=post_row["apiStatus"], outcome="completed",
                  firstFinalObservedMs=fmt_ms(rel), clientObservedMs=fmt_ms(rel),
                  completedAt=post_row["completedAt"], processingMs=post_row["processingMs"],
                  serverDurationMs=post_row["serverDurationMs"],
                  processingMsDiffMs=post_row["processingMsDiffMs"],
                  statusSequence=compress_sequence(seq), note="POST 응답이 즉시 최종 상태")
        # POST 응답에는 completedAt/processingMs 가 없으므로 GET 1회로 서버 시각을 확보한다
        if not lc.get("serverDurationMs"):
            fetch_final_times(ctx, lc, check_id, employee_id, test_type)
        append_rows(LC_CSV, LC_COLUMNS, [lc])
        return lc

    last_pending = (res["t_end"] - t_post) * 1000 if post_row["apiStatus"] == "pending" else None
    return poll_until_final(ctx, lc, check_id, employee_id, t_post, seq, last_pending, test_type,
                            client_timing=True)


def fetch_final_times(ctx, lc, check_id, employee_id, test_type, note="post-final fetch", attempts=3):
    """이미 최종 상태인 checkId 를 GET 해서 createdAt/completedAt/processingMs 를 lc 에 채운다.
    일시적 실패(5xx/예외)면 Retry-After(없으면 poll-interval) 만큼 쉬고 최대 attempts 회. 실패하면 비워 둔다."""
    for i in range(attempts):
        g = ctx.client.call("GET", EP_CHECK, check_id=check_id)
        row = build_row(ctx, test_type, "GET", EP_CHECK, g, employee_id=employee_id, check_id=check_id,
                        note=f"{note} #{i + 1}")
        record(ctx, [row])
        if g["status"] == 200 and row["completedAt"]:
            lc.update(createdAt=row["createdAt"] or lc.get("createdAt", ""), completedAt=row["completedAt"],
                      processingMs=row["processingMs"], serverDurationMs=row["serverDurationMs"],
                      processingMsDiffMs=row["processingMsDiffMs"])
            return True
        if g["status"] is not None and g["status"] < 500:
            return False
        w = retry_wait_seconds(ctx, row["retryAfter"])
        time.sleep(w if w is not None else ctx.args.poll_interval)
    return False


def poll_until_final(ctx, lc, check_id, employee_id, t_ref, seq, last_pending, test_type,
                     client_timing, note_prefix=""):
    """checkId 를 최종 상태(clear/flagged)까지 폴링하고 lifecycles.csv 에 1행 기록.
    - 최종 상태 미도달(max-wait 초과) → outcome=timeout (미완료, 처리시간 통계 제외)
    - Ctrl+C 중단 → outcome=incomplete 행을 남기고 예외를 다시 던짐 → 이후 `resume` 으로 재개 가능
    - client_timing=False (resume): POST 시점을 모르므로 클라이언트 관측 처리시간은 기록하지 않음"""
    a = ctx.args
    deadline = time.perf_counter() + a.max_wait if not client_timing else t_ref + a.max_wait
    next_sleep = a.poll_interval if client_timing else 0
    poll_count, count503, retry_values = 0, 0, []
    final_row, first_final, outcome, note = None, None, "timeout", ""

    def finish():
        lc.update(pollCount=poll_count, count503=count503, retryAfterValues=";".join(retry_values),
                  statusSequence=compress_sequence(seq), outcome=outcome, note=(note_prefix + note).strip("; "))
        if client_timing:
            lc.update(lastPendingObservedMs=fmt_ms(last_pending) if last_pending is not None else "",
                      firstFinalObservedMs=fmt_ms(first_final) if first_final is not None else "",
                      clientObservedMs=fmt_ms(first_final) if first_final is not None else "")
        if final_row:
            lc.update(finalStatus=final_row["apiStatus"],
                      createdAt=final_row["createdAt"] or lc.get("createdAt", ""),
                      completedAt=final_row["completedAt"], processingMs=final_row["processingMs"],
                      serverDurationMs=final_row["serverDurationMs"],
                      processingMsDiffMs=final_row["processingMsDiffMs"])
        append_rows(LC_CSV, LC_COLUMNS, [lc])
        print(f"  => {employee_id} checkId={check_id} outcome={outcome} final={lc.get('finalStatus', '')} "
              f"serverDurationMs={lc.get('serverDurationMs', '')} polls={poll_count} 503={count503}")

    try:
        while True:
            if time.perf_counter() + next_sleep > deadline:
                note = f"max-wait {a.max_wait}s 내 최종 상태 미관측 — 미완료(처리시간 통계 제외)"
                break
            time.sleep(next_sleep)
            g = ctx.client.call("GET", EP_CHECK, check_id=check_id)
            poll_count += 1
            row = build_row(ctx, test_type, "GET", EP_CHECK, g, employee_id=employee_id,
                            check_id=check_id, note=f"{note_prefix}poll #{poll_count}")
            record(ctx, [row])
            rel = (g["t_end"] - t_ref) * 1000
            next_sleep = a.poll_interval
            st = g["status"]
            if st == 200:
                api = row["apiStatus"]
                seq.append(api or "200(no-status)")
                if api in FINAL_STATUSES:
                    final_row, first_final, outcome = row, rel, "completed"
                    break
                if api == "pending":
                    last_pending = rel
            elif st == 503:
                count503 += 1
                seq.append("503")
                if row["retryAfter"] != "":
                    retry_values.append(str(row["retryAfter"]))
                w = retry_wait_seconds(ctx, row["retryAfter"])
                if w is not None:
                    next_sleep = w
            elif st is None:
                seq.append("EXC")
            elif 400 <= st < 500:
                seq.append(str(st))
                outcome, note = "error", f"폴링 중 HTTP {st} — 재시도하지 않고 중단"
                break
            else:
                seq.append(str(st))
    except KeyboardInterrupt:
        outcome, note = "incomplete", "사용자 중단 — `resume` 명령으로 재개 가능, 처리시간 통계 제외"
        finish()
        raise
    finish()
    return lc


def cmd_lifecycle(ctx):
    a = ctx.args
    log_run(ctx, "lifecycle")
    print(f"[lifecycle] runId={ctx.run_id} samples={a.samples} poll-interval={a.poll_interval}s "
          f"max-wait={a.max_wait}s")
    for i in range(a.samples):
        # 시드 직원 이력을 오염시키지 않도록 측정 전용 합성 employeeId 사용
        emp = f"LC-{ctx.run_id}-{i + 1:03d}"
        print(f"[lifecycle] sample {i + 1}/{a.samples} employeeId={emp}")
        run_lifecycle_once(ctx, emp, "Measure", "Lifecycle", "1990-01-01")
        if i < a.samples - 1:
            time.sleep(a.sample_gap)


def latest_lifecycles():
    """lifecycles.csv 는 append-only 이므로 같은 checkId 가 여러 번 나올 수 있다(중단 → resume).
    checkId 별로 '가장 마지막 행'을 현재 상태로 본다."""
    latest = {}
    for r in read_rows(LC_CSV):
        if r.get("source") != "auto":
            continue
        key = r.get("checkId") or f"__nocheck__{len(latest)}"
        latest[key] = r
    return list(latest.values())


def unfinished_lifecycle_checks():
    """최종 상태에 도달하지 못한 lifecycle checkId 목록.
    - lifecycles.csv 최신 행이 timeout / incomplete 인 건
    - POST 는 기록됐지만 lifecycles.csv 행이 아예 없는 건(프로세스 강제 종료 등)"""
    state = {r["checkId"]: r for r in latest_lifecycles() if r.get("checkId")}
    posts = [r for r in read_rows(MEAS_CSV)
             if r.get("source") == "auto" and r.get("testType") == "lifecycle" and r.get("method") == "POST"
             and r.get("httpStatus") == "201" and r.get("checkId")]
    out = []
    for p in posts:
        s = state.get(p["checkId"])
        if s is None or s.get("outcome") in ("timeout", "incomplete"):
            out.append((p, s))
    return out


def cmd_resume(ctx):
    log_run(ctx, "resume")
    if ctx.args.backfill:
        # POST 즉시 최종이었던 건: 최종 상태는 이미 관측됨. 서버 시각(completedAt 등)만 GET 1회로 보충한다.
        targets = [r for r in latest_lifecycles()
                   if r.get("outcome") == "completed" and r.get("checkId") and not r.get("serverDurationMs")]
        if ctx.args.check_id:
            targets = [r for r in targets if r["checkId"] in set(ctx.args.check_id)]
        print(f"[resume --backfill] runId={ctx.run_id} 서버 시각 미확보 completed 건 {len(targets)}건")
        for r in targets:
            lc = dict(r)
            ok = fetch_final_times(ctx, lc, r["checkId"], r["employeeId"], "lifecycle", note="backfill")
            lc["note"] = ((r.get("note") or "") + f"; backfill(runId {ctx.run_id}): "
                          + ("GET 으로 서버 시각 확보" if ok else "서버 시각 확보 실패")).strip("; ")
            append_rows(LC_CSV, LC_COLUMNS, [lc])
            print(f"  => {r['checkId']} serverDurationMs={lc.get('serverDurationMs', '')}")
            time.sleep(ctx.args.sample_gap)
        return
    targets = unfinished_lifecycle_checks()
    if ctx.args.check_id:
        targets = [(p, s) for p, s in targets if p["checkId"] in set(ctx.args.check_id)]
    print(f"[resume] runId={ctx.run_id} 미완료 checkId {len(targets)}건")
    for p, prev in targets:
        cid = p["checkId"]
        print(f"[resume] {cid} (원 runId {p['runId']}, 이전 상태 {prev.get('outcome') if prev else 'lifecycle 행 없음'})")
        lc = {
            "runId": ctx.run_id, "source": "auto", "employeeId": p["employeeId"], "checkId": cid,
            "postTimestamp": p["timestamp"], "postHttpStatus": p["httpStatus"], "postApiStatus": p["apiStatus"],
            "postLatencyMs": p["latencyMs"],
            "estimatedCompletionSeconds": ABSENT if "estimatedCompletionSeconds" in split_list(p["missingFields"]) else "",
            "createdAt": p["createdAt"],
        }
        # 처리시간은 서버 시각(completedAt − createdAt)으로 계산되므로 재개해도 유효하다.
        # 클라이언트 관측 처리시간은 POST 이후 연속 관측이 끊겼으므로 기록하지 않는다.
        poll_until_final(ctx, lc, cid, p["employeeId"], time.perf_counter(), ["resume"], None, "lifecycle",
                         client_timing=False, note_prefix=f"resume(원 runId {p['runId']}); ")
        time.sleep(ctx.args.sample_gap)


def resolve_completed_check_id(ctx):
    if getattr(ctx.args, "check_id", None):
        return ctx.args.check_id
    done = [r for r in latest_lifecycles() if r.get("outcome") == "completed" and r.get("checkId")]
    if done:
        cid = done[-1]["checkId"]
        print(f"  완료된 checkId 사용 (lifecycles.csv 최신): {cid}")
        return cid
    print("  완료된 checkId 가 없어 lifecycle 1건을 먼저 실행합니다.")
    lc = run_lifecycle_once(ctx, f"LC-{ctx.run_id}-PRE", "Measure", "Lifecycle", "1990-01-01",
                            test_type="lifecycle")
    if lc.get("outcome") != "completed":
        sys.exit("완료된 checkId 를 확보하지 못했습니다. --check-id 로 직접 지정하세요.")
    return lc["checkId"]


def cmd_latency(ctx):
    a = ctx.args
    log_run(ctx, "latency")
    cid = resolve_completed_check_id(ctx)
    max_requests = a.max_requests or a.samples * 2
    print(f"[latency] runId={ctx.run_id} checkId={cid} 목표 200 표본={a.samples} "
          f"최대 요청={max_requests} interval={a.interval}s")
    ok, sent = 0, 0
    while ok < a.samples and sent < max_requests:
        res = ctx.client.call("GET", EP_CHECK, check_id=cid)
        sent += 1
        row = build_row(ctx, "latency", "GET", EP_CHECK, res, check_id=cid, note=f"req #{sent}")
        record(ctx, [row])
        if res["status"] == 200:
            ok += 1
        wait = a.interval
        if res["status"] == 503:
            w = retry_wait_seconds(ctx, row["retryAfter"])
            if w is not None:
                wait = w
        time.sleep(wait)
    if ok < a.samples:
        print(f"  [주의] 200 표본 {ok}/{a.samples} — 최대 요청 수 {max_requests} 도달")


def list_checks(ctx, employee_id, test_type, note, attempts=3):
    """목록 조회. 전/후 totalCount 비교가 duplicate 판정의 핵심이므로 503/예외 시 Retry-After 만큼 쉬고 재조회.
    모든 시도는 각각 CSV 에 기록되며, 보고서는 note 가 before/after 로 시작하는 200 응답만 비교에 사용한다."""
    row, ids = None, []
    for attempt in range(1, attempts + 1):
        res = ctx.client.call("GET", EP_CHECKS, params={"employeeId": employee_id})
        body = res["body"] if isinstance(res["body"], dict) else {}
        checks = body.get("checks") if isinstance(body.get("checks"), list) else []
        ids = [c.get("checkId", "") for c in checks if isinstance(c, dict)]
        total = body.get("totalCount", "") if res["status"] == 200 else ""
        row = build_row(ctx, test_type, "GET", EP_CHECKS, res, employee_id=employee_id,
                        note=f"{note}; attempt={attempt}; listedChecks={len(ids)}; listCheckIds={'|'.join(ids)}",
                        total_count=total)
        record(ctx, [row])
        if res["status"] is not None and res["status"] < 500:
            break
        wait = retry_wait_seconds(ctx, row["retryAfter"])
        time.sleep(wait if wait is not None else ctx.args.interval)
    return row, ids


def cmd_duplicate(ctx):
    a = ctx.args
    emp = a.employee
    seed = SEED_EMPLOYEES.get(emp)
    if a.first_name and a.last_name:
        last, first = a.last_name, a.first_name
    elif seed:
        last, first = split_korean_name(seed[0])
    else:
        sys.exit(f"{emp} 는 시드 데이터에 없습니다. --first-name/--last-name/--dob 를 지정하세요.")
    dob = a.dob or (seed[1] if seed else None)
    if not dob:
        sys.exit(f"{emp} 의 생년월일이 없습니다(시드상 미확인). --dob 로 지정하거나 "
                 "errors 명령으로 생년월일 누락 시 동작을 측정하세요.")
    log_run(ctx, "duplicate")
    ctx.add_sensitive(first, last, dob)
    # 이름/생년월일 값은 출력하지 않는다(시드 데이터에서 읽어 요청 본문에만 사용)
    print(f"[duplicate] runId={ctx.run_id} employeeId={emp} count={a.count}")
    payload = {"employeeId": emp, "firstName": first, "lastName": last, "dateOfBirth": dob}
    list_checks(ctx, emp, "duplicate-list", "before", attempts=a.list_attempts)
    for i in range(a.count):
        res = ctx.client.call("POST", EP_CHECKS, json_body=payload)
        record(ctx, [build_row(ctx, "duplicate", "POST", EP_CHECKS, res, employee_id=emp,
                               note=f"attempt {i + 1}/{a.count}")])
        time.sleep(a.interval)
    list_checks(ctx, emp, "duplicate-list", "after", attempts=a.list_attempts)


def cmd_list(ctx):
    """GET /background-checks?employeeId= 만 다시 조회(레코드 생성 없음).
    duplicate 의 전/후 목록 조회가 실패했을 때, 생성된 checkId 들이 이력에 모두 나타나는지 재확인하는 용도."""
    a = ctx.args
    log_run(ctx, "list")
    print(f"[list] runId={ctx.run_id} employeeId={a.employee}")
    row, ids = list_checks(ctx, a.employee, "list-check", "recheck", attempts=a.list_attempts)
    print(f"  => HTTP {row['httpStatus']} totalCount={row['totalCount']} listed={len(ids)}")


def cmd_concurrency(ctx):
    a = ctx.args
    levels = [int(x) for x in a.levels.split(",") if x.strip()]
    if not levels or any(l < 1 for l in levels):
        sys.exit("--levels 는 1 이상의 정수 목록이어야 합니다.")
    if levels != sorted(levels):
        sys.exit("외부 API 보호를 위해 --levels 는 오름차순(점진적 증가)이어야 합니다.")
    if max(levels) > 20 and not a.force:
        sys.exit("동시성 20 초과는 --force 가 필요합니다 (외부 API 과부하 방지).")
    is_post = a.method == "post"
    if is_post:
        # POST 는 실제 레코드를 생성하므로 단계·총 요청 수를 제한한다
        total = a.requests_per_level * len(levels)
        limit = min(a.post_limit, POST_CONCURRENCY_HARD_LIMIT)
        if max(levels) > POST_CONCURRENCY_MAX_LEVEL:
            sys.exit(f"POST 동시성은 단계당 최대 {POST_CONCURRENCY_MAX_LEVEL} 까지만 허용합니다.")
        if total > limit:
            sys.exit(f"POST 총 요청 {total}건이 상한 {limit}건을 넘습니다. "
                     f"--requests-per-level 또는 --levels 를 줄이세요 (절대 상한 {POST_CONCURRENCY_HARD_LIMIT}).")
    log_run(ctx, "concurrency")
    # 기본은 기존(완료된) checkId 에 대한 GET — 새 레코드를 만들지 않는다
    cid = resolve_completed_check_id(ctx)
    print(f"[concurrency] runId={ctx.run_id} method={a.method.upper()} checkId(GET/probe)={cid} levels={levels} "
          f"requests/level={a.requests_per_level}")

    def one_request(level, i):
        if is_post:
            emp = f"CC-{ctx.run_id}-L{level}-{i + 1:02d}"
            return emp, ctx.client.call("POST", EP_CHECKS, json_body={
                "employeeId": emp, "firstName": "Measure", "lastName": "Concurrency", "dateOfBirth": "1990-01-01"})
        return "", ctx.client.call("GET", EP_CHECK, cid)

    def decision_log(info):
        append_rows(RUNS_CSV, RUN_COLUMNS, [{
            "runId": ctx.run_id, "startedAt": utc_now_iso(), "command": "concurrency-decision",
            "params": json.dumps(info, ensure_ascii=False)}])

    prev_ratio503 = None
    baseline_err = None   # 첫 단계(가장 낮은 동시성)의 5xx/예외 비율 = 이 API 의 평소 실패율
    method_u, ep = ("POST", EP_CHECKS) if is_post else ("GET", EP_CHECK)
    for li, level in enumerate(levels):
        print(f"[concurrency] level {level} ...")
        with ThreadPoolExecutor(max_workers=level) as ex:
            futures = [ex.submit(one_request, level, i) for i in range(a.requests_per_level)]
            results = [f.result() for f in futures]
        rows = [build_row(ctx, "concurrency", method_u, ep, r, employee_id=emp,
                          check_id="" if is_post else cid, concurrency=level, note=f"level {level}")
                for emp, r in results]
        record(ctx, rows)
        n = len(results)
        n503 = sum(1 for _, r in results if r["status"] == 503)
        errors = sum(1 for _, r in results if r["status"] is None or r["status"] >= 500)
        ratio503, err_ratio = n503 / n, errors / n
        ras = [x for x in (parse_int(r["retryAfter"]) for r in rows if r["retryAfter"] != "") if x is not None]
        print(f"  level {level}: 503 {n503}/{n}, 5xx/예외 {errors}/{n} ({err_ratio * 100:.1f}%)"
              + (f", Retry-After 최대 {max(ras)}s" if ras else ""))
        info = {"level": level, "requests": n, "ratio503": round(ratio503, 3),
                "prevRatio503": None if prev_ratio503 is None else round(prev_ratio503, 3),
                "errorRatio": round(err_ratio, 3), "retryAfterMax": max(ras) if ras else None}

        if baseline_err is None:
            baseline_err = err_ratio
        # 중단 기준: 절대 상한(--abort-error-ratio) 또는 '첫 단계 대비 증가폭'(--abort-margin) 초과.
        # 이 API 는 동시성 1 에서도 5xx 가 흔하므로 평소 실패율을 과부하로 오판하지 않도록 기준선과 비교한다.
        abort_at = min(a.abort_error_ratio, baseline_err + a.abort_margin)
        info.update(baselineErrorRatio=round(baseline_err, 3), abortThreshold=round(abort_at, 3))
        if li == len(levels) - 1:
            decision_log(dict(info, decision="마지막 단계"))
            break
        if li > 0 and err_ratio >= abort_at:
            print(f"  5xx/예외 비율 {err_ratio * 100:.0f}% ≥ 기준 {abort_at * 100:.0f}% "
                  f"(첫 단계 {baseline_err * 100:.0f}% + {a.abort_margin * 100:.0f}%p 또는 상한) -> 상위 단계 중단")
            decision_log(dict(info, decision="중단: 오류 비율이 기준선 대비 기준 초과"))
            break
        if li == 0 and err_ratio >= a.abort_error_ratio:
            print(f"  첫 단계부터 5xx/예외 비율 {err_ratio * 100:.0f}% ≥ 상한 {a.abort_error_ratio * 100:.0f}% -> 중단")
            decision_log(dict(info, decision="중단: 첫 단계 오류 비율 상한 초과"))
            break

        # 단계 사이 대기: 기본 pause, Retry-After 를 받았다면 그 값을 존중(상한 --max-retry-wait)
        pause = a.pause
        w = retry_wait_seconds(ctx, max(ras)) if ras else None
        if w is not None:
            pause = max(pause, w)
        print(f"  다음 단계 판단 전 {pause:.0f}s 대기")
        time.sleep(pause)
        info["waitedSeconds"] = pause

        # Retry-After 는 위에서 대기로 존중했다. 동시성 1 에서도 Retry-After 가 오므로 그것만으로 중단하지 않는다.
        # 503 비율이 이전 단계보다 증가했다면: 대기 후 확인 GET(최대 --probe-attempts 회)으로 회복 여부를 보고 결정.
        # 평소에도 5xx 가 흔하므로 확인 요청 1회 실패만으로 '회복 안 됨'이라 판단하지 않는다(한 번이라도 5xx 가 아니면 회복).
        increased = prev_ratio503 is not None and ratio503 > prev_ratio503
        if increased:
            statuses = []
            for pi in range(a.probe_attempts):
                probe = ctx.client.call("GET", EP_CHECK, cid)
                prow = build_row(ctx, "concurrency-probe", "GET", EP_CHECK, probe, check_id=cid,
                                 concurrency=level, note=f"after level {level} wait {pause:.0f}s probe {pi + 1}")
                record(ctx, [prow])
                statuses.append(prow["httpStatus"])
                if probe["status"] is not None and probe["status"] < 500:
                    break
                pw = retry_wait_seconds(ctx, prow["retryAfter"])
                time.sleep(pw if pw is not None else 1.0)
            info["probeStatus"] = ">".join(statuses)
            recovered = any(s.isdigit() and int(s) < 500 for s in statuses)
            if not recovered:
                print(f"  503 증가 + 확인 요청 {len(statuses)}회 모두 실패({info['probeStatus']}) -> 상위 단계 중단")
                decision_log(dict(info, decision="중단: 503 증가 후 확인 요청도 모두 실패"))
                break
            decision_log(dict(info, decision="진행: 503 증가했으나 대기 후 확인 요청 정상"))
        else:
            decision_log(dict(info, decision="진행: 503 비율 증가 없음" + (" (Retry-After 만큼 대기함)" if ras else "")))
        prev_ratio503 = ratio503


def cmd_errors(ctx):
    log_run(ctx, "errors")
    fake = "CHK-00000000-0000-0000-0000-000000000000"
    cases = [
        ("GET", EP_CHECK, dict(check_id=fake), "존재하지 않는 checkId (명세: 404)"),
        ("GET", EP_CHECK, dict(check_id=fake, send_key=False), "X-Candidate-Key 헤더 없음 (명세 설명: 401)"),
        ("GET", EP_CHECK, dict(check_id=fake, key_override="invalid-measurement-key"),
         "잘못된 키 (명세 설명: 401)"),
        ("GET", EP_CHECKS, dict(), "employeeId 쿼리 누락 (명세: 400)"),
        # 이름은 합성값을 쓴다 (실제 직원 이름을 측정 요청에 쓰지 않음)
        ("POST", EP_CHECKS, dict(json_body={"employeeId": f"ERR-{ctx.run_id}-NODOB",
                                            "firstName": "Measure", "lastName": "Error"}),
         "dateOfBirth 누락 — EMP-007 상황 (명세: 400). 수락되면 레코드가 생성됨"),
        ("POST", EP_CHECKS, dict(json_body={"employeeId": f"ERR-{ctx.run_id}-EMPTYDOB",
                                            "firstName": "Measure", "lastName": "Error",
                                            "dateOfBirth": ""}),
         "dateOfBirth 빈 문자열 (명세상 format: date). 수락되면 레코드가 생성됨"),
    ]
    print(f"[errors] runId={ctx.run_id}")
    for method, ep, kw, note in cases:
        res = ctx.client.call(method, ep, **kw)
        emp = (kw.get("json_body") or {}).get("employeeId", "")
        record(ctx, [build_row(ctx, "errors", method, ep, res, employee_id=emp,
                               check_id=kw.get("check_id", ""), note=note)])
        time.sleep(1)


# ---------------------------------------------------------------------------
# 수동 관찰 (지원자가 직접 호출해 확인한 1차 결과) — data/manual_observations.csv 에 별도 기록
# 기록된 사실만 담는다. 요청별 latency / 요청 시각 / 관찰 사이의 HTTP 요청 횟수는 기록되지 않았으므로 만들지 않는다.
# ---------------------------------------------------------------------------
MANUAL_OBSERVATIONS = [
    {"observationId": "M-1", "observedOrder": "1", "method": "POST", "endpoint": EP_CHECKS,
     "employeeId": "EMP-001", "httpStatus": "201", "apiStatus": "pending",
     "createdAt": "2026-09-22T04:46:16.792Z", "notObservedFields": "estimatedCompletionSeconds",
     "note": "checkId 발급 확인(값은 기록하지 않음). estimatedCompletionSeconds 는 관찰되지 않음. "
             "그 밖의 필드 구성은 기록하지 않음"},
    {"observationId": "M-2", "observedOrder": "2", "method": "GET", "endpoint": EP_CHECK,
     "employeeId": "EMP-001", "httpStatus": "503", "retryAfterBody": "30",
     "note": "위 checkId GET 조회 중 503. 응답 body 에서 retryAfter=30 확인. "
             "Retry-After 헤더 확인 여부는 기록하지 않음. M-1 과 M-2 사이 요청 횟수는 기록하지 않음"},
    {"observationId": "M-3", "observedOrder": "3", "method": "GET", "endpoint": EP_CHECK,
     "employeeId": "EMP-001", "httpStatus": "200", "apiStatus": "clear",
     "createdAt": "2026-09-22T04:46:16.792Z", "completedAt": "2026-09-22T04:46:54.720Z",
     "processingMs": "37928", "observedExtraFields": "processingMs;tenant;tenantEmployee",
     "note": "동일 checkId 재요청 → 200 clear. processingMs(37928)와 completedAt−createdAt(37.928s) 일치. "
             "명세에 없는 필드 processingMs, tenant, tenantEmployee 존재(값은 기록하지 않음). "
             "M-2 이후 대기 시간·요청 횟수는 기록하지 않음"},
]


def ensure_manual_seed(verbose=False):
    """manual_observations.csv 가 없으면 생성하고, 이전 버전이 measurements.csv / lifecycles.csv 에
    섞어 넣은 수동 행(source=manual)은 제거한다(자동 측정 통계와 분리)."""
    changed = []
    if not MANUAL_CSV.exists():
        append_rows(MANUAL_CSV, MANUAL_COLUMNS, MANUAL_OBSERVATIONS)
        changed.append("manual_observations.csv 생성")
    for path, cols in ((MEAS_CSV, MEAS_COLUMNS), (LC_CSV, LC_COLUMNS)):
        rows = read_rows(path)
        keep = [r for r in rows if r.get("source") != "manual"]
        if len(keep) != len(rows):
            with _csv_lock:
                tmp = path.with_suffix(".tmp")
                with tmp.open("w", newline="", encoding="utf-8-sig") as f:
                    wr = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
                    wr.writeheader()
                    for r in keep:
                        wr.writerow({c: r.get(c, "") or "" for c in cols})
                tmp.replace(path)
            changed.append(f"{path.name} 에서 수동 행 {len(rows) - len(keep)}건 제거")
    if verbose or changed:
        print("수동 관찰 정리: " + ("; ".join(changed) if changed else "변경 없음"))


# ---------------------------------------------------------------------------
# MEASUREMENTS.md 생성 (CSV 만 사용)
# ---------------------------------------------------------------------------
MANUAL_START = "<!-- MANUAL:START -->"
MANUAL_END = "<!-- MANUAL:END -->"
DEFAULT_MANUAL_BLOCK = f"""{MANUAL_START}
## 11. 최종 결정 (지원자 직접 기입 — `report` 재생성 시 이 블록은 보존됨)

> 위 9장의 근거 수치(n 포함)를 인용해 작성한다. 근거가 부족하면 TBD 로 둔다.

| 항목 | 결정값 | 근거 (섹션·수치·n) |
|---|---|---|
| GET 타임아웃 | TBD | |
| POST 타임아웃 | TBD | |
| 재시도 횟수 | TBD | |
| 재시도 간격 | TBD | |
| 폴링 주기 (첫 폴링 시점 포함) | TBD | |
| 폴링 중단 조건 (최대 대기) | TBD | |
| 재시도 중단 조건 | TBD | |
{MANUAL_END}"""


SUMMARY_START = "<!-- SUMMARY:START -->"
SUMMARY_END = "<!-- SUMMARY:END -->"
DEFAULT_SUMMARY_BLOCK = f"""{SUMMARY_START}
## 0. 한눈에 보기 (지원자 직접 기입 — `report` 재생성 시 이 블록은 보존됨)

> 11장에서 정한 값과 그 근거를 한 표로 모은다. 결정은 자동으로 채우지 않는다.

| 항목 | 결정값 | 실측 근거 | 상세 |
|---|---|---|---|
| (TBD) | TBD | | |
{SUMMARY_END}"""


def _extract_block(start, end, default):
    """MEASUREMENTS.md 에서 지원자가 직접 쓴 블록을 보존한다.
    블록이 여러 개면 기본 템플릿이 아닌 마지막 것을 남긴다(템플릿 뒤에 덧쓴 경우)."""
    if not REPORT_MD.exists():
        return default
    text = REPORT_MD.read_text(encoding="utf-8")
    blocks, pos = [], 0
    while True:
        s = text.find(start, pos)
        if s == -1:
            break
        e = text.find(end, s)
        if e == -1:
            break
        blocks.append(text[s:e + len(end)])
        pos = e + len(end)
    if not blocks:
        return default
    edited = [b for b in blocks if b.strip() != default.strip()]
    return edited[-1] if edited else blocks[-1]


def extract_summary_block():
    return _extract_block(SUMMARY_START, SUMMARY_END, DEFAULT_SUMMARY_BLOCK)


def extract_manual_block():
    return _extract_block(MANUAL_START, MANUAL_END, DEFAULT_MANUAL_BLOCK)


def status_code_key(code):
    return (0, int(code)) if str(code).isdigit() else (1, str(code))


def generate_report(spec: Spec):
    meas = read_rows(MEAS_CSV)
    runs = read_rows(RUNS_CSV)
    # 자동 측정 통계는 source=auto 만 사용. 수동 관찰은 manual_observations.csv 에서 따로 읽어 부록 A 에만 표시.
    auto = [r for r in meas if r.get("source") == "auto"]
    lc_auto = latest_lifecycles()          # checkId 별 최신 행 (중단 → resume 반영)
    lc_rows_total = len([r for r in read_rows(LC_CSV) if r.get("source") == "auto"])
    manual_obs = read_rows(MANUAL_CSV)
    decisions = [r for r in runs if r.get("command") in ("concurrency-decision", "concurrency-abort")]
    runs = [r for r in runs if r.get("command") not in ("concurrency-decision", "concurrency-abort")]
    L = []
    w = L.append

    MARK_ENV = len(L)
    # ---------- 1. 목적 및 환경 ----------
    def params_of(r):
        try:
            p = json.loads(r.get("params") or "{}")
            return p if isinstance(p, dict) else {}
        except ValueError:
            return {}

    base_urls = sorted({params_of(r).get("baseUrl", "") for r in runs} - {""})
    pys = sorted({params_of(r).get("python", "") for r in runs} - {""})
    ts = sorted(r["timestamp"] for r in auto if r.get("timestamp"))
    w("# MEASUREMENTS — Background Check API 실측 결과")
    w("")
    w(f"> 이 문서는 `measure_background_api.py report` 가 `data/*.csv` 만 읽어 자동 생성한다. "
      f"생성 시각: {utc_now_iso()}")
    w("> 측정하지 않은 값은 기재하지 않으며, 표본이 없거나 부족하면 `TBD` / `표본 부족` 으로 표시한다.")
    w("")
    w("## 0. 측정 개요")
    w("")
    w("- **목적**: swagger.yaml 에 적히지 않은 실제 거동(지연, 오류 분포, 처리시간, 503/Retry-After, "
      "중복 요청, 동시성)을 측정해 타임아웃·재시도·폴링 정책의 근거로 사용한다.")
    w(f"- **대상 API**: {', '.join(base_urls) if base_urls else (spec.server_url() or FALLBACK_BASE_URL) + ' (자동 측정 이력 없음)'}")
    w(f"- **명세**: `{spec.path.name}` version {spec.version or '(읽기 실패)'}"
      + (f" — {spec.error}" if spec.error else ""))
    w(f"- **측정 기간 (자동 측정, UTC)**: {ts[0] + ' ~ ' + ts[-1] if ts else 'TBD (자동 측정 없음)'}")
    w(f"- **측정 클라이언트 Python**: {', '.join(pys) if pys else 'TBD'} / 측정 위치: 단일 클라이언트(지원자 PC)")
    w(f"- **원시 데이터**: `data/measurements.csv` {len(auto)}행(요청 1건 = 1행), "
      f"`data/lifecycles.csv` {lc_rows_total}행 → checkId 기준 {len(lc_auto)}건(중단·재개 시 최신 행 사용)")
    w("- **저장 범위**: 요청/응답 본문은 저장하지 않는다. 식별자·시각·상태·숫자 필드와 필드 *이름* 목록만 기록하며, "
      "인증키·이름·생년월일·tenant/tenantEmployee 의 실제 값은 CSV·콘솔·이 문서에 남기지 않는다.")
    w("- **지연 측정 방식**: 클라이언트에서 요청 직전~응답 수신 완료까지 `time.perf_counter()` 로 측정한 "
      "wall-clock(ms). 네트워크 왕복 포함.")
    w("- **백분위수 계산**: nearest-rank — 정렬된 표본 x₁..xₙ 에서 pQ = x⌈Q·n⌉ (보간 없음, 항상 실제 관측값). "
      "예) n=100 이면 p50 = 50번째, p95 = 95번째, p99 = 99번째로 작은 값. 모든 통계 옆에 n 을 표기하고, "
      "p95 는 n<20, p99 는 n<100 이면 `표본 부족` 을 붙인다.")
    w("- **p99 불확실성**: p99 는 사실상 상위 1~2개 관측값으로 정해진다. 1-1 의 95% 신뢰구간(분포 가정 없는 "
      "순서통계량 방법)으로 보면 p99 의 상한은 관측 최대값을 넘어 **데이터로 제한되지 않는다**. "
      "따라서 p99 는 '관측된 꼬리'로만 해석하고 단일 확정값으로 쓰지 않는다.")
    w("- **용어 구분**: `httpStatus`(200/201/503 …)는 전송 계층 결과, `apiStatus`(pending/clear/flagged)는 "
      "2xx 응답 body 의 업무 상태다. 두 값은 별도 컬럼으로 기록하며 섞지 않는다.")
    w(f"- **수동 관찰**: 첫 회 손으로 확인한 {len(manual_obs)}건은 `data/manual_observations.csv` 에 따로 남겨 두었고, "
      "응답시간 기록이 없어 **이 문서의 어떤 통계에도 포함하지 않는다**. 모든 수치는 자동 측정만으로 계산한다.")
    w("- **이 문서의 구성**: 과제 설명서 [제출물 2]가 물은 순서대로 1~6장에 실측을 싣고, 그 값으로 정한 운영값을 "
      "7~12장에 적는다. 13장은 이 측정으로 말할 수 없는 것이다. 요구되지 않은 집계는 원시 CSV 에 남아 있어 "
      "`report` 를 고치면 다시 뽑을 수 있다.")
    w("")
    if runs:
        rows = []
        for r in runs:
            p = params_of(r)
            # 모든 실행에서 같은 값(poll_interval, max_wait, max_retry_wait 등)은 표에서 뺀다.
            keep = {k: v for k, v in p.items() if k in (
                "samples", "count", "employee", "levels", "requests_per_level",
                "timeout", "abortedAfterLevel")}
            rows.append([r["runId"], r["startedAt"], r["command"],
                         ", ".join(f"{k}={v}" for k, v in keep.items())])
        w("측정 실행 이력 (`data/runs.csv`):")
        w("")
        L.extend(md_table(["runId", "시작(UTC)", "명령", "주요 파라미터"], rows))
    else:
        w("측정 실행 이력: 없음 (자동 측정 미실행)")
    w("")

    MARK_LAT = len(L)
    # ---------- 2. GET 응답 지연 ----------
    get_one = [r for r in auto if r["method"] == "GET" and r["endpoint"] == EP_CHECK]

    def lat(rows):
        return [v for v in (to_float(r["latencyMs"]) for r in rows) if v is not None]

    w("## 1. GET `/background-checks/{checkId}` 응답 지연")
    w("")
    groups = [
        ("latency 테스트 (완료된 checkId 순차 조회)", [r for r in get_one if r["testType"] == "latency"]),
        ("lifecycle 폴링 중 조회", [r for r in get_one if r["testType"] == "lifecycle"]),
        ("동시성 테스트 (전 단계 합산)", [r for r in get_one if r["testType"] == "concurrency"]),
        ("전체 (자동 측정 합산)", get_one),
    ]
    w("### 1-1 HTTP 200 정상 응답 지연")
    w("")
    L.extend(md_table(LAT_HEADERS, [latency_stat_row(lbl, lat([r for r in rows if r["httpStatus"] == "200"]))
                                    for lbl, rows in groups]))
    w("")
    all200 = lat([r for r in get_one if r["httpStatus"] == "200"])
    w(f"백분위수 순위와 95% 신뢰구간 (전체 HTTP 200, n={len(all200)}; 순서통계량 기반, 분포 가정 없음):")
    w("")
    ci_rows = []
    for q in (0.50, 0.95, 0.99):
        n200 = len(all200)
        rank = f"{nearest_rank(n200, q)}번째 / {n200}" if n200 else "TBD"
        ci_rows.append([f"p{int(q * 100)}", rank, stat_cell(all200, q), quantile_ci_cell(all200, q)])
    L.extend(md_table(["통계", "nearest-rank 순위", "값 (ms)", "95% 신뢰구간 (ms, 순위)"], ci_rows))
    w("")
    if all200:
        p50v = percentile_nearest_rank(all200, 0.50)
        p95v = percentile_nearest_rank(all200, 0.95)
        maxv = max(all200)
        w(f"**읽는 법.** 절반은 약 {p50v / 1000:.2f}초 안에 응답했지만(p50 {ms(p50v)}ms), 상위 지연 구간은 "
          f"{ms(p95v)}~{ms(maxv)}ms 까지 늘어났다(n={len(all200)}). 응답이 빠른 쪽과 느린 쪽이 갈리므로 "
          f"**p50 만 보고 타임아웃을 짧게 잡으면 느리지만 정상인 응답을 끊게 된다.** "
          "p99 는 상위 1~2개 관측값에 좌우되고 위 신뢰구간의 상한이 관측 범위를 벗어나므로, "
          "보장값이 아니라 '관측된 꼬리 지연'으로만 해석한다.")
        w("")
    post_rows = [r for r in auto if r["method"] == "POST" and r["endpoint"] == EP_CHECKS]
    # POST 지연은 요구 항목은 아니지만 11장의 POST 타임아웃 근거라서 남긴다.
    w("### 1-2 POST /background-checks 지연 (HTTP 201)")
    w("")
    L.extend(md_table(LAT_HEADERS, [latency_stat_row("POST 201", lat([r for r in post_rows if r["httpStatus"] == "201"]))]))
    exc = [r for r in auto if r["httpStatus"].startswith("EXC:")]
    w("")
    w(f"- 네트워크 예외(타임아웃 포함, `EXC:*`): {ratio(len(exc), len(auto))}"
      + (f" — 종류: {dict(Counter(r['httpStatus'] for r in exc))}" if exc else ""))
    w("")

    MARK_DIST = len(L)
    # ---------- 3. HTTP 상태코드 분포 ----------
    w("## 2. HTTP 상태코드 분포")
    w("")
    w("### 2-1 전체 분포")
    w("")
    w("> 400·401·404 는 대부분 `errors` 명령으로 **일부러 만든 오류 요청**이다(6-8 참조). "
      "정상 사용에서 나온 비율이 아니므로 2-2 의 엔드포인트별 성공률과 함께 본다.")
    w("")
    cnt = Counter(r["httpStatus"] for r in auto)
    if cnt:
        L.extend(md_table(["httpStatus", "건수 / 전체 (비율)"],
                          [[c, ratio(n, len(auto))] for c, n in sorted(cnt.items(), key=lambda kv: status_code_key(kv[0]))]))
    else:
        w("TBD — 자동 측정 없음")
    w("")
    n5xx = sum(v for k, v in cnt.items() if k.isdigit() and int(k) >= 500)
    n4xx = sum(v for k, v in cnt.items() if k.isdigit() and 400 <= int(k) < 500)
    w(f"**읽는 법.** 실제 호출에서 자주 만나는 실패는 500 과 503 이다({ratio(n5xx, len(auto))}). "
      f"4xx 는 {n4xx}건뿐이고 대부분 오류 케이스 시험분이므로(6-8) 정상 사용의 실패율로 읽으면 안 된다. "
      "503 은 응답시간과 body 구성이 다른 **두 종류**로 나뉘므로 하나의 실패 유형으로 다루지 않는다(2-3).")
    w("")
    w("### 2-2 엔드포인트별 성공률")
    w("")
    w("> 오류 케이스 시험(`errors` 명령)으로 일부러 잘못 보낸 요청은 빼고, 정상 입력으로 보낸 요청만 센다.")
    w("")
    normal = [r for r in auto if r["testType"] != "errors"]
    eps = [("GET", EP_CHECK, "조회 상세", ("200",)),
           ("GET", EP_CHECKS, "이력 목록", ("200",)),
           ("POST", EP_CHECKS, "조회 요청", ("201",))]
    erows = []
    for m, ep, label, ok_codes in eps:
        rs = [r for r in normal if r["method"] == m and r["endpoint"] == ep]
        if not rs:
            continue
        ok = sum(1 for r in rs if r["httpStatus"] in ok_codes)
        c = Counter(r["httpStatus"] for r in rs if r["httpStatus"] not in ok_codes)
        erows.append([f"{m} {ep}", label, len(rs), ratio(ok, len(rs)),
                      ", ".join(f"{k} {v}" for k, v in sorted(c.items(), key=lambda kv: status_code_key(kv[0]))) or "없음"])
    if erows:
        L.extend(md_table(["operation", "쓰임", "요청 n", "성공", "그 밖의 응답"], erows))
        w("")
        w("**읽는 법.** 조회 상세는 절반 남짓 성공하지만 **이력 목록은 거의 성공하지 못했다.** "
          "POST 가 시간 초과됐을 때 '방금 건이 실제로 만들어졌는지'를 이 목록으로 확인해야 하는데, "
          "그 확인 자체가 불안정하다는 뜻이다 → 7-2 에서 '확인되지 않으면 추측하지 않고 확인 필요 상태로 둔다'의 근거.")
    else:
        w("TBD — 자동 측정 없음")
    w("")

    MARK_DUP = len(L)
    # ---------- 4. 반복 POST ----------
    w("## 3. 같은 employeeId 로 POST 를 반복하면 어떻게 되는가")
    w("")
    dup_runs = defaultdict(list)
    for r in auto:
        if r["testType"] in ("duplicate", "duplicate-list"):
            dup_runs[r["runId"]].append(r)
    if not dup_runs:
        w("TBD — duplicate 측정 미실행 (`python measure_background_api.py duplicate --employee EMP-001 --count 5`)")
    else:
        rows, findings = [], []
        for run_id, rs in sorted(dup_runs.items()):
            posts = [r for r in rs if r["testType"] == "duplicate"]
            lists = [r for r in rs if r["testType"] == "duplicate-list"]
            # 재시도 중 503 행은 제외하고 200 응답만 전/후 비교에 사용
            before = next((r for r in lists if r["note"].startswith("before") and r["httpStatus"] == "200"), None)
            after = next((r for r in lists if r["note"].startswith("after") and r["httpStatus"] == "200"), None)
            codes = Counter(r["httpStatus"] for r in posts)
            apis = Counter(r["apiStatus"] for r in posts if r["apiStatus"])
            ids = [r["checkId"] for r in posts if r["httpStatus"] in ("200", "201") and r["checkId"]]
            uniq = len(set(ids))
            tb = parse_int(before["totalCount"]) if before else None
            ta = parse_int(after["totalCount"]) if after else None
            delta = (ta - tb) if (tb is not None and ta is not None) else None
            emp = posts[0]["employeeId"] if posts else (before or {}).get("employeeId", "")
            # 'after' 조회가 실패했으면, 이후 `list` 명령으로 다시 조회한 200 응답(list-check)으로 목록 포함 여부만 재확인
            ref = after
            if ref is None:
                last_ts = max((r["timestamp"] for r in rs), default="")
                rechecks = [r for r in auto if r["testType"] == "list-check" and r["employeeId"] == emp
                            and r["httpStatus"] == "200" and r["timestamp"] > last_ts]
                ref = rechecks[-1] if rechecks else None
                if ref:
                    findings.append(f"- runId `{run_id}`: 'after' 목록 조회 실패 → 이후 재조회(runId `{ref['runId']}`, "
                                    f"totalCount {ref['totalCount']})로 목록 포함 여부만 확인. "
                                    "전/후 증가분은 'before' 가 없으므로 계산하지 않음")
            listed_after = set()
            if ref:
                m = re.search(r"listCheckIds=([^;]*)", ref["note"])
                listed_after = set(filter(None, (m.group(1) if m else "").split("|")))
            in_list = sum(1 for i in set(ids) if i in listed_after)
            if ref and ids:
                findings.append(f"- runId `{run_id}`: 생성된 checkId {len(set(ids))}개 중 목록에 나타난 수 {in_list}")
            rows.append([run_id, emp, len(posts), dict(codes), dict(apis), f"{uniq} / {len(ids)}",
                         "" if tb is None else tb, "" if ta is None else ta,
                         "" if delta is None else delta, f"{in_list} / {uniq}" if ref else "TBD (목록 조회 실패)"])
            if len(ids) >= 2 and uniq == len(ids):
                findings.append(f"- runId `{run_id}`: 성공한 POST {len(ids)}건 모두 서로 다른 checkId 가 발급됨 "
                                f"(동일 employeeId 중복 거부/병합이 관측되지 않음, n={len(ids)})")
            elif len(ids) >= 2 and uniq == 1:
                findings.append(f"- runId `{run_id}`: 성공한 POST {len(ids)}건이 모두 같은 checkId 를 반환함 (n={len(ids)})")
            elif len(ids) >= 2:
                findings.append(f"- runId `{run_id}`: 성공 POST {len(ids)}건 중 고유 checkId {uniq}개 (부분 재사용)")
            non_success = {c: n for c, n in codes.items() if c not in ("200", "201")}
            if non_success:
                findings.append(f"- runId `{run_id}`: 2xx 가 아닌 POST 응답 관측 {non_success}")
            if delta is not None:
                findings.append(f"- runId `{run_id}`: 목록 totalCount {tb} → {ta} (증가 {delta}, POST 시도 {len(posts)}건)")
            else:
                findings.append(f"- runId `{run_id}`: 목록 조회 전/후 중 HTTP 200 을 받지 못한 쪽이 있어 totalCount 비교 불가 (TBD)")
        L.extend(md_table(["runId", "employeeId", "POST 수", "HTTP 코드", "apiStatus", "고유 checkId / 성공",
                           "목록 totalCount 전", "후", "증가", "생성 checkId 중 목록 포함"], rows))
        w("")
        w("관측 사실:")
        L.extend(findings or ["- 판단 가능한 관측 없음 (표본 부족)"])
        w("")
        w("**읽는 법.** 같은 employeeId 로 POST 를 다시 보내면 **새 Background Check 가 하나 더 생긴다.** "
          "중복을 거부하거나 기존 건과 합치는 동작은 관측되지 않았다. "
          "따라서 POST 가 시간 초과됐다고 자동으로 다시 보내면 중복 checkId 가 만들어질 수 있다 → 7-2 의 'POST 자동 재전송 없음'.")
    w("")

    MARK_PROC = len(L)
    # ---------- 5. 처리시간 ----------
    w("## 4. pending 에서 최종 상태까지 걸리는 시간")
    w("")
    done = [r for r in lc_auto if r.get("outcome") == "completed"]
    outc = Counter(r.get("outcome", "") for r in lc_auto)
    finals = Counter(r.get("finalStatus", "") for r in done)

    def col(rows, name):
        return [v for v in (to_float(r.get(name)) for r in rows) if v is not None]

    unfinished = [r for r in lc_auto if r.get("outcome") != "completed"]
    orphan_posts = [p for p, s in unfinished_lifecycle_checks() if s is None]
    resumed = sum(1 for r in lc_auto if (r.get("note") or "").startswith("resume("))
    w(f"- lifecycle 자동 측정 건수(checkId 기준): {len(lc_auto)} — completed {outc.get('completed', 0)}, "
      f"timeout {outc.get('timeout', 0)}, incomplete(중단) {outc.get('incomplete', 0)}, error {outc.get('error', 0)}"
      + (f"; resume 으로 최종 상태 확인 {resumed}건" if resumed else ""))
    w(f"- **미완료 건은 처리시간 통계에서 제외**: 최종 상태 미도달 {len(unfinished)}건"
      + (f", POST 후 lifecycle 기록 없이 종료된 checkId {len(orphan_posts)}건" if orphan_posts else "")
      + (" → `python measure_background_api.py resume` 으로 재개 가능" if (unfinished or orphan_posts) else ""))
    if unfinished or orphan_posts:
        L.extend(md_table(["checkId", "원 runId", "상태", "비고"],
                          [[r["checkId"], r["runId"], r.get("outcome", ""), r.get("note", "")] for r in unfinished]
                          + [[p["checkId"], p["runId"], "기록 없음(강제 종료 추정)", ""] for p in orphan_posts]))
        w("")
    w(f"- 최종 상태 분포 (completed 기준): clear {finals.get('clear', 0)}, flagged {finals.get('flagged', 0)} "
      f"(n={len(done)})")
    immediate = sum(1 for r in lc_auto if r.get("postApiStatus") in FINAL_STATUSES)
    w(f"- POST 응답이 즉시 최종 상태였던 건수: {ratio(immediate, len(lc_auto))}")
    if len(done) < MIN_LIFECYCLE_N:
        w(f"- ⚠ 완료 표본 {len(done)}건 < {MIN_LIFECYCLE_N}건 — 표본 부족, 추가 측정 필요")
    w("")
    via_pending = [r for r in done if r.get("postApiStatus") == "pending"]
    immediate_done = [r for r in done if r.get("postApiStatus") in FINAL_STATUSES]
    no_server_time = [r for r in done if to_float(r.get("serverDurationMs")) is None]
    w(f"- 완료 {len(done)}건 = pending 경유 {len(via_pending)}건 + POST 즉시 최종 {len(immediate_done)}건. "
      "두 경로의 분포가 다르므로 나눠서 표시한다.")
    if no_server_time:
        w(f"- 서버 시각(completedAt)을 확보하지 못한 완료 건 {len(no_server_time)}건은 서버 기준 처리시간 n 에서 빠진다"
          " (POST 즉시 최종 응답에는 completedAt 이 없음 → `resume --backfill` 로 GET 1회 보충 가능)")
    w("")
    L.extend(md_table(LAT_HEADERS, [
        latency_stat_row("[pending 경유] 서버 기준: completedAt − createdAt", col(via_pending, "serverDurationMs")),
        latency_stat_row("[pending 경유] 응답 필드 processingMs (명세에 없음)", col(via_pending, "processingMs")),
        latency_stat_row("[pending 경유] 클라이언트: POST 전송 → 최종 상태 첫 관측", col(via_pending, "clientObservedMs")),
        latency_stat_row("[pending 경유] 클라이언트: POST 전송 → 마지막 pending 관측", col(via_pending, "lastPendingObservedMs")),
        latency_stat_row("[POST 즉시 최종] 서버 기준: completedAt − createdAt", col(immediate_done, "serverDurationMs")),
        latency_stat_row("[POST 즉시 최종] 클라이언트: POST 응답 시간", col(immediate_done, "clientObservedMs")),
        latency_stat_row("[전체 완료] 서버 기준: completedAt − createdAt", col(done, "serverDurationMs")),
    ]))
    w("")
    sd_via = col(via_pending, "serverDurationMs")
    if sd_via:
        w(f"**읽는 법.** POST 응답이 이미 최종 상태인 건이 {ratio(len(immediate_done), len(lc_auto))} 이고, "
          f"pending 을 거친 {len(via_pending)}건은 서버 기준으로 가장 빨라도 {ms(min(sd_via))}ms, "
          f"중앙값 {ms(percentile_nearest_rank(sd_via, 0.50))}ms, 가장 늦은 것이 {ms(max(sd_via))}ms "
          f"였다(n={len(sd_via)}). "
          "**pending 이 되면 최소 30초대는 기다려야 한다**는 뜻이고, 이것이 첫 폴링 시점의 근거가 된다(10장).")
        w("")
        # 최소 처리시간 이전에 조회하면 정말 pending 만 오는지 — 첫 폴링 시점의 직접 근거.
        min_sec = min(sd_via) / 1000.0
        via_ids = {r["checkId"] for r in via_pending if r.get("checkId")}
        post_at = {r["checkId"]: parse_iso(r.get("postTimestamp")) for r in via_pending if r.get("checkId")}
        early = Counter()
        for r in auto:
            if (r["testType"] != "lifecycle" or r["method"] != "GET" or r["httpStatus"] != "200"
                    or r["checkId"] not in via_ids):
                continue
            t0, t1 = post_at.get(r["checkId"]), parse_iso(r["timestamp"])
            if not t0 or not t1:
                continue
            if (t1 - t0).total_seconds() < min_sec:
                early[r["apiStatus"] or "(없음)"] += 1
        if early:
            total_early = sum(early.values())
            only_pending = set(early) == {"pending"}
            detail = ("**모두 pending 이었다**" if only_pending
                      else "상태는 " + ", ".join(f"{k} {v}건" for k, v in early.most_common()) + " 였다")
            w(f"- pending 을 거친 {len(via_ids)}건에 대해 **POST 후 {min_sec:.0f}초 이전**에 받은 HTTP 200 은 "
              f"{total_early}건이고, {detail}(n={total_early}). "
              "최소 처리시간 이전에 물어보면 결과를 얻지 못한다는 뜻이고, 첫 폴링을 늦게 시작하는 직접 근거다.")
            w("")
    w("- 1차 근거는 서버 시각 기반 `completedAt − createdAt` 이다(폴링 간격의 영향을 받지 않음). "
      "클라이언트 관측값은 폴링 간격·503 대기만큼 늦게 잡히며, 실제 완료 시점은 "
      "'마지막 pending 관측'과 '최종 상태 첫 관측' 사이에 있다. resume 으로 확인한 건은 클라이언트 관측값을 기록하지 않는다.")
    both_auto = [r for r in done if to_float(r.get("processingMs")) is not None and to_float(r.get("serverDurationMs")) is not None]
    diffs = [abs(to_float(r["processingMs"]) - to_float(r["serverDurationMs"])) for r in both_auto]
    w(f"- processingMs 와 (completedAt − createdAt) 일치 여부 (자동): 완전 일치 "
      f"{ratio(sum(1 for d in diffs if d == 0), len(diffs))}, |차이|≤1ms {ratio(sum(1 for d in diffs if d <= 1), len(diffs))}"
      + (f", 최대 |차이| {max(diffs):.0f}ms" if diffs else ""))
    est_present = [r for r in lc_auto if r.get("estimatedCompletionSeconds") not in ("", ABSENT, None)]
    est_absent = [r for r in lc_auto if r.get("estimatedCompletionSeconds") == ABSENT]
    w(f"- POST 201 응답의 estimatedCompletionSeconds: 관찰됨 {len(est_present)}건 / 관찰되지 않음 {len(est_absent)}건"
      + (f" — 관측값 {dict(Counter(r['estimatedCompletionSeconds'] for r in est_present))}" if est_present else ""))
    with503 = [r for r in lc_auto if (parse_int(r.get("count503")) or 0) > 0]
    w(f"- 폴링 중 503 을 1회 이상 받은 lifecycle: {ratio(len(with503), len(lc_auto))}")
    # pending 응답에 processingMs 가 이미 존재하는지, 그 값이 최종 처리시간과 같은지 (의미는 확정하지 않음)
    final_by_cid = {r["checkId"]: to_float(r.get("serverDurationMs")) for r in done if r.get("checkId")}
    pend_rows = [r for r in auto if r["testType"] == "lifecycle" and r["method"] == "GET"
                 and r["httpStatus"] == "200" and r["apiStatus"] == "pending"]
    pend_with_pms = [r for r in pend_rows if to_float(r["processingMs"]) is not None]
    pend_cmp = [r for r in pend_with_pms if final_by_cid.get(r["checkId"]) is not None]
    pend_eq = sum(1 for r in pend_cmp if to_float(r["processingMs"]) == final_by_cid[r["checkId"]])
    w(f"- pending 응답(HTTP 200) 중 processingMs 가 이미 존재: {ratio(len(pend_with_pms), len(pend_rows))}; "
      f"그 값이 같은 checkId 의 최종 (completedAt − createdAt) 과 같음: {ratio(pend_eq, len(pend_cmp))} "
      f"(checkId {len({r['checkId'] for r in pend_cmp})}개) — 명세에 없는 필드이므로 의미는 확정하지 않음")
    # 클라이언트 시계와 서버 시각의 차이 (POST 요청 시작 시각 vs 서버 createdAt)
    skews = []
    for r in post_rows:
        if r["httpStatus"] == "201":
            s = diff_ms(parse_iso(r["timestamp"]), parse_iso(r["createdAt"]))
            if s is not None:
                skews.append(s)
    if skews:
        w(f"- 서버 createdAt − 클라이언트 POST 요청 시작 시각: min {min(skews):,} ms, max {max(skews):,} ms (n={len(skews)}). "
          "음수이면 서버 시각이 클라이언트 시계보다 이르다는 뜻 → 클라이언트 시각과 서버 시각을 직접 빼서 처리시간을 계산하지 않는다")
    regress = 0
    for r in lc_auto:
        toks = [t.split("*")[0] for t in (r.get("statusSequence") or "").split(">")]
        toks = [t for t in toks if t in ("pending", "clear", "flagged")]
        if any(toks[i] in FINAL_STATUSES and toks[i + 1] == "pending" for i in range(len(toks) - 1)):
            regress += 1
    w(f"- 최종 상태 관측 후 다시 pending 이 관측된 사례: {regress}건 (n={len(lc_auto)}) "
      "— 단, 최종 상태 관측 시 폴링을 멈추므로 이후 변화는 측정 범위 밖")
    seqs = Counter(r.get("statusSequence", "") for r in lc_auto)
    if seqs:
        w("")
        w("관측된 상태 시퀀스 (상위 10개, `*n` 은 연속 횟수):")
        w("")
        L.extend(md_table(["statusSequence", "건수"], [[s or "(없음)", n] for s, n in seqs.most_common(10)]))
    w("")

    MARK_CONC = len(L)
    # ---------- 6. 동시성 ----------
    w("## 5. 동시 요청 수를 늘렸을 때 거동 변화")
    w("")
    conc = [r for r in auto if r["testType"] == "concurrency"]
    if not conc:
        w("TBD — concurrency 측정 미실행 (`python measure_background_api.py concurrency --levels 1,5,10 --requests-per-level 20`)")
    else:
        by_level = defaultdict(list)
        for r in conc:
            by_level[(r["method"], parse_int(r["concurrency"]) or 0)].append(r)
        rows = []
        for (m, level) in sorted(by_level):
            rs = by_level[(m, level)]
            n = len(rs)
            ok_code = "201" if m == "POST" else "200"
            c = Counter(r["httpStatus"] for r in rs)
            other = n - c.get(ok_code, 0) - c.get("503", 0)
            lok = lat([r for r in rs if r["httpStatus"] == ok_code])
            rows.append([m, level, n, ratio(c.get(ok_code, 0), n), ratio(c.get("503", 0), n),
                         ratio(other, n), stat_cell(lok, 0.50), stat_cell(lok, 0.95),
                         stat_cell(lok, 0.99), minmax_cell(lok, max)])
        L.extend(md_table(["method", "동시 요청 수", "요청 n", "성공(200/201)", "HTTP 503", "기타(4xx/5xx/예외)",
                           "성공 p50 (ms)", "성공 p95 (ms)", "성공 p99 (ms)", "성공 max (ms)"], rows))
        w("")
        get_levels = sorted((lvl, rs) for (m, lvl), rs in by_level.items() if m == "GET")
        if len(get_levels) >= 2:
            trend = " → ".join(f"{sum(1 for r in rs if r['httpStatus'] == '503') / len(rs) * 100:.1f}%"
                               for _, rs in get_levels)
            levels_txt = "/".join(str(lvl) for lvl, _ in get_levels)
            last_lvl, last_rs = get_levels[-1]
            last_ok = sum(1 for r in last_rs if r["httpStatus"] == "200") / len(last_rs) * 100
            w(f"**읽는 법.** 동시 요청 {levels_txt} 단계에서 503 비율은 {trend} 로, "
              "**동시성이 올라갈수록 503 이 늘어나는 관계는 나타나지 않았다.** "
              f"다만 마지막 단계(동시 {last_lvl})에서는 기타 5xx·예외가 늘어 성공률이 {last_ok:.1f}% 로 떨어졌으므로, "
              "동시성이 전혀 영향을 주지 않는다고 단정하지는 않는다. "
              f"각 단계 n 이 {min(len(rs) for _, rs in get_levels)}~{max(len(rs) for _, rs in get_levels)}건이고 "
              f"측정한 최대 단계가 {last_lvl} 이므로, 그 위 구간은 알 수 없다.")
            w("")
        if decisions:
            w("단계 진행 판단 기록 (`runs.csv` 의 concurrency-decision):")
            w("")
            drows = []
            for d in decisions:
                p = params_of(d)
                drows.append([d["runId"], p.get("level", p.get("abortedAfterLevel", "")), p.get("ratio503", ""),
                              p.get("prevRatio503", ""), p.get("errorRatio", ""), p.get("abortThreshold", "(이전 규칙)"),
                              p.get("retryAfterMax", ""), p.get("waitedSeconds", ""),
                              p.get("probeStatus", ""), p.get("decision", d.get("command", ""))])
            L.extend(md_table(["runId", "단계", "503 비율", "이전 단계 503 비율", "5xx/예외 비율", "중단 기준",
                               "Retry-After 최대(s)", "대기(s)", "대기 후 확인 요청", "판단"], drows))
            w("")
        ras = Counter(r["retryAfter"] for r in conc if r["retryAfter"] != "")
        w(f"- 동시성 테스트 중 관측된 retryAfter 값: {dict(ras) if ras else '없음'}")
        w("- 규칙: 기본은 완료된 checkId 에 대한 GET(레코드 생성 없음). 각 단계 요청은 재시도하지 않고 1회 결과만 기록. "
          "단계 사이 `--pause` 만큼 대기하고, Retry-After 를 받으면 그 값만큼 대기(상한 `--max-retry-wait`). "
          "5xx/예외 비율이 첫 단계보다 `--abort-margin`(기본 0.3) 이상 늘거나 절대 상한 `--abort-error-ratio`(기본 0.9)를 넘으면 중단. "
          "503 비율이 이전 단계보다 늘었다면 대기 후 확인 GET(최대 3회)으로 회복을 확인하고, 모두 실패하면 중단. "
          "POST 동시성은 `--method post` 로만 실행되며 총 요청 수 상한이 있다.")
        if any(params_of(d).get("abortThreshold") is None and d.get("command") == "concurrency-decision" for d in decisions):
            w("- 참고: '(이전 규칙)' 행은 첫 버전의 안전장치(확인 요청 1회 실패 시 중단)로 실행된 결과다. "
              "이 API 는 동시성 1 에서도 5xx 가 흔해 평소 실패를 과부하로 판단하고 1단계에서 멈췄다 → 규칙을 기준선 비교로 수정.")
    w("")

    MARK_SPEC = len(L)
    # ---------- 7. Swagger 대조 ----------
    w("## 6. Swagger 명세와 실제 동작 대조")
    w("")
    # 과제가 물은 세 가지 질문에 먼저 답하고, 근거 표는 7.1 이후에 둔다.
    # 아래 표들을 만들며 모은 값으로 채우기 위해, 이 자리를 비워 두고 마지막에 끼워 넣는다.
    sec7_at = len(L)
    q_undefined_codes = Counter()   # 명세에 없는데 관측된 상태코드
    q_unobserved_codes = []         # 명세에 있으나 관측되지 않은 상태코드
    q_extra_fields = Counter()      # 명세에 없으나 응답에 있던 필드
    q_notobs_fields = Counter()     # 명세에 있으나 이번 응답에서 관찰되지 않은 필드
    w("### 6-4 근거: HTTP 상태코드 대조")
    w("")
    ops = [("POST", EP_CHECKS), ("GET", EP_CHECKS), ("GET", EP_CHECK)]
    rows = []
    for m, ep in ops:
        observed = Counter(r["httpStatus"] for r in auto if r["method"] == m and r["endpoint"] == ep)
        defined = spec.defined_statuses(ep, m) if spec.doc else []
        undefined_obs = {c: n for c, n in observed.items() if c.isdigit() and c not in defined}
        unobserved = [c for c in defined if c not in observed]
        q_undefined_codes.update(undefined_obs)
        q_unobserved_codes.extend(f"{m} {ep}: {c}" for c in unobserved)
        rows.append([f"{m} {ep}", ", ".join(defined) or "(명세 읽기 실패)",
                     ", ".join(f"{c}×{n}" for c, n in sorted(observed.items(), key=lambda kv: status_code_key(kv[0]))) or "관측 없음",
                     ", ".join(f"{c}×{n}" for c, n in undefined_obs.items()) or "없음",
                     ", ".join(unobserved) or "없음"])
    L.extend(md_table(["operation", "명세 정의 코드", "실제 관측 (코드×건수)", "명세에 없는데 관측됨", "명세에 있으나 미관측"], rows))
    w("")
    w("### 6-5 근거: 응답 필드 대조")
    w("")
    w("- 값은 비교하지 않고 **필드 이름**만 비교한다.")
    w("- `명세에 없으나 응답에 있음`: 발견 사실만 기록하며 의미는 확정하지 않는다.")
    w("- `이번 응답에서 관찰되지 않음`: 명세 스키마에 있으나 `required` 로 지정되지 않은 필드가 응답에 없던 경우. "
      "**명세 위반으로 보지 않는다.** (이 swagger 의 응답 스키마에는 `required` 목록이 없다 → 응답 필드는 모두 선택적)")
    w("- `required 필드 누락`: 스키마 `required` 에 있는데 없던 경우만 '명세 위반 후보'로 따로 표시한다.")
    w("- `pending 중 미관찰(명세상 예상)`: 명세 설명에 '완료 시에만 존재'로 적힌 필드가 pending 응답에서 없던 경우.")
    w("")
    fgroups = defaultdict(list)
    for r in auto:
        if r["httpStatus"].isdigit() and r["responseFields"] != NOT_RECORDED:
            key = (r["method"], r["endpoint"], r["httpStatus"])
            fgroups[key].append(r)
    if fgroups:
        rows = []
        for (m, ep, code), rs in sorted(fgroups.items()):
            n = len(rs)
            name, schema = spec.response_schema(ep, m, code) if spec.doc else (None, None)
            ex = Counter(f for r in rs for f in split_list(r["extraFields"]))
            req, pend, notobs = Counter(), Counter(), Counter()
            for r in rs:
                cls = classify_missing(spec, ep, m, code, split_list(r["missingFields"]), r["apiStatus"])
                req.update(cls["required"])
                pend.update(cls["completion_only"])
                notobs.update(cls["not_observed"])

            # 어느 응답에서 나온 것인지 함께 남긴다. 필드 이름만 모으면 7.0 에서 맥락이 사라진다.
            q_extra_fields.update({(k, f"{m} {ep}", code): v for k, v in ex.items()})
            q_notobs_fields.update({(k, f"{m} {ep}", code): v for k, v in notobs.items()})

            def fmt(cn):
                return ", ".join(f"{k} ({v}/{n})" for k, v in sorted(cn.items()))

            rows.append([f"{m} {ep}", code, n,
                         name or "명세에 해당 응답 정의 없음 (비교 불가)",
                         fmt(ex) or "없음", fmt(notobs) or "없음", fmt(pend) or "-", fmt(req) or "없음"])
        L.extend(md_table(["operation", "HTTP", "응답 n", "명세 스키마", "명세에 없으나 응답에 있음",
                           "이번 응답에서 관찰되지 않음 (required 아님)", "pending 중 미관찰(명세상 예상)",
                           "required 필드 누락 (명세 위반 후보)"], rows))
        undefined_fields = Counter()
        for (m, ep, code), rs in fgroups.items():
            if spec.doc and spec.response_schema(ep, m, code)[1] is None:
                for r in rs:
                    undefined_fields[(f"{m} {ep}", code, r["responseFields"])] += 1
        if undefined_fields:
            w("")
            w("명세에 응답 스키마가 없는 코드에서 관측된 필드 구성:")
            w("")
            L.extend(md_table(["operation", "HTTP", "응답 필드", "건수"],
                              [[k[0], k[1], k[2] or "(body 없음/JSON 아님)", v] for k, v in undefined_fields.items()]))
    else:
        w("TBD — 자동 측정 없음")
    w("")
    w("### 6-6 근거: 명세 값 vs 실측 값")
    w("")
    sd = col(done, "serverDurationMs")
    ex_prop = None
    if spec.doc:
        ex_prop = (((spec.doc.get("components", {}).get("schemas", {}).get("BackgroundCheckCreated", {}) or {})
                    .get("properties", {}) or {}).get("estimatedCompletionSeconds", {}) or {}).get("example")
    post201_rows = [r for r in post_rows if r["httpStatus"] == "201"]
    est_missing_all = sum(1 for r in post201_rows if "estimatedCompletionSeconds" in split_list(r["missingFields"]))
    w(f"- `estimatedCompletionSeconds`: 명세 예시값 {ex_prop if ex_prop is not None else '(없음)'}초, "
      "'폴링 주기 결정에 사용하라'고 기술(required 아님). 실측: 자동 측정 POST 201 응답 중 이 필드가 관찰되지 않음 "
      f"{ratio(est_missing_all, len(post201_rows))}. 관찰되지 않았으므로 폴링 주기의 근거로 쓸 수 없다. "
      f"실제 서버 처리시간 p50 {stat_cell(sd, 0.50)} ms, max {minmax_cell(sd, max)} ms.")
    ra_hdr_n = sum(1 for r in auto if r["httpStatus"] == "503" and r["retryAfterHeader"])
    n503 = sum(1 for r in auto if r["httpStatus"] == "503")
    w(f"- 503 `Retry-After` 헤더: 명세는 헤더 제공을 기술. 실측: 자동 503 {n503}건 중 헤더 존재 {ra_hdr_n}건"
      + (" (TBD — 503 미관측)" if n503 == 0 else ""))
    w("")
    w("### 6-7 명세 문서 자체의 불일치 (swagger.yaml 정적 분석)")
    w("")
    if spec.doc:
        br = spec.broken_refs()
        unused = spec.unreferenced_components("responses")
        L.extend([f"- 해석되지 않는 `$ref`: `{r}` (위치 `{wh}`)" for wh, r in br] or ["- 해석되지 않는 `$ref`: 없음"])
        L.extend([f"- 어떤 경로에서도 참조되지 않는 응답 정의: `components/responses/{u}`" for u in unused]
                 or ["- 참조되지 않는 응답 정의: 없음"])
        for m, ep in ops:
            if "401" not in spec.defined_statuses(ep, m):
                w(f"- `{m} {ep}` 에 401 응답이 정의되어 있지 않음 (description 에는 '키가 없으면 401' 기술)")
        if "503" not in spec.defined_statuses(EP_CHECKS, "POST"):
            w(f"- `POST {EP_CHECKS}` 에 500/503 응답이 정의되어 있지 않음")
        w(f"- `POST {EP_CHECKS}` 의 동일 employeeId 반복 요청 동작은 명세에 기술되어 있지 않음 → 4장 실측 참조")
    else:
        w(f"- TBD — {spec.error}")
    w("")
    w("### 6-8 오류 케이스 (errors 명령): 명세 기대값 vs 관측")
    w("")
    err_rows = [r for r in auto if r["testType"] == "errors"]
    if err_rows:
        base500 = sum(1 for r in get_one if r["httpStatus"] == "500")
        w(f"> 각 케이스는 실행 1회당 1건이다. 이 API 는 평소에도 GET 의 {ratio(base500, len(get_one))} 가 500 이므로, "
          "오류 케이스에서 500 을 받았다면 케이스 고유의 응답인지 평소 실패인지 구분할 수 없다(재실행으로 확인 필요).")
        w("")
        by_case = defaultdict(list)
        for r in err_rows:
            by_case[r["note"]].append(r)
        erows = []
        for case, rs in by_case.items():
            codes = Counter(r["httpStatus"] for r in rs)
            msgs = sorted({r["errorMessage"] for r in rs if r["errorMessage"]})
            erows.append([case, len(rs), ", ".join(f"{c}×{n}" for c, n in codes.items()), " / ".join(msgs)[:160]])
        L.extend(md_table(["케이스 (명세 기대값)", "시도 n", "관측 HTTP", "관측 message"], erows))
        w("")
        w("- 주의: '잘못된 키' 케이스는 잘못된 키와 **존재하지 않는 checkId 를 함께** 보냈다. 따라서 404 가 키 때문인지 "
          "checkId 때문인지 이 시험만으로 구분할 수 없고, '잘못된 키는 401 이 아니다'라고 단정하지 않는다. "
          "(구분하려면 유효한 checkId + 잘못된 키 조합이 필요하다.)")
    else:
        w("TBD — errors 측정 미실행")
    w("")

    # 7.0 — 과제가 물은 세 질문에 대한 답. 위에서 모은 값만 쓰고 새로 계산하지 않는다.
    all503 = [r for r in auto if r["httpStatus"] == "503"]
    hdr503 = sum(1 for r in all503 if (r.get("retryAfterHeader") or "").strip())

    def _fmt_counter(cn, unit=""):
        return ", ".join(f"`{k}` {v}건{unit}" for k, v in cn.most_common()) if cn else "없음"

    def _fmt_fields(cn):
        """(필드, operation, 코드) 카운터를 응답별로 묶어 한 줄씩 낸다."""
        if not cn:
            return "없음"
        grouped = defaultdict(list)
        for (field, op, code), v in cn.most_common():
            grouped[(op, code)].append(f"`{field}` {v}건")
        return "; ".join(f"{op} {code} 응답 — " + ", ".join(items) for (op, code), items in grouped.items())

    summary = []
    summary += [
        "### 6-1 명세에 적혀 있으나 실제로는 확인되지 않은 것",
        "",
        f"- 응답 필드: {_fmt_fields(q_notobs_fields)} (6-5). 이 swagger 의 응답 스키마에는 `required` 가 없으므로 "
        "명세 위반이 아니라 '이번 측정에서 관찰되지 않음'으로 기록한다.",
        f"- 503 의 `Retry-After` **헤더**: 명세는 헤더 제공을 기술하지만 {ratio(hdr503, len(all503))} 이었다 (6-6, 2-3).",
        f"- 상태코드: {', '.join(q_unobserved_codes) if q_unobserved_codes else '명세 정의 코드 중 미관측 없음'} (6-4).",
        "",
        "### 6-2 명세에 없으나 실제 호출에서 발견한 동작",
        "",
        f"- 응답 필드: {_fmt_fields(q_extra_fields)} (6-5). 발견 사실만 적고 의미는 확정하지 않는다.",
        f"- 상태코드: {_fmt_counter(q_undefined_codes)} (6-4).",
        "- 503 이 성격이 다른 **두 종류**로 나뉜다 — 즉시 오는 것과 약 30초 뒤에 오는 것 (2-3).",
        "- 같은 employeeId 로 반복 POST 했을 때의 동작이 명세에 기술되어 있지 않다 (6-7) → 실측은 3장.",
        "",
        "### 6-3 명세에 적힌 값이 실제 상황에 맞지 않는다고 판단한 것",
        "",
        "- `estimatedCompletionSeconds` — 명세 예시값은 20초이고 '폴링 주기 결정에 쓰라'고 기술되어 있다. "
        "그러나 이 필드는 한 번도 오지 않았고(6-6), 실제 처리시간은 4장의 값이다. 폴링 주기의 근거로 쓸 수 없다.",
        "",
    ]
    L[sec7_at:sec7_at] = summary

    MARK_503 = len(L)
    # ---------- 8. 503 / Retry-After ----------
    w("### 2-3 503 과 Retry-After")
    w("")
    r503 = [r for r in auto if r["httpStatus"] == "503"]
    w(f"- 자동 측정 503: {ratio(len(r503), len(auto))}")
    if r503:
        w(f"  - testType 별: {dict(Counter(r['testType'] for r in r503))}")
    hdr = Counter(r["retryAfterHeader"] for r in r503 if r["retryAfterHeader"])
    bod = Counter(r["retryAfterBody"] for r in r503 if r["retryAfterBody"] != "")
    both_eq = sum(1 for r in r503 if r["retryAfterHeader"] and r["retryAfterBody"] != ""
                  and parse_int(r["retryAfterHeader"]) == parse_int(r["retryAfterBody"]))
    both_n = sum(1 for r in r503 if r["retryAfterHeader"] and r["retryAfterBody"] != "")
    w(f"- `Retry-After` 헤더: 존재 {ratio(sum(hdr.values()), len(r503))}, 값 분포 {dict(hdr) if hdr else '없음'}")
    w(f"- body `retryAfter`: 존재 {ratio(sum(bod.values()), len(r503))}, 값 분포 {dict(bod) if bod else '없음'}")
    w(f"- 헤더와 body 둘 다 있는 503 중 값 일치: {ratio(both_eq, both_n)}")
    other5xx = Counter(r["httpStatus"] for r in auto if r["httpStatus"].isdigit() and int(r["httpStatus"]) >= 500
                       and r["httpStatus"] != "503")
    w(f"- 503 이외 5xx: {dict(other5xx) if other5xx else '관측 없음'}")

    # 동일 checkId 순차 요청에서 503 이후 거동 (lifecycle / latency 만; 동시성은 순서 의미 없음)
    seq_rows = defaultdict(list)
    for r in auto:
        if r["testType"] in ("lifecycle", "latency") and r["method"] == "GET" and r["endpoint"] == EP_CHECK:
            seq_rows[(r["runId"], r["checkId"])].append(r)
    def is_transient(code):
        # 일시적 실패로 분류: 5xx 전체 + 네트워크 예외(타임아웃 등). 4xx 는 제외.
        return code.startswith("EXC:") or (code.isdigit() and int(code) >= 500)

    def analyze(pred):
        """순차 요청에서 pred(httpStatus) 가 참인 응답의 '연속 길이'와 '직후 재요청 결과'."""
        follow_, gaps_, streaks_ = Counter(), [], []
        for rs in seq_rows.values():
            rs.sort(key=lambda x: x["timestamp"])
            streak = 0
            for i, r in enumerate(rs):
                if pred(r["httpStatus"]):
                    streak += 1
                    if i + 1 < len(rs):
                        nxt = rs[i + 1]
                        follow_[nxt["httpStatus"]] += 1
                        g = diff_ms(parse_iso(r["timestamp"]), parse_iso(nxt["timestamp"]))
                        if g is not None:
                            gaps_.append((g / 1000, r["retryAfter"]))
                else:
                    if streak:
                        streaks_.append(streak)
                    streak = 0
            if streak:
                streaks_.append(streak)
        return follow_, gaps_, streaks_

    follow, gaps, streaks = analyze(lambda c: c == "503")
    t_follow, _, t_streaks = analyze(is_transient)
    seq_total = sum(len(rs) for rs in seq_rows.values())
    seq_transient = Counter(r["httpStatus"] for rs in seq_rows.values() for r in rs if is_transient(r["httpStatus"]))
    w(f"- 순차 요청(lifecycle/latency)에서 503 직후 같은 checkId 재요청 결과: "
      f"{dict(follow) if follow else 'TBD (관측 없음)'} (n={sum(follow.values())})")
    if gaps:
        w(f"  - 503 → 다음 요청 간격(s): min {min(g for g, _ in gaps):.1f}, max {max(g for g, _ in gaps):.1f} "
          f"(n={len(gaps)}; 측정 스크립트는 기본적으로 Retry-After 만큼 대기 후 재요청, "
          f"--no-wait-on-503 실행분은 폴링/요청 간격으로 재요청)")
    w(f"- 연속 503 길이 분포: {dict(Counter(streaks)) if streaks else 'TBD (관측 없음)'} "
      f"(연속 구간 n={len(streaks)}, 최대 {max(streaks) if streaks else 'TBD'})")
    if len(streaks) < MIN_503_SEQUENCES:
        w(f"  - ⚠ 503 연속 구간 표본 {len(streaks)} < {MIN_503_SEQUENCES} — 표본 부족, 추가 측정 필요")
    w("")
    w("#### 2-3-1 503 의 두 유형")
    w("")
    w("> 같은 503 이라도 body 구성과 응답시간이 다르면 다른 경로에서 생성됐을 수 있으므로 나눠서 본다. "
      "원인(어느 계층이 생성했는지)은 확정하지 않는다.")
    w("")
    by_shape = defaultdict(list)
    for r in r503:
        by_shape[(r["responseFields"] or "(body 없음/JSON 아님)", r["errorMessage"])].append(r)
    if by_shape:
        srows = []
        for (fields, msg), rs in sorted(by_shape.items(), key=lambda kv: -len(kv[1])):
            lt = lat(rs)
            has_ra = sum(1 for r in rs if r["retryAfter"] != "")
            srows.append([fields.replace(";", ", "), msg or "", len(rs), ratio(has_ra, len(rs)),
                          minmax_cell(lt, min), stat_cell(lt, 0.50), minmax_cell(lt, max)])
        L.extend(md_table(["body 필드", "message", "건수", "Retry-After 제공", "지연 min (ms)", "지연 p50 (ms)",
                           "지연 max (ms)"], srows))
        fast503 = [r for r in r503 if (to_float(r["latencyMs"]) or 0) < 5000]
        slow503 = [r for r in r503 if (to_float(r["latencyMs"]) or 0) >= 5000]
        if fast503 and slow503:
            lf, ls_ = lat(fast503), lat(slow503)
            w("")
            w(f"**읽는 법.** 503 은 두 갈래다. 하나는 **즉시 오는 것**({len(fast503)}건, "
              f"{ms(min(lf))}~{ms(max(lf))}ms)으로 body 에 재시도 간격이 들어 있고, "
              f"다른 하나는 **약 30초 뒤에 오는 것**({len(slow503)}건, {ms(min(ls_))}~{ms(max(ls_))}ms)으로 "
              "메시지만 있다. 뒤쪽 때문에 GET 타임아웃을 30초로 두면 이 응답을 받기 전에 끊어 "
              "'무응답'으로 잘못 기록하게 된다 → 7-1 의 35초. 어느 계층이 어느 503 을 만들었는지는 실측만으로 확정하지 않는다.")
    else:
        w("TBD — 503 미관측")
    long_timeout_runs = [r["runId"] for r in runs if params_of(r).get("timeout") and float(params_of(r)["timeout"]) > 30]
    if long_timeout_runs:
        lr = [r for r in auto if r["runId"] in set(long_timeout_runs) and r["method"] == "GET"]
        slow_non200 = Counter(r["httpStatus"] for r in lr if (to_float(r["latencyMs"]) or 0) >= 29000 and r["httpStatus"] != "200")
        slow200 = [v for v in lat([r for r in lr if r["httpStatus"] == "200"]) if v >= 29000]
        exc_lr = sum(1 for r in lr if r["httpStatus"].startswith("EXC:"))
        w("")
        w(f"- 측정용 타임아웃을 30s 보다 길게 둔 실행({len(long_timeout_runs)}회, GET {len(lr)}건)에서: "
          f"29s 이상 걸린 비정상 응답 {dict(slow_non200) if slow_non200 else '없음'}, 29s 이상 걸린 200 {len(slow200)}건, "
          f"네트워크 예외 {exc_lr}건. (30s 타임아웃 실행에서 끊긴 요청이 같은 응답이었는지는 직접 관측하지 않았다)")
    w("")
    w("### 2-4 일시적 실패와 회복 (5xx 전체 + 네트워크 예외·타임아웃)")
    w("")
    w(f"- 순차 GET 요청 중 일시적 실패: {ratio(sum(seq_transient.values()), seq_total)} — 종류 "
      f"{dict(seq_transient) if seq_transient else '없음'}")
    non503 = [r for r in auto if is_transient(r["httpStatus"]) and r["httpStatus"] != "503"]
    non503_ra = sum(1 for r in non503 if r["retryAfter"] != "")
    w(f"- 503 이외 일시적 실패 중 Retry-After(헤더/바디) 제공: {ratio(non503_ra, len(non503))}")
    msgs = Counter(r["errorMessage"] for r in auto if r["httpStatus"].isdigit() and int(r["httpStatus"]) >= 500
                   and r["httpStatus"] != "503" and r["errorMessage"])
    if msgs:
        w(f"- 503 이외 5xx 응답 메시지: {dict(msgs)}")
    w(f"- 일시적 실패 직후 같은 checkId 재요청 결과: {dict(t_follow) if t_follow else 'TBD (관측 없음)'} "
      f"(n={sum(t_follow.values())})")
    w(f"- 연속 일시적 실패 길이 분포: {dict(Counter(t_streaks)) if t_streaks else 'TBD (관측 없음)'} "
      f"(연속 구간 n={len(t_streaks)}, 최대 {max(t_streaks) if t_streaks else 'TBD'})")
    if len(t_streaks) < MIN_503_SEQUENCES:
        w(f"  - ⚠ 연속 구간 표본 {len(t_streaks)} < {MIN_503_SEQUENCES} — 표본 부족, 추가 측정 필요")
    timeouts = [r for r in auto if r["httpStatus"] == "EXC:ReadTimeout"]
    if timeouts:
        w(f"- ReadTimeout 은 측정용 타임아웃(`--timeout`, runs.csv 참조)에서 끊은 것이다. "
          f"그 이후에 응답이 왔을지는 관측하지 못했다(우측 절단 데이터, n={len(timeouts)}).")
    w("")

    MARK_POLICY = len(L)
    # ---------- 9. 정책 결정 영역 ----------
    w("## 9. 결정에 쓸 근거 수치 (자동 집계)")
    w("")
    w("> 11장의 결정값이 어떤 수치 위에 서 있는지 한 표로 모은 것이다. "
      "**근거 수치만 자동으로 채우고 값은 정하지 않는다.** 표본이 기준에 못 미치면 `TBD` 로 남긴다. "
      "결정과 그 이유는 11장에 서술했다.")
    w("")
    get200 = lat([r for r in get_one if r["httpStatus"] == "200"])
    post201 = lat([r for r in post_rows if r["httpStatus"] == "201"])
    lat503 = lat(r503)
    ra_vals = [parse_int(r["retryAfter"]) for r in r503 if r["retryAfter"] != ""]
    ra_vals = [v for v in ra_vals if v is not None]
    no_ra = sum(1 for r in r503 if r["retryAfter"] == "")
    status4xx = Counter(r["httpStatus"] for r in auto if r["httpStatus"].isdigit() and 400 <= int(r["httpStatus"]) < 500)
    # 동시성 단계별 503/5xx 비율 (GET 만). 503 은 동시성 1 에서도 나오므로 '처음 나타난 단계'가 아니라 기준선 대비로 본다.
    conc_get = [r for r in conc if r["method"] == "GET"]
    level_err = []
    for level in sorted({parse_int(r["concurrency"]) or 0 for r in conc_get}):
        rs = [r for r in conc_get if (parse_int(r["concurrency"]) or 0) == level]
        n5 = sum(1 for r in rs if r["httpStatus"] == "503")
        ne = sum(1 for r in rs if is_transient(r["httpStatus"]))
        level_err.append(f"동시성 {level}: 503 {ratio(n5, len(rs))}, 5xx/예외 {ratio(ne, len(rs))}")
    seq_err = f"순차 GET 일시적 실패 {ratio(sum(seq_transient.values()), seq_total)}"

    def judge(ok):
        return "근거 충족 — 11장에서 결정" if ok else "TBD (표본 부족)"

    get_timeouts = sum(1 for r in get_one if r["httpStatus"] == "EXC:ReadTimeout")
    sd_p = col(via_pending, "serverDurationMs")
    policy = [
        ["GET 타임아웃",
         f"HTTP 200 지연 p99 {stat_cell(get200, 0.99)}, max {minmax_cell(get200, max)} ms; "
         f"503 응답 지연 max {minmax_cell(lat503, max)} ms; "
         f"측정용 타임아웃에서 끊긴 GET {ratio(get_timeouts, len(get_one))}",
         "정상 응답을 끊지 않으려면 관측 max 이상이어야 함. 측정용 타임아웃에서 끊긴 요청이 이후 응답했을지는 미관측 "
         "→ 그 요청을 기다릴지, 끊고 재시도할지는 '끊은 뒤 재요청 결과'(8.1)와 함께 판단",
         judge(len(get200) >= MIN_N_FOR_PERCENTILE[0.99])],
        ["POST 타임아웃",
         f"HTTP 201 지연 p95 {stat_cell(post201, 0.95)}, max {minmax_cell(post201, max)} ms",
         "POST 는 재전송 시 중복 레코드 가능성(4장)과 함께 판단",
         judge(len(post201) >= MIN_N_FOR_PERCENTILE[0.95])],
        ["재시도 간격",
         f"503 retryAfter 관측값 {dict(Counter(ra_vals)) if ra_vals else '없음'} (n={len(ra_vals)}); "
         f"retryAfter 없는 503 {no_ra}건; Retry-After 헤더 있는 503 {ratio(ra_hdr_n, n503)}; "
         f"503 이외 일시적 실패 {len(non503)}건 중 Retry-After 제공 {non503_ra}건",
         "Retry-After 가 제공되면 그 값을 우선 적용(명세 기술 + 실측 제공 여부, 헤더/바디 중 실제 제공처 확인). "
         "Retry-After 가 없는 실패(500·타임아웃)의 간격은 '직후 재요청 결과'(8.1)로 판단",
         "Retry-After 적용 근거 있음" if ra_vals else "TBD (자동 측정에서 503 미관측)"],
        ["재시도 횟수",
         f"연속 503 최대 {max(streaks) if streaks else 'TBD'} (연속 구간 n={len(streaks)}); "
         f"연속 일시적 실패(5xx+예외) 최대 {max(t_streaks) if t_streaks else 'TBD'} (연속 구간 n={len(t_streaks)}); "
         f"일시적 실패 직후 재요청 결과 {dict(t_follow) if t_follow else 'TBD'}",
         "관측된 연속 실패 최대 길이를 넘는 재시도는 근거 없음. 사용자 대기 허용시간과 함께 판단",
         judge(len(t_streaks) >= MIN_503_SEQUENCES)],
        ["폴링 주기 / 첫 폴링 시점",
         f"POST 즉시 최종 {ratio(len(immediate_done), len(done))}; "
         f"[pending 경유] 서버 처리시간 min {minmax_cell(sd_p, min)}, p50 {stat_cell(sd_p, 0.50)}, p95 {stat_cell(sd_p, 0.95)} ms; "
         f"estimatedCompletionSeconds 관찰 {len(est_present)}/{len(lc_auto)}; 폴링 중 503 lifecycle {len(with503)}/{len(lc_auto)}",
         "처리시간 min 이전 폴링은 pending 만 받음. GET 1회 비용(2장)과 503 발생률(3·8장)을 함께 고려",
         judge(len(sd_p) >= MIN_LIFECYCLE_N)],
        ["폴링 중단 조건 (최대 대기)",
         f"[pending 경유] 서버 처리시간 max {minmax_cell(sd_p, max)} ms, p99 {stat_cell(sd_p, 0.99)} ms; "
         f"미완료(timeout {outc.get('timeout', 0)}, incomplete {outc.get('incomplete', 0)}) / error {outc.get('error', 0)}건 "
         f"(n={len(lc_auto)})",
         "관측 max 이후 완료 사례는 측정되지 않음 → 최대 대기는 관측 max 와의 관계로 설명",
         judge(len(sd_p) >= MIN_LIFECYCLE_N)],
        ["재시도 중단 조건 (비재시도 코드)",
         f"관측 4xx: {dict(status4xx) if status4xx else '없음'}; 503 이외 5xx: {dict(other5xx) if other5xx else '없음'}",
         "4xx 는 재요청해도 결과가 같을 가능성 → errors 측정에서 실제 코드 확인 후 판단",
         "근거 있음 (errors 측정)" if status4xx else "TBD (errors 측정 미실행)"],
        ["일괄 실행 동시성 상한",
         ("; ".join(level_err) + f" (기준선: {seq_err})") if level_err else "TBD",
         "503/5xx 는 동시성 1 에서도 발생한다. 동시성을 올렸을 때 기준선보다 실패율·지연이 늘어나는 단계가 제한 근거. "
         "측정한 최대 단계까지만 말할 수 있음",
         ("근거 있음" if len(level_err) >= 2 else "TBD (단계 1개만 측정됨 — 비교 불가)") if level_err
         else "TBD (concurrency 측정 미실행)"],
    ]
    L.extend(md_table(["항목", "근거 수치 (n 포함)", "판단 기준", "자동 판정"], policy))
    w("")

    MARK_LIMIT = len(L)
    # ---------- 10. 측정 한계 ----------
    w("## 13. 이 측정으로 말할 수 없는 것")
    w("")
    w("- 단일 클라이언트·단일 네트워크 위치·측정 기간 내의 결과이며, 서버 측 부하나 다른 지원자 트래픽은 알 수 없다.")
    w("- 지연은 클라이언트 wall-clock 이므로 DNS/TLS/네트워크 변동이 포함된다 (세션 재사용으로 연결 수립은 대부분 제외).")
    w("- latency 테스트는 완료된 checkId 1건을 반복 조회한다. pending 중 조회 지연은 1-1 의 lifecycle 폴링 행으로만 확인된다.")
    w("- 클라이언트 관측 처리시간은 폴링 간격과 503 대기만큼 과대 측정된다 → 서버 시각 기반 값을 1차 근거로 사용.")
    w("- lifecycle 은 합성 employeeId(`LC-...`)와 고정 이름/생년월일로 측정했다. 이름·생년월일에 따라 결과(clear/flagged, 처리시간)가 달라지는지는 측정하지 않았다.")
    w("- 동시성 테스트는 외부 API 보호를 위해 낮은 단계만 측정했다. 측정 최대 단계를 넘는 동시성의 거동은 알 수 없다.")
    w("- 최종 상태를 관측하면 폴링을 멈추므로, 최종 상태가 이후 바뀌는지는 측정 범위 밖이다.")
    w("- 명세에 없는 필드(processingMs, tenant, tenantEmployee 등)는 필드 이름만 기록했고 값·의미는 확정하지 않았다.")
    w("- 응답 스키마에 `required` 가 없으므로, 응답에 없던 명세 필드는 '이번 응답에서 관찰되지 않음'으로만 기록했다(명세 위반으로 단정하지 않음).")
    w("- **p99 의 한계**: nearest-rank 로 p99 는 사실상 상위 1~2개 관측값으로 정해지고, 95% 신뢰구간 상한은 관측 최대값 밖이다(1-1). "
      "즉 n=203 에서도 p99 는 크게 불확실하며, 상위 1~2건의 이상치에 따라 값이 크게 달라진다.")
    w("- 측정용 타임아웃(기본 30s)에서 끊은 요청은 그 뒤의 응답 여부를 모른다(우측 절단). 지연 통계는 응답을 받은 요청만으로 계산했다.")
    w("- 중단 후 `resume` 으로 확인한 건은 서버 시각 기반 처리시간만 유효하고 클라이언트 관측 처리시간은 없다. 최종 상태에 도달하지 못한 건은 처리시간 통계에서 제외했다.")
    w("- 수동 관찰(`data/manual_observations.csv`)은 요청별 응답시간·요청 시각·요청 횟수가 기록되지 않았으므로 어떤 통계에도 포함하지 않았고, 이 문서에도 싣지 않았다.")
    if len(get200) < MIN_N_FOR_PERCENTILE[0.99]:
        w(f"- GET 200 지연 표본 n={len(get200)} < 100 → p99 는 표본 부족 (추가 측정 필요)")
    if len(done) < MIN_LIFECYCLE_N:
        w(f"- 처리시간 완료 표본 n={len(done)} < {MIN_LIFECYCLE_N} → 추가 측정 필요")
    w("")

    MARK_END = len(L)

    # ---------- 출력 순서 재구성 ----------
    # 과제 [제출물 2]가 물은 순서대로 싣는다. 계산은 위에서 끝났고 여기서는 순서만 바꾼다.
    #   0 측정 개요 / 1 지연 / 2 상태코드(503·일시적 실패 포함) / 3 반복 POST /
    #   4 처리시간 / 5 동시성 / 6 명세 대조 / 7~12 결정(지원자 작성 블록) / 13 한계
    # 9장(자동 판정 표)은 결정 장과 역할이 겹쳐 싣지 않는다. 계산은 남겨 두어 언제든 되살릴 수 있다.
    head = L[:MARK_ENV]
    env = L[MARK_ENV:MARK_LAT]
    lat_ = L[MARK_LAT:MARK_DIST]
    dist = L[MARK_DIST:MARK_DUP]
    dup = L[MARK_DUP:MARK_PROC]
    proc = L[MARK_PROC:MARK_CONC]
    conc = L[MARK_CONC:MARK_SPEC]
    spec_out = L[MARK_SPEC:MARK_503]
    r503 = L[MARK_503:MARK_POLICY]
    limit = L[MARK_LIMIT:MARK_END]
    L = (head + env + lat_ + dist + r503 + dup + proc + conc + spec_out
         + [extract_manual_block(), ""] + limit)


    REPORT_MD.write_text("\n".join(L), encoding="utf-8")
    print(f"MEASUREMENTS.md 갱신: {REPORT_MD}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def build_parser():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--base-url", default=None, help="기본값: swagger.yaml servers[0].url")
    common.add_argument("--spec", default=str(DEFAULT_SPEC), help="swagger.yaml 경로")
    common.add_argument("--timeout", type=float, default=30.0,
                        help="측정용 HTTP 타임아웃(초). 정책값이 아니라 측정 도구 파라미터")
    common.add_argument("--poll-interval", type=float, default=2.0,
                        help="lifecycle 폴링 간격(초) — 클라이언트 관측 처리시간의 해상도")
    common.add_argument("--max-wait", type=float, default=300.0,
                        help="lifecycle 1건당 최대 대기(초). 초과 시 outcome=timeout 으로 기록")
    common.add_argument("--max-retry-wait", type=float, default=120.0,
                        help="503 Retry-After 대기 상한(초, 측정 보호용)")
    common.add_argument("--no-wait-on-503", action="store_true",
                        help="503 수신 시 Retry-After 만큼 대기하지 않음")
    common.add_argument("--no-report", action="store_true", help="측정 후 MEASUREMENTS.md 갱신 생략")

    p = argparse.ArgumentParser(description="Background Check API 실측 도구")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("lifecycle", parents=[common], help="POST → pending → 최종상태 처리시간 측정")
    s.add_argument("--samples", type=int, default=10)
    s.add_argument("--sample-gap", type=float, default=1.0, help="표본 간 대기(초)")
    s.set_defaults(func=cmd_lifecycle)

    s = sub.add_parser("latency", parents=[common], help="완료된 checkId GET 지연 측정")
    s.add_argument("--samples", type=int, default=100, help="목표 HTTP 200 표본 수 (p99 용 100 이상)")
    s.add_argument("--max-requests", type=int, default=None, help="최대 요청 수 (기본: samples×2)")
    s.add_argument("--check-id", default=None)
    s.add_argument("--interval", type=float, default=0.2, help="요청 간 대기(초)")
    s.set_defaults(func=cmd_latency)

    s = sub.add_parser("duplicate", parents=[common], help="동일 employeeId 반복 POST")
    s.add_argument("--employee", default="EMP-001")
    s.add_argument("--count", type=int, default=5)
    s.add_argument("--interval", type=float, default=1.0)
    s.add_argument("--first-name", default=None)
    s.add_argument("--last-name", default=None)
    s.add_argument("--dob", default=None)
    s.add_argument("--list-attempts", type=int, default=5, help="전/후 목록 조회 최대 시도 횟수(일시적 실패 대비)")
    s.set_defaults(func=cmd_duplicate)

    s = sub.add_parser("list", parents=[common], help="직원별 조회 이력 목록만 다시 조회 (duplicate 결과 재확인)")
    s.add_argument("--employee", default="EMP-001")
    s.add_argument("--list-attempts", type=int, default=5)
    s.add_argument("--interval", type=float, default=1.0)
    s.set_defaults(func=cmd_list)

    s = sub.add_parser("lifecycle-resume", aliases=["resume"], parents=[common],
                       help="중단된 lifecycle 의 저장된 checkId 로 pending 조회 재개")
    s.add_argument("--check-id", action="append", default=None, help="특정 checkId 만 재개 (여러 번 지정 가능)")
    s.add_argument("--sample-gap", type=float, default=1.0)
    s.add_argument("--backfill", action="store_true",
                   help="POST 즉시 최종이라 서버 시각(completedAt 등)이 비어 있는 건을 GET 1회로 보충")
    s.set_defaults(func=cmd_resume)

    s = sub.add_parser("concurrency", parents=[common],
                       help="동시 요청 단계별 측정 (기본: 기존 checkId GET, 레코드 생성 없음)")
    s.add_argument("--levels", default="1,5,10")
    s.add_argument("--requests-per-level", type=int, default=20)
    s.add_argument("--check-id", default=None)
    s.add_argument("--pause", type=float, default=5.0, help="단계 간 기본 대기(초). Retry-After 가 오면 그 값 이상 대기")
    s.add_argument("--abort-error-ratio", type=float, default=0.9,
                   help="5xx/예외 비율 절대 상한. 이 이상이면 상위 단계 중단 (외부 API 보호용)")
    s.add_argument("--abort-margin", type=float, default=0.3,
                   help="첫 단계 5xx/예외 비율 대비 이만큼(비율 차) 이상 늘면 상위 단계 중단")
    s.add_argument("--probe-attempts", type=int, default=3,
                   help="503 비율 증가 시 대기 후 회복 확인 GET 최대 횟수")
    s.add_argument("--force", action="store_true", help="동시성 20 초과 허용 (GET 만)")
    s.add_argument("--method", choices=["get", "post"], default="get",
                   help="post 는 레코드를 실제로 생성하므로 별도 지정 필요")
    s.add_argument("--post-limit", type=int, default=POST_CONCURRENCY_DEFAULT_LIMIT,
                   help=f"POST 총 요청 수 상한 (기본 {POST_CONCURRENCY_DEFAULT_LIMIT}, 절대 상한 {POST_CONCURRENCY_HARD_LIMIT})")
    s.set_defaults(func=cmd_concurrency)

    s = sub.add_parser("errors", parents=[common], help="400/401/404 오류 응답 구조 확인")
    s.set_defaults(func=cmd_errors)

    s = sub.add_parser("seed-manual", parents=[common],
                       help="manual_observations.csv 생성(없을 때만) + 이전 버전의 수동 행을 자동 측정 CSV 에서 분리")
    s.set_defaults(func=None)

    s = sub.add_parser("report", parents=[common], help="누적 CSV 로 MEASUREMENTS.md 재생성")
    s.set_defaults(func=None)
    return p


def main():
    try:
        sys.stdout.reconfigure(errors="replace")
    except AttributeError:
        pass
    args = build_parser().parse_args()
    spec = Spec(Path(args.spec))
    if spec.error:
        print(f"[경고] {spec.error}")

    ensure_manual_seed(verbose=args.command == "seed-manual")
    if args.command == "seed-manual":
        if not args.no_report:
            generate_report(spec)
        return
    if args.command == "report":
        generate_report(spec)
        return

    if requests is None:
        sys.exit("requests 패키지가 필요합니다: pip install -r requirements.txt")
    key = os.environ.get("CANDIDATE_KEY", "").strip()
    if not key:
        sys.exit("환경변수 CANDIDATE_KEY 가 설정되지 않았습니다. (키는 코드/CSV/문서에 기록하지 않습니다)")
    base_url = args.base_url or spec.server_url() or FALLBACK_BASE_URL
    ctx = RunContext(args, spec, ApiClient(base_url, key, args.timeout), key)
    try:
        args.func(ctx)
    except KeyboardInterrupt:
        print("\n중단됨 — 지금까지의 요청은 CSV 에 저장되어 있습니다. "
              "최종 상태에 도달하지 못한 checkId 는 `python measure_background_api.py resume` 으로 재개할 수 있습니다.")
    finally:
        if not args.no_report:
            generate_report(spec)


if __name__ == "__main__":
    main()
