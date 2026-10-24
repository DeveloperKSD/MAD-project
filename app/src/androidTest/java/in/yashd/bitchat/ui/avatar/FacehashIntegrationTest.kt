package `in`.yashd.bitchat.ui.avatar

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import `in`.yashd.bitchat.PersonAvatar
import `in`.yashd.bitchat.RelayDesignTheme
import `in`.yashd.bitchat.RelayHeader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FacehashIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allEyeStylesBlinkPromptlyAndRepeatAtHeaderRowAndIdentitySizes() {
        compose.mainClock.autoAdvance = false
        val names = listOf("Alice", "Bob", "Charlie", "Diana")
        compose.setContent {
            RelayDesignTheme {
                Row {
                    names.zip(listOf(34, 42, 56, 84)).forEach { (name, size) ->
                        PersonAvatar(name, size.dp, Modifier.testTag(name))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val open = names.associateWith { pixels(it) }
        compose.mainClock.advanceTimeBy(960)
        names.forEach {
            assertFalse("$it should blink within a second", open.getValue(it).contentEquals(pixels(it)))
            assertTrue("$it should draw content", open.getValue(it).distinct().size > 10)
        }
        // Bob has a 3765ms interval; verify multiple complete cycles, not just one blink.
        repeat(2) {
            compose.mainClock.advanceTimeBy(200)
            assertTrue("Eyes should reopen", open.getValue("Bob").contentEquals(pixels("Bob")))
            compose.mainClock.advanceTimeBy(3765 - 200)
            assertFalse("Blinking should keep repeating", open.getValue("Bob").contentEquals(pixels("Bob")))
        }
    }

    @Test
    fun profileAvatarKeepsAccessibleClickAction() {
        var clicked = false
        compose.setContent {
            RelayDesignTheme { RelayHeader("Relay", "Alice") { clicked = true } }
        }
        compose.onNodeWithContentDescription("Your profile").assertHasClickAction().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    private fun pixels(tag: String): IntArray {
        val image = compose.onNodeWithTag(tag).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }
}
