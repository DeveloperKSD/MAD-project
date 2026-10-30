package `in`.yashd.bitchat.ui.relay

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
private fun QrCameraView(modifier: Modifier, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analyzerExecutor.shutdown() } }
    AndroidView(modifier = modifier, factory = { ctx ->
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
}
