package com.commute.app

import android.Manifest
import android.os.Build
import com.commute.app.ble.requiredBleScanPermissions
import org.junit.Assert.assertEquals
import org.junit.Test

class BlePermissionTest {
    @Test
    fun `android 12 and newer needs bluetooth scan and fine location`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION),
            requiredBleScanPermissions(Build.VERSION_CODES.S).toList()
        )
    }

    @Test
    fun `before android 12 needs fine location only`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            requiredBleScanPermissions(Build.VERSION_CODES.R).toList()
        )
    }
}
