import { useState, useEffect, useRef } from 'react';
import { io } from 'socket.io-client';
import { 
  Share2, 
  Monitor, 
  Smartphone, 
  File, 
  Download, 
  Upload, 
  Wifi, 
  Key, 
  CheckCircle, 
  AlertCircle,
  Copy,
  Layers,
  ArrowRight
} from 'lucide-react';
import './App.css';

// Determine the server URL dynamically:
// In development, port 4000. In production / online deployment, uses current origin (relayed through express)
const SERVER_URL = import.meta.env.VITE_SERVER_URL || (
  window.location.port === '5173' 
    ? `http://${window.location.hostname}:4000` 
    : window.location.origin
);

const getDeviceType = () => {
  const ua = navigator.userAgent;
  if (/(tablet|ipad|playbook|silk)|(android(?!.*mobi))/i.test(ua)) return 'tablet';
  if (/Mobile|Android|iP(hone|od)|IEMobile|BlackBerry|Kindle|Silk-Accelerated|(hpw|web)OS|Opera M(obi|ini)/i.test(ua)) return 'mobile';
  return 'desktop';
};

function App() {
  const [socket, setSocket] = useState(null);
  const [connected, setConnected] = useState(false);
  const [me, setMe] = useState('');
  const [myName, setMyName] = useState('');
  const [networkInfo, setNetworkInfo] = useState({ room: '', isCustom: false, ip: '' });
  const [roomCodeInput, setRoomCodeInput] = useState('');
  const [isEditingName, setIsEditingName] = useState(false);
  const [editNameValue, setEditNameValue] = useState('');
  const [peers, setPeers] = useState([]);
  const [receivedFiles, setReceivedFiles] = useState([]);
  const [transferProgress, setTransferProgress] = useState(null); // { fileName, percent, status }
  const [notification, setNotification] = useState(null);

  const incomingFiles = useRef({});

  const showToast = (message, type = 'info') => {
    setNotification({ message, type });
    setTimeout(() => {
      setNotification(null);
    }, 4000);
  };

  useEffect(() => {
    const newSocket = io(SERVER_URL, { 
      maxHttpBufferSize: 1e8, // allow up to 100MB
      query: { deviceType: getDeviceType() }
    });
    setSocket(newSocket);

    newSocket.on('connect', () => {
      setConnected(true);
      setMe(newSocket.id);
      showToast('Connected to Relay Service', 'success');
    });

    newSocket.on('disconnect', () => {
      setConnected(false);
      showToast('Disconnected from Relay Service', 'error');
    });

    newSocket.on('init-profile', (data) => {
      setMe(data.id);
      setMyName(data.name);
      setNetworkInfo({
        room: data.currentRoom,
        isCustom: data.isCustomRoom,
        ip: data.networkIp
      });
    });

    newSocket.on('room-changed', (data) => {
      setNetworkInfo(prev => ({
        ...prev,
        room: data.currentRoom,
        isCustom: data.isCustomRoom
      }));
      showToast(`Switched to room: ${data.roomCode}`, 'success');
    });

    newSocket.on('peers-list', (existingPeers) => {
      setPeers(existingPeers.filter(p => p.id !== newSocket.id));
    });

    newSocket.on('peer-joined', (peer) => {
      setPeers(prev => {
        const without = prev.filter(p => p.id !== peer.id);
        return [...without, peer];
      });
      showToast(`${peer.name} joined the room`, 'info');
    });

    newSocket.on('peer-left', (peerId) => {
      setPeers(prev => prev.filter(p => p.id !== peerId));
    });

    newSocket.on('peer-renamed', ({ id, name }) => {
      setPeers(prev => prev.map(p => p.id === id ? { ...p, name } : p));
    });

    // Handle incoming file data directly via Socket.io relay
    newSocket.on('file-transfer', ({ from, senderName, file }) => {
      if (file.type === 'meta') {
        incomingFiles.current[file.fileId] = {
          name: file.name,
          fileType: file.fileType,
          size: file.size,
          totalChunks: file.totalChunks,
          senderName: senderName || 'Unknown',
          chunks: []
        };
        setTransferProgress({
          fileName: file.name,
          percent: 5,
          status: `Receiving from ${senderName || 'Peer'}...`
        });
      } else if (file.type === 'chunk') {
        const fileTransfer = incomingFiles.current[file.fileId];
        if (fileTransfer) {
          fileTransfer.chunks[file.chunkIndex] = file.data;
          const receivedCount = fileTransfer.chunks.filter(Boolean).length;
          const pct = Math.round((receivedCount / fileTransfer.totalChunks) * 100);

          setTransferProgress({
            fileName: fileTransfer.name,
            percent: pct,
            status: `Receiving (${pct}%)...`
          });
          
          if (receivedCount === fileTransfer.totalChunks) {
            const fullData = fileTransfer.chunks.join('');
            setReceivedFiles(prev => [{
              name: fileTransfer.name,
              type: fileTransfer.fileType,
              size: fileTransfer.size,
              senderName: fileTransfer.senderName,
              data: fullData,
              time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
            }, ...prev]);

            setTransferProgress(null);
            showToast(`Received ${fileTransfer.name}!`, 'success');
            delete incomingFiles.current[file.fileId];
          }
        }
      }
    });

    return () => newSocket.disconnect();
  }, []);

  const handleRename = () => {
    if (!editNameValue.trim() || editNameValue === myName) {
      setIsEditingName(false);
      return;
    }
    socket.emit('rename-device', editNameValue.trim(), (response) => {
      if (response.success) {
        setMyName(response.name);
        setIsEditingName(false);
        showToast(`Renamed to ${response.name}`, 'success');
      } else {
        alert(response.error);
      }
    });
  };

  const handleCreateRoom = () => {
    if (!socket) return;
    const code = Math.floor(100000 + Math.random() * 900000).toString();
    socket.emit('join-room', { roomCode: code }, (res) => {
      if (res.success) {
        showToast(`Created secure room: ${code}`, 'success');
      }
    });
  };

  const handleJoinCustomRoom = (e) => {
    e.preventDefault();
    if (!socket) return;
    socket.emit('join-room', { roomCode: roomCodeInput }, (res) => {
      if (res.success) {
        setRoomCodeInput('');
      }
    });
  };

  const handleResetToLocalNetwork = () => {
    if (!socket) return;
    socket.emit('join-room', { roomCode: '' }, (res) => {
      if (res.success) {
        showToast('Returned to auto-detected local network', 'info');
      }
    });
  };

  const sendFile = (peerId, file) => {
    if (!socket) return;
    
    const CHUNK_SIZE = 500000; // 500KB chunks
    const reader = new FileReader();
    
    setTransferProgress({
      fileName: file.name,
      percent: 0,
      status: 'Preparing to send...'
    });

    reader.onload = (e) => {
      const fileBase64 = e.target.result;
      const totalChunks = Math.ceil(fileBase64.length / CHUNK_SIZE);
      const fileId = Math.random().toString(36).substring(2, 9);
      
      socket.emit('file-transfer', {
        to: peerId,
        file: {
          type: 'meta',
          fileId,
          name: file.name,
          fileType: file.type,
          size: file.size,
          totalChunks
        }
      });

      let offset = 0;
      let chunkIndex = 0;
      
      const sendNextChunk = () => {
        if (offset < fileBase64.length) {
          const chunk = fileBase64.slice(offset, offset + CHUNK_SIZE);
          socket.emit('file-transfer', {
            to: peerId,
            file: {
              type: 'chunk',
              fileId,
              chunkIndex,
              data: chunk
            }
          });
          offset += CHUNK_SIZE;
          chunkIndex++;
          const pct = Math.round((chunkIndex / totalChunks) * 100);
          setTransferProgress({
            fileName: file.name,
            percent: pct,
            status: `Sending to peer (${pct}%)...`
          });
          setTimeout(sendNextChunk, 15);
        } else {
          setTransferProgress(null);
          showToast(`Successfully sent ${file.name}`, 'success');
        }
      };
      
      sendNextChunk();
    };
    reader.readAsDataURL(file);
  };

  const broadcastFile = (file) => {
    if (!socket || peers.length === 0) return;
    
    const CHUNK_SIZE = 500000;
    const reader = new FileReader();
    
    setTransferProgress({
      fileName: file.name,
      percent: 0,
      status: 'Broadcasting...'
    });

    reader.onload = (e) => {
      const fileBase64 = e.target.result;
      const totalChunks = Math.ceil(fileBase64.length / CHUNK_SIZE);
      const fileId = Math.random().toString(36).substring(2, 9);
      
      socket.emit('broadcast-file', {
        type: 'meta',
        fileId,
        name: file.name,
        fileType: file.type,
        size: file.size,
        totalChunks
      });

      let offset = 0;
      let chunkIndex = 0;
      
      const sendNextChunk = () => {
        if (offset < fileBase64.length) {
          const chunk = fileBase64.slice(offset, offset + CHUNK_SIZE);
          socket.emit('broadcast-file', {
            type: 'chunk',
            fileId,
            chunkIndex,
            data: chunk
          });
          offset += CHUNK_SIZE;
          chunkIndex++;
          const pct = Math.round((chunkIndex / totalChunks) * 100);
          setTransferProgress({
            fileName: file.name,
            percent: pct,
            status: `Broadcasting (${pct}%)...`
          });
          setTimeout(sendNextChunk, 15);
        } else {
          setTransferProgress(null);
          showToast(`Broadcasted ${file.name} to all devices`, 'success');
        }
      };
      
      sendNextChunk();
    };
    reader.readAsDataURL(file);
  };

  return (
    <div className="app-container">
      {/* Toast Notification */}
      {notification && (
        <div className={`toast-notification ${notification.type}`}>
          {notification.type === 'success' && <CheckCircle size={18} />}
          {notification.type === 'error' && <AlertCircle size={18} />}
          {notification.type === 'info' && <Share2 size={18} />}
          <span>{notification.message}</span>
        </div>
      )}

      {/* Header */}
      <div className="header glass-panel">
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <div className="logo-badge">
            <Share2 className="header-icon" size={28} />
          </div>
          <div>
            <h1>Tranferase Online</h1>
            <p className="subtitle">Instant peer-to-peer file transfer anywhere without setup</p>
          </div>
        </div>
        <div className="status-indicator">
          <span className={`status-dot ${connected ? 'online' : 'offline'}`}></span>
          <span>{connected ? 'Relay Active' : 'Connecting...'}</span>
        </div>
      </div>

      {/* Network & Room Switcher Bar */}
      <div className="network-banner glass-panel">
        <div className="network-info-left">
          <div className="network-chip">
            {networkInfo.isCustom ? <Key size={16} /> : <Wifi size={16} />}
            <span>
              {networkInfo.isCustom 
                ? `Custom Room: ${networkInfo.room.replace('custom_', '')}` 
                : 'Auto-Matched Local Wi-Fi'}
            </span>
          </div>
          <span className="network-detail">
            {networkInfo.isCustom 
              ? 'Devices with the same room code can exchange files directly.' 
              : 'Devices on your same Wi-Fi router appear automatically.'}
          </span>
        </div>

        <div className="room-controls-wrapper">
          {!networkInfo.isCustom && (
            <button type="button" onClick={handleCreateRoom} className="room-btn create-btn">
              <Key size={14} /> Create Room
            </button>
          )}
          <form onSubmit={handleJoinCustomRoom} className="room-form">
            <input
              type="text"
              placeholder="6-digit code"
              value={roomCodeInput}
              onChange={(e) => setRoomCodeInput(e.target.value)}
              className="room-input"
              maxLength={6}
            />
            <button type="submit" className="room-btn join-btn">
              Join <ArrowRight size={14} />
            </button>
            {networkInfo.isCustom && (
              <button 
                type="button" 
                onClick={handleResetToLocalNetwork} 
                className="room-btn secondary"
                title="Return to auto-detected local network"
              >
                Reset to Wi-Fi
              </button>
            )}
          </form>
        </div>
      </div>

      {/* Active Transfer Progress */}
      {transferProgress && (
        <div className="progress-banner glass-panel">
          <div className="progress-header">
            <span className="file-transferring-name">
              <Upload size={16} className="spin-slow" /> {transferProgress.fileName}
            </span>
            <span className="transfer-percentage">{transferProgress.percent}%</span>
          </div>
          <div className="progress-bar-track">
            <div 
              className="progress-bar-fill" 
              style={{ width: `${transferProgress.percent}%` }}
            ></div>
          </div>
          <span className="progress-status-text">{transferProgress.status}</span>
        </div>
      )}

      {/* Your Device Card & Network Peer List */}
      <div className="glass-panel main-device-area">
        <div className="my-info">
          <div>
            <div className="label-text">YOUR DEVICE</div>
            {isEditingName ? (
              <div className="rename-input-wrap">
                <input 
                  type="text" 
                  value={editNameValue} 
                  onChange={(e) => setEditNameValue(e.target.value)} 
                  onKeyDown={(e) => e.key === 'Enter' && handleRename()}
                  autoFocus
                  className="rename-input"
                />
                <button onClick={handleRename} className="action-btn small-btn">Save</button>
                <button onClick={() => setIsEditingName(false)} className="action-btn small-btn secondary">Cancel</button>
              </div>
            ) : (
              <div className="device-name-row">
                <h2 className="my-device-name">{myName || `Peer-${me?.substring(0, 4)}`}</h2>
                <button 
                  className="rename-link"
                  onClick={() => { setEditNameValue(myName); setIsEditingName(true); }}
                >
                  Rename
                </button>
              </div>
            )}
            <p className="helper-text">
              Visible as <strong>{getDeviceType()}</strong> to devices in this network
            </p>
          </div>

          {peers.length > 0 && (
            <label className="action-btn broadcast-btn">
              <Upload size={18} />
              Broadcast to All ({peers.length})
              <input 
                type="file" 
                className="file-input" 
                onChange={(e) => {
                  if (e.target.files && e.target.files[0]) {
                    broadcastFile(e.target.files[0]);
                  }
                }}
              />
            </label>
          )}
        </div>

        <div className="section-title-wrap">
          <div>
            <h3>Connected Peers</h3>
            <p className="subtitle">
              {networkInfo.isCustom 
                ? 'Other phones and laptops joined with the same room code' 
                : 'Other devices on your Wi-Fi will pop up here instantly'}
            </p>
          </div>
          <div className="peer-counter">
            {peers.length} {peers.length === 1 ? 'device' : 'devices'} ready
          </div>
        </div>

        {peers.length === 0 ? (
          <div className="no-peers glass-panel">
            <div className="pulse-circle">
              <Smartphone size={36} />
            </div>
            <h4>Waiting for nearby devices...</h4>
            <p style={{ maxWidth: '440px', margin: '0 auto', lineHeight: '1.5' }}>
              Open this website on your mobile phone or another computer. 
              {networkInfo.isCustom 
                ? ` Enter room code "${networkInfo.room.replace('custom_', '')}" to connect.` 
                : ' If both devices are on the same Wi-Fi, they will discover each other automatically.'}
            </p>
          </div>
        ) : (
          <div className="peers-grid">
            {peers.map(peer => (
              <div 
                key={peer.id} 
                className="peer-card glass-panel"
                onDragOver={(e) => {
                  e.preventDefault();
                  e.currentTarget.classList.add('drag-active');
                }}
                onDragLeave={(e) => {
                  e.preventDefault();
                  e.currentTarget.classList.remove('drag-active');
                }}
                onDrop={(e) => {
                  e.preventDefault();
                  e.currentTarget.classList.remove('drag-active');
                  if (e.dataTransfer.files && e.dataTransfer.files[0]) {
                    sendFile(peer.id, e.dataTransfer.files[0]);
                  }
                }}
              >
                <div className="peer-avatar">
                  {peer.deviceType === 'mobile' || peer.deviceType === 'tablet' 
                    ? <Smartphone size={32} /> 
                    : <Monitor size={32} />}
                </div>
                <div className="peer-name">{peer.name}</div>
                <div className="peer-type-tag">{peer.deviceType}</div>
                
                <label className="action-btn send-btn">
                  <Upload size={18} />
                  Send File
                  <input 
                    type="file" 
                    className="file-input" 
                    onChange={(e) => {
                      if (e.target.files && e.target.files[0]) {
                        sendFile(peer.id, e.target.files[0]);
                      }
                    }}
                  />
                </label>
                <p className="drag-hint">
                  tap to choose or drop file
                </p>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Received Files Area */}
      {receivedFiles.length > 0 && (
        <div className="glass-panel transfer-area">
          <div className="transfer-header-row">
            <h3 style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Download size={22} className="accent-icon" />
              Received Files ({receivedFiles.length})
            </h3>
            <button 
              onClick={() => setReceivedFiles([])} 
              className="clear-link"
            >
              Clear all
            </button>
          </div>

          <div className="transfer-list">
            {receivedFiles.map((file, i) => (
              <div key={i} className="transfer-item">
                <div className="transfer-meta-row">
                  <div className="transfer-info">
                    <div className="file-icon-box">
                      <File size={22} color="var(--accent-color)" />
                    </div>
                    <div>
                      <div className="transfer-name">{file.name}</div>
                      <div className="transfer-size">
                        {(file.size / 1024).toFixed(1)} KB • from {file.senderName} • {file.time}
                      </div>
                    </div>
                  </div>
                  <a 
                    href={file.data} 
                    download={file.name}
                    className="action-btn download-btn"
                  >
                    <Download size={16} /> Save to Device
                  </a>
                </div>

                {file.type && file.type.startsWith('image/') && (
                  <div className="preview-wrap">
                    <img src={file.data} alt={file.name} className="image-preview" />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
