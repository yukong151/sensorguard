package com.yuexiao12.sensorguard.probe

import com.yuexiao12.sensorguard.enums.SgEnum

/**
 * 内测版相机精确归因 (Shizuku T2 通道)。
 *
 * 解析 `dumpsys media.camera` 的 `Active Camera Clients` 段,提取每个活跃相机客户端的
 * 精确包名 + PID + 用户 ID,解决 CameraProbe(T0) 只能感知占用、无法归因的盲区。
 *
 * 真机实测格式(CameraService 标准 dump,相机打开时):
 *   `Active Camera Clients:`
 *   `[`
 *   `  (Camera ID: 4, Cost: 100, PID: 28221, Score: -2147483648, State: 2User Id: 0, Client Package Name: com.motorola.camera3, Conflicting Client Devices: {0, 2, 3, })`
 *   `]`
 * 相机关闭时:  `Active Camera Clients:` 后接 `[]`。
 */
object CameraServiceParser {

    /** 单个活跃相机客户端(精确归因结果)。*/
    data class CameraClient(
        val packageName: String,
        val pid: Int,
    )

    /**
     * 解析完整 `dumpsys media.camera` 输出中的 Active Camera Clients 段。
     * @return 当前活跃相机客户端(去重);无活跃客户端或解析失败返回空列表。
     */
    fun parse(output: String): List<CameraClient> {
        val result = ArrayList<CameraClient>()
        for (line in output.lineSequence()) {
            val m = CLIENT_RE.find(line) ?: continue
            val pkg = m.groupValues[2]
            val pid = m.groupValues[1].toIntOrNull() ?: continue
            result.add(CameraClient(pkg, pid))
        }
        return result.distinctBy { "${it.packageName}:${it.pid}" }
    }

    /** 活跃客户端行: `(Camera ID: 4, Cost: 100, PID: 123, ..., Client Package Name: pkg, ...)` */
    private val CLIENT_RE = Regex(
        "Camera\\s+ID:\\s*\\d+.*?PID:\\s*(\\d+).*?Client\\s+Package\\s+Name:\\s*(\\S+)"
    )
}
