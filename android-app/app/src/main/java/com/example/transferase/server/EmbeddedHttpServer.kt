package com.example.transferase.server

import android.content.Context
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class EmbeddedHttpServer(
    private val context: Context,
    private val port: Int = 4000,
    private val onFileReceived: ((String, Long) -> Unit)? = null
) {
    private val TAG = "EmbeddedHttpServer"
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false

    private val sseClients = CopyOnWriteArrayList<OutputStream>()

    fun start() {
        if (isRunning) return
        isRunning = true

        // Background prune task for connected peer heartbeats
        executor.execute {
            while (isRunning) {
                try {
                    Thread.sleep(15000)
                    if (!isRunning) break
                    if (PeerManager.pruneExpiredPeers()) {
                        broadcastEvent("peers_updated", getPeersJson())
                    }
                } catch (ignored: Exception) {}
            }
        }

        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Server started on port $port")

                while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        for (client in sseClients) {
            try { client.close() } catch (ignored: Exception) {}
        }
        sseClients.clear()
        PeerManager.clearAll()
        Log.i(TAG, "Server stopped")
    }

    fun broadcastEvent(eventName: String, dataJson: String) {
        val message = "event: $eventName\ndata: $dataJson\n\n".toByteArray(StandardCharsets.UTF_8)
        val iterator = sseClients.iterator()
        while (iterator.hasNext()) {
            val os = iterator.next()
            try {
                os.write(message)
                os.flush()
            } catch (e: Exception) {
                sseClients.remove(os)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestHeaderLines = mutableListOf<String>()
            val lineBuffer = ByteArrayOutputStream()
            var prevByte = -1

            // Read HTTP request headers
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (prevByte == '\r'.code && b == '\n'.code) {
                    val line = String(lineBuffer.toByteArray(), 0, lineBuffer.size() - 1, StandardCharsets.UTF_8)
                    lineBuffer.reset()
                    if (line.isEmpty()) break
                    requestHeaderLines.add(line)
                } else {
                    lineBuffer.write(b)
                }
                prevByte = b
            }

            if (requestHeaderLines.isEmpty()) {
                socket.close()
                return
            }

            val requestLine = requestHeaderLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.split("?")[0]
            val queryString = if (rawPath.contains("?")) rawPath.substring(rawPath.indexOf("?") + 1) else ""

            val headers = mutableMapOf<String, String>()
            for (i in 1 until requestHeaderLines.size) {
                val h = requestHeaderLines[i]
                val colonIdx = h.indexOf(":")
                if (colonIdx > 0) {
                    headers[h.substring(0, colonIdx).trim().lowercase()] = h.substring(colonIdx + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", "text/plain", ByteArray(0))
                return
            }

            // Route handling
            when {
                path == "/api/status" -> handleStatus(output)
                path == "/api/peers" && method == "GET" -> handleListPeers(output)
                path == "/api/peers/register" && method == "POST" -> handleRegisterPeer(input, headers, socket, output)
                path == "/api/peers/heartbeat" && method == "POST" -> handlePeerHeartbeat(input, headers, output)
                path == "/api/peers/rename" && method == "POST" -> handlePeerRename(input, headers, output)
                path == "/api/peers/unregister" && method == "POST" -> handlePeerUnregister(input, headers, output)
                path == "/api/device/rename" && method == "POST" -> handleDeviceRename(input, headers, output)
                path == "/api/files" -> handleListSharedFiles(output)
                path == "/api/received" -> handleListReceivedFiles(output)
                path == "/api/download" -> handleDownload(queryString, output)
                path == "/api/upload" && method == "POST" -> handleUpload(input, headers, output)
                path == "/api/message" -> handleMessage(method, input, headers, output)
                path == "/api/events" -> handleSse(queryString, output, socket)
                path.startsWith("/socket.io") -> handleSocketIo(output)
                else -> handleStaticAsset(path, output)
            }

        } catch (e: Exception) {
            Log.d(TAG, "Client handle exception: ${e.message}")
        } finally {
            // Keep socket open if it's SSE, otherwise close
            try {
                if (!socket.isClosed && !sseClients.any { it == socket.getOutputStream() }) {
                    socket.close()
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun getPeersJson(): String {
        val phoneName = PeerManager.getPhoneDeviceName(context)
        val netInfo = NetworkUtils.getNetworkInfo(context)
        val hostPeerJson = """{"id":"phone_host_server","name":"${escapeJson(phoneName)} (Host)","deviceType":"mobile","isPhoneHost":true,"status":"online","ip":"${netInfo.primaryIp}"}"""

        val clientPeers = PeerManager.peers.value.map { p ->
            """{"id":"${escapeJson(p.id)}","name":"${escapeJson(p.name)}","deviceType":"${escapeJson(p.deviceType)}","isPhoneHost":false,"status":"online","ip":"${escapeJson(p.ip)}"}"""
        }
        val allPeers = listOf(hostPeerJson) + clientPeers
        return "[${allPeers.joinToString(",")}]"
    }

    private fun handleStatus(output: OutputStream) {
        val devName = PeerManager.getPhoneDeviceName(context)
        val json = """
            {
              "status": "online",
              "app": "Transferase Mobile",
              "deviceName": "${escapeJson(devName)}",
              "port": $port,
              "onlineUrl": "https://transferase.onrender.com",
              "sharedCount": ${SharedFileManager.sharedFiles.value.size},
              "receivedCount": ${SharedFileManager.receivedFiles.value.size},
              "peerCount": ${PeerManager.peers.value.size}
            }
        """.trimIndent()
        sendResponse(output, 200, "OK", "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleListPeers(output: OutputStream) {
        val json = getPeersJson()
        sendResponse(output, 200, "OK", "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleRegisterPeer(input: InputStream, headers: Map<String, String>, socket: Socket, output: OutputStream) {
        val body = readBodyString(input, headers)
        val id = extractJsonField(body, "id") ?: ("usr_" + System.currentTimeMillis())
        val name = extractJsonField(body, "name") ?: "Connected Device"
        val deviceType = extractJsonField(body, "deviceType") ?: "desktop"
        val ip = socket.inetAddress?.hostAddress ?: "unknown"

        PeerManager.registerOrUpdate(id, name, deviceType, ip)
        broadcastEvent("peers_updated", getPeersJson())
        sendResponse(output, 200, "OK", "application/json", """{"success":true,"id":"$id"}""".toByteArray(StandardCharsets.UTF_8))
    }

    private fun handlePeerHeartbeat(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val body = readBodyString(input, headers)
        val id = extractJsonField(body, "id")
        if (id != null) {
            PeerManager.heartbeat(id)
        }
        sendResponse(output, 200, "OK", "application/json", """{"success":true}""".toByteArray(StandardCharsets.UTF_8))
    }

    private fun handlePeerRename(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val body = readBodyString(input, headers)
        val id = extractJsonField(body, "id")
        val name = extractJsonField(body, "name")
        if (id != null && !name.isNullOrBlank()) {
            PeerManager.renamePeer(id, name)
            broadcastEvent("peers_updated", getPeersJson())
        }
        sendResponse(output, 200, "OK", "application/json", """{"success":true}""".toByteArray(StandardCharsets.UTF_8))
    }

    private fun handlePeerUnregister(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val body = readBodyString(input, headers)
        val id = extractJsonField(body, "id")
        if (id != null) {
            PeerManager.removePeer(id)
            broadcastEvent("peers_updated", getPeersJson())
        }
        sendResponse(output, 200, "OK", "application/json", """{"success":true}""".toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleDeviceRename(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val body = readBodyString(input, headers)
        val name = extractJsonField(body, "name")
        if (!name.isNullOrBlank()) {
            PeerManager.setPhoneDeviceName(context, name)
            broadcastEvent("peers_updated", getPeersJson())
            broadcastEvent("device_renamed", """{"name":"${escapeJson(name)}"}""")
        }
        sendResponse(output, 200, "OK", "application/json", """{"success":true}""".toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleListSharedFiles(output: OutputStream) {
        val files = SharedFileManager.sharedFiles.value
        val itemsJson = files.joinToString(",") { f ->
            val isText = f.textContent != null || f.mimeType.startsWith("text/") || f.name.endsWith(".txt", ignoreCase = true)
            val textEscaped = f.textContent?.let { escapeJson(it) } ?: ""
            """{"id":"${f.id}","name":"${escapeJson(f.name)}","size":${f.size},"mimeType":"${f.mimeType}","isText":$isText,"textContent":"$textEscaped"}"""
        }
        val json = "[$itemsJson]"
        sendResponse(output, 200, "OK", "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleListReceivedFiles(output: OutputStream) {
        SharedFileManager.refreshReceivedFiles(context)
        val files = SharedFileManager.receivedFiles.value
        val itemsJson = files.joinToString(",") { f ->
            """{"name":"${escapeJson(f.name)}","size":${f.size},"lastModified":${f.lastModified}}"""
        }
        val json = "[$itemsJson]"
        sendResponse(output, 200, "OK", "application/json", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleDownload(queryString: String, output: OutputStream) {
        val params = parseQuery(queryString)
        val fileId = params["id"] ?: ""
        val sharedPair = SharedFileManager.openInputStreamForSharedFile(context, fileId)

        if (sharedPair == null) {
            sendResponse(output, 404, "Not Found", "text/plain", "File not found or expired".toByteArray())
            return
        }

        val (item, fileStream) = sharedPair
        try {
            val header = StringBuilder()
            header.append("HTTP/1.1 200 OK\r\n")
            header.append("Content-Type: ${item.mimeType}\r\n")
            if (item.size > 0) {
                header.append("Content-Length: ${item.size}\r\n")
            }
            header.append("Content-Disposition: attachment; filename=\"${item.name}\"\r\n")
            header.append("Access-Control-Allow-Origin: *\r\n")
            header.append("Connection: close\r\n\r\n")
            output.write(header.toString().toByteArray(StandardCharsets.UTF_8))

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Download streaming interrupted: ${e.message}")
        } finally {
            try { fileStream.close() } catch (ignored: Exception) {}
        }
    }

    private fun handleUpload(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val contentType = headers["content-type"] ?: ""
        var fileName = headers["x-file-name"]

        if (fileName != null) {
            fileName = URLDecoder.decode(fileName, "UTF-8")
        }

        val targetDir = SharedFileManager.getDownloadDir(context)

        if (fileName != null && fileName.isNotBlank()) {
            val destFile = File(targetDir, fileName)
            var written = 0L
            FileOutputStream(destFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = if (contentLength > 0) contentLength else Long.MAX_VALUE
                while (remaining > 0) {
                    val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    written += read
                    if (contentLength > 0) remaining -= read
                }
            }

            SharedFileManager.refreshReceivedFiles(context)
            onFileReceived?.invoke(destFile.name, written)
            broadcastEvent("file_received", """{"name":"${escapeJson(destFile.name)}","size":$written}""")

            val res = """{"success":true,"name":"${escapeJson(destFile.name)}","size":$written}"""
            sendResponse(output, 200, "OK", "application/json", res.toByteArray(StandardCharsets.UTF_8))
            return
        }

        // Multipart/form-data upload fallback
        if (contentType.contains("multipart/form-data") && contentType.contains("boundary=")) {
            val actualName = "upload_${System.currentTimeMillis()}"
            val destFile = File(targetDir, actualName)

            var totalRead = 0L
            FileOutputStream(destFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = if (contentLength > 0) contentLength else 100 * 1024 * 1024L
                while (remaining > 0) {
                    val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                    val r = input.read(buffer, 0, toRead)
                    if (r == -1) break
                    fos.write(buffer, 0, r)
                    totalRead += r
                    remaining -= r
                }
            }

            SharedFileManager.refreshReceivedFiles(context)
            onFileReceived?.invoke(destFile.name, totalRead)
            broadcastEvent("file_received", """{"name":"${escapeJson(destFile.name)}","size":$totalRead}""")

            val res = """{"success":true,"name":"${escapeJson(destFile.name)}","size":$totalRead}"""
            sendResponse(output, 200, "OK", "application/json", res.toByteArray(StandardCharsets.UTF_8))
            return
        }

        sendResponse(output, 400, "Bad Request", "application/json", """{"error":"Missing file name or unsupported content type"}""".toByteArray())
    }

    private fun handleMessage(method: String, input: InputStream, headers: Map<String, String>, output: OutputStream) {
        if (method == "POST") {
            val body = readBodyString(input, headers)

            if (body.isNotBlank()) {
                SharedFileManager.setMessage(body)
                try {
                    val targetDir = SharedFileManager.getDownloadDir(context)
                    val noteFile = File(targetDir, "note_pc_${System.currentTimeMillis() % 10000}.txt")
                    noteFile.writeText(body, StandardCharsets.UTF_8)
                    SharedFileManager.refreshReceivedFiles(context)
                    onFileReceived?.invoke(noteFile.name, noteFile.length())
                } catch (ignored: Exception) {}
                broadcastEvent("new_message", """{"message":"${escapeJson(body)}"}""")
            }
            sendResponse(output, 200, "OK", "application/json", """{"success":true}""".toByteArray())
        } else {
            val msg = SharedFileManager.lastMessage.value ?: ""
            sendResponse(output, 200, "OK", "application/json", """{"message":"${escapeJson(msg)}"}""".toByteArray())
        }
    }

    private fun handleSse(queryString: String, output: OutputStream, socket: Socket) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()

        val params = parseQuery(queryString)
        val clientId = params["clientId"]
        val clientName = params["clientName"]
        val deviceType = params["deviceType"] ?: "desktop"
        val clientIp = socket.inetAddress?.hostAddress ?: "unknown"

        if (!clientId.isNullOrBlank()) {
            PeerManager.registerOrUpdate(clientId, clientName ?: "Connected Device", deviceType, clientIp)
            broadcastEvent("peers_updated", getPeersJson())
        }

        sseClients.add(output)
        val initialPing = "event: connected\ndata: {\"status\":\"connected\",\"peers\":${getPeersJson()}}\n\n"
        output.write(initialPing.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun handleSocketIo(output: OutputStream) {
        val response = "0{\"sid\":\"transferase_mobile_sid\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":100000000}"
        sendResponse(output, 200, "OK", "text/plain", response.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleStaticAsset(requestedPath: String, output: OutputStream) {
        var cleanPath = requestedPath.trimStart('/')
        if (cleanPath.isEmpty()) cleanPath = "index.html"

        val assetPath = "web/$cleanPath"
        var inputStream: InputStream? = null

        try {
            inputStream = context.assets.open(assetPath)
        } catch (e: Exception) {
            try {
                inputStream = context.assets.open("web/index.html")
            } catch (ignored: Exception) {
                sendResponse(output, 404, "Not Found", "text/plain", "Asset not found".toByteArray())
                return
            }
        }

        val mime = getMimeType(cleanPath)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        try {
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            val buf = ByteArray(32 * 1024)
            var n: Int
            while (inputStream.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
            }
            output.flush()
        } catch (e: Exception) {
            Log.d(TAG, "Static asset write interrupted: ${e.message}")
        } finally {
            try { inputStream.close() } catch (ignored: Exception) {}
        }
    }

    private fun readBodyString(input: InputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val r = input.read(bytes, read, length - read)
            if (r == -1) break
            read += r
        }
        return String(bytes, 0, read, StandardCharsets.UTF_8)
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun sendResponse(output: OutputStream, code: Int, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
        try {
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.write(body)
            output.flush()
        } catch (ignored: Exception) {}
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        for (param in query.split("&")) {
            val pair = param.split("=")
            if (pair.size == 2) {
                map[URLDecoder.decode(pair[0], "UTF-8")] = URLDecoder.decode(pair[1], "UTF-8")
            }
        }
        return map
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
