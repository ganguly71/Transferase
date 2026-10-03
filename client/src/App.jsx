import { useState, useEffect, useRef } from 'react';
import { io } from 'socket.io-client';
import { Share2, Monitor, Smartphone, File, Download, Upload } from 'lucide-react';
import './App.css';

const SERVER_URL = `http://${window.location.hostname}:4000`;

const getDeviceType = () => {
  const ua = navigator.userAgent;
  if (/(tablet|ipad|playbook|silk)|(android(?!.*mobi))/i.test(ua)) return 'tablet';
  if (/Mobile|Android|iP(hone|od)|IEMobile|BlackBerry|Kindle|Silk-Accelerated|(hpw|web)OS|Opera M(obi|ini)/i.test(ua)) return 'mobile';
  return 'desktop';
};

function App() {
  const [socket, setSocket] = useState(null);
  const [me, setMe] = useState('');
  const [myName, setMyName] = useState('');
  const [isEditingName, setIsEditingName] = useState(false);
  const [editNameValue, setEditNameValue] = useState('');
  const [peers, setPeers] = useState([]);
  const [receivedFiles, setReceivedFiles] = useState([]);
  const incomingFiles = useRef({});

  useEffect(() => {
    const newSocket = io(SERVER_URL, { 
      maxHttpBufferSize: 1e8, // allow up to 100MB
      query: { deviceType: getDeviceType() }
    });
    setSocket(newSocket);

    newSocket.on('connect', () => {
      setMe(newSocket.id);
      setMyName(`Peer-${newSocket.id.substring(0, 4)}`);
    });

    newSocket.on('peers-list', (existingPeers) => {
      setPeers(existingPeers.filter(p => p.id !== newSocket.id));
    });

    newSocket.on('peer-joined', (peer) => {
      setPeers(prev => [...prev, peer]);
    });

    newSocket.on('peer-left', (peerId) => {
      setPeers(prev => prev.filter(p => p.id !== peerId));
    });

    newSocket.on('peer-renamed', ({ id, name }) => {
      setPeers(prev => prev.map(p => p.id === id ? { ...p, name } : p));
    });

    // Handle incoming file data directly via Socket.io
    newSocket.on('file-transfer', ({ from, senderName, file }) => {
      if (file.type === 'meta') {
        incomingFiles.current[file.fileId] = {
          name: file.name,
          fileType: file.fileType,
          size: file.size,
          totalChunks: file.totalChunks,
          senderName: senderName,
          chunks: []
        };
      } else if (file.type === 'chunk') {
        const fileTransfer = incomingFiles.current[file.fileId];
        if (fileTransfer) {
          fileTransfer.chunks[file.chunkIndex] = file.data;
          
          if (fileTransfer.chunks.filter(Boolean).length === fileTransfer.totalChunks) {
            const fullData = fileTransfer.chunks.join('');
            setReceivedFiles(prev => [...prev, {
              name: fileTransfer.name,
              type: fileTransfer.fileType,
              size: fileTransfer.size,
              senderName: fileTransfer.senderName,
              data: fullData
            }]);
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
      } else {
        alert(response.error);
      }
    });
  };

  const sendFile = (peerId, file) => {
    if (!socket) return;
    
    const CHUNK_SIZE = 500000; // 500KB chunks over websocket
    const reader = new FileReader();
    
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
          setTimeout(sendNextChunk, 10);
        } else {
          alert(`Sent ${file.name}`);
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
          setTimeout(sendNextChunk, 10);
        } else {
          alert(`Broadcasted ${file.name} to all devices`);
        }
      };
      
      sendNextChunk();
    };
    reader.readAsDataURL(file);
  };

  return (
    <div className="app-container">
      <div className="header glass-panel">
        <Share2 className="header-icon" size={32} />
        <div>
          <h1>Tranferase</h1>
          <p style={{ color: 'var(--text-secondary)' }}>File sharing on your local network</p>
        </div>
      </div>

      <div className="glass-panel">
        <div className="my-info">
          <div>
            <h3>Your Device</h3>
            {isEditingName ? (
              <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.25rem' }}>
                <input 
                  type="text" 
                  value={editNameValue} 
                  onChange={(e) => setEditNameValue(e.target.value)} 
                  onKeyDown={(e) => e.key === 'Enter' && handleRename()}
                  autoFocus
                  style={{ background: 'rgba(0,0,0,0.3)', color: 'white', border: '1px solid var(--accent-color)', borderRadius: '4px', padding: '0.25rem 0.5rem' }}
                />
                <button onClick={handleRename} className="action-btn" style={{ padding: '0.25rem 0.75rem', width: 'auto' }}>Save</button>
                <button onClick={() => setIsEditingName(false)} className="action-btn" style={{ background: 'rgba(255,255,255,0.1)', padding: '0.25rem 0.75rem', width: 'auto' }}>Cancel</button>
              </div>
            ) : (
              <p style={{ color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                You are {myName || `Peer-${me?.substring(0, 4)}`}
              </p>
            )}
          </div>
          <button 
            className="action-btn" 
            style={{ width: 'auto', background: 'rgba(59, 130, 246, 0.2)', color: 'var(--accent-color)' }}
            onClick={() => { setEditNameValue(myName || `Peer-${me?.substring(0, 4)}`); setIsEditingName(true); }}
          >
            Rename Device
          </button>
        </div>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
          <div>
            <h3 style={{ margin: 0 }}>Available Devices on Network</h3>
            <p style={{ color: 'var(--text-secondary)', margin: 0, fontSize: '0.9rem' }}>
              Open this app on another device to see it here.
            </p>
          </div>
          {peers.length > 0 && (
            <label className="action-btn" style={{ width: 'auto', background: 'var(--success-color)' }}>
              <Upload size={18} />
              Broadcast File to All
              <input 
                type="file" 
                className="file-input" 
                onChange={(e) => {
                  if (e.target.files[0]) {
                    broadcastFile(e.target.files[0]);
                  }
                }}
              />
            </label>
          )}
        </div>

        {peers.length === 0 ? (
          <div className="no-peers glass-panel" style={{ background: 'rgba(0,0,0,0.2)' }}>
            <Monitor size={48} style={{ opacity: 0.5, marginBottom: '1rem' }} />
            <p>Waiting for other devices to connect...</p>
          </div>
        ) : (
          <div className="peers-grid">
            {peers.map(peer => (
              <div 
                key={peer.id} 
                className="peer-card glass-panel"
                onDragOver={(e) => {
                  e.preventDefault();
                  e.currentTarget.style.border = '2px dashed var(--accent-color)';
                  e.currentTarget.style.background = 'rgba(59, 130, 246, 0.1)';
                }}
                onDragLeave={(e) => {
                  e.preventDefault();
                  e.currentTarget.style.border = '1px solid var(--card-border)';
                  e.currentTarget.style.background = 'var(--card-bg)';
                }}
                onDrop={(e) => {
                  e.preventDefault();
                  e.currentTarget.style.border = '1px solid var(--card-border)';
                  e.currentTarget.style.background = 'var(--card-bg)';
                  if (e.dataTransfer.files && e.dataTransfer.files[0]) {
                    sendFile(peer.id, e.dataTransfer.files[0]);
                  }
                }}
              >
                <div className="peer-avatar">
                  {peer.deviceType === 'mobile' || peer.deviceType === 'tablet' ? <Smartphone size={32} /> : <Monitor size={32} />}
                </div>
                <div className="peer-name">{peer.name}</div>
                
                <label className="action-btn">
                  <Upload size={18} />
                  Send File
                  <input 
                    type="file" 
                    className="file-input" 
                    onChange={(e) => {
                      if (e.target.files[0]) {
                        sendFile(peer.id, e.target.files[0]);
                      }
                    }}
                  />
                </label>
                <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
                  or drag & drop here
                </p>
              </div>
            ))}
          </div>
        )}
      </div>

      {receivedFiles.length > 0 && (
        <div className="glass-panel transfer-area">
          <h3 style={{ marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <Download size={20} />
            Received Files
          </h3>
          <div className="transfer-list">
            {receivedFiles.map((file, i) => (
              <div key={i} className="transfer-item" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: '1rem' }}>
                <div style={{ display: 'flex', width: '100%', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div className="transfer-info">
                    <File size={24} color="var(--accent-color)" />
                    <div>
                      <div className="transfer-name">{file.name}</div>
                      <div className="transfer-size">{(file.size / 1024).toFixed(1)} KB • from {file.senderName || 'Unknown'}</div>
                    </div>
                  </div>
                  <a 
                    href={file.data} 
                    download={file.name}
                    className="action-btn" 
                    style={{ width: 'auto', padding: '0.5rem 1rem' }}
                  >
                    <Download size={16} /> Save
                  </a>
                </div>
                {file.type.startsWith('image/') && (
                  <img src={file.data} alt={file.name} style={{ maxWidth: '100%', maxHeight: '300px', borderRadius: '8px', objectFit: 'contain' }} />
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
