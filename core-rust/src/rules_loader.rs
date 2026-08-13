//! W6 D3 (tabbit_文档(2).md §6):规则 JSON → Rust 结构的手写极简加载器。
//!
//! 不引入 serde_json(release 增量约 180~250 KB,违反 §6 体积预算 ≤ 15 KB);
//! 规则文件字段集合封闭(§2 20 条清单),仅按 key 名做子串定位 + 类型转换,
//! 不构建通用 JSON AST。输入为 UTF-8 字节流,来自 rules.v1.json
//! (运行时经 Ed25519 校验后传入,签名验证接线属 W7+ 范围)。
//!
//! schema(顶层数组,元素为规则对象):
//!   { "id", "category", "kind", "severity", "min_tier", "batch",
//!     "debounce": { window_s, min_hits, cool_down_s },
//!     "predicates": [ { <谓词 key>: 标量|数组|对象 }, ... ] }
//! 谓词 key 集与 rules.rs::Predicate 变体一一对应(见 parse_predicate)。

use crate::rules::{Debounce, Predicate, Rule, RuleBatch, RuleCategory, RuleKind};

/// 解析 rules.v1.json(顶层数组)为规则向量。任何结构不符返回 Err(&'static str)。
pub fn load_rules(json_bytes: &[u8]) -> Result<Vec<Rule>, &'static str> {
    let text = std::str::from_utf8(json_bytes).map_err(|_| "bad utf8")?;
    let objects = split_top_level_objects(text)?;
    let mut rules = Vec::with_capacity(objects.len());
    for obj in objects {
        rules.push(parse_one_rule(obj)?);
    }
    Ok(rules)
}

// ---------- 顶层数组切分 ----------

/// 顶层数组(元素为规则对象)切片。返回每个对象字面量(含花括号),不做 AST。
fn split_top_level_objects(text: &str) -> Result<Vec<&str>, &'static str> {
    let s = text.trim();
    if !s.starts_with('[') || !s.ends_with(']') {
        return Err("expected top-level array");
    }
    let inner = &s[1..s.len() - 1];
    let bytes = inner.as_bytes();
    let mut out: Vec<&str> = Vec::new();
    let mut depth = 0i32;
    let mut in_str = false;
    let mut start = 0usize;
    for (i, &c) in bytes.iter().enumerate() {
        if in_str {
            if c == b'"' {
                in_str = false;
            }
            continue;
        }
        match c {
            b'"' => in_str = true,
            b'{' => {
                depth += 1;
                if depth == 1 {
                    start = i;
                }
            }
            b'}' => {
                depth -= 1;
                if depth == 0 {
                    out.push(&inner[start..=i]);
                }
            }
            b'[' | b']' => {} // 组内数组,不影响对象切分
            b',' | b' ' | b'\t' | b'\n' | b'\r' => {}
            _ if depth == 0 => return Err("unexpected token between objects"),
            _ => {} // 对象内部的 key/数字/冒号等一律放行
        }
    }
    if depth != 0 {
        return Err("unbalanced top-level array");
    }
    Ok(out)
}

/// 数组元素切分(顶层深度 0,忽略字符串),返回元素切片(trim 过)。
fn split_array_elements(arr: &str) -> Result<Vec<&str>, &'static str> {
    let s = arr.trim();
    if !s.starts_with('[') || !s.ends_with(']') {
        return Err("expected array");
    }
    let inner = &s[1..s.len() - 1];
    let bytes = inner.as_bytes();
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut in_str = false;
    let mut start = 0usize;
    for (i, &c) in bytes.iter().enumerate() {
        if in_str {
            if c == b'"' {
                in_str = false;
            }
            continue;
        }
        match c {
            b'"' => in_str = true,
            b'[' | b'{' => depth += 1,
            b']' | b'}' => depth -= 1,
            b',' if depth == 0 => {
                out.push(inner[start..i].trim());
                start = i + 1;
            }
            _ => {}
        }
    }
    if depth != 0 {
        return Err("unbalanced array");
    }
    let last = inner[start..].trim();
    if !last.is_empty() {
        out.push(last);
    }
    Ok(out)
}

// ---------- 字段定位 ----------

/// 在对象文本中定位 `"key"`,返回冒号后的值切片(起始处已 trim)。
fn value_after_key<'a>(obj: &'a str, key: &str) -> Option<&'a str> {
    let needle = format!("\"{key}\"");
    let mut from = 0usize;
    while let Some(rel) = obj[from..].find(&needle) {
        let idx = from + rel;
        let after = obj[idx + needle.len()..].trim_start();
        if let Some(rest) = after.strip_prefix(':') {
            return Some(rest.trim_start());
        }
        from = idx + needle.len();
    }
    None
}

/// 从标量值切片取单个 token(数字/布尔),到顶层 , 或 } 为止(忽略字符串内部)。
fn take_scalar(v: &str) -> Result<&str, &'static str> {
    let mut in_str = false;
    for (i, c) in v.char_indices() {
        match c {
            '"' => in_str = !in_str,
            ',' | '}' => {
                if !in_str {
                    return Ok(v[..i].trim());
                }
            }
            _ => {}
        }
    }
    Ok(v.trim())
}

/// 解析 `"str"` 字面量(封闭 schema 无转义,遇反斜杠报错)。
fn parse_string_literal(v: &str) -> Result<&str, &'static str> {
    let v = v.trim();
    if !v.starts_with('"') {
        return Err("expected string");
    }
    let inner = &v[1..];
    let end = inner.find('"').ok_or("unterminated string")?;
    if inner[..end].contains('\\') {
        return Err("string escape unsupported");
    }
    Ok(&inner[..end])
}

// ---------- 类型化取值 ----------

fn get_int<T: std::str::FromStr>(obj: &str, key: &str) -> Result<T, &'static str> {
    let v = value_after_key(obj, key).ok_or("missing key")?;
    take_scalar(v)?
        .parse::<T>()
        .map_err(|_| "bad number")
}

fn get_float(obj: &str, key: &str) -> Result<f32, &'static str> {
    let v = value_after_key(obj, key).ok_or("missing key")?;
    take_scalar(v)?
        .parse::<f32>()
        .map_err(|_| "bad float")
}

fn get_string<'a>(obj: &'a str, key: &str) -> Result<&'a str, &'static str> {
    parse_string_literal(value_after_key(obj, key).ok_or("missing key")?)
}

/// 取 `"key": { ... }` 的平衡对象切片(含花括号)。
fn get_obj<'a>(obj: &'a str, key: &str) -> Result<&'a str, &'static str> {
    object_from_value(value_after_key(obj, key).ok_or("missing key")?)
}

/// 值切片直接是对象时,提取平衡对象切片(含花括号)。
fn object_from_value(v: &str) -> Result<&str, &'static str> {
    let v = v.trim_start();
    if !v.starts_with('{') {
        return Err("expected object");
    }
    let mut depth = 0i32;
    let mut in_str = false;
    for (i, c) in v.char_indices() {
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
                    return Ok(&v[..=i]);
                }
            }
            _ => {}
        }
    }
    Err("unbalanced object")
}

/// 取 `"key": [ ... ]` 的平衡数组切片(含方括号)。
fn get_array<'a>(obj: &'a str, key: &str) -> Result<&'a str, &'static str> {
    array_from_value(value_after_key(obj, key).ok_or("missing key")?)
}

/// 值切片直接是数组时,提取平衡数组切片(含方括号)。
fn array_from_value(v: &str) -> Result<&str, &'static str> {
    let v = v.trim_start();
    if !v.starts_with('[') {
        return Err("expected array");
    }
    let mut depth = 0i32;
    let mut in_str = false;
    for (i, c) in v.char_indices() {
        if in_str {
            if c == '"' {
                in_str = false;
            }
            continue;
        }
        match c {
            '"' => in_str = true,
            '[' => depth += 1,
            ']' => {
                depth -= 1;
                if depth == 0 {
                    return Ok(&v[..=i]);
                }
            }
            _ => {}
        }
    }
    Err("unbalanced array")
}

// ---------- 规则装配 ----------

fn parse_one_rule(obj: &str) -> Result<Rule, &'static str> {
    let id = get_int::<u16>(obj, "id")?;
    let category = parse_category(get_string(obj, "category")?)?;
    let kind = parse_kind(get_string(obj, "kind")?)?;
    let severity = get_int::<u8>(obj, "severity")?;
    let min_tier = get_int::<u8>(obj, "min_tier")?;
    let batch = parse_batch(get_string(obj, "batch")?)?;
    let debounce = parse_debounce(get_obj(obj, "debounce")?)?;
    let predicates = parse_predicates(get_array(obj, "predicates")?)?;
    Ok(Rule {
        id,
        category,
        kind,
        severity,
        predicates,
        debounce,
        min_tier,
        batch,
    })
}

fn parse_debounce(obj: &str) -> Result<Debounce, &'static str> {
    Ok(Debounce {
        window_s: get_int::<u32>(obj, "window_s")?,
        min_hits: get_int::<u32>(obj, "min_hits")?,
        cool_down_s: get_int::<u32>(obj, "cool_down_s")?,
    })
}

fn parse_predicates(arr: &str) -> Result<Vec<Predicate>, &'static str> {
    split_array_elements(arr)?
        .into_iter()
        .map(parse_predicate)
        .collect()
}

/// 谓词对象以单一 key 分派到对应变体。key 集 = rules.rs::Predicate 变体名(蛇形)。
fn parse_predicate(obj: &str) -> Result<Predicate, &'static str> {
    const KEYS: &[&str] = &[
        "op_equals",
        "op_in",
        "duration_lt_ms",
        "duration_gt_ms",
        "interval_lt_ms",
        "count_in_window_gte",
        "sample_rate_gte",
        "user_present_equals",
        "fg_state_equals",
        "power_state_equals",
        "intent_hint_equals",
        "system_proxy_equals",
        "decl_purpose_not_in",
        "decl_purpose_in",
        "ks_d_gt",
        "burst_entropy_between",
        "coupled_op_ratio_gte",
        "distinct_sensitive_ops_gte",
    ];
    for k in KEYS {
        if let Some(v) = value_after_key(obj, k) {
            return parse_predicate_value(k, v);
        }
    }
    Err("unknown predicate")
}

fn parse_predicate_value(key: &str, v: &str) -> Result<Predicate, &'static str> {
    match key {
        "op_equals" => Ok(Predicate::OpEquals(take_scalar(v)?.parse::<u8>().map_err(|_| "bad number")?)),
        "op_in" => Ok(Predicate::OpIn(u8_vec(v)?)),
        "duration_lt_ms" => Ok(Predicate::DurationLt(take_scalar(v)?.parse::<u32>().map_err(|_| "bad number")?)),
        "duration_gt_ms" => Ok(Predicate::DurationGt(take_scalar(v)?.parse::<u32>().map_err(|_| "bad number")?)),
        "interval_lt_ms" => Ok(Predicate::IntervalLt(take_scalar(v)?.parse::<u32>().map_err(|_| "bad number")?)),
        "count_in_window_gte" => {
            let o = object_from_value(v)?;
            Ok(Predicate::CountInWindowGte {
                window_s: get_int::<u32>(o, "window_s")?,
                gte: get_int::<u32>(o, "gte")?,
            })
        }
        "sample_rate_gte" => Ok(Predicate::SampleRateGte(take_scalar(v)?.parse::<f32>().map_err(|_| "bad float")?)),
        "user_present_equals" => Ok(Predicate::UserPresentEquals(bool_scalar(v)?)),
        "fg_state_equals" => Ok(Predicate::FgStateEquals(take_scalar(v)?.parse::<u8>().map_err(|_| "bad number")?)),
        "power_state_equals" => Ok(Predicate::PowerStateEquals(bool_scalar(v)?)),
        "intent_hint_equals" => Ok(Predicate::IntentHintEquals(bool_scalar(v)?)),
        "system_proxy_equals" => Ok(Predicate::SystemProxyEquals(bool_scalar(v)?)),
        "decl_purpose_not_in" => Ok(Predicate::DeclPurposeNotIn(u8_vec(v)?)),
        "decl_purpose_in" => Ok(Predicate::DeclPurposeIn(u8_vec(v)?)),
        "ks_d_gt" => Ok(Predicate::KsDGt(take_scalar(v)?.parse::<f32>().map_err(|_| "bad float")?)),
        "burst_entropy_between" => {
            let arr = array_from_value(v)?;
            let e = split_array_elements(arr)?;
            if e.len() != 2 {
                return Err("entropy needs 2 bounds");
            }
            Ok(Predicate::BurstEntropyBetween(
                e[0].parse::<f32>().map_err(|_| "bad float")?,
                e[1].parse::<f32>().map_err(|_| "bad float")?,
            ))
        }
        "coupled_op_ratio_gte" => {
            let o = object_from_value(v)?;
            Ok(Predicate::CoupledOpRatioGte {
                coupled_op: get_int::<u8>(o, "coupled_op")?,
                ratio: get_float(o, "ratio")?,
            })
        }
        "distinct_sensitive_ops_gte" => {
            let o = object_from_value(v)?;
            Ok(Predicate::DistinctSensitiveOpsGte {
                window_s: get_int::<u32>(o, "window_s")?,
                gte: get_int::<u32>(o, "gte")?,
            })
        }
        _ => Err("unknown predicate"),
    }
}

fn u8_vec(v: &str) -> Result<Vec<u8>, &'static str> {
    let arr = array_from_value(v)?;
    split_array_elements(arr)?
        .into_iter()
        .map(|e| e.parse::<u8>().map_err(|_| "bad number"))
        .collect()
}

fn bool_scalar(v: &str) -> Result<bool, &'static str> {
    match take_scalar(v)? {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err("bad bool"),
    }
}

fn parse_category(s: &str) -> Result<RuleCategory, &'static str> {
    match s {
        "out_of_scope" => Ok(RuleCategory::OutOfScope),
        "stealth_hours" => Ok(RuleCategory::StealthHours),
        "side_channel" => Ok(RuleCategory::SideChannel),
        "fingerprint" => Ok(RuleCategory::Fingerprint),
        _ => Err("bad category"),
    }
}

fn parse_kind(s: &str) -> Result<RuleKind, &'static str> {
    match s {
        "legit" => Ok(RuleKind::Legit),
        "observe" => Ok(RuleKind::Observe),
        "alert" => Ok(RuleKind::Alert),
        _ => Err("bad kind"),
    }
}

fn parse_batch(s: &str) -> Result<RuleBatch, &'static str> {
    match s {
        "A" => Ok(RuleBatch::A),
        "B" => Ok(RuleBatch::B),
        _ => Err("bad batch"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rules::builtin_rules;
    use crate::rules::EvalContext;
    use crate::rules::RuleEngine;
    use std::collections::HashMap;

    /// W6 D4 产物:真实 rules.v1.json(经编辑逐条手工核对,与 §2 清单一致)。
    const V1_JSON: &[u8] = include_bytes!("../rules/rules.v1.json");

    #[test]
    fn loads_all_20_rules_from_v1_json() {
        let rules = load_rules(V1_JSON).expect("rules.v1.json 应可解析");
        assert_eq!(rules.len(), 20, "文档 §2 声明 20 条规则");
        let mut ids: Vec<u16> = rules.iter().map(|r| r.id).collect();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids, (101..=120).collect::<Vec<u16>>(), "id 101..=120 且唯一");
        // 与 builtin_rules(经 §2 手工核对的可编译清单)逐字段一致 —— loader 与内置双源同构
        assert_eq!(rules, builtin_rules());
    }

    #[test]
    fn rejects_malformed_inputs() {
        // 非 UTF-8
        assert!(load_rules(b"\xff\xfe\x00").is_err());
        // 非顶层数组
        assert!(load_rules(b"{}").is_err());
        assert!(load_rules(br#"{"rules":[]}"#).is_err());
        // 顶层数组不闭合
        assert!(load_rules(br#"[{"id":1}"#).is_err());
        // 缺必填字段
        assert!(load_rules(br#"[{"id":1}]"#).is_err());
        // 未知谓词 key
        let bad_pred = br#"[{"id":1,"category":"out_of_scope","kind":"alert","severity":1,"min_tier":0,"batch":"A","debounce":{"window_s":1,"min_hits":1,"cool_down_s":1},"predicates":[{"op_equals_bad":1}]}]"#;
        assert!(load_rules(bad_pred).is_err());
        // 非法 category
        let bad_cat = br#"[{"id":1,"category":"nope","kind":"alert","severity":1,"min_tier":0,"batch":"A","debounce":{"window_s":1,"min_hits":1,"cool_down_s":1},"predicates":[{"op_equals":0}]}]"#;
        assert!(load_rules(bad_cat).is_err());
        // 谓词数越界(u8)
        let bad_u8 = br#"[{"id":1,"category":"out_of_scope","kind":"alert","severity":300,"min_tier":0,"batch":"A","debounce":{"window_s":1,"min_hits":1,"cool_down_s":1},"predicates":[{"op_equals":0}]}]"#;
        assert!(load_rules(bad_u8).is_err());
    }

    // ---------- W6 D5:20 条规则各自正例/反例 ----------

    /// 从规则谓词构造一条全部满足的 EvalContext。
    fn ctx_for_rule(rule: &Rule) -> EvalContext {
        let mut ctx = EvalContext {
            op: 255,
            uid: 1000,
            decl_purpose: 0,
            duration_ms: 0,
            avg_interval_ms: 0,
            sample_rate_hz: 0.0,
            user_present: true,
            fg_state: 0,
            power_state: true,
            intent_hint: true,
            system_proxy: true,
            count_in_window: HashMap::new(),
            ks_d: None,
            burst_entropy: None,
            coupling_ratio: None,
            distinct_sensitive_ops: None,
        };
        for p in &rule.predicates {
            apply_satisfy(&mut ctx, p);
        }
        ctx
    }

    fn apply_satisfy(ctx: &mut EvalContext, p: &Predicate) {
        match p {
            Predicate::OpEquals(v) => ctx.op = *v,
            Predicate::OpIn(list) => ctx.op = list[0],
            Predicate::DurationLt(_) => ctx.duration_ms = 0,
            Predicate::DurationGt(ms) => ctx.duration_ms = ms.saturating_add(1),
            Predicate::IntervalLt(_) => ctx.avg_interval_ms = 0,
            Predicate::CountInWindowGte { window_s, gte } => {
                let cur = ctx.count_in_window.get(window_s).copied().unwrap_or(0);
                ctx.count_in_window.insert(*window_s, cur.max(*gte));
            }
            Predicate::SampleRateGte(hz) => ctx.sample_rate_hz = *hz,
            Predicate::UserPresentEquals(v) => ctx.user_present = *v,
            Predicate::FgStateEquals(v) => ctx.fg_state = *v,
            Predicate::PowerStateEquals(v) => ctx.power_state = *v,
            Predicate::IntentHintEquals(v) => ctx.intent_hint = *v,
            Predicate::SystemProxyEquals(v) => ctx.system_proxy = *v,
            Predicate::DeclPurposeNotIn(list) => {
                ctx.decl_purpose = (0u8..=6).find(|c| !list.contains(c)).unwrap_or(7);
            }
            Predicate::DeclPurposeIn(list) => ctx.decl_purpose = list[0],
            Predicate::KsDGt(t) => ctx.ks_d = Some(t + 0.05),
            Predicate::BurstEntropyBetween(lo, hi) => ctx.burst_entropy = Some((lo + hi) / 2.0),
            Predicate::CoupledOpRatioGte { coupled_op, ratio } => {
                ctx.coupling_ratio
                    .get_or_insert_with(HashMap::new)
                    .insert(*coupled_op, *ratio);
            }
            Predicate::DistinctSensitiveOpsGte { window_s, gte } => {
                ctx.distinct_sensitive_ops
                    .get_or_insert_with(HashMap::new)
                    .insert(*window_s, *gte);
            }
        }
    }

    /// 翻转首谓词使其必然不满足(其余谓词保持满足),用于反例。
    fn break_first_pred(ctx: &mut EvalContext, p: &Predicate) {
        match p {
            Predicate::OpEquals(v) => ctx.op = v.wrapping_add(1),
            Predicate::OpIn(_) => ctx.op = 255,
            Predicate::DurationLt(ms) => ctx.duration_ms = ms.saturating_add(1),
            Predicate::DurationGt(_) => ctx.duration_ms = 0,
            Predicate::IntervalLt(ms) => ctx.avg_interval_ms = ms.saturating_add(1),
            Predicate::CountInWindowGte { window_s, .. } => {
                ctx.count_in_window.remove(window_s);
            }
            Predicate::SampleRateGte(_) => ctx.sample_rate_hz = 0.0,
            Predicate::UserPresentEquals(v) => ctx.user_present = !v,
            Predicate::FgStateEquals(v) => ctx.fg_state = v.wrapping_add(1),
            Predicate::PowerStateEquals(v) => ctx.power_state = !v,
            Predicate::IntentHintEquals(v) => ctx.intent_hint = !v,
            Predicate::SystemProxyEquals(v) => ctx.system_proxy = !v,
            Predicate::DeclPurposeNotIn(list) => ctx.decl_purpose = list[0],
            Predicate::DeclPurposeIn(_) => ctx.decl_purpose = 200,
            Predicate::KsDGt(_) => ctx.ks_d = Some(0.0),
            Predicate::BurstEntropyBetween(_, _) => ctx.burst_entropy = Some(0.0),
            Predicate::CoupledOpRatioGte { coupled_op, .. } => {
                if let Some(m) = ctx.coupling_ratio.as_mut() {
                    m.remove(coupled_op);
                }
            }
            Predicate::DistinctSensitiveOpsGte { window_s, .. } => {
                if let Some(m) = ctx.distinct_sensitive_ops.as_mut() {
                    m.remove(window_s);
                }
            }
        }
    }

    fn eval_once(engine: &RuleEngine, rule: &Rule, ctx: &EvalContext, now_ns: i64) -> Option<crate::rules::RuleHit> {
        if rule.batch == RuleBatch::A {
            engine.eval_batch_a(ctx, rule.min_tier, now_ns)
        } else {
            engine.eval_batch_b(ctx, rule.min_tier, now_ns)
        }
    }

    #[test]
    fn every_rule_positive_and_negative() {
        let rules = load_rules(V1_JSON).unwrap();
        assert_eq!(rules.len(), 20);
        let base = 1_000_000_000i64;
        for rule in &rules {
            // 正例:全部谓词满足,min_hits 次命中后必告警(OBSERVE 级单次即返)
            let engine = RuleEngine::new(vec![rule.clone()]);
            let ctx = ctx_for_rule(rule);
            let attempts = rule.debounce.min_hits.max(1) as usize;
            let mut fired = None;
            for i in 0..attempts {
                fired = eval_once(&engine, rule, &ctx, base + (i as i64) * 30_000_000_000);
            }
            assert_eq!(
                fired.map(|h| h.rule_id),
                Some(rule.id),
                "正例应命中 rule {}",
                rule.id
            );

            // 反例:翻转首谓词,min_hits+2 次均不得命中
            let engine2 = RuleEngine::new(vec![rule.clone()]);
            let mut bad = ctx_for_rule(rule);
            break_first_pred(&mut bad, &rule.predicates[0]);
            for i in 0..(attempts + 2) {
                let hit = eval_once(&engine2, rule, &bad, base + (i as i64) * 30_000_000_000);
                assert!(hit.is_none(), "反例不应命中 rule {}", rule.id);
            }
        }
    }
}
