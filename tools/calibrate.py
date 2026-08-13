#!/usr/bin/env python3
"""
SensorGuard v1.0-final — 阈值离线校准脚本 (W9 里程碑 §13)
===========================================================
从标定 corpus 读取正常样本(N)与攻击样本(P)的特征向量,按文档 §2 阈值推导公式
自动产出 thresholds.vN.json。无 corpus 时以 --defaults 输出文档默认值。

用法:
  # 从 corpus 派生阈值
  python calibrate.py --corpus-dir ./corpus-2026Q3 --output ../core-rust/rules/thresholds.v1.json

  # 输出文档默认值(无 corpus 时)
  python calibrate.py --defaults --output ../core-rust/rules/thresholds.v1.json

corpus 格式: JSONL,每行一条样本
  {"label":"normal","features":{"ks_d":0.12,"burst_entropy":3.2,"kl_divergence":0.15,"period_energy":0.6}}
  {"label":"attack","features":{"ks_d":0.35,"burst_entropy":1.8,"kl_divergence":0.62,"period_energy":0.9}}
"""

import argparse
import json
import math
import os
import sys
from datetime import date
from typing import TextIO


# ── 文档默认值(无 corpus 回退) ──────────────────────────────────────────────
DEFAULT_THRESHOLDS = {
    "ks_tau": 0.18,
    "kl_divergence": 0.35,
    "burst_entropy_min": 2.5,
    "burst_entropy_max": 4.5,
    "period_energy_concentration": 0.4,
    "min_events_for_l3": 20,
}

SCHEMA_VERSION = 1


# ── 特征提取 ─────────────────────────────────────────────────────────────────

def load_corpus(corpus_dir: str) -> tuple[list[dict], list[dict]]:
    """加载 corpus 目录下所有 JSONL 文件,返回 (normal_samples, attack_samples)。"""
    normal: list[dict] = []
    attack: list[dict] = []
    loaded = 0
    for fname in sorted(os.listdir(corpus_dir)):
        if not fname.endswith(".jsonl"):
            continue
        fpath = os.path.join(corpus_dir, fname)
        with open(fpath, "r") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                sample = json.loads(line)
                label = sample.get("label", "normal")
                feats = sample.get("features", {})
                if label == "attack":
                    attack.append(feats)
                else:
                    normal.append(feats)
                loaded += 1
    print(f"[calibrate] loaded {loaded} samples ({len(normal)} normal, {len(attack)} attack)", file=sys.stderr)
    return normal, attack


# ── 阈值推导 ─────────────────────────────────────────────────────────────────

def percentile(values: list[float], p: float) -> float:
    """计算百分位数(线性插值,与 numpy.percentile 一致)。"""
    if not values:
        return 0.0
    sorted_vals = sorted(values)
    n = len(sorted_vals)
    k = (p / 100.0) * (n - 1)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[int(k)]
    d0 = sorted_vals[f] * (c - k)
    d1 = sorted_vals[c] * (k - f)
    return d0 + d1


def youden_j(normal_scores: list[float], attack_scores: list[float],
             num_pts: int = 200) -> float:
    """Youden's J 最大化: 在 N 个候选阈值中找 J = TPR - FPR 最大的点。"""
    if not normal_scores or not attack_scores:
        return 0.0
    lo = min(min(normal_scores), min(attack_scores))
    hi = max(max(normal_scores), max(attack_scores))
    margin = (hi - lo) * 0.05 if hi > lo else 1.0
    lo -= margin
    hi += margin
    best_t = lo
    best_j = -1.0
    for i in range(num_pts + 1):
        t = lo + (hi - lo) * i / num_pts
        tp = sum(1 for s in attack_scores if s >= t)
        fp = sum(1 for s in normal_scores if s >= t)
        tpr = tp / len(attack_scores) if attack_scores else 0.0
        fpr = fp / len(normal_scores) if normal_scores else 0.0
        j = tpr - fpr
        if j > best_j:
            best_j = j
            best_t = t
    return best_t


def eer_threshold(normal_scores: list[float], attack_scores: list[float],
                  num_pts: int = 200) -> tuple[float, float, float]:
    """等错误率(EER)边界: 找 TPR ≈ 1-FPR 的点,返回 (下限, 上限, EER 值)。"""
    if not normal_scores or not attack_scores:
        return (0.0, 0.0, 0.0)
    lo = min(min(normal_scores), min(attack_scores))
    hi = max(max(normal_scores), max(attack_scores))
    margin = (hi - lo) * 0.05 if hi > lo else 1.0
    lo -= margin
    hi += margin
    best_eer = 1.0
    best_t = lo
    for i in range(num_pts + 1):
        t = lo + (hi - lo) * i / num_pts
        tpr = sum(1 for s in attack_scores if s >= t) / len(attack_scores)
        fpr = sum(1 for s in normal_scores if s >= t) / len(normal_scores)
        eer = abs(tpr - (1.0 - fpr))
        if eer < best_eer:
            best_eer = eer
            best_t = t
    # 区间:正常分布 P5~P95 作为上下界(覆盖 90% 正常样本)
    lower = percentile(normal_scores, 5.0)
    upper = percentile(normal_scores, 95.0)
    # 当 attack 分布在此区间之外时,缩窄到 attack 与正常重叠的边界(EER 点附近)
    attack_lo = percentile(attack_scores, 5.0)
    attack_hi = percentile(attack_scores, 95.0)
    if attack_lo > upper:
        upper = attack_lo  # attack 整体偏高(如 KS),上限取 attack 低端
    if attack_hi < lower:
        lower = attack_hi  # attack 整体偏低(如熵),下限取 attack 高端
    return (lower, upper, best_t)


def derive_thresholds(normal: list[dict], attack: list[dict]) -> dict:
    """从 corpus 推导所有阈值。"""
    ks_vals = [s.get("ks_d", 0.0) for s in normal]
    kl_normal = [s.get("kl_divergence", 0.0) for s in normal]
    kl_attack = [s.get("kl_divergence", 0.0) for s in attack]
    ent_normal = [s.get("burst_entropy", 0.0) for s in normal]
    ent_attack = [s.get("burst_entropy", 0.0) for s in attack]
    pe_normal = [s.get("period_energy", 0.0) for s in normal]
    pe_attack = [s.get("period_energy", 0.0) for s in attack]

    thresholds = {}

    # KS τ: 正常样本 P99.5 → 单侧误报 ≤ 0.5%
    thresholds["ks_tau"] = round(percentile(ks_vals, 99.5), 4)
    print(f"[calibrate] ks_tau = P99.5(normal.ks_d) = {thresholds['ks_tau']}", file=sys.stderr)

    # KL 散度: Youden's J 最大化
    thresholds["kl_divergence"] = round(youden_j(kl_normal, kl_attack), 4)
    print(f"[calibrate] kl_divergence = YoudenJ(kl) = {thresholds['kl_divergence']}", file=sys.stderr)

    # Burst 熵区间: EER 边界
    ent_lo, ent_hi, _ = eer_threshold(ent_normal, ent_attack)
    thresholds["burst_entropy_min"] = round(ent_lo, 4)
    thresholds["burst_entropy_max"] = round(ent_hi, 4)
    print(f"[calibrate] burst_entropy = [{ent_lo}, {ent_hi}] (EER)", file=sys.stderr)

    # 周期能量集中度: Youden's J 最大化
    thresholds["period_energy_concentration"] = round(youden_j(pe_normal, pe_attack), 4)
    print(f"[calibrate] period_energy = YoudenJ(pe) = {thresholds['period_energy_concentration']}", file=sys.stderr)

    # min_events_for_l3: 文档 §3 固定值 20
    thresholds["min_events_for_l3"] = 20

    return thresholds


# ── 输出 ─────────────────────────────────────────────────────────────────────

def write_thresholds(thresholds: dict, output: TextIO) -> None:
    """输出 thresholds.vN.json。"""
    doc = {
        "schema_version": SCHEMA_VERSION,
        "corpus_id": "default-v1",
        "generated_at": date.today().isoformat(),
        "generated_by": "calibrate.py",
        "description": "SensorGuard v1.0 默认阈值,由 calibrate.py 从标定 corpus 产出。",
        "thresholds": thresholds,
    }
    json.dump(doc, output, indent=2)
    output.write("\n")


# ── CLI ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="SensorGuard 阈值校准脚本")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--corpus-dir", help="标定 corpus 目录(JSONL 文件)")
    group.add_argument("--defaults", action="store_true", help="输出文档默认阈值(无 corpus 时)")
    parser.add_argument("--output", "-o", required=True, help="输出 thresholds.vN.json 路径")
    args = parser.parse_args()

    if args.defaults:
        thresholds = DEFAULT_THRESHOLDS.copy()
        print("[calibrate] using document defaults", file=sys.stderr)
    else:
        normal, attack = load_corpus(args.corpus_dir)
        if len(normal) < 50 or len(attack) < 20:
            print(f"[calibrate] WARNING: corpus too small ({len(normal)}N/{len(attack)}P), "
                  f"falling back to defaults", file=sys.stderr)
            thresholds = DEFAULT_THRESHOLDS.copy()
        else:
            thresholds = derive_thresholds(normal, attack)

    with open(args.output, "w") as f:
        write_thresholds(thresholds, f)
    print(f"[calibrate] wrote {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()