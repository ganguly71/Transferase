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
// socketId -> { id, name, deviceType, ip, room, customRoom }
const peers = new Map();

io.on('connection', (socket) => {
  const { ip: rawIp, roomName: defaultNetworkRoom, isLocalMode } = getNetworkRoom(socket);

  console.log(`User connected: ${socket.id} from IP: ${rawIp} (Room: ${defaultNetworkRoom})`);

  // Device type passed via handshake or defaults to desktop
  const deviceType = socket.handshake.query.deviceType || 'desktop';
  const initialName = `Peer-${socket.id.substring(0, 4)}`;

  const peerData = {
    id: socket.id,
    name: initialName,
    deviceType,
    ip: rawIp,
    currentRoom: defaultNetworkRoom,
    isCustomRoom: false
  };

  peers.set(socket.id, peerData);
  socket.join(defaultNetworkRoom);

  // Send the user their own assigned profile & current room name
  socket.emit('init-profile', {
    id: socket.id,
    name: initialName,
    currentRoom: defaultNetworkRoom,
    isCustomRoom: false,
    networkIp: rawIp
  });

  // Notify this user of other peers currently in the same network room
  const roomPeers = Array.from(peers.values())
    .filter(p => p.currentRoom === defaultNetworkRoom && p.id !== socket.id);
  socket.emit('peers-list', roomPeers);

  // Broadcast to other peers in the room that this user joined
  socket.to(defaultNetworkRoom).emit('peer-joined', peerData);

  // Join a custom room code (for connecting across cellular data or different networks)
  socket.on('join-room', ({ roomCode }, callback) => {
    const trimmed = (roomCode || '').trim().toLowerCase();
    const oldRoom = peerData.currentRoom;

    // Leave old room
    socket.leave(oldRoom);
    socket.to(oldRoom).emit('peer-left', socket.id);

    // If empty room code, reset to local network room
    if (!trimmed) {
      peerData.currentRoom = defaultNetworkRoom;
      peerData.isCustomRoom = false;
    } else {
      peerData.currentRoom = `custom_${trimmed}`;
      peerData.isCustomRoom = true;
    }

    socket.join(peerData.currentRoom);

    // Notify room peers
    const newRoomPeers = Array.from(peers.values())
      .filter(p => p.currentRoom === peerData.currentRoom && p.id !== socket.id);

    socket.emit('room-changed', {
      currentRoom: peerData.currentRoom,
      isCustomRoom: peerData.isCustomRoom,
      roomCode: trimmed || 'Local Network'
    });

    socket.emit('peers-list', newRoomPeers);
    socket.to(peerData.currentRoom).emit('peer-joined', peerData);

    if (callback) callback({ success: true, room: peerData.currentRoom });
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

    // Check if name is taken in the current room
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
      if (callback) callback({ success: true, name: cleanName });
    }
  });

  socket.on('disconnect', () => {
    console.log('User disconnected:', socket.id);
    const room = peerData.currentRoom;
    peers.delete(socket.id);
    io.to(room).emit('peer-left', socket.id);
  });
});

// Fallback to React index.html for any GET request
app.get('*', (req, res) => {
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
