package `in`.yashd.bitchat.ui.relay

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import `in`.yashd.bitchat.RelayColors
import `in`.yashd.bitchat.RelayType
import `in`.yashd.bitchat.nostr.NostrNpub
import java.util.concurrent.Executors

/** Accepts a raw npub or a nostr:npub1… URI (NIP-21) and returns the lowercase npub, or null when invalid. */
fun parseScannedPublicId(raw: String): String? {
    val text = raw.trim().removePrefix("nostr:").removePrefix("NOSTR:").trim().lowercase()
    return runCatching { NostrNpub.decode(text); text }.getOrNull()
}

/** Full-screen camera scanner for a friend's QR code. Only the camera frames are used; nothing is uploaded. */
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().background(RelayColors.Background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Scan your friend’s QR code", color = RelayColors.TextPrimary, fontSize = RelayType.SubTitle)
        if (granted) {
            QrCameraView(Modifier.fillMaxWidth().weight(1f).background(RelayColors.Card, RoundedCornerShape(16.dp)), onResult)
            Text("Point the camera at the QR code shown on your friend’s phone.",
                color = RelayColors.TextSecondary, fontSize = RelayType.Label)
        } else {
            Text("Relay needs camera access once, on this device, to read your friend’s QR code. Nothing is uploaded.",
                color = RelayColors.TextSecondary, fontSize = RelayType.Label)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Allow camera") }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun ScanOverlay(modifier: Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition()
    val fraction by transition.animateFloat(0f, 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse))
    androidx.compose.foundation.Canvas(modifier) {
        val boxSize = size.minDimension * 0.62f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f
        val stroke = 3.dp.toPx()
        val corner = 22.dp.toPx()
        val accent = RelayColors.Blue
        // Dim outside the framing box.
        drawRect(androidx.compose.ui.graphics.Color(0x990E0F11))
        drawRoundRect(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.geometry.Offset(left, top),
            androidx.compose.ui.geometry.Size(boxSize, boxSize), androidx.compose.ui.geometry.CornerRadius(corner, corner),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        drawRoundRect(accent, androidx.compose.ui.geometry.Offset(left, top),
            androidx.compose.ui.geometry.Size(boxSize, boxSize), androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val lineY = top + boxSize * fraction
        drawRect(accent, androidx.compose.ui.geometry.Offset(left + stroke * 2, lineY),
            androidx.compose.ui.geometry.Size(boxSize - stroke * 4, stroke), alpha = 0.8f)
    }
}

@Composable
private fun QrCameraView(modifier: Modifier, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analyzerExecutor.shutdown() } }
    androidx.compose.foundation.layout.Box(modifier) {
    AndroidView(modifier = Modifier.matchParentSize(), factory = { ctx ->
        val view = PreviewView(ctx)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val reader = MultiFormatReader()
            analysis.setAnalyzer(analyzerExecutor) { image ->
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val source = PlanarYUVLuminanceSource(bytes, image.planes[0].rowStride, image.height,
                        0, 0, image.width, image.height, false)
                    val result = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
                    val text = result?.text
                    if (text != null) view.post { onResult(text) }
                } finally { image.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
    ScanOverlay(Modifier.matchParentSize())
    }
}
