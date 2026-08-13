use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

// W2~W3 (文档 §12): build.rs 从占位改为真正接入 flatc 代码生成。
// fbs 是契约唯一来源(文档 §4.1),生成的 Rust 绑定输出到 OUT_DIR,
// 由 lib.rs 以 include!(concat!(env!("OUT_DIR"), ...)) 挂载。
fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let fbs = manifest.join("../schemas/sensorguard.fbs");
    let fbs_abs = fbs.canonicalize().unwrap_or(fbs);
    println!("cargo:rerun-if-changed={}", fbs_abs.display());

    let flatc = env::var("FLATC")
        .map(PathBuf::from)
        .unwrap_or_else(|_| manifest.join("../tools/flatc-25.12.19/flatc.exe"));
    let flatc_abs = flatc.canonicalize().unwrap_or(flatc);
    println!("cargo:rerun-if-changed={}", flatc_abs.display());

    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let out_str = out.to_str().expect("OUT_DIR non-utf8");

    // --gen-object-api 生成 builder side(.add_*/.create_*),ffi 组装 VerdictBatch 时使用
    let status = Command::new(&flatc_abs)
        .args(["--rust", "--gen-mutable", "--gen-object-api", "-o", out_str])
        .arg(&fbs_abs)
        .status()
        .expect("failed to run flatc");
    assert!(status.success(), "flatc codegen failed (exit {status})");

    // 平台无关代码,不依赖 cc 工具链
    let _ = Path::new("");
}
