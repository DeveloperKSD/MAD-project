# Name → animated avatar

The implementation lives in `app/src/main/java/in/yashd/bitchat/ui/avatar/`.
It is local and deterministic, with no network calls or new dependencies.

## SVG markup

```kotlin
import `in`.yashd.bitchat.ui.avatar.Facehash

val svg = Facehash.svg("Alice")
val staticSvg = Facehash.svg("Alice", animated = false)
```

The returned string is a complete SVG document with a `100 × 100` view box.
Resize it using the consumer's image dimensions. The default export contains
CSS eye blinking and honors browser `prefers-reduced-motion`. Animation requires
a viewer that supports SVG/CSS animation; Android SVG loaders usually render
only a static image.

## Animated Android rendering

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import `in`.yashd.bitchat.ui.avatar.FacehashAvatar

FacehashAvatar(
    name = "Alice",
    modifier = Modifier.size(48.dp).clip(CircleShape),
)
```

The Compose component defaults to `40.dp`, shares the SVG geometry and generation
logic, and animates blinking natively. Set `animated = false` to disable blinking.
Native blinking starts within one second and repeats every 2.8–3.8 seconds,
with a name-derived interval. Animation is stopped while the host lifecycle is below `STARTED` and follows
Compose's system animation-duration scale. The avatar is decorative: add a
content description to its caller when it conveys information without a nearby
name. `FacehashAvatarPreview` shows all four eye styles in Android Studio.

The app's shared `PersonAvatar` uses this renderer for conversation rows, chat
headers, discovery, contact details, and profile/settings avatars, including
Nearby identity. Existing sizes, circular clipping and profile click actions are
retained. All person avatars use the same name-derived background palette on
every screen, including Bluetooth/Nearby identity. The Compose renderer also
accepts optional `backgroundColor` and `foregroundColor` overrides for other uses.
Group conversations retain their
dedicated group icon.

## Compatibility

Hashing, face selection, pose, default palette, and exported SVG blink timing follow the
checked-in `my-avatar-app` source. Both eyes share timing, as in that source;
native rendering uses a quicker repeating blink so it is apparent on short visits.
Names are not trimmed or lowercased. Renaming changes the avatar.

Intentional differences: default backgrounds use the five-color palette rather
than React's implicit transparent background; initials preserve an entire Unicode
code point (including emoji), reject invalid XML characters, and use
locale-independent uppercase. Browser and Android monospace fonts may differ.
This implementation provides blinking, not hover-to-front interaction.

## Source attribution

Eye paths and generation formulas are adapted from **facehash 0.1.0**, authored
by the **Cossistant team**, in the supplied `my-avatar-app` folder.
Its `package.json` and README declare MIT licensing. Upstream reference:
https://github.com/cossistantcom/cossistant/tree/main/packages/facehash

The supplied package has no LICENSE file; the upstream repository root currently
has a different license. A package-specific copyright/license notice was not
available at the checked upstream package LICENSE URL. Preserve this attribution
and confirm the package-specific notice before redistributing the port.
