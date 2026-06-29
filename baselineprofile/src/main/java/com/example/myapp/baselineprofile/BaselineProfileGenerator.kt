package com.example.myapp.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.example.myapp") {
        // The framework reinstalls the app before each collection pass, resetting permissions.
        // Grant before launch so the notification permission dialog does not block startup.
        device.executeShellCommand("pm grant com.example.myapp android.permission.POST_NOTIFICATIONS")

        pressHome()
        startActivityAndWait()

        device.findObject(By.text("Flashcards"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}
