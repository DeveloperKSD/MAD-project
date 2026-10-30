package `in`.yashd.bitchat

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * App start from the Figma: the blue splash screen, then three onboarding pages you can swipe
 * through ("What Is Relay?", "How Relay Works?", "More Open Network"), then the normal app.
 *
 * UI only: nothing here touches Bluetooth, Nostr or storage. The pages are laid out on the
 * Figma's 393 x 852 frame (all positions below are in that frame's dp) and scaled to the phone's
 * width, so they keep the design's proportions on every screen. The illustrations are the SVGs
 * from the Figma, converted to vector drawables (res/drawable/onboarding_*.xml, relay_logo.xml).
 */

private val SplashBlue = Color(0xFF3E67E2)
private val OnboardingBackground = Color(0xFF101010)
private val OnboardingGray = Color(0xFF9A9A9A)
private val DotGray = Color(0xFF575757)

private const val FRAME_WIDTH = 393f        // Figma frame width (dp)
private const val BUTTON_BOTTOM = 815.3f    // Continue button's bottom edge in the frame
private const val BUTTON_TOP = 765.4f
private const val DOTS_TOP = 718.5f
private const val DOT_SIZE = 6f
private const val TEXTS_TOP = 540f          // top of the area holding each page's bottom texts
private const val TITLE_CAP_TOP = 675.5f
private const val SPLASH_MILLIS = 1600L

private const val START_PREFS = "relay_start"
private const val KEY_ONBOARDED = "onboarding_done"
private const val KEY_USERNAME = "username"

/**
 * Shows the blue splash, then - only on the first run - a name step and the onboarding
 * pages, then [content] (the app as it was before). Later launches go splash -> app.
 * Installs that already had the app (upgrade, not a fresh install) keep their identity
 * and never see the onboarding again.
 */
@Composable
fun RelayStartFlow(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.applicationContext.getSharedPreferences(START_PREFS, Context.MODE_PRIVATE) }
    val firstRun = remember {
        val done = prefs.getBoolean(KEY_ONBOARDED, false)
        // Established installs skip onboarding: either the app was upgraded, or a Nearby
        // identity file from before this process exists. An install that never finished
        // setup has neither and still gets the first-run flow.
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val upgrade = info != null && info.lastUpdateTime != info.firstInstallTime
        val processStartWall = System.currentTimeMillis() - SystemClock.elapsedRealtime() + Process.getStartElapsedRealtime()
        val identityFile = File(context.applicationInfo.dataDir, "shared_prefs/identity.xml")
        val establishedIdentity = runCatching { identityFile.exists() && identityFile.lastModified() < processStartWall }.getOrDefault(false)
        val existing = upgrade || establishedIdentity
        if (!done && existing) prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        !done && !existing
    }
    // "splash" -> ("username" -> "onboarding" on first run) -> "app".
    var step by rememberSaveable { mutableStateOf("splash") }
    Crossfade(targetState = step, animationSpec = tween(350), label = "relay-start") { current ->
        when (current) {
            "splash" -> {
                RelayDesignTheme { RelaySplashScreen() }
                LaunchedEffect(Unit) {
                    delay(SPLASH_MILLIS)
                    step = if (firstRun) "username" else "app"
                }
            }
            "username" -> RelayDesignTheme {
                RelayUsernameScreen(onContinue = { name ->
                    prefs.edit().putString(KEY_USERNAME, name).apply()
                    `in`.yashd.bitchat.mesh.MeshRuntime.renameSelf(name)
                    step = "onboarding"
                })
            }
            "onboarding" -> RelayDesignTheme {
                RelayOnboarding(onFinish = {
                    prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
                    step = "app"
                })
            }
            else -> content()
        }
    }
}

/** First run only, on the blue start screen: the name friends will see for this device. */
@Composable
internal fun RelayUsernameScreen(onContinue: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(SplashBlue)) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 47.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.relay_logo),
                contentDescription = "Relay",
                modifier = Modifier.size(116.dp, 39.dp)
            )
            Spacer(Modifier.height(48.dp))
            Text(
                "What should friends call you?",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(20.dp))
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onContinue(name.trim()) }),
                textStyle = TextStyle(color = Color.Black, fontSize = 16.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .wrapContentSize(Alignment.Center)
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (name.isBlank()) Color.White.copy(alpha = 0.35f) else Color.White)
                    .clickable(enabled = name.isNotBlank(), role = Role.Button) { onContinue(name.trim()) },
                contentAlignment = Alignment.Center
            ) {
                Text("Continue", color = Color.Black, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Splash (Figma image 1)
// ---------------------------------------------------------------------------

@Composable
internal fun RelaySplashScreen() {
    Box(Modifier.fillMaxSize().background(SplashBlue)) {
        Image(
            painter = painterResource(R.drawable.relay_logo),
            contentDescription = "Relay",
            modifier = Modifier.align(Alignment.Center).size(116.dp, 39.dp)
        )
        Text(
            text = "Messages find their way.",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            letterSpacing = 0.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Onboarding pages (Figma images 2, 3 and 4)
// ---------------------------------------------------------------------------

/** An illustration placed at (x, y) in the Figma frame, drawn at its SVG size. */
private class OnboardingArt(@DrawableRes val drawable: Int, val x: Float, val y: Float, val width: Float, val height: Float)

/** A title + text block next to an illustration. [capTop] is where the title's letters start. */
private class OnboardingRow(val x: Float, val capTop: Float, val title: String, val body: String)

private class OnboardingPageSpec(
    val art: List<OnboardingArt>,
    val rows: List<OnboardingRow>,
    /** Lowest point of the illustrations and their texts, used to shrink them on short screens. */
    val artBottom: Float,
    val description: String,
    val descriptionCapTop: Float,
    val title: String
)

// Line breaks follow the Figma exactly, so the text wraps the same way on every phone.
private val onboardingPages = listOf(
    OnboardingPageSpec(
        art = listOf(OnboardingArt(R.drawable.onboarding_what_is_relay, 46.9f, 116f, 302f, 348f)),
        rows = emptyList(),
        artBottom = 464f,
        description = "Relay lets messages travel directly between\n" +
            "nearby devices using Bluetooth. When\n" +
            "devices aren’t directly connected, messages\n" +
            "can hop through other devices.",
        descriptionCapTop = 551.3f,
        title = "What Is Relay?"
    ),
    OnboardingPageSpec(
        art = listOf(
            OnboardingArt(R.drawable.onboarding_find_nearby, 32.4f, 87.4f, 163f, 168f),
            OnboardingArt(R.drawable.onboarding_messages_hop, -0.5f, 273.2f, 203f, 115f),
            OnboardingArt(R.drawable.onboarding_stay_private, 17.5f, 406.2f, 179f, 133f)
        ),
        rows = listOf(
            OnboardingRow(216.5f, 129f, "Find Nearby",
                "Discover people around you\nusing Bluetooth. No internet,\nno accounts, just nearby\ndevices."),
            OnboardingRow(216.5f, 291.8f, "Messages Hop",
                "When devices aren’t directly\nconnected, messages can\ntravel through other Relay\ndevices."),
            OnboardingRow(216.5f, 429.6f, "Stay Private",
                "Your messages are encrypted\nend-to-end, so only you and\nthe intended person can read\nthem.")
        ),
        artBottom = 540f,
        description = "A simple private decentralized way to stay in\ntouch",
        descriptionCapTop = 621.7f,
        title = "How Relay Works?"
    ),
    OnboardingPageSpec(
        art = listOf(
            OnboardingArt(R.drawable.onboarding_publish_relay, 229.6f, 106.5f, 159f, 257f),
            OnboardingArt(R.drawable.onboarding_reach_anywhere, 32.8f, 409.3f, 163f, 168f)
        ),
        rows = listOf(
            OnboardingRow(47f, 118.8f, "Publish To a Relay",
                "Your messages are\npublished to decentralized\nNostr relays instead of a\ncentral server"),
            OnboardingRow(47f, 265.4f, "Relays Share It",
                "Multiple independent\nrelays store and forward\nyour message making it\navailable to your friends\nacross the globe"),
            OnboardingRow(216.5f, 450.1f, "Reach Anywhere",
                "Your friends can receive the\nmessage from any relay even\nif you are not nearby, no\nsingle country controls the\nnetwork")
        ),
        artBottom = 556f,
        description = "Nostr Relays keep conversations open,\nresilient and in your control",
        descriptionCapTop = 621.7f,
        title = "More Open Network"
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RelayOnboarding(onFinish: () -> Unit, initialPage: Int = 0) {
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == onboardingPages.lastIndex
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize().background(OnboardingBackground)) {
        val scale = maxWidth.value / FRAME_WIDTH
        // Screen position of the Continue button's bottom edge; the page texts hang from it.
        val buttonBottom = maxHeight - navBottom - (12f * scale).dp

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            OnboardingPageView(onboardingPages[index], scale, maxWidth, buttonBottom)
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = navBottom + (12f * scale).dp)
        ) {
            Row(
                modifier = Modifier.padding(start = (47f * scale).dp),
                horizontalArrangement = Arrangement.spacedBy((3f * scale).dp)
            ) {
                repeat(onboardingPages.size) { index ->
                    val color by animateColorAsState(
                        if (index == pager.currentPage) Color.White else DotGray,
                        label = "onboarding-dot"
                    )
                    Box(
                        Modifier
                            .size((DOT_SIZE * scale).dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
            Spacer(Modifier.height(((BUTTON_TOP - DOTS_TOP - DOT_SIZE) * scale).dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = (46.2f * scale).dp)
                    .fillMaxWidth()
                    .height(((BUTTON_BOTTOM - BUTTON_TOP) * scale).dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable(role = Role.Button) {
                        if (last) onFinish()
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Continue", color = Color.Black, fontSize = (16.5f * scale).sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
            }
        }
    }
}

@Composable
private fun OnboardingPageView(page: OnboardingPageSpec, scale: Float, width: Dp, buttonBottom: Dp) {
    // Bottom texts keep the Figma's distance from the Continue button.
    val textsTop = buttonBottom - ((BUTTON_BOTTOM - TEXTS_TOP) * scale).dp
    // Illustrations start at the top of the frame. On a short screen they shrink so they
    // never run into the texts below them.
    val firstTextTop = buttonBottom - ((BUTTON_BOTTOM - page.descriptionCapTop + 6f) * scale).dp
    val artScale = minOf(scale, (firstTextTop - 8.dp) / page.artBottom.dp).coerceAtLeast(0.3f)
    val artLeft = (width - (FRAME_WIDTH * artScale).dp) / 2

    Box(Modifier.fillMaxSize()) {
        FigmaFrame(artScale, page.artBottom, artLeft, 0.dp) {
            page.art.forEach { art ->
                Image(
                    painter = painterResource(art.drawable),
                    contentDescription = null,
                    modifier = Modifier.atFrame(art.x, art.y).requiredSize(art.width.dp, art.height.dp)
                )
            }
            page.rows.forEach { row ->
                DesignText(row.title, row.x, row.capTop, 16.5f, 22f, FontWeight.SemiBold, Color.White)
                DesignText(row.body, row.x, row.capTop + 26.4f, 12.5f, 16f, FontWeight.Normal, OnboardingGray)
            }
        }
        FigmaFrame(scale, BUTTON_TOP - TEXTS_TOP, 0.dp, textsTop) {
            DesignText(page.description, 47f, page.descriptionCapTop - TEXTS_TOP, 14f, 19.1f, FontWeight.Normal, OnboardingGray)
            DesignText(page.title, 47f, TITLE_CAP_TOP - TEXTS_TOP, 31f, 38f, FontWeight.Bold, Color.White)
        }
    }
}

/** A piece of the Figma frame ([height] dp tall, 393 dp wide) drawn at [scale] from (x, y). */
@Composable
private fun FigmaFrame(scale: Float, height: Float, x: Dp, y: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .offset(x, y)
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(FRAME_WIDTH.dp, height.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
        content = content
    )
}

/** Places a child at frame coordinates without it being squeezed or re-centred. */
private fun Modifier.atFrame(x: Float, y: Float): Modifier =
    offset(x.dp, y.dp).wrapContentSize(Alignment.TopStart, unbounded = true)

/**
 * Text positioned by where its letters start ([capTop]), as measured in the Figma, rather than
 * by its line box. Lines break only where the text has "\n".
 */
@Composable
private fun DesignText(
    text: String, x: Float, capTop: Float, size: Float, lineHeight: Float,
    weight: FontWeight, color: Color
) {
    // With centred line height, letters start this far below the line box (Roboto proportions).
    val lineTop = capTop - ((lineHeight - 1.172f * size) / 2f + 0.198f * size)
    Text(
        text = text,
        modifier = Modifier.atFrame(x, lineTop),
        style = LocalTextStyle.current.merge(
            TextStyle(
                color = color,
                fontSize = size.sp,
                lineHeight = lineHeight.sp,
                fontWeight = weight,
                letterSpacing = 0.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
            )
        ),
        softWrap = false,
        overflow = TextOverflow.Visible
    )
}

// ---------------------------------------------------------------------------
// Android Studio previews at the Figma's 393 x 852 frame size
// ---------------------------------------------------------------------------

@Preview(name = "Splash", widthDp = 393, heightDp = 852)
@Composable
private fun SplashPreview() = RelayDesignTheme { RelaySplashScreen() }

@Preview(name = "Onboarding 1 · What Is Relay?", widthDp = 393, heightDp = 852)
@Composable
private fun OnboardingPreview1() = RelayDesignTheme { RelayOnboarding(onFinish = {}, initialPage = 0) }

@Preview(name = "Onboarding 2 · How Relay Works?", widthDp = 393, heightDp = 852)
@Composable
private fun OnboardingPreview2() = RelayDesignTheme { RelayOnboarding(onFinish = {}, initialPage = 1) }

@Preview(name = "Onboarding 3 · More Open Network", widthDp = 393, heightDp = 852)
@Composable
private fun OnboardingPreview3() = RelayDesignTheme { RelayOnboarding(onFinish = {}, initialPage = 2) }
