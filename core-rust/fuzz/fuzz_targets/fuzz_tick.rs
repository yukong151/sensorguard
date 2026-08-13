//! P4-3 (文档 §13):fuzz sg_tick —— 任意 TickInput FlatBuffers 字节应返回错误码而非崩溃。
//! 这是最复杂的入口:FlatBuffers 解析 + 窗口评估 + Verdict 构造全链路 fuzz。

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if data.is_empty() {
        return;
    }
    let mut out = vec![0u8; 4096];
    let mut out_len = 0usize;
    let _ = sensorguard::sg_tick(
        data.as_ptr(),
        data.len(),
        out.as_mut_ptr(),
        out.len(),
        &mut out_len,
    );
});