//! P4-3 (文档 §13):fuzz sg_push_sensor —— 任意标量输入应返回错误码而非 panic。

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if data.len() < 16 {
        return;
    }
    let ts_ns = i64::from_le_bytes(data[0..8].try_into().unwrap());
    let kind = data[8];
    let x = f32::from_le_bytes(data[9..13].try_into().unwrap());
    let y = f32::from_le_bytes(data[13..17].try_into().unwrap());
    let z = f32::from_le_bytes(data[17..21].try_into().unwrap());
    let _ = sensorguard::sg_push_sensor(ts_ns, kind, x, y, z);
});