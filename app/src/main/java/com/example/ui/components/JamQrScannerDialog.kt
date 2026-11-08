package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.jam.extractJamInviteCode
import com.example.ui.theme.BentoCardBorder
import com.example.ui.theme.BentoCardSurface
import com.example.ui.theme.BentoPrimary
import com.example.ui.theme.BentoTextPrimary
import com.example.ui.theme.BentoTextSecondary
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun JamQrScannerDialog(
    onDismiss: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val hasDeliveredResult = remember { AtomicBoolean(false) }
    var scannerMessage by remember { mutableStateOf<String?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraExecutor.shutdown() }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeQrFromUri(context.contentResolver, uri)
            }
            val code = decoded?.let(::extractJamInviteCode).orEmpty()
            if (code.length >= 12) {
                if (hasDeliveredResult.compareAndSet(false, true)) {
                    onCodeScanned(code)
                }
            } else {
                scannerMessage = "No valid Jam invitation QR code was found in that image."
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("jam_qr_scanner_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
            border = BorderStroke(1.dp, BentoCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Scan Jam QR Code",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Point your camera at the host's QR code",
                            style = MaterialTheme.typography.bodySmall,
                            color = BentoTextSecondary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_qr_scanner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close QR scanner",
                            tint = BentoTextSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .border(2.dp, BentoPrimary.copy(alpha = 0.7f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull()
                                    ?: return@addListener
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val reader = createQrReader()
                                val analysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { imageAnalysis ->
                                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                            try {
                                                if (!hasDeliveredResult.get()) {
                                                    val raw = decodeQrFromImageProxy(reader, imageProxy)
                                                    val code = raw?.let(::extractJamInviteCode).orEmpty()
                                                    if (code.length >= 12 && hasDeliveredResult.compareAndSet(false, true)) {
                                                        previewView.post {
                                                            onCodeScanned(code)
                                                        }
                                                    }
                                                }
                                            } finally {
                                                imageProxy.close()
                                            }
                                        }
                                    }

                                runCatching {
                                    cameraProvider.unbindAll()
                                    val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    } else {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    }
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        selector,
                                        preview,
                                        analysis
                                    )
                                }.onFailure {
                                    previewView.post {
                                        scannerMessage = "Camera preview unavailable. You can also scan a QR image from your gallery."
                                    }
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )

                    // Framing reticle overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.68f)
                            .border(2.dp, BentoPrimary, RoundedCornerShape(16.dp))
                    )
                }

                if (!scannerMessage.isNullOrBlank()) {
                    Text(
                        text = scannerMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "Align the QR code inside the frame to join automatically.",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedButton(
                    onClick = {
                        scannerMessage = null
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("scan_qr_from_gallery_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BentoCardBorder)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = BentoPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose QR image from gallery", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun createQrReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
    )
}

private fun decodeQrFromImageProxy(reader: MultiFormatReader, image: ImageProxy): String? {
    val planes = image.planes
    if (planes.isEmpty()) return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null

    val luminance = extractLuminanceBytes(image, width, height) ?: return null
    val source = PlanarYUVLuminanceSource(
        luminance,
        width,
        height,
        0,
        0,
        width,
        height,
        false
    )
    return tryDecodeSource(reader, source)
}

private fun extractLuminanceBytes(image: ImageProxy, width: Int, height: Int): ByteArray? = runCatching {
    val plane = image.planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val output = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.get(output, 0, width * height)
    } else {
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (rowStart >= buffer.limit()) break
            buffer.position(rowStart)
            val bytesToRead = minOf(rowStride, buffer.remaining())
            buffer.get(rowBuffer, 0, bytesToRead)
            if (pixelStride == 1) {
                System.arraycopy(rowBuffer, 0, output, row * width, minOf(width, bytesToRead))
            } else {
                for (col in 0 until width) {
                    val idx = col * pixelStride
                    if (idx < bytesToRead) {
                        output[row * width + col] = rowBuffer[idx]
                    }
                }
            }
        }
    }
    output
}.getOrNull()

private fun decodeQrFromUri(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
    val bitmap = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
    } ?: return null
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val reader = createQrReader()
    tryDecodeSource(reader, source)
}.getOrNull()

private fun tryDecodeSource(
    reader: MultiFormatReader,
    source: com.google.zxing.LuminanceSource
): String? {
    runCatching {
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }
    reader.reset()
    runCatching {
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
    }
    reader.reset()
    return null
}
