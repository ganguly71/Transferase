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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
import com.example.transferase.server.ConnectedPeer
import com.example.transferase.server.NetworkUtils
import com.example.transferase.server.PeerManager
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
    val connectedPeers by PeerManager.peers.collectAsState()

    var phoneDeviceName by remember { mutableStateOf(PeerManager.getPhoneDeviceName(context)) }
    var showRenamePhoneDialog by remember { mutableStateOf(false) }
    var renamePhoneInput by remember { mutableStateOf("") }

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
            Toast.makeText(context, "${uris.size} file(s) added to queue", Toast.LENGTH_SHORT).show()
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

    val totalSharedSize = sharedFiles.sumOf { it.size }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBgGradientBottom)
            ) {
                // Flowing binary data track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33000000))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "01010100 01110010 01100001 01101110 01110011 01100110 01100101 01110010 01100001 01110011 01100101 00100000 01010000 00110010 01010000",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0x7738BDF8),
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = AccentCyan.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
                                modifier = Modifier.size(36.dp)
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
                                    color = TextPrimary,
                                    letterSpacing = (-0.3).sp
                                )
                                Text(
                                    "FAST OFFLINE LOCAL P2P TRANSFER",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = {
                                networkInfo = NetworkUtils.getNetworkInfo(context)
                                SharedFileManager.refreshReceivedFiles(context)
                                Toast.makeText(context, "Refreshed network & files", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0x2238BDF8),
                                contentColor = AccentCyan
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("🔄 Refresh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBgGradientBottom
                    )
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    // Modern dark space ambient gradient glow
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(DarkBgGradientTop, DarkBgGradientBottom),
                            center = Offset(size.width * 0.15f, 0f),
                            radius = size.maxDimension * 1.1f
                        )
                    )
                    drawCircle(
                        color = Color(0x0C38BDF8),
                        radius = size.width * 0.5f,
                        center = Offset(size.width * 0.85f, size.height * 0.35f)
                    )
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Unified Network & Server Status Card
            item {
                ModernGlassCard {
                    // Status Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .scale(if (isRunning) pulseScale else 1f)
                                    .clip(CircleShape)
                                    .background(if (isRunning) EmeraldOnline else ErrorRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isRunning) "SERVER ONLINE" else "SERVER STOPPED",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                letterSpacing = 0.5.sp,
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
                                containerColor = if (isRunning) ErrorRed else EmeraldOnline
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (isRunning) "STOP SERVER" else "START SERVER",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
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
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Address row with Copy button
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CardBgElevated,
                        border = BorderStroke(1.dp, CardBorder),
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
                                color = AccentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = {
                                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase URL", displayUrl))
                                    Toast.makeText(context, "Copied URL to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0x3338BDF8),
                                    contentColor = AccentCyan
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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
                            shape = RoundedCornerShape(999.dp),
                            color = if (networkInfo.connectionType.contains("Hotspot")) AmberBg else EmeraldBg,
                            border = BorderStroke(1.dp, if (networkInfo.connectionType.contains("Hotspot")) AmberBorder else EmeraldBorder)
                        ) {
                            Text(
                                "📶 ${networkInfo.connectionType.uppercase()}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = if (networkInfo.connectionType.contains("Hotspot")) AmberHotspot else EmeraldLight,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = { showQrDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("📱 QR CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = AccentCyan)
                        }
                    }
                }
            }

            // 2. YOUR DEVICE Card
            item {
                ModernGlassCard {
                    Text(
                        "YOUR DEVICE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.8.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                phoneDeviceName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = CardBgElevated,
                                border = BorderStroke(1.dp, CardBorder)
                            ) {
                                Text(
                                    "MOBILE • MAX SAFE: ~1 GB (RAM)",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                renamePhoneInput = phoneDeviceName
                                showRenamePhoneDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0x2238BDF8),
                                contentColor = AccentCyan
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("✏️ Rename", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3 Quick Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("📁 Add Files", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val clip = clipboardManager.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        SharedFileManager.addSharedText(context, text)
                                        Toast.makeText(context, "Clipboard shared to queue!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardBgElevated,
                                contentColor = TextPrimary
                            ),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("📋 Paste Clip", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showTextDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardBgElevated,
                                contentColor = TextPrimary
                            ),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("✏️ Write Note", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 3. CONNECTED PEERS (THE NEW FEATURE)
            item {
                ModernGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "CONNECTED PEERS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (connectedPeers.isNotEmpty()) EmeraldBg else CardBgElevated,
                            border = BorderStroke(1.dp, if (connectedPeers.isNotEmpty()) EmeraldBorder else CardBorder)
                        ) {
                            Text(
                                "${connectedPeers.size} ${if (connectedPeers.size == 1) "DEVICE" else "DEVICES"} READY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (connectedPeers.isNotEmpty()) EmeraldLight else TextSecondary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (connectedPeers.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CardBgElevated.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = AccentCyan.copy(alpha = 0.12f),
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("📡", fontSize = 26.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "WAITING FOR NEARBY DEVICES...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Open $displayUrl on your PC, laptop, or other phone to connect automatically.",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            connectedPeers.forEach { peer ->
                                ConnectedPeerItemCard(
                                    peer = peer,
                                    queueCount = sharedFiles.size,
                                    onSendQueue = {
                                        Toast.makeText(context, "Queue is live and accessible to ${peer.name}!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 4. SENDING QUEUE (SHARED TO PC)
            item {
                ModernGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "SENDING QUEUE (SHARED TO PC)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                letterSpacing = 0.5.sp
                            )
                            if (sharedFiles.isNotEmpty()) {
                                Text(
                                    "Total: ${formatFileSize(totalSharedSize)} (${sharedFiles.size} items)",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        if (sharedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = { SharedFileManager.clearSharedFiles() }
                            ) {
                                Text(
                                    "CLEAR QUEUE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (sharedFiles.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CardBgElevated.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { filePickerLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("📁", fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "SENDING QUEUE IS EMPTY",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "Tap 'Add Files', 'Paste Clip', or 'Write Note' above to share with PC.",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sharedFiles.forEach { item ->
                                SharedQueueItemCard(
                                    item = item,
                                    onCopyText = { text ->
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase Note", text))
                                        Toast.makeText(context, "Copied note to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    onRemove = {
                                        SharedFileManager.removeSharedFile(item.id)
                                        Toast.makeText(context, "Removed item from queue", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 5. RECEIVED FILES & TEXT (FROM PC)
            item {
                ModernGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "RECEIVED FILES & TEXT (FROM PC)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )

                        if (receivedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = { SharedFileManager.clearReceivedFiles(context) }
                            ) {
                                Text(
                                    "CLEAR ALL",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (receivedFiles.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CardBgElevated.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("📥", fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "NO FILES OR TEXT RECEIVED YET",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "When PC sends files or text notes, they appear here instantly.",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            receivedFiles.forEach { fileItem ->
                                ReceivedFileItemCard(
                                    item = fileItem,
                                    onCopyToClipboard = { text ->
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase Note", text))
                                        Toast.makeText(context, "Copied text to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    onOpenFile = { file ->
                                        openFile(context, file)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename Phone Dialog
    if (showRenamePhoneDialog) {
        AlertDialog(
            onDismissRequest = { showRenamePhoneDialog = false },
            title = {
                Text(
                    "Rename Device",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        "Set a custom name for this phone so connected PCs can easily recognize it:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = renamePhoneInput,
                        onValueChange = { renamePhoneInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = renamePhoneInput.trim()
                        if (clean.isNotBlank()) {
                            PeerManager.setPhoneDeviceName(context, clean)
                            phoneDeviceName = clean
                            Toast.makeText(context, "Renamed device to $clean", Toast.LENGTH_SHORT).show()
                        }
                        showRenamePhoneDialog = false
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenamePhoneDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            shape = RoundedCornerShape(18.dp),
            containerColor = CardBgSolid
        )
    }

    // Write Note Dialog
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = {
                Text(
                    "Write or Paste Text Note",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Note Content:", fontSize = 12.sp, color = TextSecondary)
                        FilledTonalButton(
                            onClick = {
                                val clip = clipboardManager.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    textInputState = text
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("📋 Paste Clipboard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = textInputState,
                        onValueChange = { textInputState = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        shape = RoundedCornerShape(10.dp),
                        placeholder = { Text("Enter text, URLs, notes, code snippets...", fontSize = 12.sp, color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = textInputState.trim()
                        if (text.isNotBlank()) {
                            SharedFileManager.addSharedText(context, text)
                            Toast.makeText(context, "Note added to queue!", Toast.LENGTH_SHORT).show()
                            textInputState = ""
                            showTextDialog = false
                        } else {
                            Toast.makeText(context, "Note cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                ) {
                    Text("Add to Queue", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            shape = RoundedCornerShape(18.dp),
            containerColor = CardBgSolid
        )
    }

    // QR Code Dialog
    if (showQrDialog) {
        val qrBitmap = remember(displayUrl) {
            QRCodeGenerator.generateQRCode(displayUrl, 512)
        }

        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = {
                Text(
                    "Scan to Connect",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary,
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
                        "Scan with your PC, laptop, or other phone camera:",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (qrBitmap != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        displayUrl,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showQrDialog = false },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(18.dp),
            containerColor = CardBgSolid
        )
    }
}

// -------------------------------------------------------------
// Modern Glass Card Component (Rounded 18dp + Soft Shadow)
// -------------------------------------------------------------
@Composable
fun ModernGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

// -------------------------------------------------------------
// Connected Peer Item Card
// -------------------------------------------------------------
@Composable
fun ConnectedPeerItemCard(
    peer: ConnectedPeer,
    queueCount: Int,
    onSendQueue: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBgElevated,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EmeraldBg,
                        border = BorderStroke(1.dp, EmeraldBorder),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (peer.deviceType == "mobile") "📱" else if (peer.deviceType == "tablet") "📱" else "🖥️",
                                fontSize = 18.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            peer.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${peer.deviceType.uppercase()} • ",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldOnline)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "READY • ⚡ DIRECT LINK",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldLight
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = DarkBg,
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text(
                        peer.ip,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onSendQueue,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text(
                    if (queueCount > 0) "⚡ Send Queue ($queueCount) to ${peer.name.split(" ")[0]}"
                    else "⚡ Connected to ${peer.name.split(" ")[0]}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Shared Queue Item Card
// -------------------------------------------------------------
@Composable
fun SharedQueueItemCard(
    item: SharedFileItem,
    onCopyText: (String) -> Unit,
    onRemove: () -> Unit
) {
    val isText = item.textContent != null || item.mimeType.startsWith("text/") || item.name.endsWith(".txt", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardBgElevated,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isText) "📝" else "📁", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            item.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatFileSize(item.size)} • ${if (isText) "Text Note" else item.mimeType}",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isText && item.textContent != null) {
                        FilledTonalButton(
                            onClick = { onCopyText(item.textContent) },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("📋 Copy", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("✕", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
                    }
                }
            }

            if (isText && !item.textContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkBg,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        item.textContent,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Received File Item Card
// -------------------------------------------------------------
@Composable
fun ReceivedFileItemCard(
    item: ReceivedFileItem,
    onCopyToClipboard: (String) -> Unit,
    onOpenFile: (File) -> Unit
) {
    val isText = item.textContent != null || item.name.endsWith(".txt", ignoreCase = true) || item.name.startsWith("note_", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardBgElevated,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isText) "📝" else "📥", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            item.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatFileSize(item.size)} • ${formatDate(item.lastModified)}",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isText && item.textContent != null) {
                        Button(
                            onClick = { onCopyToClipboard(item.textContent) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldOnline, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("📋 Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Button(
                        onClick = { onOpenFile(File(item.path)) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isText && !item.textContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkBg,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyToClipboard(item.textContent) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            item.textContent,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "👆 Tap to copy to clipboard",
                            fontSize = 9.sp,
                            color = AccentCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Format Utilities
// -------------------------------------------------------------
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val k = 1024
    val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (Math.log(bytes.toDouble()) / Math.log(k.toDouble())).toInt()
    val value = bytes / Math.pow(k.toDouble(), i.toDouble())
    return String.format(Locale.US, "%.1f %s", value, sizes[i])
}

private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun openFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
