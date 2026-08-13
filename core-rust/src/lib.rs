#![deny(warnings)]
// W2~W3: 生成代码(flatbuffers --gen-object-api)引用 alloc::vec::Vec。
// crate 根显式 extern crate alloc,保证嵌套 mod schema 内可见(E0433 修复)。
#[allow(unused_extern_crates)]
extern crate alloc;
pub mod event_window;
mod ffi;
pub mod iforest;
pub mod ring;
pub mod sensor_baseline;
pub mod rules;
pub mod rules_loader;
pub mod state;
pub mod stats;
pub mod thresholds;
pub mod verdict;

// W2~W3 (文档 §4.1): FlatBuffers 绑定由 build.rs 中 flatc 从 schemas/sensorguard.fbs
// 静态生成到 OUT_DIR(契约唯一来源),此处 include 挂载。
// Deviation(doc-frozen): 生成代码含大量平台无关 len/aligned 死代码,clippy 原生告警。
// 生成器输出自带 #[allow(unused_imports, dead_code)],此处不改动生成文件。
#[allow(clippy::all, unused_extern_crates)]
pub mod schema {
    include!(concat!(env!("OUT_DIR"), "/sensorguard_generated.rs"));
}

pub use ffi::*;
