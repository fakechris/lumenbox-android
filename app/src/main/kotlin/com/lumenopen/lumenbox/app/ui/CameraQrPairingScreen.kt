package com.lumenopen.lumenbox.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumenopen.lumenbox.pairing.PairingQrDecoder
import com.lumenopen.lumenbox.pairing.QrScanOutcome
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX `#fragment` QR pairing screen (`docs/110 §3.2`, INV-2254).
 *
 * Scans `lumenbox://pair#hostPub=...&relay=...&route=...&pairSecret=...` using CameraX `ImageAnalysis`
 * + offline [PairingQrDecoder], rejecting any query-string secret leaks or expired offers before
 * invoking [onPairRequested].
 */
@Composable
fun CameraQrPairingScreen(
    initialPairingUri: String? = null,
    isBusy: Boolean,
    externalError: String?,
    onPairRequested: (qrUri: String, macDisplayName: String, deviceName: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    var macDisplayName by remember { mutableStateOf("个人 Mac") }
    var deviceName by remember {
        mutableStateOf(
            listOfNotNull(Build.MANUFACTURER?.takeIf { it.isNotBlank() }, Build.MODEL?.takeIf { it.isNotBlank() })
                .joinToString(" ")
                .ifBlank { "Android Phone" }
        )
    }
    var manualUri by remember(initialPairingUri) { mutableStateOf(initialPairingUri.orEmpty()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val consumedQr = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && initialPairingUri.isNullOrBlank()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(initialPairingUri) {
        val uri = initialPairingUri?.trim().orEmpty()
        if (uri.isNotEmpty() && consumedQr.compareAndSet(false, true)) {
            when (val outcome = PairingQrDecoder.evaluateDecodedText(uri)) {
                is QrScanOutcome.ValidOffer -> {
                    scanError = null
                    onPairRequested(outcome.rawUri, macDisplayName, deviceName)
                }
                is QrScanOutcome.RejectedOffer -> {
                    consumedQr.set(false)
                    scanError = mapScanError(outcome.code, outcome.reason)
                }
                else -> {
                    consumedQr.set(false)
                    scanError = "未识别为有效的 LumenBox 个人 Mac 配对二维码"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("扫码配对个人 Mac", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onCancel, enabled = !isBusy) {
                Text("返回")
            }
        }

        Text(
            "打开 Mac 端 LumenBox → Connect a phone，扫描一次性 #fragment 配对二维码（2 分钟内有效，密钥永不经过中继服务器）。",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = macDisplayName,
            onValueChange = { macDisplayName = it },
            label = { Text("Mac 显示名称") },
            singleLine = true,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("本机设备名称") },
            singleLine = true,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )

        val activeError = externalError ?: scanError
        if (!activeError.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = activeError,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (isBusy) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("正在执行 Noise_NK 配对并建立 Noise_IK 加密多路复用通道…")
                }
            }
        } else if (hasCameraPermission) {
            CameraPreviewBox(
                lifecycleOwner = lifecycleOwner,
                onQrOutcome = { outcome ->
                    when (outcome) {
                        is QrScanOutcome.ValidOffer -> {
                            if (consumedQr.compareAndSet(false, true)) {
                                scanError = null
                                onPairRequested(
                                    outcome.rawUri,
                                    macDisplayName.ifBlank { "个人 Mac" },
                                    deviceName.ifBlank { "Android Phone" },
                                )
                            }
                        }
                        is QrScanOutcome.RejectedOffer -> {
                            scanError = mapScanError(outcome.code, outcome.reason)
                        }
                        is QrScanOutcome.IgnoredNonLumenBox, QrScanOutcome.NoQrFound -> Unit
                    }
                },
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("需要相机权限以扫描 Mac 屏幕上的配对二维码。")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予相机权限")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("或粘贴配对链接（模拟器 / 无相机设备）", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = manualUri,
            onValueChange = {
                manualUri = it
                scanError = null
            },
            placeholder = { Text("lumenbox://pair#hostPub=...&relay=...&route=...&pairSecret=...") },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                when (val outcome = PairingQrDecoder.evaluateDecodedText(manualUri)) {
                    is QrScanOutcome.ValidOffer -> {
                        scanError = null
                        onPairRequested(
                            outcome.rawUri,
                            macDisplayName.ifBlank { "个人 Mac" },
                            deviceName.ifBlank { "Android Phone" },
                        )
                    }
                    is QrScanOutcome.RejectedOffer -> {
                        scanError = mapScanError(outcome.code, outcome.reason)
                    }
                    else -> {
                        scanError = "请输入以 lumenbox://pair# 开头的配对 URI"
                    }
                }
            },
            enabled = !isBusy && manualUri.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("验证并配对")
        }
    }
}

@Composable
private fun CameraPreviewBox(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onQrOutcome: (QrScanOutcome) -> Unit,
) {
    val context = LocalContext.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@Runnable
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val plane = imageProxy.planes.firstOrNull()
                            if (plane != null) {
                                val buffer = plane.buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                val outcome = PairingQrDecoder.decodeYPlane(
                                    yPlane = bytes,
                                    width = imageProxy.width,
                                    height = imageProxy.height,
                                    rowStride = plane.rowStride,
                                )
                                if (outcome is QrScanOutcome.ValidOffer || outcome is QrScanOutcome.RejectedOffer) {
                                    mainExecutor.execute { onQrOutcome(outcome) }
                                }
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                }
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }
        providerFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
        }
    }
}

private fun mapScanError(code: String, fallback: String): String = when (code) {
    "expired_pairing_offer" -> "该配对二维码已过期（有效期 2 分钟），请在 Mac 端重新生成二维码。"
    "invalid_pairing_uri" -> "安全拦截：配对参数必须位于 #fragment 内且包含完整公钥与密钥（$fallback）。"
    else -> "配对二维码校验失败（$code）：$fallback"
}
