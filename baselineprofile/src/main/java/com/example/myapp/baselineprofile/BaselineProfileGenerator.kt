package com.example.myapp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TYPED_LINE = "Une_ligne_assez_longue_pour_remplir_la_largeur"

// Runs against the phone's real data, which is why it only reads, except for the one note it
// types into on each pass and then sends to the notes trash.
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    // The default runs the journey up to 15 times waiting for the profile to settle, about 18 minutes
    // on the phone; three passes already catch the code that runs every time.
    fun generate() = rule.collect(packageName = "com.example.myapp", maxIterations = 3, stableIterations = 2) {
        // The framework reinstalls the app before each collection pass, resetting permissions.
        // Grant before launch so the notification permission dialog does not block startup.
        device.executeShellCommand("pm grant com.example.myapp android.permission.POST_NOTIFICATIONS")

        pressHome()
        startActivityAndWait()

        openFromMenu("Notes") {
            flingScrollable()
            if (tap("New note")) {
                // A long note typed a line at a time, the path that was slow.
                repeat(12) {
                    device.executeShellCommand("input text $TYPED_LINE")
                    device.executeShellCommand("input keyevent KEYCODE_ENTER")
                }
                device.waitForIdle()
                // Out of edit mode first, which draws the note in its read-only view.
                device.pressBack()
                device.waitForIdle()
                // Back on the list, the note goes to the trash instead of piling up in the real notes.
                repeat(3) {
                    if (!device.hasObject(By.text("New note"))) device.pressBack()
                    device.waitForIdle()
                }
                trashTypedNote()
            }
        }
        openFromMenu("Music") { flingScrollable() }
        openFromMenu("Flashcards") {
            flingScrollable()
            // Every card of every list, the biggest list the detail screen draws.
            if (tapDescription("Search everything")) {
                flingScrollable()
                device.pressBack()
                device.waitForIdle()
            }
            // A list's own cards, then a review round on it: one swipe, undone, so the card keeps its progress.
            if (tap(By.textEndsWith("to review"))) {
                flingScrollable()
                if (tap(By.text("Play"))) {
                    swipeCardAndUndo()
                    device.pressBack()
                    device.waitForIdle()
                }
                device.pressBack()
                device.waitForIdle()
            }
        }
        openFromMenu("Gallery") {
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
        // One back is not always enough (an open keyboard eats the first one), and a tool left open
        // makes every later step miss its label and silently record nothing.
        repeat(5) {
            if (device.hasObject(By.text("CogniTools"))) return
            device.pressBack()
            device.wait(Until.hasObject(By.text("CogniTools")), 2_000)
        }
    }

    // The bin of the card the journey typed: the nearest "Delete" below its first line, same column.
    private fun MacrobenchmarkScope.trashTypedNote() {
        try {
            val line = device.findObject(By.text(TYPED_LINE)) ?: return
            val card = line.visibleBounds
            val bin = device.findObjects(By.desc("Delete"))
                .filter { it.visibleBounds.top > card.top && it.visibleBounds.left < card.right && it.visibleBounds.right > card.left }
                .minByOrNull { it.visibleBounds.top } ?: return
            bin.click()
            // Only ever confirms a dialog that names the typed note, never a real one.
            if (device.wait(Until.hasObject(By.textContains("“$TYPED_LINE”")), 3_000)) tap("Ok")
            else device.pressBack()
        } catch (_: StaleObjectException) {
        }
    }

    // Only undoes when the swipe answered a card: without one the Undo button stays disabled.
    private fun MacrobenchmarkScope.swipeCardAndUndo() {
        if (!device.wait(Until.hasObject(By.textStartsWith("Cards to review")), 5_000)) return
        val y = device.displayHeight / 2
        device.swipe(device.displayWidth * 8 / 10, y, device.displayWidth / 10, y, 15)
        device.waitForIdle()
        Thread.sleep(500)
        device.findObject(By.desc("Undo").enabled(true))?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.tap(text: String): Boolean = tap(By.text(text))

    private fun MacrobenchmarkScope.tapDescription(description: String): Boolean = tap(By.desc(description))

    private fun MacrobenchmarkScope.tap(selector: BySelector): Boolean {
        val target = device.wait(Until.findObject(selector), 5_000) ?: return false
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
