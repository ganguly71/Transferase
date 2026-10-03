const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const app = express();
app.use(cors());

const server = http.createServer(app);
const io = new Server(server, {
  maxHttpBufferSize: 1e8, // Allow up to 100MB payloads
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

const peers = new Map();

io.on('connection', (socket) => {
  console.log('A user connected:', socket.id);

  // Inform the new user about existing peers
  const existingPeers = Array.from(peers.entries()).map(([id, data]) => ({ id, ...data }));
  socket.emit('peers-list', existingPeers);

  // Broadcast to others that a new peer joined
  const peerData = { 
    name: `Peer-${socket.id.substring(0, 4)}`,
    deviceType: socket.handshake.query.deviceType || 'desktop'
  };
  peers.set(socket.id, peerData);
  socket.broadcast.emit('peer-joined', { id: socket.id, ...peerData });

  socket.on('signal', (data) => {
    // Relay signaling data (offer, answer, candidate) to the target peer
    const { to, signalData } = data;
    io.to(to).emit('signal', {
      from: socket.id,
      signalData
    });
  });

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
    socket.broadcast.emit('file-transfer', {
      from: socket.id,
      senderName: sender ? sender.name : 'Unknown',
      file: file
    });
  });

  socket.on('rename-device', (newName, callback) => {
    const nameExists = Array.from(peers.values()).some(p => p.name.toLowerCase() === newName.toLowerCase());
    if (nameExists) {
      if (callback) callback({ success: false, error: 'Name already in use on the network' });
    } else {
      const peerData = peers.get(socket.id);
      if (peerData) {
        peerData.name = newName;
        peers.set(socket.id, peerData);
        io.emit('peer-renamed', { id: socket.id, name: newName });
        if (callback) callback({ success: true, name: newName });
      }
    }
  });

  socket.on('disconnect', () => {
    console.log('User disconnected:', socket.id);
    peers.delete(socket.id);
    io.emit('peer-left', socket.id);
  });
});

const PORT = process.env.PORT || 4000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Signaling server running on port ${PORT}`);
});
