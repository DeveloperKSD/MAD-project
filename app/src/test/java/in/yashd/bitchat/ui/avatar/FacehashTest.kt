package `in`.yashd.bitchat.ui.avatar

import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource

class FacehashTest {
    @Test
    fun `matches JavaScript hash face color pose and blink fixtures`() {
        // Generated with my-avatar-app's UTF-16 hash and computeFacehash formulas in Node.
        data class Fixture(val name: String, val hash: Long, val face: Int, val color: String, val pose: Int, val delay: Int)
        val fixtures = listOf(
            Fixture("", 0, 0, "#ec4899", 0, 0),
            Fixture("Alice", 63350368, 0, "#f97316", 7, 800),
            Fixture("Bob", 66965, 1, "#ec4899", 5, 3500),
            Fixture("Charlie", 1891246254, 2, "#10b981", 6, 3400),
            Fixture("Diana", 66024207, 3, "#3b82f6", 0, 1700),
            Fixture("agent-47", 1469108811, 3, "#f59e0b", 3, 2100),
            Fixture("john@example.com", 326742856, 0, "#f59e0b", 7, 1600),
            Fixture("你好", 652829, 1, "#10b981", 5, 1900),
            Fixture("😀", 1772899, 3, "#10b981", 7, 2900),
            Fixture("polygenelubricants", 2147483648, 0, "#f97316", 2, 800),
            Fixture(" Alice", 979483200, 0, "#ec4899", 6, 0),
        )
        val gradientCenters = listOf(44 to 44, 44 to 56, 50 to 56, 44 to 50, 50 to 44, 50 to 50, 56 to 50, 56 to 44, 56 to 56)
        for (fixture in fixtures) {
            val scene = Facehash.scene(fixture.name)
            assertEquals(fixture.name, fixture.hash, scene.hash)
            assertEquals(fixture.face, scene.faceIndex)
            assertEquals(fixture.color, scene.color)
            assertEquals(gradientCenters[fixture.pose], scene.gradientX to scene.gradientY)
            assertEquals(fixture.delay, scene.blinkDelayMs)
            assertEquals(2000 + fixture.delay, scene.blinkDurationMs)
            assertEquals(Facehash.svg(fixture.name), Facehash.svg(fixture.name))
        }
    }

    @Test
    fun `SVG is self contained with both eyes initial gradient and reduced motion support`() {
        for (name in listOf("Alice", "Bob", "Charlie", "Diana")) {
            val document = parse(Facehash.svg(name))
            assertEquals("http://www.w3.org/2000/svg", document.documentElement.namespaceURI)
            assertEquals("0 0 100 100", document.documentElement.getAttribute("viewBox"))
            assertEquals(name.take(1), document.getElementsByTagName("text").item(0).textContent)
            val gradient = document.getElementsByTagName("radialGradient").item(0)
            assertTrue(gradient.attributes.getNamedItem("id").nodeValue.startsWith("facehash-"))
            val svg = Facehash.svg(name)
            assertEquals(2, Regex("class=\"facehash-eye\"").findAll(svg).count())
            assertTrue(svg.contains("prefers-reduced-motion:reduce"))
            assertTrue(svg.contains("animation-duration:${Facehash.scene(name).blinkDurationMs}ms"))
            assertFalse(svg.contains("<script"))
            assertFalse(svg.contains("href="))
        }
    }

    @Test
    fun `escapes initials without injecting SVG and rejects malformed XML characters`() {
        for (name in listOf("<script>alert(1)</script>", "&alice", ">bob", "\"charlie", "'diana", "😀", "ß")) {
            val document = parse(Facehash.svg(name))
            assertEquals(Facehash.scene(name).initial, document.getElementsByTagName("text").item(0).textContent)
            assertEquals(0, document.getElementsByTagName("script").length)
        }
        for (name in listOf("", "\u0000name", "\uD800", "\uDC00", "\uFFFE", "\uFFFF")) {
            assertEquals("", parse(Facehash.svg(name)).getElementsByTagName("text").item(0).textContent)
        }
        assertEquals("😀", Facehash.scene("😀").initial)
    }

    @Test
    fun `static export has no animation and uppercase is locale independent`() {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("I", Facehash.scene("istanbul").initial)
            val svg = Facehash.svg("istanbul", animated = false)
            val document = parse(svg)
            assertEquals(0, document.getElementsByTagName("style").length)
            assertFalse(svg.contains("animation"))
            assertEquals("I", document.getElementsByTagName("text").item(0).textContent)
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `projection matches the original SVG transform order`() {
        val center = Facehash.scene("Bob")
        listOf(1f, 0f, 0f, 1f, 0f, 0f).zip(center.matrix).forEach { (a, b) -> assertEquals(a, b, 0f) }
        val alice = Facehash.scene("Alice")
        // seed pose (-1, -1): translate(-5, 5), skewX(3.5), skewY(-1.75), scale(.92, .92)
        val expected = listOf(.91828081f, -.028108542f, .05626961f, .92f, -3.727521f, 10.405427f)
        expected.zip(alice.matrix).forEach { (a, b) -> assertEquals(a, b, .00002f) }
        assertEquals(60f * 15 / 63, alice.geometry.height * alice.eyeScale, .00001f)
    }

    private fun parse(svg: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(InputSource(StringReader(svg)))
}
