package com.sensorguard.app.logic

import com.sensorguard.app.jni.SgEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionRouterTest {

    // ---------- 文档 §6 干预路由表映射 ----------

    @Test
    fun `mic op routes to privacy settings deep link`() {
        val i = ActionRouter.resolve(SgEnum.OP_RECORD_AUDIO)
        assertEquals(ActionRouter.InterventionKind.PRIVACY_MIC, i?.kind)
        assertEquals(ActionRouter.ACTION_PRIVACY_SETTINGS, i?.intentAction)
    }

    @Test
    fun `camera op routes to privacy settings deep link`() {
        val i = ActionRouter.resolve(SgEnum.OP_CAMERA)
        assertEquals(ActionRouter.InterventionKind.PRIVACY_CAMERA, i?.kind)
        assertEquals(ActionRouter.ACTION_PRIVACY_SETTINGS, i?.intentAction)
    }

    @Test
    fun `imu ops route to sensor guide`() {
        for (op in intArrayOf(SgEnum.OP_ACCEL, SgEnum.OP_GYRO, SgEnum.OP_MAG)) {
            val i = ActionRouter.resolve(op)
            assertEquals(ActionRouter.InterventionKind.SENSOR_GUIDE, i?.kind)
            assertEquals(ActionRouter.ACTION_PRIVACY_SETTINGS, i?.intentAction)
        }
    }

    @Test
    fun `ops outside routing table resolve to null`() {
        // 定位/气压/光/接近不在 §6 干预路由表内
        for (op in intArrayOf(
            SgEnum.OP_FINE_LOCATION, SgEnum.OP_BARO,
            SgEnum.OP_LIGHT, SgEnum.OP_PROX,
        )) {
            assertNull("op=$op should not route", ActionRouter.resolve(op))
        }
    }
}