#!/usr/bin/env python3
"""安卓端 ↔ 网关路由 ↔ 后端 OpenAPI 的接口契约比对。

为什么要这个脚本（2026-10-25，内测放行前）：

内测用户装的 APK 里**接口路径是编译进去的**（Retrofit 注解）。
后端改了路由（改名、换前缀、删掉），APK 不会重新编译 ⇒
内测用户点一下就 404，而**后端自己的测试全绿**（后端只测自己还存在的接口）。

所以「后端测试通过」**不能**证明「安卓端能用」。
必须三方对账：安卓端调用的每个 (METHOD, path) 都得在网关路由表里存在，
网关路由表的每个下游都得在 OpenAPI 里存在。

输出三张差集：
  A. 安卓有、网关没有  ⇒ 内测 404（网关兜底也可能猜得到，要单独看）
  B. 安卓有、OpenAPI 没有 ⇒ 后端服务里根本没这个端点
  C. 网关有、OpenAPI 没有 ⇒ 网关转发过去会 404

用法：
  python3 scripts/check_contract_match.py [--android-root <dir>] [--backend-root <dir>]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Retrofit 注解：@GET("api/v1/xxx") / @POST("api/v1/xxx")
_ANNOTATION_RE = re.compile(
    r'@(GET|POST|PUT|DELETE|PATCH)\(\s*"([^"]+)"\s*\)',
    re.MULTILINE,
)

# 网关 config.py 里的 Route("POST", "/api/v1/xxx", ...)
_ROUTE_RE = re.compile(
    r'Route\(\s*"([A-Z]+)"\s*,\s*"([^"]+)"',
    re.MULTILINE,
)


_PARAM_RE = re.compile(r"\{[^}]+\}")


def normalize(path: str) -> str:
    """把路径归一成可比较形式。

    三件事：
    1. 去掉 query string；
    2. 统一前导斜杠（Retrofit 注解不带，网关 Route 带）；
    3. **把路径参数名抹平**（`{id}` / `{article_id}` / `{tag_id_or_slug}` → `{}`）。

    第 3 条是关键：路径参数**叫什么**不影响路由匹配，
    只有**位置和段数**才决定命中。把命名差异当缺口报出去是假阳性，
    会把「11 个内测 404」这种吓人的结论塞进报告里 —— 必须先抹平再看。
    """
    path = path.split("?", 1)[0]
    if not path.startswith("/"):
        path = "/" + path
    return _PARAM_RE.sub("{}", path)


def collect_android(root: Path) -> set[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    src = root / "app" / "src" / "main"
    for path in src.rglob("*.kt"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for method, api_path in _ANNOTATION_RE.findall(text):
            found.add((method.upper(), normalize(api_path)))
    return found


def collect_gateway(backend_root: Path) -> set[tuple[str, str]]:
    cfg = backend_root / "backend" / "api-gateway" / "config.py"
    if not cfg.exists():
        return set()
    text = cfg.read_text(encoding="utf-8", errors="replace")
    return {(m.upper(), normalize(p)) for m, p in _ROUTE_RE.findall(text)}


def collect_openapi(backend_root: Path) -> set[tuple[str, str]]:
    spec_path = backend_root / "backend" / "docs" / "openapi" / "stashbox-openapi.json"
    if not spec_path.exists():
        return set()
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    out: set[tuple[str, str]] = set()
    for path, ops in spec.get("paths", {}).items():
        for method in ops:
            if method.upper() in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}:
                out.add((method.upper(), normalize(path)))
    return out


def report(title: str, rows: list[str]) -> None:
    print(f"\n=== {title}（{len(rows)}） ===")
    for row in rows:
        print(f"  {row}")
    if not rows:
        print("  （无）")


def main() -> int:
    parser = argparse.ArgumentParser()
    # 默认布局：<work>/stashbox-android/scripts/本脚本 与 <work>/stashbox/ 是兄弟仓。
    # parents[0]=scripts  parents[1]=stashbox-android  parents[2]=<work>
    here = Path(__file__).resolve()
    parser.add_argument("--android-root", default=str(here.parents[1]))
    parser.add_argument(
        "--backend-root",
        default=str(here.parents[2] / "stashbox"),
        help="后端仓路径（内含 backend/ 子目录）",
    )
    args = parser.parse_args()

    android = collect_android(Path(args.android_root))
    gateway = collect_gateway(Path(args.backend_root))
    openapi = collect_openapi(Path(args.backend_root))

    print(f"安卓端 Retrofit 接口：{len(android)}")
    print(f"网关路由表条目：      {len(gateway)}")
    print(f"后端 OpenAPI 端点：   {len(openapi)}")

    a_missing_in_gateway = sorted(android - gateway)
    a_missing_in_openapi = sorted(android - openapi)
    g_missing_in_openapi = sorted(gateway - openapi)

    report("A. 安卓端调用了、但网关路由表里没有", [f"{m} {p}" for m, p in a_missing_in_gateway])
    report("B. 安卓端调用了、但后端 OpenAPI 里没有", [f"{m} {p}" for m, p in a_missing_in_openapi])
    report("C. 网关路由表里有、但后端 OpenAPI 里没有", [f"{m} {p}" for m, p in g_missing_in_openapi])

    fatal = bool(a_missing_in_openapi or g_missing_in_openapi)
    if a_missing_in_gateway and not a_missing_in_openapi:
        print(
            "\n注：A 非空但 B 为空 ⇒ 这些端点后端有，只是网关没显式路由（可能走 proxy_fallback 兜底）。"
            "\n    需人工确认兜底白名单，否则内测仍会 404。"
        )
    print("\n判定：" + ("❌ 有内测会 404 的缺口" if fatal else "✅ 安卓端调用的接口后端都存在"))
    return 1 if fatal else 0


if __name__ == "__main__":
    sys.exit(main())
