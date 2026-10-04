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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.RectangleShape
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
        initialValue = 0.90f,
        targetValue = 1.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val totalSharedSize = sharedFiles.sumOf { it.size }

    Scaffold(
        containerColor = WebBgColor,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WebBgGradientBottom)
            ) {
                // Binary track animation visual matching web header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x55000000))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "01010100 01110010 01100001 01101110 01110011 01100110 01100101 01110010 01100001 01110011 01100101",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WebCopper,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RectangleShape,
                                color = WebAccentDark,
                                border = BorderStroke(1.dp, WebCopper),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("⚡", fontSize = 16.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "TRANSFERASE",
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = WebTextLight,
                                    letterSpacing = 1.5.sp
                                )
                                Text(
                                    "OFFLINE P2P LOCAL TRANSFER",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    color = WebCopperLight,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                networkInfo = NetworkUtils.getNetworkInfo(context)
                                SharedFileManager.refreshReceivedFiles(context)
                                Toast.makeText(context, "Refreshed network & files", Toast.LENGTH_SHORT).show()
                            },
                            shape = RectangleShape
                        ) {
                            Text(
                                "🔄 REFRESH",
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = WebCopperLight,
                                letterSpacing = 1.sp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = WebBgGradientBottom
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
                    // Radial gradient background like website
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(WebBgGradientTop, WebBgGradientBottom),
                            center = Offset(size.width * 0.2f, 0f),
                            radius = size.maxDimension * 1.2f
                        )
                    )
                    // Crosshatch texture
                    val step = 32f
                    val strokeW = 1.2f
                    val lineCol = WebCrosshatchLine
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(
                            color = lineCol,
                            start = Offset(x, 0f),
                            end = Offset(x + size.height, size.height),
                            strokeWidth = strokeW
                        )
                        x += step
                    }
                    x = 0f
                    while (x < size.width + size.height) {
                        drawLine(
                            color = lineCol,
                            start = Offset(x, 0f),
                            end = Offset(x - size.height, size.height),
                            strokeWidth = strokeW
                        )
                        x += step
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Unified Network Card (Matches website .network-unified-card)
            item {
                WebBrutalistCard {
                    // Top: Connection & Status
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
                                    .background(if (isRunning) WebSuccess else WebError, shape = RectangleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isRunning) "SERVER ONLINE" else "SERVER STOPPED",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp,
                                color = if (isRunning) WebSuccess else WebError
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
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) WebError else WebSuccess
                            ),
                            border = BorderStroke(1.dp, WebTextPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (isRunning) "STOP SERVER" else "START SERVER",
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                                color = WebTextLight
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "ACCESS ADDRESS (ENTER IN PC BROWSER):",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = WebTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Address row with Copy button
                    Surface(
                        shape = RectangleShape,
                        color = WebCardInnerBg,
                        border = BorderStroke(1.dp, WebCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                displayUrl,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = WebTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Transferase URL", displayUrl))
                                    Toast.makeText(context, "Copied URL to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RectangleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WebAccentDark,
                                    contentColor = WebTextLight
                                ),
                                border = BorderStroke(1.dp, WebCopper),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("📋 COPY", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RectangleShape,
                            color = if (networkInfo.connectionType.contains("Hotspot")) Color(0x33D97706) else WebSuccessBg,
                            border = BorderStroke(1.dp, if (networkInfo.connectionType.contains("Hotspot")) WebAmber else WebSuccessBorder)
                        ) {
                            Text(
                                "📶 ${networkInfo.connectionType.uppercase()}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                letterSpacing = 0.5.sp,
                                color = if (networkInfo.connectionType.contains("Hotspot")) WebAmber else WebSuccess,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = { showQrDialog = true },
                            shape = RectangleShape,
                            border = BorderStroke(1.dp, WebAccentDark),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("📱 QR CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WebAccentDark)
                        }
                    }
                }
            }

            // 2. YOUR DEVICE Card
            item {
                WebBrutalistCard {
                    Text(
                        "YOUR DEVICE",
                        fontFamily = FontFamily.Serif,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = WebTextSecondary,
                        letterSpacing = 1.sp
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
                                fontFamily = FontFamily.Serif,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = WebTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RectangleShape,
                                    color = WebCardInnerBg,
                                    border = BorderStroke(1.dp, WebCardBorder)
                                ) {
                                    Text(
                                        "MOBILE • MAX SAFE: ~1 GB (RAM)",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif,
                                        color = WebTextSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                renamePhoneInput = phoneDeviceName
                                showRenamePhoneDialog = true
                            },
                            shape = RectangleShape,
                            border = BorderStroke(1.dp, WebCopper),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("✏️ RENAME", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WebTextPrimary)
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
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = WebAccentDark, contentColor = WebTextLight),
                            border = BorderStroke(1.dp, WebCopper),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("📁 ADD FILES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        Button(
                            onClick = {
                                val clip = clipboardManager.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        SharedFileManager.addSharedText(context, text)
                                        Toast.makeText(context, "Clipboard text shared to queue!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = WebCardInnerBg, contentColor = WebTextPrimary),
                            border = BorderStroke(1.dp, WebTextPrimary),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("📋 PASTE CLIP", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        Button(
                            onClick = { showTextDialog = true },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = WebCardInnerBg, contentColor = WebTextPrimary),
                            border = BorderStroke(1.dp, WebTextPrimary),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("✏️ WRITE NOTE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            // 3. CONNECTED PEERS (THE NEW FEATURE)
            item {
                WebBrutalistCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "CONNECTED PEERS",
                            fontFamily = FontFamily.Serif,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = WebTextPrimary,
                            letterSpacing = 1.sp
                        )

                        Surface(
                            shape = RectangleShape,
                            color = if (connectedPeers.isNotEmpty()) WebSuccessBg else WebCardInnerBg,
                            border = BorderStroke(1.dp, if (connectedPeers.isNotEmpty()) WebSuccessBorder else WebCardBorder)
                        ) {
                            Text(
                                "${connectedPeers.size} ${if (connectedPeers.size == 1) "DEVICE" else "DEVICES"} READY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                color = if (connectedPeers.isNotEmpty()) WebSuccess else WebTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (connectedPeers.isEmpty()) {
                        Surface(
                            shape = RectangleShape,
                            color = WebCardInnerBg,
                            border = BorderStroke(1.dp, WebCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("📡", fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "WAITING FOR NEARBY DEVICES...",
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = WebTextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Open $displayUrl on your PC, laptop, or other phone to connect automatically.",
                                    fontSize = 11.sp,
                                    color = WebTextSecondary,
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
                WebBrutalistCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "SENDING QUEUE (SHARED TO PC)",
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = WebTextPrimary,
                                letterSpacing = 1.sp
                            )
                            if (sharedFiles.isNotEmpty()) {
                                Text(
                                    "Total: ${formatFileSize(totalSharedSize)} (${sharedFiles.size} items)",
                                    fontSize = 11.sp,
                                    color = WebTextSecondary
                                )
                            }
                        }

                        if (sharedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = { SharedFileManager.clearSharedFiles() },
                                shape = RectangleShape
                            ) {
                                Text(
                                    "CLEAR QUEUE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = WebError,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (sharedFiles.isEmpty()) {
                        Surface(
                            shape = RectangleShape,
                            color = WebCardInnerBg,
                            border = BorderStroke(1.dp, WebCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { filePickerLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("📁", fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "SENDING QUEUE IS EMPTY",
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = WebTextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "Tap 'Add Files', 'Paste Clip', or 'Write Note' above to share with PC.",
                                    fontSize = 11.sp,
                                    color = WebTextSecondary,
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
                WebBrutalistCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "RECEIVED FILES & TEXT (FROM PC)",
                            fontFamily = FontFamily.Serif,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = WebTextPrimary,
                            letterSpacing = 1.sp
                        )

                        if (receivedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = { SharedFileManager.clearReceivedFiles(context) },
                                shape = RectangleShape
                            ) {
                                Text(
                                    "CLEAR ALL",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = WebError,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (receivedFiles.isEmpty()) {
                        Surface(
                            shape = RectangleShape,
                            color = WebCardInnerBg,
                            border = BorderStroke(1.dp, WebCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("📥", fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "NO FILES OR TEXT RECEIVED YET",
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = WebTextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "When PC sends files or text notes, they appear here instantly.",
                                    fontSize = 11.sp,
                                    color = WebTextSecondary,
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
                    "RENAME DEVICE",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = WebTextPrimary,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Set a custom name for this phone so connected PCs can easily recognize it:",
                        fontSize = 12.sp,
                        color = WebTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renamePhoneInput,
                        onValueChange = { renamePhoneInput = it },
                        singleLine = true,
                        shape = RectangleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WebCopper,
                            unfocusedBorderColor = WebCardBorder,
                            focusedTextColor = WebTextPrimary,
                            unfocusedTextColor = WebTextPrimary
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
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = WebAccentDark, contentColor = WebTextLight)
                ) {
                    Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenamePhoneDialog = false }, shape = RectangleShape) {
                    Text("CANCEL", color = WebTextSecondary)
                }
            },
            shape = RectangleShape,
            containerColor = WebCardBgSolid
        )
    }

    // Write Note Dialog
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = {
                Text(
                    "WRITE OR PASTE TEXT NOTE",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = WebTextPrimary,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Note Content:", fontSize = 12.sp, color = WebTextSecondary)
                        TextButton(
                            onClick = {
                                val clip = clipboardManager.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    textInputState = text
                                }
                            },
                            shape = RectangleShape
                        ) {
                            Text("📋 Paste Clipboard", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WebCopper)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = textInputState,
                        onValueChange = { textInputState = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        shape = RectangleShape,
                        placeholder = { Text("Enter text, URLs, notes, code snippets...", fontSize = 12.sp, color = WebTextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WebCopper,
                            unfocusedBorderColor = WebCardBorder,
                            focusedTextColor = WebTextPrimary,
                            unfocusedTextColor = WebTextPrimary
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
                            Toast.makeText(context, "Note added to sending queue!", Toast.LENGTH_SHORT).show()
                            textInputState = ""
                            showTextDialog = false
                        } else {
                            Toast.makeText(context, "Note cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = WebAccentDark, contentColor = WebTextLight)
                ) {
                    Text("ADD TO QUEUE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }, shape = RectangleShape) {
                    Text("CANCEL", color = WebTextSecondary)
                }
            },
            shape = RectangleShape,
            containerColor = WebCardBgSolid
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
                    "SCAN TO CONNECT",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = WebTextPrimary,
                    letterSpacing = 1.sp,
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
                        color = WebTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (qrBitmap != null) {
                        Surface(
                            shape = RectangleShape,
                            color = Color.White,
                            border = BorderStroke(2.dp, WebCopper),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .size(210.dp)
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
                        color = WebTextPrimary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showQrDialog = false },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = WebAccentDark, contentColor = WebTextLight)
                ) {
                    Text("CLOSE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            shape = RectangleShape,
            containerColor = WebCardBgSolid
        )
    }
}

// -------------------------------------------------------------
// Website Brutalist Card Wrapper (Exact 0-radius + 4px shadow)
// -------------------------------------------------------------
@Composable
fun WebBrutalistCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(WebShadowBrutal, shape = RectangleShape)
            .padding(end = 4.dp, bottom = 4.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.5.dp, WebCardBorder), shape = RectangleShape)
                .padding(1.dp)
                .border(BorderStroke(1.dp, WebCardInsetBorder), shape = RectangleShape),
            shape = RectangleShape,
            color = WebCardBg
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                content = content
            )
        }
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
        shape = RectangleShape,
        color = WebCardInnerBg,
        border = BorderStroke(1.dp, WebCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RectangleShape,
                        color = WebSuccessBg,
                        border = BorderStroke(1.dp, WebSuccessBorder),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (peer.deviceType == "mobile") "📱" else if (peer.deviceType == "tablet") "📱" else "🖥️",
                                fontSize = 16.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            peer.name,
                            fontFamily = FontFamily.Serif,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = WebTextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${peer.deviceType.uppercase()} • ",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.SansSerif,
                                color = WebTextSecondary
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(WebSuccess, shape = RectangleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "READY • ⚡ DIRECT LINK",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                color = WebSuccess
                            )
                        }
                    }
                }

                Surface(
                    shape = RectangleShape,
                    color = WebCardLightBg,
                    border = BorderStroke(1.dp, WebCardBorder)
                ) {
                    Text(
                        peer.ip,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WebTextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onSendQueue,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = WebAccentDark, contentColor = WebTextLight),
                border = BorderStroke(1.dp, WebCopper),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text(
                    if (queueCount > 0) "⚡ SEND QUEUE ($queueCount) TO ${peer.name.split(" ")[0].uppercase()}"
                    else "⚡ CONNECTED TO ${peer.name.split(" ")[0].uppercase()}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
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
        shape = RectangleShape,
        color = WebCardInnerBg,
        border = BorderStroke(1.dp, WebCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
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
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = WebTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatFileSize(item.size)} • ${if (isText) "Text Note" else item.mimeType}",
                            fontSize = 10.sp,
                            color = WebTextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isText && item.textContent != null) {
                        Button(
                            onClick = { onCopyText(item.textContent) },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = WebCardLightBg, contentColor = WebTextPrimary),
                            border = BorderStroke(1.dp, WebTextPrimary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("📋 COPY", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    OutlinedButton(
                        onClick = onRemove,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, WebError),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("✕", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WebError)
                    }
                }
            }

            if (isText && !item.textContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RectangleShape,
                    color = WebCardLightBg,
                    border = BorderStroke(1.dp, WebCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        item.textContent,
                        fontSize = 11.sp,
                        color = WebTextPrimary,
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
        shape = RectangleShape,
        color = WebCardInnerBg,
        border = BorderStroke(1.dp, WebCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
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
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = WebTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatFileSize(item.size)} • ${formatDate(item.lastModified)}",
                            fontSize = 10.sp,
                            color = WebTextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isText && item.textContent != null) {
                        Button(
                            onClick = { onCopyToClipboard(item.textContent) },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = WebSuccess, contentColor = WebTextLight),
                            border = BorderStroke(1.dp, WebTextPrimary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("📋 COPY", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Button(
                        onClick = { onOpenFile(File(item.path)) },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = WebAccentDark, contentColor = WebTextLight),
                        border = BorderStroke(1.dp, WebCopper),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("OPEN", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
            }

            if (isText && !item.textContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RectangleShape,
                    color = WebCardLightBg,
                    border = BorderStroke(1.dp, WebCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyToClipboard(item.textContent) }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            item.textContent,
                            fontSize = 11.sp,
                            color = WebTextPrimary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "👆 Tap to copy to clipboard",
                            fontSize = 9.sp,
                            color = WebTextSecondary,
                            fontWeight = FontWeight.Bold
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
