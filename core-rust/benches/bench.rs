//! W2~W3 (文档 §9):criterion 基准 — 性能预算门禁回归。
//!   预算(文档 §9):sg_push_sensor P99 ≤ 5 μs;sg_tick P99 ≤ 6 ms。
//!   本基准为 host 侧回归基线,真机预算以 §13 W10 压测为准。
//!   附加手动 P99 采样:文档预算按 P99 定义,criterion 默认仅报 mean。
//!
//! W10 (文档 §13):补充 warm path 基准 —— sg_push_op(≤ 80 μs)+
//!   verdict 解码,覆盖 §9 预算矩阵的 Warm 行。

use criterion::{criterion_group, criterion_main, BatchSize, Criterion};
use flatbuffers::FlatBufferBuilder;
use sensorguard::event_window::WINDOWS;
use sensorguard::ring::RING;
use sensorguard::schema::sg;
use sensorguard::{sg_init, sg_push_op, sg_push_sensor, sg_tick, E_OK, E_RESOURCE};
use std::hint::black_box;
use std::ptr;
use std::time::Instant;

const T0: i64 = 1_700_000_000_000_000_000;
const UID: i32 = 10_000;
const OP: u8 = 0;
// 文档 §9 预算(ns)
const PUSH_SENSOR_BUDGET_NS: u64 = 5_000;
const TICK_BUDGET_NS: u64 = 6_000_000;
// W10: Warm 路径预算(文档 §9:OpEvent ≤ 80 μs,verdict 解码 ≤ 100 μs)
const PUSH_OP_BUDGET_NS: u64 = 80_000;
const VERDICT_DECODE_BUDGET_NS: u64 = 100_000;

/// 构造合法 OpEvent FlatBuffers(与 ffi 测试构造器同构)
fn make_op_event(ts_ns: i64) -> Vec<u8> {
    let mut b = FlatBufferBuilder::new();
    let mut eb = sg::OpEventBuilder::new(&mut b);
    eb.add_ts_ns(ts_ns);
    eb.add_uid(UID);
    eb.add_op(sg::OpKind(OP));
    let root = eb.finish();
    b.finish(root, None);
    b.finished_data().to_vec()
}

/// 构造 TickInput FlatBuffers,active_pairs 含单个 (UID, OP)
fn make_tick_input(tick_id: u64, now_ns: i64) -> Vec<u8> {
    let mut b = FlatBufferBuilder::new();
    let ph = sg::PkgHash([1u8; 12]);
    let aps = [sg::ActivePair::new(UID, sg::OpKind(OP), &ph)];
    let v = b.create_vector(&aps);
    let mut tb = sg::TickInputBuilder::new(&mut b);
    tb.add_tick_id(tick_id);
    tb.add_now_ns(now_ns);
    tb.add_active_pairs(v);
    let root = tb.finish();
    b.finish(root, None);
    b.finished_data().to_vec()
}

/// 预填 60 个 1s 间隔事件到窗口(触发 changed),供 sg_tick 评估。
fn prefill_window() {
    WINDOWS.lock().unwrap().clear();
    for i in 0..60 {
        let buf = make_op_event(T0 + i * 1_000_000_000);
        assert_eq!(sg_push_op(buf.as_ptr(), buf.len()), E_OK);
    }
}

fn bench_push_sensor(c: &mut Criterion) {
    assert_eq!(sg_init(ptr::null(), 0), E_OK);
    // 清空 ring 至空,从空态开始
    while RING.pop().is_some() {}
    c.bench_function("sg_push_sensor", |b| {
        b.iter(|| {
            let rc = sg_push_sensor(T0, 10, 0.1, 0.2, 0.3);
            if rc == E_RESOURCE {
                // 满 ring 稳态:腾一位再入,保持"满 ring 推入循环"测态
                let _ = RING.pop();
            }
            black_box(rc)
        });
    });
    // 手动 P99 采样(文档预算按 P99 定义)
    let mut samples = Vec::with_capacity(10_000);
    for _ in 0..10_000 {
        let t = Instant::now();
        let rc = sg_push_sensor(T0, 10, 0.1, 0.2, 0.3);
        if rc == E_RESOURCE {
            let _ = RING.pop();
        }
        black_box(rc);
        samples.push(t.elapsed().as_nanos() as u64);
    }
    samples.sort_unstable();
    let p99 = samples[9_899];
    eprintln!("[门禁] sg_push_sensor P99 = {p99} ns(预算 {PUSH_SENSOR_BUDGET_NS} ns)");
    assert!(
        p99 <= PUSH_SENSOR_BUDGET_NS,
        "sg_push_sensor P99 超预算:{p99} ns > {PUSH_SENSOR_BUDGET_NS} ns"
    );
}

fn bench_tick(c: &mut Criterion) {
    assert_eq!(sg_init(ptr::null(), 0), E_OK);
    c.bench_function("sg_tick", |b| {
        b.iter_batched(
            || {
                prefill_window();
                let input = make_tick_input(42, T0 + 60 * 1_000_000_000);
                let out = vec![0u8; 4096];
                (input, out)
            },
            |(input, mut out)| {
                let mut out_len = 0usize;
                black_box(sg_tick(
                    input.as_ptr(),
                    input.len(),
                    out.as_mut_ptr(),
                    out.len(),
                    &mut out_len,
                ))
            },
            BatchSize::SmallInput,
        );
    });
    // 手动 P99 采样:计时仅包 sg_tick,setup(清窗+预填)在计时外
    let mut samples = Vec::with_capacity(1_000);
    for _ in 0..1_000 {
        prefill_window();
        let input = make_tick_input(42, T0 + 60 * 1_000_000_000);
        let mut out = vec![0u8; 4096];
        let mut out_len = 0usize;
        let t = Instant::now();
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_OK);
        samples.push(t.elapsed().as_nanos() as u64);
    }
    samples.sort_unstable();
    let p99 = samples[989];
    eprintln!("[门禁] sg_tick P99 = {p99} ns(预算 {TICK_BUDGET_NS} ns)");
    assert!(
        p99 <= TICK_BUDGET_NS,
        "sg_tick P99 超预算:{p99} ns > {TICK_BUDGET_NS} ns"
    );
}

/// W10: Warm 路径基准 —— sg_push_op(OpEvent 反序列化 + 窗口落桶)。
fn bench_push_op_warm(c: &mut Criterion) {
    assert_eq!(sg_init(ptr::null(), 0), E_OK);
    let buf = make_op_event(T0);
    c.bench_function("sg_push_op", |b| {
        b.iter(|| {
            // 复用同一 uid/op,get_or_create 命中既有窗口,仅测解析+落桶
            let rc = sg_push_op(buf.as_ptr(), buf.len());
            black_box(rc)
        })
    });
    // 手动 P99 采样(预算按 P99 定义)
    let mut samples = Vec::with_capacity(1_000);
    for _ in 0..1_000 {
        let t = Instant::now();
        let rc = sg_push_op(buf.as_ptr(), buf.len());
        black_box(rc);
        samples.push(t.elapsed().as_nanos() as u64);
    }
    samples.sort_unstable();
    let p99 = samples[989];
    eprintln!("[门禁] sg_push_op P99 = {p99} ns(预算 {PUSH_OP_BUDGET_NS} ns)");
    assert!(
        p99 <= PUSH_OP_BUDGET_NS,
        "sg_push_op P99 超预算:{p99} ns > {PUSH_OP_BUDGET_NS} ns"
    );
}

/// W10: Warm 路径基准 —— sg_tick 输出 VerdictBatch 的 FlatBuffers 解码(0 拷贝 root 读取)。
fn bench_tick_decode_warm(c: &mut Criterion) {
    assert_eq!(sg_init(ptr::null(), 0), E_OK);
    // 预生成含 1 个 verdict 的 batch 输出,测解码(read-only)不测评估
    prefill_window();
    let input = make_tick_input(42, T0 + 60 * 1_000_000_000);
    let mut out = vec![0u8; 4096];
    let mut out_len = 0usize;
    let rc = sg_tick(
        input.as_ptr(),
        input.len(),
        out.as_mut_ptr(),
        out.len(),
        &mut out_len,
    );
    assert_eq!(rc, E_OK);
    assert!(out_len > 0, "tick 应有输出");
    let batch_bytes = out[..out_len].to_vec();

    c.bench_function("verdict_batch_decode", |b| {
        b.iter(|| {
            let batch = flatbuffers::root::<sg::VerdictBatch>(&batch_bytes).expect("valid batch");
            black_box(batch.verdicts().map(|v| v.len()).unwrap_or(0))
        })
    });
    // 手动 P99 采样
    let mut samples = Vec::with_capacity(1_000);
    for _ in 0..1_000 {
        let t = Instant::now();
        let batch = flatbuffers::root::<sg::VerdictBatch>(&batch_bytes).expect("valid batch");
        black_box(batch.verdicts().map(|v| v.len()).unwrap_or(0));
        samples.push(t.elapsed().as_nanos() as u64);
    }
    samples.sort_unstable();
    let p99 = samples[989];
    eprintln!("[门禁] verdict_batch_decode P99 = {p99} ns(预算 {VERDICT_DECODE_BUDGET_NS} ns)");
    assert!(
        p99 <= VERDICT_DECODE_BUDGET_NS,
        "verdict_batch_decode P99 超预算:{p99} ns > {VERDICT_DECODE_BUDGET_NS} ns"
    );
}

criterion_group!(benches, bench_push_sensor, bench_tick, bench_push_op_warm, bench_tick_decode_warm);
criterion_main!(benches);
