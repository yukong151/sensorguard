//! JNI C 桥接层(W2 · 文档 §9 Kick-off)
//!
//! 手写 `extern "C" JNIEXPORT` 函数直调 `sg_*`,**零 `jni` crate 依赖**
//! (文档明确:避免 jni crate 引入 ~100 KB 体积)。
//!
//! 符号命名遵循 JNI 规范 `Java_<包名>_<类名>_<方法名>`:
//!   Kotlin `object SgNative`(包 com.yuexiao12.sensorguard.jni)→ 实例方法(非 static),
//!   第二参数为 `jobject`(单例 INSTANCE)。
//!
//! 类型模型(NDK jni.h C 模式):
//!   - `JNIEnv = const struct JNINativeInterface*`;原生函数收到的 `env: *mut JNIEnv`
//!     是"指向表指针的指针",`**env` 即函数表。
//!   - 引用类型皆为 `void*` → Rust 侧用 `*mut c_void`。
//!   - 函数表槽位索引按 NDK jni.h 逐条核对(0..232,共 233 项),仅取用 3 个槽位。
//!
//! 仅 Android 目标编译(`cfg(target_os = "android")`),host 构建不包含。

use super::{sg_init, sg_push_op, sg_push_sensor, sg_sensor_health, sg_shutdown, sg_snapshot, sg_tick, E_RESOURCE};
use std::ffi::c_void;

// ---- JNI 基础类型(C 模式:引用类型皆为 void*) ----
pub type JInt = i32;
pub type JLong = i64;
pub type JByte = i8;
pub type JFloat = f32;
pub type JSize = i32;
pub type JBoolean = u8;
/// jobject / jarray / jbyteArray 在 C 模式下都是 `void*`
pub type JObject = *mut c_void;
pub type JArray = *mut c_void;
pub type JByteArray = *mut c_void;

/// ReleaseByteArrayElements 模式:不拷回,仅释放(输入数组用)
const JNI_ABORT: JInt = 2;
/// 模式 0:拷回内容并释放(输出数组用)

/// JNIEnv 不透明结构:首字段为函数表指针,与 ART JNIEnvExt 布局一致(偏移 0)
#[repr(C)]
pub struct JNIEnv {
    functions: *const JNINativeInterface,
}

// ---- JNINativeInterface 函数表(槽位索引与 NDK jni.h 逐一核对) ----
type FnGetArrayLength = unsafe extern "C" fn(*mut JNIEnv, JArray) -> JSize;
type FnGetByteArrayElements =
    unsafe extern "C" fn(*mut JNIEnv, JByteArray, *mut JBoolean) -> *mut JByte;
type FnReleaseByteArrayElements = unsafe extern "C" fn(*mut JNIEnv, JByteArray, *mut JByte, JInt);

/// JNINativeInterface 函数表:仅声明用到的槽位,其余以 `[usize; N]` 填充
/// (函数指针与 usize 同宽,填充保证后续槽位偏移精确)。
#[repr(C)]
struct JNINativeInterface {
    _pad_0_170: [usize; 171],                                // 槽位 0..170
    get_array_length: FnGetArrayLength,                      // 槽位 171: GetArrayLength
    _pad_172_183: [usize; 12],                               // 槽位 172..183
    get_byte_array_elements: FnGetByteArrayElements,         // 槽位 184: GetByteArrayElements
    _pad_185_191: [usize; 7],                                // 槽位 185..191
    release_byte_array_elements: FnReleaseByteArrayElements, // 槽位 192: ReleaseByteArrayElements
    _pad_193_232: [usize; 40],                               // 槽位 193..232
}

/// 取出当前 JNIEnv 的函数表(env 在调用期内始终有效)
#[inline]
unsafe fn table(env: *mut JNIEnv) -> &'static JNINativeInterface {
    // SAFETY: JNI 回调期间 env 有效,且函数表生命周期为整个 .so
    &*((*env).functions)
}

// ---- JNI 桥接入口(符号名 = JNI 规范命名) ----

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgInit(
    env: *mut JNIEnv,
    _this: JObject,
    cfg: JByteArray,
) -> JInt {
    if cfg.is_null() {
        return sg_init(std::ptr::null(), 0);
    }
    unsafe {
        let t = table(env);
        let len = (t.get_array_length)(env, cfg) as usize;
        let mut is_copy: JBoolean = 0;
        let ptr = (t.get_byte_array_elements)(env, cfg, &mut is_copy);
        if ptr.is_null() {
            return E_RESOURCE;
        }
        let rc = sg_init(ptr.cast::<u8>(), len);
        (t.release_byte_array_elements)(env, cfg, ptr, JNI_ABORT);
        rc
    }
}

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgPushSensor(
    _env: *mut JNIEnv,
    _this: JObject,
    ts_ns: JLong,
    kind: JByte,
    x: JFloat,
    y: JFloat,
    z: JFloat,
) -> JInt {
    // 纯标量,无 JNI 数组访问,直接转发
    sg_push_sensor(ts_ns, kind as u8, x, y, z)
}

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgPushOp(
    env: *mut JNIEnv,
    _this: JObject,
    buf: JByteArray,
) -> JInt {
    if buf.is_null() {
        return sg_push_op(std::ptr::null(), 0);
    }
    unsafe {
        let t = table(env);
        let len = (t.get_array_length)(env, buf) as usize;
        let mut is_copy: JBoolean = 0;
        let ptr = (t.get_byte_array_elements)(env, buf, &mut is_copy);
        if ptr.is_null() {
            return E_RESOURCE;
        }
        let rc = sg_push_op(ptr.cast::<u8>(), len);
        (t.release_byte_array_elements)(env, buf, ptr, JNI_ABORT);
        rc
    }
}

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgTick(
    env: *mut JNIEnv,
    _this: JObject,
    input: JByteArray,
    out: JByteArray,
) -> JInt {
    unsafe {
        let t = table(env);
        // 输入数组(只读)
        let in_len = (t.get_array_length)(env, input) as usize;
        let mut is_copy: JBoolean = 0;
        let in_ptr = (t.get_byte_array_elements)(env, input, &mut is_copy);
        if in_ptr.is_null() {
            return E_RESOURCE;
        }
        // 输出数组(可写)
        let out_cap = (t.get_array_length)(env, out) as usize;
        let out_ptr = (t.get_byte_array_elements)(env, out, &mut is_copy);
        if out_ptr.is_null() {
            (t.release_byte_array_elements)(env, input, in_ptr, JNI_ABORT);
            return E_RESOURCE;
        }
        let mut out_len: usize = 0;
        let rc = sg_tick(
            in_ptr.cast::<u8>(),
            in_len,
            out_ptr.cast::<u8>(),
            out_cap,
            &mut out_len,
        );
        // 输入不回写(ABORT);输出拷回(模式 0)
        (t.release_byte_array_elements)(env, input, in_ptr, JNI_ABORT);
        (t.release_byte_array_elements)(env, out, out_ptr, 0);
        if rc < 0 {
            rc
        } else {
            out_len as JInt // Kotlin 约定:>=0 为 out 有效长度
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgSnapshot(
    env: *mut JNIEnv,
    _this: JObject,
    out: JByteArray,
) -> JInt {
    unsafe {
        let t = table(env);
        let out_cap = (t.get_array_length)(env, out) as usize;
        let mut is_copy: JBoolean = 0;
        let out_ptr = (t.get_byte_array_elements)(env, out, &mut is_copy);
        if out_ptr.is_null() {
            return E_RESOURCE;
        }
        let mut out_len: usize = 0;
        let rc = sg_snapshot(out_ptr.cast::<u8>(), out_cap, &mut out_len);
        (t.release_byte_array_elements)(env, out, out_ptr, 0);
        if rc < 0 {
            rc
        } else {
            out_len as JInt
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgShutdown(
    _env: *mut JNIEnv,
    _this: JObject,
) -> JInt {
    sg_shutdown()
}

#[no_mangle]
pub extern "C" fn Java_com_sensorguard_sensorguard_jni_SgNative_sgSensorHealth(
    env: *mut JNIEnv,
    _this: JObject,
    out: JByteArray,
) -> JInt {
    unsafe {
        let t = table(env);
        let out_cap = (t.get_array_length)(env, out) as usize;
        let mut is_copy: JBoolean = 0;
        let out_ptr = (t.get_byte_array_elements)(env, out, &mut is_copy);
        if out_ptr.is_null() {
            return E_RESOURCE;
        }
        let mut out_len: usize = 0;
        let rc = sg_sensor_health(out_ptr.cast::<u8>(), out_cap, &mut out_len);
        (t.release_byte_array_elements)(env, out, out_ptr, 0);
        if rc < 0 {
            rc
        } else {
            out_len as JInt
        }
    }
}
