package com.example.myapp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Runs against the phone's real data, which is why it only reads, except for the one note it
// types into: the data is restored from a backup once the profile is recorded.
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

        openFromMenu("Notes") {
            flingScrollable()
            if (tap("Nouvelle note")) {
                // A long note typed a line at a time, the path that was slow.
                repeat(12) {
                    device.executeShellCommand("input text Une_ligne_assez_longue_pour_remplir_la_largeur")
                    device.executeShellCommand("input keyevent KEYCODE_ENTER")
                }
                device.waitForIdle()
                // Out of edit mode first, which draws the note in its read-only view.
                device.pressBack()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }
        }
        openFromMenu("Musique") { flingScrollable() }
        openFromMenu("Flashcards") { flingScrollable() }
        openFromMenu("Galerie") {
            flingScrollable()
            try {
                device.findObject(By.scrollable(true))?.children?.firstOrNull()?.click()
            } catch (_: StaleObjectException) {
            }
            device.waitForIdle()
            flingScrollable()
            device.pressBack()
        }
    }

    private fun MacrobenchmarkScope.openFromMenu(label: String, onScreen: MacrobenchmarkScope.() -> Unit) {
        if (!tap(label)) return
        device.waitForIdle()
        Thread.sleep(1500)
        onScreen()
        device.pressBack()
        device.wait(Until.hasObject(By.text("Bienvenue !")), 5_000)
    }

    private fun MacrobenchmarkScope.tap(text: String): Boolean {
        val target = device.wait(Until.findObject(By.text(text)), 5_000) ?: return false
        target.click()
        device.waitForIdle()
        return true
    }

    // Found again for each fling: the list recomposes under the first one and goes stale.
    private fun MacrobenchmarkScope.flingScrollable() {
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            try {
                val list = device.findObject(By.scrollable(true)) ?: return
                list.setGestureMargin(device.displayWidth / 5)
                list.fling(direction)
            } catch (_: StaleObjectException) {
            }
            device.waitForIdle()
        }
    }
}
