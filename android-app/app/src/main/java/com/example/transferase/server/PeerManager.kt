package com.example.transferase.server

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ConnectedPeer(
    val id: String,
    var name: String,
    val deviceType: String, // "desktop", "mobile", "tablet"
    val ip: String,
    var lastSeen: Long = System.currentTimeMillis()
)

object PeerManager {
    private const val PREFS_NAME = "transferase_prefs"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val HEARTBEAT_TIMEOUT_MS = 45000L // 45 seconds

    private val peerMap = ConcurrentHashMap<String, ConnectedPeer>()
    private val _peers = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val peers = _peers.asStateFlow()

    fun getPhoneDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_DEVICE_NAME, null)
        if (!saved.isNullOrBlank()) return saved

        val manu = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val defaultName = if (model.startsWith(manu, ignoreCase = true)) model else "$manu $model"
        return defaultName
    }

    fun setPhoneDeviceName(context: Context, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_NAME, clean).apply()
    }

    fun registerOrUpdate(id: String, name: String, deviceType: String, ip: String) {
        val cleanName = if (name.isBlank()) "Connected Device" else name
        val peer = peerMap.computeIfAbsent(id) {
            ConnectedPeer(id = id, name = cleanName, deviceType = deviceType, ip = ip)
        }
        peer.name = cleanName
        peer.lastSeen = System.currentTimeMillis()
        updateFlow()
    }

    fun heartbeat(id: String) {
        peerMap[id]?.let {
            it.lastSeen = System.currentTimeMillis()
        }
    }

    fun renamePeer(id: String, newName: String) {
        peerMap[id]?.let {
            it.name = newName
            it.lastSeen = System.currentTimeMillis()
            updateFlow()
        }
    }

    fun removePeer(id: String) {
        if (peerMap.remove(id) != null) {
            updateFlow()
        }
    }

    fun pruneExpiredPeers(): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        val iterator = peerMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > HEARTBEAT_TIMEOUT_MS) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            updateFlow()
        }
        return changed
    }

    fun clearAll() {
        peerMap.clear()
        updateFlow()
    }

    private fun updateFlow() {
        _peers.value = peerMap.values.toList().sortedBy { it.name }
    }
}
