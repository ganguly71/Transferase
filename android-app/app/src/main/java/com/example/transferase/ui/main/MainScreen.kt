package com.example.transferase.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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
import com.example.transferase.theme.*
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
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val isRunning by TransferServerService.isServerRunning.collectAsState()
    val serverUrl by TransferServerService.serverUrl.collectAsState()

    var networkInfo by remember { mutableStateOf(NetworkUtils.getNetworkInfo(context)) }
    val sharedFiles by SharedFileManager.sharedFiles.collectAsState()
    val receivedFiles by SharedFileManager.receivedFiles.collectAsState()

    var showQrDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var textInputState by remember { mutableStateOf("") }

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

    val deviceModel = remember {
        val manu = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        if (model.startsWith(manu, ignoreCase = true)) model else "$manu $model"
    }

    val totalSharedSize = sharedFiles.sumOf { it.size }

    Scaffold(
        containerColor = DarkBg,
        contentColor = TextPrimary,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
            ) {
                // Binary track animation visual matching web header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33000000))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "01010100 01110010 01100001 01101110 01110011 01100110 01100101 01110010 01100001 01110011 01100101",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0x66C49267),
                        letterSpacing = 1.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = CopperPrimary,
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
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    color = CopperLight,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    "OFFLINE P2P LOCAL TRANSFER",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    letterSpacing = 1.sp
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
                            Text("🔄 Refresh", fontSize = 12.sp, color = CopperLight)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBg
                    )
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Server Status & Network Address Card (styled identical to web network card)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, BorderCopper),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .scale(if (isRunning) pulseScale else 1f)
                                        .clip(CircleShape)
                                        .background(if (isRunning) EmeraldOnline else ErrorRed)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isRunning) "SERVER ONLINE" else "SERVER STOPPED",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp,
                                    color = if (isRunning) EmeraldLight else Color(0xFFF87171)
                                )
                            }

                            Button(
                                onClick = {
                                    if (isRunning) {
                                        TransferServerService.stopService(context)
                                    } else {
                                        TransferServerService.startService(context)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRunning) ErrorRed else EmeraldDark
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    if (isRunning) "STOP SERVER" else "START SERVER",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            "ACCESS ADDRESS (ENTER IN PC BROWSER):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0D0703),
                            border = BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    displayUrl,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = CopperLight,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = {
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase URL", displayUrl))
                                        Toast.makeText(context, "Copied URL to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = DarkCardElevated,
                                        contentColor = CopperLight
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("📋 Copy", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (networkInfo.connectionType.contains("Hotspot")) Color(0x33D97706) else Color(0x3310B981),
                                border = BorderStroke(1.dp, if (networkInfo.connectionType.contains("Hotspot")) BorderCopper else BorderEmerald)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "📶 ${networkInfo.connectionType}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (networkInfo.connectionType.contains("Hotspot")) AmberGlow else EmeraldLight
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { showQrDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BorderCopper),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CopperLight),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("📷 Show QR Code", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 2. YOUR DEVICE & Quick Action Bar (File + Clipboard Copy-Paste)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, BorderCopper),
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
                                    "YOUR DEVICE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    deviceModel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = TextPrimary
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0x2610B981),
                                border = BorderStroke(1.dp, BorderEmerald)
                            ) {
                                Text(
                                    "⚡ DIRECT LINK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EmeraldLight,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Action Buttons: Add Files + Paste Clipboard + Write Note
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CopperPrimary),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text("📁 Add Files", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DarkBg)
                            }

                            Button(
                                onClick = {
                                    val clip = clipboardManager.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                                        if (text.isNotBlank()) {
                                            SharedFileManager.addSharedText(context, text)
                                            Toast.makeText(context, "📋 Pasted from clipboard and shared to PC!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text("📋 Paste Clip", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            OutlinedButton(
                                onClick = { showTextDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderCopper),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CopperLight),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text("✏️ Write Note", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Sending Queue (Files & Text Shared to PC)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, BorderCopper),
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
                                    "SENDING QUEUE (SHARED TO PC)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${sharedFiles.size} item(s) • ${formatBytes(totalSharedSize)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                            }

                            if (sharedFiles.isNotEmpty()) {
                                TextButton(
                                    onClick = { SharedFileManager.clearSharedFiles() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF87171))
                                ) {
                                    Text("Clear All", fontSize = 12.sp)
                                }
                            }
                        }

                        if (sharedFiles.isEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x33000000),
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("📦", fontSize = 26.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Queue is empty",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Tap 'Add Files', 'Paste Clip', or 'Write Note' above to share photos, videos, or text notes with your PC.",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                            sharedFiles.forEach { file ->
                                SharedItemRow(
                                    item = file,
                                    onCopyText = {
                                        file.textContent?.let { txt ->
                                            clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase Note", txt))
                                            Toast.makeText(context, "Copied text note to clipboard!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onRemove = { SharedFileManager.removeSharedFile(file.id) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            // 4. Received Files & Text from PC (with 1-tap Copy Text)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, BorderCopper),
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
                                    "RECEIVED FILES & TEXT (FROM PC)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${receivedFiles.size} item(s) saved in Downloads/Transferase",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                            }

                            if (receivedFiles.isNotEmpty()) {
                                TextButton(
                                    onClick = { SharedFileManager.clearReceivedFiles(context) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF87171))
                                ) {
                                    Text("Clear All", fontSize = 12.sp)
                                }
                            }
                        }

                        if (receivedFiles.isEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x33000000),
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("📥", fontSize = 26.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "No items received yet",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Files and text notes uploaded from your PC browser appear here immediately.",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                            receivedFiles.forEach { file ->
                                ReceivedItemRow(
                                    item = file,
                                    onCopyText = { txt ->
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase Received Text", txt))
                                        Toast.makeText(context, "📋 Copied text to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    onOpen = { openFile(context, file.path) },
                                    onShare = { shareFile(context, file.path) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Write Note Dialog
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            containerColor = DarkCardElevated,
            title = {
                Text(
                    "✏️ Write or Paste Text Note",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = CopperLight
                )
            },
            text = {
                Column {
                    Text(
                        "Share clipboard text, URLs, or notes with your PC:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = textInputState,
                        onValueChange = { textInputState = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("Type or paste message / link here...", color = TextMuted, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CopperPrimary,
                            unfocusedBorderColor = BorderCopper,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = CopperPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val clip = clipboardManager.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val t = clip.getItemAt(0).text?.toString() ?: ""
                                if (t.isNotBlank()) textInputState = t
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AmberGlow)
                    ) {
                        Text("📋 Paste from Clipboard", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = textInputState.trim()
                        if (trimmed.isNotBlank()) {
                            SharedFileManager.addSharedText(context, trimmed)
                            Toast.makeText(context, "Added text note to share queue!", Toast.LENGTH_SHORT).show()
                            textInputState = ""
                            showTextDialog = false
                        } else {
                            Toast.makeText(context, "Please enter some text", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CopperPrimary)
                ) {
                    Text("Add to Queue", fontWeight = FontWeight.Bold, color = DarkBg)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTextDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // QR Code Dialog
    if (showQrDialog) {
        val qrBitmap = remember(displayUrl) {
            QRCodeGenerator.generateQRCode(displayUrl, 600)
        }

        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            containerColor = DarkCardElevated,
            title = {
                Text(
                    "📷 Scan to Connect",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = CopperLight,
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
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (qrBitmap != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .size(240.dp)
                                    .padding(8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        displayUrl,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CopperLight
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showQrDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CopperPrimary)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold, color = DarkBg)
                }
            }
        )
    }
}

@Composable
fun SharedItemRow(
    item: SharedFileItem,
    onCopyText: () -> Unit,
    onRemove: () -> Unit
) {
    val isText = item.textContent != null || item.mimeType.startsWith("text/") || item.name.endsWith(".txt")

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F0804),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isText) "📝" else "📄", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatBytes(item.size) + (if (isText) " • Text Note" else ""),
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                if (isText && item.textContent != null) {
                    FilledTonalButton(
                        onClick = onCopyText,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = DarkCardElevated,
                            contentColor = EmeraldLight
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("📋 Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("✕", fontSize = 14.sp, color = Color(0xFFF87171))
                }
            }

            if (isText && !item.textContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x33000000),
                    border = BorderStroke(1.dp, Color(0x22C49267)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "“${item.textContent.take(120)}${if (item.textContent.length > 120) "..." else ""}”",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ReceivedItemRow(
    item: ReceivedFileItem,
    onCopyText: (String) -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val isText = item.textContent != null || item.name.endsWith(".txt", ignoreCase = true) || item.name.startsWith("note_")
    val dateStr = remember(item.lastModified) {
        SimpleDateFormat("HH:mm • dd MMM", Locale.getDefault()).format(Date(item.lastModified))
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F0804),
        border = BorderStroke(1.dp, if (isText) BorderEmerald else BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isText) "📝" else "📥", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatBytes(item.size)} • $dateStr",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                if (isText && item.textContent != null) {
                    Button(
                        onClick = { onCopyText(item.textContent) },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("📋 Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                TextButton(
                    onClick = onOpen,
                    colors = ButtonDefaults.textButtonColors(contentColor = CopperLight),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Open", fontSize = 11.sp)
                }

                TextButton(
                    onClick = onShare,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Share", fontSize = 11.sp)
                }
            }

            if (isText && !item.textContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x33000000),
                    border = BorderStroke(1.dp, Color(0x3310B981)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = item.textContent,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                "Tap '📋 Copy' to copy text to phone clipboard",
                                fontSize = 10.sp,
                                color = EmeraldLight
                            )
                        }
                    }
                }
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
