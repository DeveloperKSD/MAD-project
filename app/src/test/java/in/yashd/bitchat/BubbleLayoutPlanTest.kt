package `in`.yashd.bitchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLayoutPlanTest {
    private fun plan(
        lineCount: Int = 1, textW: Int = 100, textH: Int = 40,
        lineRight: Float = 100f, lineTop: Float = 0f, lineBottom: Float = 40f,
        metaW: Int = 60, metaH: Int = 20,
        minW: Int = 0, maxW: Int = 300, maxH: Int = Int.MAX_VALUE,
        gap: Int = 6, below: Int = 2
    ) = planBubbleLayout(lineCount, textW, textH, lineRight, lineTop, lineBottom,
        metaW, metaH, minW, maxW, maxH, gap, below)

    @Test
    fun `meta tucks beside the last line when it fits`() {
        val p = plan()
        assertTrue(p.inline)
        assertEquals(100 + 6 + 60, p.width)
        assertTrue(p.metaY in 0..(p.height - 20))
        assertTrue(p.metaX + 60 <= p.width)
    }

    @Test
    fun `meta drops below right when the last line is full`() {
        val p = plan(lineRight = 290f, textW = 290)
        assertFalse(p.inline)
        assertEquals(290, p.width)
        assertEquals(40 + 2 + 20, p.height)
        assertEquals(42, p.metaY)
    }

    @Test
    fun `meta taller than one-line text never coerces into an empty range`() {
        // Regression: coerceIn(0, height - metaHeight) threw when meta was taller.
        val p = plan(textH = 14, lineBottom = 14f, metaH = 30)
        assertTrue(p.metaY in 0..(p.height - p.metaY).coerceAtLeast(0))
        assertTrue(p.metaY >= 0)
        assertTrue(p.metaY + 30 <= p.height || p.height == 14)
    }

    @Test
    fun `blank body stacks meta under text`() {
        val p = plan(lineCount = 0, textW = 0, textH = 0)
        assertFalse(p.inline)
        assertEquals(60, p.width)
        assertEquals(2, p.metaY)
    }

    @Test
    fun `width respects min and max constraints`() {
        val small = plan(lineRight = 40f, textW = 40, minW = 120)
        assertEquals(120, small.width)
        val tight = plan(lineRight = 200f, textW = 200, maxW = 250)
        assertTrue(tight.width <= 250)
        assertFalse(tight.inline)
    }

    @Test
    fun `exact fit stays inline`() {
        val p = plan(lineRight = 234f, textW = 234, metaW = 60, maxW = 300) // 234+6+60 == 300
        assertTrue(p.inline)
    }
}
