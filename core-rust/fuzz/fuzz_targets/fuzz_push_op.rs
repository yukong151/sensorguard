//! P4-3 (文档 §13):fuzz sg_push_op —— 任意 FlatBuffers 字节应返回错误码而非崩溃。
//! FlatBuffers root 解析对恶意输入需返回 E_INVALID_ARG,不 panic。

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = sensorguard::sg_push_op(data.as_ptr(), data.len());
});