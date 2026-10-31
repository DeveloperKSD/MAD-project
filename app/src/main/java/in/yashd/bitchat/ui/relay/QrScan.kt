package `in`.yashd.bitchat.ui.relay

import com.journeyapps.barcodescanner.ScanOptions
import `in`.yashd.bitchat.nostr.NostrNpub

/** Accepts a raw npub or a nostr:npub1… URI (NIP-21) and returns the lowercase npub, or null when invalid. */
fun parseScannedPublicId(raw: String): String? {
    val text = raw.trim().removePrefix("nostr:").removePrefix("NOSTR:").trim().lowercase()
    return runCatching { NostrNpub.decode(text); text }.getOrNull()
}

/** QR-only scanner configuration. Decoding happens on-device inside the ZXing capture activity; nothing is uploaded. */
fun qrScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Point the camera at the QR code on your friend’s phone.")
    .setBeepEnabled(false)
    .setOrientationLocked(true)
