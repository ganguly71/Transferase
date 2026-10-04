const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');

const app = express();
app.set('trust proxy', true);
app.use(cors());

// Serve static frontend files if built (for all-in-one cloud deployment on Render/Heroku/Railway)
const distPath = path.join(__dirname, '../client/dist');
app.use(express.static(distPath));

const server = http.createServer(app);
const io = new Server(server, {
  maxHttpBufferSize: 1e8, // Allow up to 100MB payloads for fallback relay
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

// Clean and normalize IP string from brackets, ports, and prefixes
function sanitizeIp(raw) {
  if (!raw) return '';
  let str = Array.isArray(raw) ? String(raw[0] || '') : String(raw).trim();
  if (str.includes(',')) {
    str = str.split(',')[0].trim();
  }
  if (str.startsWith('::ffff:')) {
    str = str.substring(7);
  }
  // Strip square brackets if IPv6 with port: [2409:40d0:...]:51234
  if (str.startsWith('[') && str.includes(']')) {
    str = str.substring(1, str.indexOf(']'));
  } else if (!str.includes(':') && str.includes(':')) {
    // IPv4 with port: 1.2.3.4:5678
    str = str.split(':')[0];
  }
  return str.trim();
}

// Helper to normalize an IPv6 address to its /64 network prefix
// In IPv6, every device gets its own /128 address, but devices on the same
// Wi-Fi router or mobile hotspot share the identical /64 network prefix (RFC 4291 / RFC 6177).
function getIPv6Prefix64(ip) {
  const clean = sanitizeIp(ip).toLowerCase();
  const parts = clean.split('::');
  let hextets = [];
  if (parts.length === 2) {
    const left = parts[0] ? parts[0].split(':').filter(Boolean) : [];
    const right = parts[1] ? parts[1].split(':').filter(Boolean) : [];
    const missing = 8 - (left.length + right.length);
    const middle = Array(Math.max(0, missing)).fill('0');
    hextets = [...left, ...middle, ...right];
  } else {
    hextets = clean.split(':').filter(Boolean);
  }
  const normalized = hextets.map(h => {
    const val = parseInt(h || '0', 16);
    return isNaN(val) ? '0' : val.toString(16);
  });
  while (normalized.length < 8) normalized.push('0');
  return normalized.slice(0, 4).join(':');
}

// Helper to normalize an IPv4 address to its /24 network prefix
function getIPv4Prefix24(ip) {
  const clean = sanitizeIp(ip);
  const parts = clean.split('.');
  if (parts.length === 4) {
    return `${parts[0]}.${parts[1]}.${parts[2]}`;
  }
  return clean;
}

// Helper to normalize an IPv4 address to its /16 ISP/carrier pool
function getIPv4Prefix16(ip) {
  const clean = sanitizeIp(ip);
  const parts = clean.split('.');
  if (parts.length >= 2) {
    return `${parts[0]}.${parts[1]}`;
  }
  return clean;
}

// Helper to determine client IP address and network group
function getNetworkRoom(socket) {
  const headers = socket.handshake.headers || {};
  // Priority: Cloudflare -> x-forwarded-for (first client IP) -> x-real-ip -> socket address
  const forwardedHeader = headers['cf-connecting-ip'] || 
                          headers['x-forwarded-for'] || 
                          headers['x-real-ip'];
  let rawIp = '';
  if (forwardedHeader) {
    rawIp = Array.isArray(forwardedHeader) ? forwardedHeader[0] : forwardedHeader;
  } else {
    rawIp = socket.handshake.address || socket.conn?.remoteAddress || 'unknown';
  }

  const ip = sanitizeIp(rawIp);

  // Detect if connection is local / LAN (IPv4 or IPv6 private ranges)
  const isLoopback = ip === '127.0.0.1' || ip === '::1' || ip === 'localhost';
  const isPrivateLan = /^192\.168\./.test(ip) || 
                       /^10\./.test(ip) || 
                       /^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(ip) ||
                       ip.toLowerCase().startsWith('fe80:') ||
                       ip.toLowerCase().startsWith('fd') ||
                       ip.toLowerCase().startsWith('fc');

  // If running locally on home LAN / localhost, place all LAN and localhost devices in the same local room!
  if (isLoopback || isPrivateLan) {
    return {
      ip,
      roomName: 'local_lan_network',
      isLocalMode: true,
      subnet24: '192.168.local',
      subnet16: '192.168',
      isV6: false
    };
  }

  // If client is on IPv6:
  // Normalize to /64 network prefix so all devices on the same mobile hotspot or home IPv6 router match!
  if (ip.includes(':')) {
    const prefix64 = getIPv6Prefix64(ip);
    return {
      ip,
      roomName: `network_v6_${prefix64.replace(/[^a-zA-Z0-9]/g, '_')}`,
      isLocalMode: false,
      subnet24: prefix64,
      subnet16: prefix64.split(':').slice(0, 2).join(':'),
      isV6: true
    };
  }

  // If running in cloud on IPv4:
  // Normalize to /24 subnet (first 3 octets) so mobile hotspot tethered devices and home routers match
  const prefix24 = getIPv4Prefix24(ip);
  const prefix16 = getIPv4Prefix16(ip);
  return {
    ip,
    roomName: `network_v4_${prefix24.replace(/[^a-zA-Z0-9]/g, '_')}`,
    isLocalMode: false,
    subnet24: prefix24,
    subnet16: prefix16,
    isV6: false
  };
}

// Stores info about each connected peer
// socketId -> { id, userId, name, deviceType, ip, defaultNetworkRoom, currentRoom, isCustomRoom }
const peers = new Map();

// Map of roomCode -> {
//   code: string,
//   creatorUserId: string,
//   creatorName: string,
//   hostUserId: string,
//   participants: [ { socketId, userId, name, deviceType, joinedAt, isCreator } ],
//   disconnectTimers: Map<userId, Timeout>
// }
const rooms = new Map();

function getOrCreateRoom(code, user, isExplicitCreator = false) {
  const normCode = code.toLowerCase().trim();
  if (!rooms.has(normCode)) {
    rooms.set(normCode, {
      code: normCode,
      creatorUserId: user.userId,
      creatorName: user.name,
      hostUserId: user.userId,
      participants: [],
      disconnectTimers: new Map()
    });
  } else {
    const room = rooms.get(normCode);
    if (isExplicitCreator && (!room.creatorUserId || room.participants.length === 0)) {
      room.creatorUserId = user.userId;
      room.creatorName = user.name;
    }
  }
  return rooms.get(normCode);
}

function resolveRoomHost(room) {
  if (!room || room.participants.length === 0) return null;

  // RULE: If room-maker is present in the room, he MUST be the host!
  const creator = room.participants.find(p => p.userId === room.creatorUserId);
  let targetHost = null;

  if (creator) {
    targetHost = creator;
  } else {
    // If room-maker is absent, host is current host if still in room, or oldest remaining participant (second-arrived user)
    const existingHost = room.participants.find(p => p.userId === room.hostUserId);
    targetHost = existingHost || room.participants[0];
  }

  const previousHostUserId = room.hostUserId;
  room.hostUserId = targetHost.userId;

  // If host changed, notify room participants
  if (previousHostUserId && previousHostUserId !== targetHost.userId) {
    const isCreatorHost = (targetHost.userId === room.creatorUserId);
    console.log(`Host privilege in "${room.code}" transferred to: ${targetHost.name} (${targetHost.userId}) [isRoomMaker: ${isCreatorHost}]`);
    io.to(`custom_${room.code}`).emit('host-changed', {
      newHostId: targetHost.socketId,
      newHostUserId: targetHost.userId,
      newHostName: targetHost.name,
      isRoomMaker: isCreatorHost
    });
  }

  io.to(`custom_${room.code}`).emit('room-info', {
    code: room.code,
    creatorUserId: room.creatorUserId,
    hostUserId: room.hostUserId,
    hostName: targetHost.name,
    isRoomMakerPresent: !!creator,
    participantsCount: room.participants.length
  });

  return targetHost;
}

function removeParticipantFromRoom(roomCode, userId, socketId) {
  const normCode = (roomCode || '').toLowerCase().trim();
  const room = rooms.get(normCode);
  if (!room) return;

  // Clear any existing disconnect grace-period timer
  if (room.disconnectTimers.has(userId)) {
    clearTimeout(room.disconnectTimers.get(userId));
    room.disconnectTimers.delete(userId);
  }

  // Remove participant from list
  room.participants = room.participants.filter(p => p.userId !== userId && p.socketId !== socketId);

  // If room is empty, close and delete it automatically!
  if (room.participants.length === 0) {
    console.log(`Room "${normCode}" is empty. Automatically closed and deleted.`);
    rooms.delete(normCode);
    return;
  }

  // If participants remain, resolve host (room maker if present, otherwise oldest remaining)
  resolveRoomHost(room);
}

// Multi-tier helper to find matching room for a peer across dual-stack IPv4/IPv6, CGNAT, and hotspots
function findMatchingRoomForPeer(peerData) {
  if (peerData.isCustomRoom) return null;

  for (const [otherId, other] of peers.entries()) {
    if (otherId === peerData.id || other.isCustomRoom) continue;

    // Check 1: STUN Reflexive Public IP Match (Crucial for dual-stack IPv4/IPv6 & Hotspot!)
    // When both devices query STUN, they get the exact same public NAT IP even if one accessed Render via IPv6!
    if (peerData.stunIp && other.stunIp && peerData.stunIp === other.stunIp && peerData.stunIp !== 'unknown') {
      console.log(`[Auto-Match] Found STUN public IP match: ${peerData.name} and ${other.name} on ${peerData.stunIp}`);
      return other.currentRoom;
    }

    // Check 2: Same Local Subnet (e.g. 192.168.43 on Android hotspot or 192.168.1 on home Wi-Fi)
    if (peerData.localSubnet && other.localSubnet && peerData.localSubnet === other.localSubnet) {
      const p16 = peerData.subnet16 || getIPv4Prefix16(peerData.ip);
      const o16 = other.subnet16 || getIPv4Prefix16(other.ip);
      // Android mobile hotspot always assigns 192.168.43.x, or same /16 carrier pool
      if (peerData.localSubnet === '192.168.43' || (p16 && o16 && p16 === o16)) {
        console.log(`[Auto-Match] Found Local Subnet match: ${peerData.name} and ${other.name} on ${peerData.localSubnet}`);
        return other.currentRoom;
      }
    }

    // Check 3: Same Public Subnet (/24 IPv4 or /64 IPv6)
    if (peerData.defaultNetworkRoom && peerData.defaultNetworkRoom === other.defaultNetworkRoom) {
      return other.currentRoom;
    }

    // Check 4: Same /16 Subnet (for mobile carriers where CGNAT varies within /16 pool)
    const p16 = peerData.subnet16 || getIPv4Prefix16(peerData.ip);
    const o16 = other.subnet16 || getIPv4Prefix16(other.ip);
    if (!peerData.isV6 && !other.isV6 && p16 && o16 && p16 === o16 && p16 !== 'unknown') {
      console.log(`[Auto-Match] Matched nearby /16 carrier pool: ${peerData.name} and ${other.name} on ${p16}`);
      return other.currentRoom;
    }
  }

  return null;
}

// Find nearby peers on the same network/carrier to suggest or auto-pair
function findNearbyPeers(peerData) {
  const nearby = [];
  for (const [otherId, other] of peers.entries()) {
    if (otherId === peerData.id) continue;
    if (other.currentRoom === peerData.currentRoom) continue;

    let matchReason = null;
    if (peerData.stunIp && other.stunIp && peerData.stunIp === other.stunIp) {
      matchReason = 'Same Gateway / Router (STUN Verified)';
    } else if (peerData.localSubnet && other.localSubnet && peerData.localSubnet === other.localSubnet) {
      matchReason = `Same Wi-Fi Subnet (${peerData.localSubnet}.x)`;
    } else {
      const p16 = peerData.subnet16 || getIPv4Prefix16(peerData.ip);
      const o16 = other.subnet16 || getIPv4Prefix16(other.ip);
      if (p16 && o16 && p16 === o16) {
        matchReason = 'Same Local Network / Carrier Pool';
      }
    }

    if (matchReason) {
      nearby.push({
        id: other.id,
        name: other.name,
        deviceType: other.deviceType,
        roomCode: other.currentRoom,
        reason: matchReason
      });
    }
  }
  return nearby;
}

io.on('connection', (socket) => {
  const net = getNetworkRoom(socket);
  const rawIp = net.ip;
  const defaultNetworkRoom = net.roomName;

  const query = socket.handshake.query || {};
  const deviceType = query.deviceType || 'desktop';
  const persistentUserId = query.userId || `user_${socket.id.substring(0, 6)}`;
  const savedName = (query.savedName || '').trim();
  const initialName = savedName || `Peer-${socket.id.substring(0, 4)}`;
  const requestedRoomCode = (query.roomCode || '').trim().toLowerCase();

  console.log(`User connected: ${socket.id} (user: ${persistentUserId}) from IP: ${rawIp}, room: ${defaultNetworkRoom}`);

  const peerData = {
    id: socket.id,
    userId: persistentUserId,
    name: initialName,
    deviceType,
    ip: rawIp,
    defaultNetworkRoom,
    currentRoom: defaultNetworkRoom,
    isCustomRoom: false,
    subnet24: net.subnet24,
    subnet16: net.subnet16,
    isV6: net.isV6,
    stunIp: null,
    localSubnet: null,
    localIp: null,
    connectedAt: Date.now()
  };

  peers.set(socket.id, peerData);

  const isExplicitCreator = query.isCreator === 'true';

  let targetRoom = defaultNetworkRoom;
  let isCustom = false;
  let isHost = false;
  let currentHostName = '';

  // If reconnecting with a saved custom room (e.g. browser refresh)
  if (requestedRoomCode) {
    const room = getOrCreateRoom(requestedRoomCode, { userId: persistentUserId, name: initialName }, isExplicitCreator);

    // Cancel refresh grace-period timer if user reconnected before timeout
    if (room.disconnectTimers.has(persistentUserId)) {
      clearTimeout(room.disconnectTimers.get(persistentUserId));
      room.disconnectTimers.delete(persistentUserId);
    }

    const existingIndex = room.participants.findIndex(p => p.userId === persistentUserId);
    if (existingIndex >= 0) {
      room.participants[existingIndex].socketId = socket.id;
      room.participants[existingIndex].name = initialName;
    } else {
      room.participants.push({
        socketId: socket.id,
        userId: persistentUserId,
        name: initialName,
        deviceType,
        joinedAt: Date.now(),
        isCreator: isExplicitCreator || (room.creatorUserId === persistentUserId)
      });
    }

    targetRoom = `custom_${requestedRoomCode}`;
    isCustom = true;
    peerData.currentRoom = targetRoom;
    peerData.isCustomRoom = true;

    // Resolve room host: If room-maker is present, he MUST be the host!
    const activeHost = resolveRoomHost(room);
    isHost = (room.hostUserId === persistentUserId);
    currentHostName = activeHost ? activeHost.name : (isHost ? initialName : 'Host');
  } else {
    // Attempt auto-match with existing active peers on same network
    const matched = findMatchingRoomForPeer(peerData);
    if (matched) {
      targetRoom = matched;
      peerData.currentRoom = matched;
      console.log(`[Initial Connect Match] Placed ${peerData.name} in room "${matched}"`);
    }
  }

  socket.join(targetRoom);

  // Send the user their own assigned profile & current room name
  socket.emit('init-profile', {
    id: socket.id,
    userId: persistentUserId,
    name: initialName,
    currentRoom: targetRoom,
    isCustomRoom: isCustom,
    roomCode: isCustom ? requestedRoomCode : 'Local Network',
    isHost,
    hostName: currentHostName,
    networkIp: rawIp
  });

  // Notify this user of other peers currently in the same network room
  const roomPeers = Array.from(peers.values())
    .filter(p => p.currentRoom === targetRoom && p.id !== socket.id);
  socket.emit('peers-list', roomPeers);

  // Broadcast to other peers in the room that this user joined
  socket.to(targetRoom).emit('peer-joined', peerData);

  // Inform about nearby peers if any exist
  const initialNearby = findNearbyPeers(peerData);
  if (initialNearby.length > 0) {
    socket.emit('nearby-peers-detected', initialNearby);
  }

  // Handle client reporting WebRTC STUN reflexive IP and local subnet fingerprint
  socket.on('sync-network-fingerprint', (data = {}) => {
    peerData.stunIp = data.stunIp ? sanitizeIp(data.stunIp) : null;
    peerData.localSubnet = data.localSubnet || null;
    peerData.localIp = data.localIp || null;

    console.log(`[Fingerprint Sync] Peer ${peerData.name} (${socket.id}) reported: stunIp=${peerData.stunIp}, localSubnet=${peerData.localSubnet}`);

    // If currently in a default network room (not custom room), perform auto-match
    if (!peerData.isCustomRoom) {
      const matchedRoom = findMatchingRoomForPeer(peerData);
      if (matchedRoom && matchedRoom !== peerData.currentRoom) {
        console.log(`[Auto-Match Sync] Migrating ${peerData.name} from "${peerData.currentRoom}" to matched room "${matchedRoom}"`);
        const oldRoom = peerData.currentRoom;
        socket.leave(oldRoom);
        socket.to(oldRoom).emit('peer-left', socket.id);

        peerData.currentRoom = matchedRoom;
        socket.join(matchedRoom);

        socket.emit('room-changed', {
          currentRoom: matchedRoom,
          isCustomRoom: false,
          roomCode: 'Auto-Matched Wi-Fi / Hotspot',
          isHost: false,
          hostName: ''
        });

        const activePeers = Array.from(peers.values()).filter(p => p.currentRoom === matchedRoom && p.id !== socket.id);
        socket.emit('peers-list', activePeers);
        socket.to(matchedRoom).emit('peer-joined', peerData);
        return;
      }
    }

    const nearby = findNearbyPeers(peerData);
    if (nearby.length > 0) {
      socket.emit('nearby-peers-detected', nearby);
    }
  });

  // Instant 1-tap Auto-Pairing for nearby devices or active network peers
  socket.on('auto-pair-request', (callback) => {
    let targetPeer = null;

    // 1. STUN or localSubnet match
    for (const [otherId, other] of peers.entries()) {
      if (otherId === socket.id) continue;
      if (peerData.stunIp && other.stunIp && peerData.stunIp === other.stunIp) {
        targetPeer = other;
        break;
      }
      if (peerData.localSubnet && other.localSubnet && peerData.localSubnet === other.localSubnet) {
        targetPeer = other;
        break;
      }
    }

    // 2. Same /16 carrier pool
    if (!targetPeer) {
      for (const [otherId, other] of peers.entries()) {
        if (otherId === socket.id) continue;
        const p16 = peerData.subnet16 || getIPv4Prefix16(peerData.ip);
        const o16 = other.subnet16 || getIPv4Prefix16(other.ip);
        if (p16 && o16 && p16 === o16) {
          targetPeer = other;
          break;
        }
      }
    }

    // 3. Any active peer on the server
    if (!targetPeer && peers.size > 1) {
      for (const [otherId, other] of peers.entries()) {
        if (otherId !== socket.id) {
          targetPeer = other;
          break;
        }
      }
    }

    if (targetPeer) {
      console.log(`[Auto-Pair Success] Pairing ${peerData.name} with ${targetPeer.name} in room "${targetPeer.currentRoom}"`);
      const targetRoom = targetPeer.currentRoom;
      const oldRoom = peerData.currentRoom;
      socket.leave(oldRoom);
      socket.to(oldRoom).emit('peer-left', socket.id);

      peerData.currentRoom = targetRoom;
      peerData.isCustomRoom = targetPeer.isCustomRoom;
      socket.join(targetRoom);

      socket.emit('room-changed', {
        currentRoom: targetRoom,
        isCustomRoom: targetPeer.isCustomRoom,
        roomCode: targetPeer.isCustomRoom ? targetRoom.replace('custom_', '') : 'Auto-Paired Network',
        isHost: false,
        hostName: ''
      });

      const activePeers = Array.from(peers.values()).filter(p => p.currentRoom === targetRoom && p.id !== socket.id);
      socket.emit('peers-list', activePeers);
      socket.to(targetRoom).emit('peer-joined', peerData);

      if (callback) callback({ success: true, pairedWith: targetPeer.name });
    } else {
      if (callback) callback({ success: false, error: 'No other active devices found right now. Open Transferase on your phone/other device to pair!' });
    }
  });

  // Join a custom room code
  socket.on('join-room', ({ roomCode, isCreator: explicitCreatorFlag }, callback) => {
    const trimmed = (roomCode || '').trim().toLowerCase();
    const oldRoom = peerData.currentRoom;

    // Leave old room
    socket.leave(oldRoom);
    socket.to(oldRoom).emit('peer-left', socket.id);

    // If leaving custom room, remove from participant tracking
    if (peerData.isCustomRoom) {
      const oldCode = oldRoom.replace('custom_', '');
      removeParticipantFromRoom(oldCode, peerData.userId, socket.id);
    }

    // If empty room code, reset to local network room
    if (!trimmed) {
      peerData.currentRoom = defaultNetworkRoom;
      peerData.isCustomRoom = false;
      socket.join(defaultNetworkRoom);

      const defPeers = Array.from(peers.values())
        .filter(p => p.currentRoom === defaultNetworkRoom && p.id !== socket.id);

      socket.emit('room-changed', {
        currentRoom: defaultNetworkRoom,
        isCustomRoom: false,
        roomCode: 'Local Network',
        isHost: false,
        hostName: ''
      });
      socket.emit('peers-list', defPeers);
      socket.to(defaultNetworkRoom).emit('peer-joined', peerData);

      if (callback) callback({ success: true, room: defaultNetworkRoom, isHost: false });
      return;
    }

    const isCreatorJoin = !!explicitCreatorFlag;
    const room = getOrCreateRoom(trimmed, { userId: peerData.userId, name: peerData.name }, isCreatorJoin);

    if (room.disconnectTimers.has(peerData.userId)) {
      clearTimeout(room.disconnectTimers.get(peerData.userId));
      room.disconnectTimers.delete(peerData.userId);
    }

    const existingIndex = room.participants.findIndex(p => p.userId === peerData.userId);
    if (existingIndex >= 0) {
      room.participants[existingIndex].socketId = socket.id;
      room.participants[existingIndex].name = peerData.name;
    } else {
      room.participants.push({
        socketId: socket.id,
        userId: peerData.userId,
        name: peerData.name,
        deviceType: peerData.deviceType,
        joinedAt: Date.now(),
        isCreator: isCreatorJoin || (room.creatorUserId === peerData.userId)
      });
    }

    const newRoomName = `custom_${trimmed}`;
    peerData.currentRoom = newRoomName;
    peerData.isCustomRoom = true;
    socket.join(newRoomName);

    // Resolve room host: If room-maker is present, he MUST be the host!
    const activeHost = resolveRoomHost(room);
    const isHostNow = (room.hostUserId === peerData.userId);
    const hostName = activeHost ? activeHost.name : (isHostNow ? peerData.name : 'Host');

    const newRoomPeers = Array.from(peers.values())
      .filter(p => p.currentRoom === newRoomName && p.id !== socket.id);

    socket.emit('room-changed', {
      currentRoom: newRoomName,
      isCustomRoom: true,
      roomCode: trimmed,
      isHost: isHostNow,
      hostName
    });

    socket.emit('peers-list', newRoomPeers);
    socket.to(newRoomName).emit('peer-joined', peerData);

    if (callback) callback({ success: true, room: newRoomName, isHost: isHostNow });
  });

  // Leave room button: leaves custom room and returns to default network room
  socket.on('leave-room', (callback) => {
    if (!peerData.isCustomRoom) {
      if (callback) callback({ success: true });
      return;
    }

    const oldRoom = peerData.currentRoom;
    const oldCode = oldRoom.replace('custom_', '');

    socket.leave(oldRoom);
    socket.to(oldRoom).emit('peer-left', socket.id);
    removeParticipantFromRoom(oldCode, peerData.userId, socket.id);

    peerData.currentRoom = defaultNetworkRoom;
    peerData.isCustomRoom = false;
    socket.join(defaultNetworkRoom);

    const defPeers = Array.from(peers.values())
      .filter(p => p.currentRoom === defaultNetworkRoom && p.id !== socket.id);

    socket.emit('room-changed', {
      currentRoom: defaultNetworkRoom,
      isCustomRoom: false,
      roomCode: 'Local Network',
      isHost: false,
      hostName: ''
    });

    socket.emit('peers-list', defPeers);
    socket.to(defaultNetworkRoom).emit('peer-joined', peerData);

    if (callback) callback({ success: true });
  });

  // Close room privilege: creator/host closes room for all participants
  socket.on('close-room', (callback) => {
    if (!peerData.isCustomRoom) {
      if (callback) callback({ success: false, error: 'Not in a custom room' });
      return;
    }

    const roomName = peerData.currentRoom;
    const roomCode = roomName.replace('custom_', '');
    const room = rooms.get(roomCode);

    if (!room) {
      if (callback) callback({ success: false, error: 'Room not found' });
      return;
    }

    if (room.hostUserId !== peerData.userId) {
      if (callback) callback({ success: false, error: 'Only the room creator / host can close the room' });
      return;
    }

    console.log(`Room "${roomCode}" closed by host: ${peerData.name}`);

    // Notify all participants in this room that the room was closed by host
    io.to(roomName).emit('room-closed', {
      reason: `Room "${roomCode}" was closed by the host (${peerData.name})`
    });

    // Move all participants back to their default local network rooms
    const roomSockets = io.sockets.adapter.rooms.get(roomName);
    if (roomSockets) {
      for (const sid of Array.from(roomSockets)) {
        const s = io.sockets.sockets.get(sid);
        const p = peers.get(sid);
        if (s && p) {
          s.leave(roomName);
          p.currentRoom = p.defaultNetworkRoom;
          p.isCustomRoom = false;
          s.join(p.defaultNetworkRoom);

          s.emit('room-changed', {
            currentRoom: p.defaultNetworkRoom,
            isCustomRoom: false,
            roomCode: 'Local Network',
            isHost: false,
            hostName: ''
          });

          const defPeers = Array.from(peers.values())
            .filter(x => x.currentRoom === p.defaultNetworkRoom && x.id !== sid);
          s.emit('peers-list', defPeers);
        }
      }
    }

    // Delete room from memory
    rooms.delete(roomCode);
    if (callback) callback({ success: true });
  });

  // WebRTC / Direct Signaling relay
  socket.on('signal', (data) => {
    const { to, signalData } = data;
    io.to(to).emit('signal', {
      from: socket.id,
      signalData
    });
  });

  // Fast socket file transfer fallback
  socket.on('file-transfer', (data) => {
    const sender = peers.get(socket.id);
    io.to(data.to).emit('file-transfer', {
      from: socket.id,
      senderName: sender ? sender.name : 'Unknown',
      file: data.file
    });
  });

  socket.on('broadcast-file', (file) => {
    const sender = peers.get(socket.id);
    if (!sender) return;
    socket.to(sender.currentRoom).emit('file-transfer', {
      from: socket.id,
      senderName: sender.name,
      file: file
    });
  });

  socket.on('rename-device', (newName, callback) => {
    const cleanName = (newName || '').trim();
    if (!cleanName) return;

    // Check if name is taken in current room
    const currentRoom = peerData.currentRoom;
    const nameExists = Array.from(peers.values()).some(
      p => p.currentRoom === currentRoom && p.name.toLowerCase() === cleanName.toLowerCase() && p.id !== socket.id
    );

    if (nameExists) {
      if (callback) callback({ success: false, error: 'Name already taken in this room/network' });
    } else {
      peerData.name = cleanName;
      peers.set(socket.id, peerData);
      io.to(currentRoom).emit('peer-renamed', { id: socket.id, name: cleanName });

      // If in custom room, update participant name and room info
      if (peerData.isCustomRoom) {
        const roomCode = currentRoom.replace('custom_', '');
        const room = rooms.get(roomCode);
        if (room) {
          const participant = room.participants.find(p => p.userId === peerData.userId);
          if (participant) participant.name = cleanName;
          const hostUser = room.participants.find(p => p.userId === room.hostUserId);
          io.to(currentRoom).emit('room-info', {
            code: room.code,
            hostUserId: room.hostUserId,
            hostName: hostUser ? hostUser.name : cleanName,
            participantsCount: room.participants.length
          });
        }
      }

      if (callback) callback({ success: true, name: cleanName });
    }
  });

  socket.on('disconnect', () => {
    console.log('User disconnected:', socket.id);
    const roomName = peerData.currentRoom;
    peers.delete(socket.id);

    if (!peerData.isCustomRoom) {
      io.to(roomName).emit('peer-left', socket.id);
      return;
    }

    const roomCode = roomName.replace('custom_', '');
    const room = rooms.get(roomCode);
    if (!room) {
      io.to(roomName).emit('peer-left', socket.id);
      return;
    }

    io.to(roomName).emit('peer-left', socket.id);

    // 4-second grace period: If user refreshed the browser, they will reconnect with the same userId before this timer fires!
    const timerId = setTimeout(() => {
      console.log(`Grace period expired for user ${peerData.userId} in room "${roomCode}"`);
      removeParticipantFromRoom(roomCode, peerData.userId, socket.id);
    }, 4000);

    room.disconnectTimers.set(peerData.userId, timerId);
  });
});

app.get('/api/status', (req, res) => {
  res.json({
    app: 'Transferase Relay',
    status: 'online',
    version: '1.0.0',
    peersCount: peers.size,
    roomsCount: rooms.size
  });
});

app.get('/api/nearby-peers', (req, res) => {
  const net = getNetworkRoom({
    handshake: {
      headers: req.headers,
      address: req.socket.remoteAddress
    },
    conn: { remoteAddress: req.socket.remoteAddress }
  });

  const dummyPeer = {
    id: 'query',
    ip: net.ip,
    subnet24: net.subnet24,
    subnet16: net.subnet16,
    isV6: net.isV6,
    currentRoom: ''
  };

  const nearby = findNearbyPeers(dummyPeer);
  res.json({
    clientIp: net.ip,
    nearby
  });
});

app.get('/api/debug-network', (req, res) => {
  const headers = req.headers;
  const net = getNetworkRoom({
    handshake: {
      headers: req.headers,
      address: req.socket.remoteAddress
    },
    conn: { remoteAddress: req.socket.remoteAddress }
  });
  res.json({
    clientIp: net.ip,
    roomName: net.roomName,
    subnet24: net.subnet24,
    subnet16: net.subnet16,
    headers: {
      cfConnectingIp: headers['cf-connecting-ip'],
      xRealIp: headers['x-real-ip'],
      xForwardedFor: headers['x-forwarded-for'],
      remoteAddress: req.socket.remoteAddress
    },
    connectedPeers: Array.from(peers.values()).map(p => ({
      id: p.id,
      name: p.name,
      deviceType: p.deviceType,
      ip: p.ip,
      stunIp: p.stunIp,
      localSubnet: p.localSubnet,
      localIp: p.localIp,
      currentRoom: p.currentRoom,
      defaultNetworkRoom: p.defaultNetworkRoom
    }))
  });
});

// Fallback to React index.html for any GET request (Express 5 safe regex)
app.get(/^.*$/, (req, res) => {
  res.sendFile(path.join(distPath, 'index.html'), (err) => {
    if (err) {
      res.status(200).send('Web Transfer Relay Server is Running. Frontend not yet built into client/dist.');
    }
  });
});

const PORT = process.env.PORT || 4000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Relay & Signaling server running on port ${PORT}`);
});
