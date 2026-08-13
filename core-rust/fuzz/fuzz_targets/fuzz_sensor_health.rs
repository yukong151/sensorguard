//! P4-3 (文档 §13):fuzz sg_sensor_health —— 输出缓冲 cap 任意,应返回错误码/合法字节而非崩溃。

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let cap = data.len().min(65536);
    let mut out = vec![0u8; cap];
    let mut out_len = 0usize;
    let _ = sensorguard::sg_sensor_health(out.as_mut_ptr(), out.len(), &mut out_len);
});