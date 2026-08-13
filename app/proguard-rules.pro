# SensorGuard v1.0 ProGuard/R8 规则 (W11 文档 §7 供应链/合规)
# 保留规则:JNI 入口、Rust 库、Room/序列化、枚举值。

# ── JNI 入口(SgNative,external fun 由 Kotlin 声明,保留类与 native 方法) ──
-keepclasseswithmembernames class * {
    native <methods>;
}

# Rust .so 通过 System.loadLibrary 加载,无需 java 侧保留,
# 但 Kotlin external fun 所在类必须保留(否则 JNI 方法表丢失)。
-keep class com.tabbit.sensorguard.jni.SgNative { *; }

# ── Room(Room 生成代码 + 实体) ──────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ── 枚举(SystemHealth.HealthLevel / RuleKind 等,反射/Debug 输出用) ─────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── FlatBuffers 生成类(契约字节码反射读取,混淆会破坏字段序) ───────────────
-keep class com.tabbit.sensorguard.jni.** { *; }

# ── 序列化(Parcelable/可序列化,当前无 Parcelable 但防未来回归) ─────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── 反射告警:不打日志(应用无三方 SDK,保持安静构建) ────────────────────────
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# ── 三方库缺失的注解类(W11: Tink 引用 errorprone 注解,仅编译期元数据) ─────
-dontwarn com.google.errorprone.annotations.**