const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');

const app = express();
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

// Helper to determine client IP address and network group
function getNetworkRoom(socket) {
  const forwarded = socket.handshake.headers['x-forwarded-for'];
  let ip = forwarded ? forwarded.split(',')[0].trim() : (socket.handshake.address || socket.conn.remoteAddress || 'unknown');

  // Clean IPv6 mapped IPv4 prefix (::ffff:192.168.x.x)
  if (ip.startsWith('::ffff:')) {
    ip = ip.substring(7);
  }

  // Detect if connection is local / LAN
  const isLoopback = ip === '127.0.0.1' || ip === '::1' || ip === 'localhost';
  const isPrivateLan = /^192\.168\./.test(ip) || /^10\./.test(ip) || /^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(ip);

  // If running locally on home LAN / localhost, place all LAN and localhost devices in the same local room!
  if (isLoopback || isPrivateLan) {
    return {
      ip,
      roomName: 'local_lan_network',
      isLocalMode: true
    };
  }

  // If running in cloud (Render/Heroku), group by external public IP so devices on same router match
  return {
    ip,
    roomName: `network_${ip.replace(/[^a-zA-Z0-9]/g, '_')}`,
    isLocalMode: false
  };
}

// Stores info about each connected peer
// socketId -> { id, userId, name, deviceType, ip, defaultNetworkRoom, currentRoom, isCustomRoom }
const peers = new Map();

// Map of roomCode -> {
//   code: string,
//   hostUserId: string,
//   participants: [ { socketId, userId, name, deviceType, joinedAt } ],
//   disconnectTimers: Map<userId, Timeout>
// }
const rooms = new Map();

function getOrCreateRoom(code, creator) {
  const normCode = code.toLowerCase().trim();
  if (!rooms.has(normCode)) {
    rooms.set(normCode, {
      code: normCode,
      hostUserId: creator.userId,
      participants: [],
      disconnectTimers: new Map()
    });
  }
  return rooms.get(normCode);
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

  // If the departing participant was the host, transfer privilege to the second arrived user (oldest remaining)
  if (room.hostUserId === userId) {
    const nextHost = room.participants[0];
    room.hostUserId = nextHost.userId;
    console.log(`Host privilege in "${normCode}" transferred to next participant: ${nextHost.name} (${nextHost.userId})`);
    
    io.to(`custom_${normCode}`).emit('host-changed', {
      newHostId: nextHost.socketId,
      newHostUserId: nextHost.userId,
      newHostName: nextHost.name
    });
  }

  const currentHost = room.participants.find(p => p.userId === room.hostUserId);
  io.to(`custom_${normCode}`).emit('room-info', {
    code: normCode,
    hostUserId: room.hostUserId,
    hostName: currentHost ? currentHost.name : 'Unknown',
    participantsCount: room.participants.length
  });
}

io.on('connection', (socket) => {
  const { ip: rawIp, roomName: defaultNetworkRoom } = getNetworkRoom(socket);

  const query = socket.handshake.query || {};
  const deviceType = query.deviceType || 'desktop';
  const persistentUserId = query.userId || `user_${socket.id.substring(0, 6)}`;
  const savedName = (query.savedName || '').trim();
  const initialName = savedName || `Peer-${socket.id.substring(0, 4)}`;
  const requestedRoomCode = (query.roomCode || '').trim().toLowerCase();

  console.log(`User connected: ${socket.id} (user: ${persistentUserId}) from IP: ${rawIp}`);

  const peerData = {
    id: socket.id,
    userId: persistentUserId,
    name: initialName,
    deviceType,
    ip: rawIp,
    defaultNetworkRoom,
    currentRoom: defaultNetworkRoom,
    isCustomRoom: false
  };

  peers.set(socket.id, peerData);

  let targetRoom = defaultNetworkRoom;
  let isCustom = false;
  let isHost = false;
  let currentHostName = '';

  // If reconnecting with a saved custom room (e.g. browser refresh)
  if (requestedRoomCode) {
    const room = getOrCreateRoom(requestedRoomCode, { userId: persistentUserId, name: initialName });

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
        joinedAt: Date.now()
      });
    }

    targetRoom = `custom_${requestedRoomCode}`;
    isCustom = true;
    peerData.currentRoom = targetRoom;
    peerData.isCustomRoom = true;
    isHost = (room.hostUserId === persistentUserId);
    const hostUser = room.participants.find(p => p.userId === room.hostUserId);
    currentHostName = hostUser ? hostUser.name : (isHost ? initialName : 'Host');
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

  if (isCustom) {
    const room = rooms.get(requestedRoomCode);
    const actualHost = room ? room.participants.find(p => p.userId === room.hostUserId) : null;
    io.to(targetRoom).emit('room-info', {
      code: requestedRoomCode,
      hostUserId: room ? room.hostUserId : persistentUserId,
      hostName: actualHost ? actualHost.name : currentHostName,
      participantsCount: room ? room.participants.length : 1
    });
  }

  // Join a custom room code
  socket.on('join-room', ({ roomCode }, callback) => {
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

    const room = getOrCreateRoom(trimmed, { userId: peerData.userId, name: peerData.name });

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
        joinedAt: Date.now()
      });
    }

    const newRoomName = `custom_${trimmed}`;
    peerData.currentRoom = newRoomName;
    peerData.isCustomRoom = true;
    socket.join(newRoomName);

    const isHostNow = (room.hostUserId === peerData.userId);
    const hostUser = room.participants.find(p => p.userId === room.hostUserId);
    const hostName = hostUser ? hostUser.name : (isHostNow ? peerData.name : 'Host');

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

    io.to(newRoomName).emit('room-info', {
      code: trimmed,
      hostUserId: room.hostUserId,
      hostName,
      participantsCount: room.participants.length
    });

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
