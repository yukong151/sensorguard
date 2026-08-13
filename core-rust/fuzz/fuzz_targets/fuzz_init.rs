//! P4-3 (文档 §13):AFL/libFuzzer fuzz 6 个 JNI 入口 —— sg_init。
//! 运行: cargo +nightly fuzz run fuzz_init
//! 验收: 任意随机输入不 panic、不 native crash(错误码路径返回而非崩溃)。

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // sg_init 接收配置字节流;fuzz 任意 bytes 应拒绝或接受,绝不 panic。
    let _ = sensorguard::sg_init(data.as_ptr(), data.len());
});