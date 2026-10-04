package com.example.transferase.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.transferase.server.NetworkUtils
import com.example.transferase.server.ReceivedFileItem
import com.example.transferase.server.SharedFileManager
import com.example.transferase.server.SharedFileItem
import com.example.transferase.server.TransferServerService
import com.example.transferase.ui.QRCodeGenerator
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRunning by TransferServerService.isServerRunning.collectAsState()
    val serverUrl by TransferServerService.serverUrl.collectAsState()

    var networkInfo by remember { mutableStateOf(NetworkUtils.getNetworkInfo(context)) }
    val sharedFiles by SharedFileManager.sharedFiles.collectAsState()
    val receivedFiles by SharedFileManager.receivedFiles.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {}
            SharedFileManager.addSharedFile(context, uri)
        }
        if (uris.isNotEmpty()) {
            Toast.makeText(context, "${uris.size} file(s) added to share pool", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        SharedFileManager.refreshReceivedFiles(context)
    }

    val displayUrl = if (isRunning && serverUrl.isNotBlank()) {
        serverUrl
    } else {
        "http://${networkInfo.primaryIp}:4000"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF3B82F6),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("⚡", fontSize = 18.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Transferase",
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Local Server & P2P Transfer",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = {
                        networkInfo = NetworkUtils.getNetworkInfo(context)
                        SharedFileManager.refreshReceivedFiles(context)
                        Toast.makeText(context, "Refreshed network & files", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("🔄 Refresh", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ServerStatusCard(
                    isRunning = isRunning,
                    displayUrl = displayUrl,
                    pulseScale = pulseScale,
                    onToggleServer = {
                        if (isRunning) {
                            TransferServerService.stopService(context)
                        } else {
                            TransferServerService.startService(context)
                        }
                    },
                    onCopyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transferase URL", displayUrl))
                        Toast.makeText(context, "Copied: $displayUrl", Toast.LENGTH_SHORT).show()
                    },
                    onOpenBrowser = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(displayUrl))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShowQr = { showQrDialog = true }
                )
            }

            item {
                NetworkInfoCard(
                    networkInfo = networkInfo,
                    isRunning = isRunning
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Files Shared to PC",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${sharedFiles.size} file(s) available for download",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("➕ Add Files", fontSize = 13.sp)
                            }
                        }

                        if (sharedFiles.isEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No files shared yet. Tap \"Add Files\" to select photos, videos, or documents from your phone for PC to download.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                            sharedFiles.forEach { file ->
                                SharedFileRow(
                                    item = file,
                                    onRemove = { SharedFileManager.removeSharedFile(file.id) }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Received Files from PC",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Saved in Downloads/Transferase",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { SharedFileManager.refreshReceivedFiles(context) }) {
                                Text("🔄 Refresh", fontSize = 12.sp)
                            }
                        }

                        if (receivedFiles.isEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No files received yet. Any file uploaded from the PC browser will appear here instantly.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                            receivedFiles.forEach { file ->
                                ReceivedFileRow(
                                    item = file,
                                    onOpen = { openFile(context, file.path) },
                                    onShare = { shareFile(context, file.path) }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showQrDialog) {
        val qrBitmap = remember(displayUrl) {
            QRCodeGenerator.generateQRCode(displayUrl, 600)
        }

        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = {
                Text(
                    "Scan to Connect",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Scan with PC camera, phone, or tablet on the same Wi-Fi/Hotspot:",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        displayUrl,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ServerStatusCard(
    isRunning: Boolean,
    displayUrl: String,
    pulseScale: Float,
    onToggleServer: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenBrowser: () -> Unit,
    onShowQr: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .scale(if (isRunning) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(if (isRunning) Color(0xFF10B981) else Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isRunning) "SERVER ONLINE" else "SERVER STOPPED",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp,
                        color = if (isRunning) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onToggleServer,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Color(0xFFEF4444) else Color(0xFF10B981)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (isRunning) "STOP SERVER" else "START SERVER",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Access Address (Enter in PC Browser):",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        displayUrl,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = onCopyUrl,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("📋 Copy", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onShowQr,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📷 QR Code")
                }

                FilledTonalButton(
                    onClick = onOpenBrowser,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🌐 Open Web App")
                }
            }
        }
    }
}

@Composable
fun NetworkInfoCard(
    networkInfo: com.example.transferase.server.DeviceNetworkInfo,
    isRunning: Boolean
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Network & Hotspot Setup",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connection:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                SuggestionChip(
                    onClick = {},
                    label = { Text(networkInfo.connectionType, fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (networkInfo.connectionType.contains("Hotspot")) Color(0xFFFEF3C7) else Color(0xFFDBEAFE),
                        labelColor = if (networkInfo.connectionType.contains("Hotspot")) Color(0xFFB45309) else Color(0xFF1D4ED8)
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "How to connect without internet:",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("1. Turn on Mobile Hotspot on this phone (Recommended: 100% offline).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("2. Connect your PC / laptop to this phone's Hotspot.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3. Open Chrome or Edge on PC and enter the address above (including :4000).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text("💡 Tip: If using Home Wi-Fi and PC can't reach phone, router 'AP Isolation' is blocking connections. Switch to Mobile Hotspot.", fontSize = 11.sp, color = Color(0xFFD97706))
        }
    }
}

@Composable
fun SharedFileRow(
    item: SharedFileItem,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📄", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatBytes(item.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRemove) {
                Text("🗑️", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun ReceivedFileRow(
    item: ReceivedFileItem,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val dateStr = remember(item.lastModified) {
        SimpleDateFormat("HH:mm - dd MMM", Locale.getDefault()).format(Date(item.lastModified))
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✅", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatBytes(item.size)} • $dateStr",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpen) {
                Text("Open", fontSize = 12.sp)
            }
            TextButton(onClick = onShare) {
                Text("Share", fontSize = 12.sp)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun openFile(context: Context, path: String) {
    try {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, path: String) {
    try {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share File"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
