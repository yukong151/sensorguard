# P4-2 (文档 §7):SBOM CycloneDX 生成器。
# 从 `gradlew :app:dependencies` 与 `cargo tree --format json` 输出解析第三方依赖,
# 产出 CycloneDX 1.5 JSON 清单(供应链合规,文档 §7 要求)。
#
# 用法:
#   python tools/gen_sbom.py            # 读取 gradle_deps.txt + cargo_deps.json → sbom.json
#   tools/gen_sbom.bat                  # 先抓依赖再生成(Windows)

import json
import re
import sys
from datetime import date

GRADLE_INPUT = "build/sbom/gradle_deps.txt"
CARGO_INPUT = "build/sbom/cargo_deps.json"
OUTPUT = "build/sbom/sensorguard-sbom.json"

COMPONENT_TYPE_APP = "application"
COMPONENT_TYPE_LIB = "library"


def parse_gradle_deps(path: str) -> list[dict]:
    """解析 `gradlew :app:dependencies` 输出的依赖列表行(Gradle 树格式解析行级坐标)。"""
    comps = []
    seen = set()
    with open(path, encoding="utf-8-sig", errors="replace") as f:
        for line in f:
            # 匹配 "--- androidx.core:core-ktx:1.13.1" 或 "|--- ..." 坐标
            m = re.search(r"([\w.\-]+:[\w.\-]+:[\w.\-]+)", line)
            if not m:
                continue
            gav = m.group(1)
            if gav in seen or gav.startswith("org.jetbrains:"):
                continue
            seen.add(gav)
            parts = gav.split(":")
            comps.append({
                "type": COMPONENT_TYPE_LIB,
                "group": parts[0],
                "name": parts[1],
                "version": parts[2] if len(parts) > 2 else "",
                "purl": f"pkg:maven/{parts[0]}/{parts[1]}@{parts[2]}" if len(parts) > 2 else f"pkg:maven/{parts[0]}/{parts[1]}",
            })
    return comps


def parse_cargo_deps(path: str) -> list[dict]:
    """解析 `cargo tree --format json` 输出(NodeJson 数组,每节点含 name/version)。"""
    comps = []
    try:
        with open(path, encoding="utf-8-sig") as f:
            data = json.load(f)
    except Exception:
        return comps
    if not isinstance(data, list):
        return comps
    for node in data:
        name = node.get("name", "")
        version = node.get("version", "")
        if name == "sensorguard":  # 本项目自身
            continue
        comps.append({
            "type": COMPONENT_TYPE_LIB,
            "name": name,
            "version": version,
            "purl": f"pkg:cargo/{name}@{version}",
        })
    return comps


def build_sbom(gradle_comps, cargo_comps) -> dict:
    components = sorted(
        gradle_comps + cargo_comps,
        key=lambda c: (c.get("group", c.get("name", "")), c.get("name", "")),
    )
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:00000000-0000-0000-0000-000000000001",
        "version": 1,
        "metadata": {
            "timestamp": date.today().isoformat() + "T00:00:00Z",
            "tools": [{"vendor": "SensorGuard", "name": "gen_sbom.py", "version": "1.0"}],
            "component": {
                "type": COMPONENT_TYPE_APP,
                "name": "com.yuexiao12.sensorguard",
                "version": "1.0.0",
                "purl": "pkg:maven/com.yuexiao12/sensorguard@1.0.0",
            },
        },
        "components": components,
    }


def main():
    gradle_comps = parse_gradle_deps(GRADLE_INPUT)
    cargo_comps = parse_cargo_deps(CARGO_INPUT)
    sbom = build_sbom(gradle_comps, cargo_comps)
    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(sbom, f, indent=2, ensure_ascii=False)
    print(f"SBOM written: {OUTPUT}")
    print(f"  Gradle components: {len(gradle_comps)}, Cargo components: {len(cargo_comps)}, total: {len(sbom['components'])}")


if __name__ == "__main__":
    main()