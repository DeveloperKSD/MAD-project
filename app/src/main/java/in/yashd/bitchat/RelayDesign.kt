package `in`.yashd.bitchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.ui.avatar.FacehashAvatar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Shared look for the Relay screens, taken from the Figma design:
 * near-black background, rounded dark cards, blue accents and animated face avatars.
 * UI only: nothing in this file talks to Bluetooth, Nostr or storage.
 */

internal object RelayColors {
    val Background = Color(0xFF0E0F11)
    val Card = Color(0xFF1A1B1E)
    val CardHigh = Color(0xFF26272B)
    val Divider = Color(0xFF2C2E33)
    val TextPrimary = Color(0xFFF4F5F7)
    val TextSecondary = Color(0xFF9DA1A9)
    val TextMuted = Color(0xFF6F737B)
    val Blue = Color(0xFF3D6DF2)
    val BlueBubble = Color(0xFF2C55B8)
    val BlueSelected = Color(0xFF1E3A70)
    val Green = Color(0xFF34C77B)
    val GreenPill = Color(0xFF16392A)
    val Orange = Color(0xFFF5A03D)
    val Red = Color(0xFFF0605D)
    val Bluetooth = Color(0xFF4DA8FF)
    val Nostr = Color(0xFFA07CFF)
    val Me = Color(0xFFF26B3A)

    val Avatars = listOf(
        Color(0xFF34C77B), // green
        Color(0xFFF38BB0), // pink
        Color(0xFF8A5CF6), // purple
        Color(0xFFF5A03D), // orange
        Color(0xFF4F8EF7), // blue
        Color(0xFF2BB5A8), // teal
        Color(0xFFE5605B)  // red
    )
}

@Composable
fun RelayDesignTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RelayColors.Blue,
            onPrimary = Color.White,
            primaryContainer = RelayColors.BlueSelected,
            onPrimaryContainer = Color.White,
            secondary = RelayColors.Bluetooth,
            onSecondary = Color.Black,
            secondaryContainer = RelayColors.BlueSelected,
            onSecondaryContainer = Color.White,
            tertiary = RelayColors.Nostr,
            background = RelayColors.Background,
            onBackground = RelayColors.TextPrimary,
            surface = RelayColors.Background,
            onSurface = RelayColors.TextPrimary,
            surfaceVariant = RelayColors.Card,
            onSurfaceVariant = RelayColors.TextSecondary,
            surfaceContainer = RelayColors.Card,
            surfaceContainerLow = RelayColors.Card,
            surfaceContainerHigh = RelayColors.CardHigh,
            surfaceContainerHighest = RelayColors.CardHigh,
            outline = Color(0xFF4A4D53),
            outlineVariant = RelayColors.Divider,
            error = RelayColors.Red
        ),
        typography = `in`.yashd.bitchat.ui.theme.Typography,
        content = content
    )
}

@Composable
internal fun RIcon(
    @DrawableRes id: Int,
    description: String? = null,
    tint: Color = LocalContentColor.current,
    size: Dp = 22.dp
) {
    Icon(painterResource(id), description, Modifier.size(size), tint = tint)
}

// One colour per first letter, so a person always gets the same colour (as in the Figma).
// G green, B blue, T teal, P purple, K pink, O orange, R red, N gray
private const val LETTER_COLORS = "GBTPKORTBPOGKNTPBBGOKPTROB"

internal fun avatarColor(name: String): Color {
    val letter = name.trim().firstOrNull { it.isLetter() }?.uppercaseChar()
    val code = if (letter != null && letter in 'A'..'Z') LETTER_COLORS[letter - 'A'] else 'N'
    return when (code) {
        'G' -> Color(0xFF34C77B)
        'B' -> Color(0xFF4F8EF7)
        'T' -> Color(0xFF2BB5A8)
        'P' -> Color(0xFF8A5CF6)
        'K' -> Color(0xFFF38BB0)
        'O' -> Color(0xFFF5A03D)
        'R' -> Color(0xFFE5605B)
        else -> Color(0xFF8E8E93)
    }
}

/** Shared round, animated face avatar for profiles, people and conversations. */
@Composable
internal fun PersonAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    FacehashAvatar(
        name = name,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

/** Shared compact type scale; the chat list is the reference. */
internal object RelayType {
    val ScreenTitle = 24.sp
    val SubTitle = 17.sp
    val RowTitle = 16.sp
    val Body = 15.sp
    val Label = 14.sp
    val Secondary = 13.sp
    val Meta = 12.sp
    val Caption = 11.sp
}

/** Big title on the left ("Relay" / "Settings") and your own avatar on the right. */
@Composable
internal fun RelayHeader(title: String, myName: String, onAvatar: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = RelayColors.TextPrimary,
            fontSize = RelayType.ScreenTitle,
            fontWeight = FontWeight.SemiBold
        )
        PersonAvatar(
            name = myName.ifBlank { "Me" },
            size = 34.dp,
            modifier = Modifier.clip(CircleShape)
                .semantics { contentDescription = "Your profile" }
                .clickable(onClickLabel = "Open settings", onClick = onAvatar)
        )
    }
}

/** Rounded dark card that groups rows together. */
@Composable
internal fun RelayCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RelayColors.Card),
        content = content
    )
}

@Composable
internal fun RowDivider(startPadding: Dp = 56.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startPadding),
        thickness = 0.5.dp,
        color = RelayColors.Divider
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        color = RelayColors.TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Small rounded label such as "Available" or "Connected". */
@Composable
internal fun RelayPill(text: String, color: Color, background: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = RelayType.Meta, fontWeight = FontWeight.Medium)
    }
}

/** A settings-style row: icon, title, optional subtitle, optional trailing content, chevron. */
@Composable
internal fun RelayListRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String? = null,
    iconTint: Color = RelayColors.TextPrimary,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(icon, null, tint = iconTint, size = 22.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = RelayColors.TextPrimary, fontSize = RelayType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, color = RelayColors.TextSecondary, fontSize = RelayType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            RIcon(R.drawable.relay_chevron, null, tint = RelayColors.TextMuted, size = 18.dp)
        }
    }
}

/** Dot + coloured word, e.g. "● On" or "● 3 pending" in Settings. */
@Composable
internal fun DotLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color, 7.dp)
        Text(text, color = color, fontSize = RelayType.Secondary)
    }
}

/** Card with an icon and two lines of text, used for empty states. */
@Composable
internal fun EmptyCard(@DrawableRes icon: Int, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    RelayCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RIcon(icon, null, tint = RelayColors.TextSecondary, size = 30.dp)
            Text(title, color = RelayColors.TextPrimary, fontSize = RelayType.RowTitle, fontWeight = FontWeight.SemiBold)
            Text(body, color = RelayColors.TextSecondary, fontSize = RelayType.Secondary, textAlign = TextAlign.Center)
            if (action != null) action()
        }
    }
}

// ---- time and text helpers ----

private fun dayNumber(ms: Long, offsetDays: Int = 0): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = ms
    calendar.add(Calendar.DAY_OF_YEAR, offsetDays)
    return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
}

internal fun sameDay(a: Long, b: Long): Boolean = dayNumber(a) == dayNumber(b)

/** "7:20 pm" */
internal fun clockTime(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).lowercase(Locale.getDefault())

/** Time shown on a chat row: "7:20 pm", "Yesterday", "Mon" or "14 Oct". */
internal fun listTime(ms: Long, now: Long): String = when {
    dayNumber(ms) == dayNumber(now) -> clockTime(ms)
    dayNumber(ms) == dayNumber(now, -1) -> "Yesterday"
    now - ms < 6L * 24 * 60 * 60 * 1000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
    else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
}

/** Date label between messages: "Today", "Yesterday" or "Wed, 14 Oct". */
internal fun dayTitle(ms: Long, now: Long): String = when {
    dayNumber(ms) == dayNumber(now) -> "Today"
    dayNumber(ms) == dayNumber(now, -1) -> "Yesterday"
    else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(ms))
}

/** "Last seen 2h ago" style text. */
internal fun agoText(ms: Long, now: Long): String {
    val minutes = ((now - ms) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

/** Rough distance from Bluetooth signal strength (log-distance model). Only an estimate. */
internal fun distanceText(rssi: Int?): String? {
    if (rssi == null || rssi == 0) return null
    val meters = 10.0.pow((-59.0 - rssi) / 20.0)
    return when {
        meters < 1.0 -> "< 1 m away"
        meters < 100.0 -> "~${meters.roundToInt()} m away"
        else -> "far away"
    }
}

/** "npub1x9s…7k3a" */
internal fun shortId(id: String, head: Int = 9, tail: Int = 4): String =
    if (id.length <= head + tail + 1) id else id.take(head) + "…" + id.takeLast(tail)

internal fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
