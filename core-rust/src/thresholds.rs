//! W9 (文档 §13):阈值加载器 —— 编译时嵌入 `thresholds.v1.json`,运行时提供全局阈值。
//!
//! 阈值文件由 `calibrate.py` 从标定 corpus 产出,是文档 §2 阈值推导公式的唯一冻结版本。
//! 人肉修改阈值文件 CI 拒绝合入;`git blame thresholds.v*.json` 可追溯到 corpus 版本 +
//! calibrate.py 提交号 + 算法负责人签名。
//!
//! ## 设计
//! - 使用 `include_str!` 编译时嵌入 JSON,零运行时文件 IO。
//! - 手写 JSON 解析(无 serde,符合 §6 体积预算 ≤ 15 KB)。
//! - 解析失败在 `Lazy::new` 初始化时 panic 阻断启动(阈值加载失败 => 系统不可用)。
//!
//! ## 添加新阈值
//! 1. 在 `thresholds.v1.json` 的 `thresholds` 对象中加字段。
//! 2. 在 `Thresholds` 结构体加同名成员。
//! 3. 在 `parse_thresholds` 中加解析分支。
//! 4. CI 验证:新阈值必须有默认值 + 单元测试覆盖。

use once_cell::sync::Lazy;

/// 编译时嵌入的阈值 JSON 源码。
const THRESHOLDS_JSON: &str = include_str!("../rules/thresholds.v1.json");

/// 全局阈值实例(启动时惰性初始化,解析失败 panic)。
pub static THRESHOLDS: Lazy<Thresholds> = Lazy::new(|| load_thresholds().expect("thresholds.v1.json parse failed"));

/// 冻结阈值结构体,字段与 `thresholds.v1.json` 的 `thresholds` 对象一一对应。
#[derive(Debug, Clone, PartialEq)]
pub struct Thresholds {
    /// KS 检验阈值 $\tau_{KS}$,正常样本 P99.5 百分位(默认 0.18)。
    pub ks_tau: f64,
    /// KL 散度阈值,Youden's J 最大化(默认 0.35)。
    pub kl_divergence: f64,
    /// Burst 熵下限,EER 边界(默认 2.5)。
    pub burst_entropy_min: f64,
    /// Burst 熵上限,EER 边界(默认 4.5)。
    pub burst_entropy_max: f64,
    /// 周期能量集中度阈值,Youden's J 最大化(默认 0.4)。
    pub period_energy_concentration: f64,
    /// L3 统计检验最低事件数(默认 20)。
    pub min_events_for_l3: u32,
}

// ── 加载入口 ─────────────────────────────────────────────────────────────────

fn load_thresholds() -> Result<Thresholds, &'static str> {
    let text = THRESHOLDS_JSON.trim();
    // 顶层对象
    let root = balanced_object(text)?;
    // 取 "thresholds" 子对象
    let thresh_obj = get_obj(root, "thresholds")?;
    let t = parse_thresholds(thresh_obj)?;
    Ok(t)
}

fn parse_thresholds(obj: &str) -> Result<Thresholds, &'static str> {
    Ok(Thresholds {
        ks_tau: get_float(obj, "ks_tau")? as f64,
        kl_divergence: get_float(obj, "kl_divergence")? as f64,
        burst_entropy_min: get_float(obj, "burst_entropy_min")? as f64,
        burst_entropy_max: get_float(obj, "burst_entropy_max")? as f64,
        period_energy_concentration: get_float(obj, "period_energy_concentration")? as f64,
        min_events_for_l3: get_int(obj, "min_events_for_l3")?,
    })
}

// ── 手写 JSON 解析器(封闭 schema,无 serde) ─────────────────────────────────

/// 从 `"key": value` 中提取 value 的平衡切片(含内部嵌套结构)。
fn value_after_key<'a>(obj: &'a str, key: &str) -> Option<&'a str> {
    let target = format!("\"{}\":", key);
    let pos = obj.find(&target)?;
    let rest = &obj[pos + target.len()..];
    let rest = rest.trim_start();
    Some(rest)
}

/// 取平衡花括号对象(含 `{` `}`)。
fn balanced_object<'a>(s: &'a str) -> Result<&'a str, &'static str> {
    let s = s.trim_start();
    if !s.starts_with('{') {
        return Err("expected '{'");
    }
    let mut depth = 0i32;
    let mut in_str = false;
    for (i, c) in s.char_indices() {
        if in_str {
            if c == '"' {
                in_str = false;
            }
            continue;
        }
        match c {
            '"' => in_str = true,
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return Ok(&s[..=i]);
                }
            }
            _ => {}
        }
    }
    Err("unbalanced object")
}

/// 取 `"key": "scalar"` 或 `"key": 123` 的标量字符串切片(去引号)。
fn take_scalar(v: &str) -> Result<&str, &'static str> {
    let v = v.trim_start();
    if v.starts_with('"') {
        // 字符串:找闭引号
        let end = v[1..].find('"').ok_or("unterminated string")?;
        Ok(&v[1..=end])
    } else {
        // 数字/布尔:取到逗号/花括号/空白/换行为止
        let end = v.find(|c: char| c == ',' || c == '}' || c == ']' || c.is_whitespace())
            .unwrap_or(v.len());
        Ok(v[..end].trim())
    }
}

fn get_int<T: std::str::FromStr>(obj: &str, key: &str) -> Result<T, &'static str> {
    let v = value_after_key(obj, key).ok_or("missing key")?;
    take_scalar(v)?.parse::<T>().map_err(|_| "bad number")
}

fn get_float(obj: &str, key: &str) -> Result<f32, &'static str> {
    let v = value_after_key(obj, key).ok_or("missing key")?;
    take_scalar(v)?.parse::<f32>().map_err(|_| "bad float")
}

fn get_obj<'a>(obj: &'a str, key: &str) -> Result<&'a str, &'static str> {
    let v = value_after_key(obj, key).ok_or("missing key")?;
    balanced_object(v)
}

// ── 单元测试 ─────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loads_default_thresholds() {
        let t = &*THRESHOLDS;
        // 阈值由 calibrate.py 从 corpus 产出,值随 corpus 更新而变化;
        // 此处仅验证加载正常且范围合理,不绑死具体数值。
        assert!(t.ks_tau > 0.0 && t.ks_tau < 0.5);
        assert!(t.kl_divergence > 0.0 && t.kl_divergence < 1.0);
        assert!(t.burst_entropy_min < t.burst_entropy_max);
        assert!(t.burst_entropy_min >= 0.0);
        assert!(t.period_energy_concentration > 0.0 && t.period_energy_concentration < 1.0);
        assert_eq!(t.min_events_for_l3, 20);
    }

    #[test]
    fn thresholds_are_sane() {
        let t = &*THRESHOLDS;
        assert!(t.ks_tau > 0.0 && t.ks_tau < 1.0);
        assert!(t.kl_divergence > 0.0 && t.kl_divergence < 2.0);
        assert!(t.burst_entropy_min < t.burst_entropy_max);
        assert!(t.burst_entropy_min >= 0.0);
        assert!(t.min_events_for_l3 >= 5);
    }

    #[test]
    fn parse_missing_key_returns_err() {
        assert!(get_int::<i32>("{}", "nonexistent").is_err());
        assert!(get_float("{}", "nonexistent").is_err());
        assert!(get_obj("{}", "nonexistent").is_err());
    }
}