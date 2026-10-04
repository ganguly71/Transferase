import { useState, useEffect, useRef } from 'react';
import { io } from 'socket.io-client';
import JSZip from 'jszip';
import { 
  Share2, 
  Monitor, 
  Smartphone, 
  Tablet,
  File, 
  Download, 
  Upload, 
  Wifi, 
  Key, 
  CheckCircle, 
  AlertCircle, 
  ArrowRight,
  Trash2,
  X,
  Plus,
  Search,
  MessageSquare,
  Send,
  CheckSquare,
  Square,
  FileText,
  AlertTriangle,
  Clipboard,
  Copy,
  Check,
  Layers,
  HardDrive,
  Pencil,
  Archive,
  LogOut,
  XCircle,
  Crown
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

const getStoredUserId = () => {
  let uid = sessionStorage.getItem('transferase_client_id');
  if (!uid) {
    uid = 'usr_' + Math.random().toString(36).substring(2, 9) + Date.now().toString(36);
    sessionStorage.setItem('transferase_client_id', uid);
  }
  return uid;
};

const formatBytes = (bytes) => {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

const getDeviceDataLimit = (deviceType) => {
  const dt = (deviceType || '').toLowerCase();
  if (dt === 'mobile') return 'Max Safe: ~1 GB (RAM limit)';
  if (dt === 'tablet') return 'Max Safe: ~1.5 GB (RAM limit)';
  return 'Max Safe: ~2-4 GB (Desktop RAM)';
};

const MAX_SAFE_QUEUE_SIZE = 2 * 1024 * 1024 * 1024; // 2 GB safe threshold

const splitFileName = (filename) => {
  if (!filename) return { baseName: '', extension: '' };
  const lastDotIndex = filename.lastIndexOf('.');
  if (lastDotIndex > 0 && lastDotIndex < filename.length - 1) {
    return {
      baseName: filename.substring(0, lastDotIndex),
      extension: filename.substring(lastDotIndex)
    };
  }
  return {
    baseName: filename,
    extension: ''
  };
};

// Auto-number duplicates: e.g. "photo.jpg" -> "photo (2).jpg", "(3)", etc.
const resolveDuplicateName = (desiredName, existingNames = []) => {
  if (!existingNames.includes(desiredName)) {
    return desiredName;
  }

  const { baseName, extension } = splitFileName(desiredName);
  const match = baseName.match(/^(.*?)(?:\s*\((\d+)\))$/);
  const cleanBase = match ? match[1].trim() : baseName;

  let copyNo = 2;
  if (match && match[2]) {
    copyNo = parseInt(match[2], 10) + 1;
  }

  let candidate = `${cleanBase} (${copyNo})${extension}`;
  while (existingNames.includes(candidate)) {
    copyNo++;
    candidate = `${cleanBase} (${copyNo})${extension}`;
  }
  return candidate;
};

function App() {
  const [socket, setSocket] = useState(null);
  const [connected, setConnected] = useState(false);
  const [me, setMe] = useState('');
  const [myName, setMyName] = useState('');
  const [networkInfo, setNetworkInfo] = useState({ room: '', isCustom: false, roomCode: '', ip: '' });
  const [roomCodeInput, setRoomCodeInput] = useState('');
  const [isHost, setIsHost] = useState(false);
  const [roomHostName, setRoomHostName] = useState('');
  const [isEditingName, setIsEditingName] = useState(false);
  const [editNameValue, setEditNameValue] = useState('');
  const [peers, setPeers] = useState([]);
  const [receivedFiles, setReceivedFiles] = useState([]);
  const [isZipping, setIsZipping] = useState(false);
  const [transferProgress, setTransferProgress] = useState(null); // { fileName, percent, status }
  const [notification, setNotification] = useState(null);

  // Queue State
  const [queue, setQueue] = useState([]); // array of { id, file, name, size, type, isText, textContent, caption }
  const [textInput, setTextInput] = useState('');
  const [isSendingQueue, setIsSendingQueue] = useState(false);
  const [isQueueDragging, setIsQueueDragging] = useState(false);
  const [editingItemId, setEditingItemId] = useState(null);
  const [editingItemBaseName, setEditingItemBaseName] = useState('');
  const [editingItemExt, setEditingItemExt] = useState('');
  const [editingTextId, setEditingTextId] = useState(null);
  const [editingTextContent, setEditingTextContent] = useState('');

  // Recipient Modal State
  const [showRecipientModal, setShowRecipientModal] = useState(false);
  const [showTextModal, setShowTextModal] = useState(false);
  const [recipientSearch, setRecipientSearch] = useState('');
  const [selectedPeerIds, setSelectedPeerIds] = useState([]);
  const [copiedId, setCopiedId] = useState(null);

  const incomingFiles = useRef({});
  const textareaRef = useRef(null);
  const queueFileInputRef = useRef(null);

  const showToast = (message, type = 'info') => {
    setNotification({ message, type });
    setTimeout(() => {
      setNotification(null);
    }, 4500);
  };

  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const urlRoom = (urlParams.get('room') || '').trim().toLowerCase();
    if (urlRoom) {
      sessionStorage.setItem('transferase_room_code', urlRoom);
      window.history.replaceState({}, document.title, window.location.pathname);
    }

    const savedRoom = urlRoom || sessionStorage.getItem('transferase_room_code') || '';
    const savedName = sessionStorage.getItem('transferase_device_name') || '';
    const isSavedCreator = savedRoom ? (sessionStorage.getItem('transferase_creator_room_' + savedRoom) === 'true') : false;

    const newSocket = io(SERVER_URL, { 
      maxHttpBufferSize: 1e8, // allow up to 100MB socket packets
      query: { 
        deviceType: getDeviceType(),
        userId: getStoredUserId(),
        roomCode: savedRoom,
        savedName: savedName,
        isCreator: isSavedCreator ? 'true' : 'false'
      }
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
      sessionStorage.setItem('transferase_device_name', data.name);
      setNetworkInfo({
        room: data.currentRoom,
        isCustom: data.isCustomRoom,
        roomCode: data.roomCode,
        ip: data.networkIp
      });
      setIsHost(!!data.isHost);
      setRoomHostName(data.hostName || '');
      if (data.isCustomRoom && data.roomCode && data.roomCode !== 'Local Network') {
        sessionStorage.setItem('transferase_room_code', data.roomCode);
      }
    });

    newSocket.on('room-changed', (data) => {
      setNetworkInfo(prev => ({
        ...prev,
        room: data.currentRoom,
        isCustom: data.isCustomRoom,
        roomCode: data.roomCode
      }));
      setIsHost(!!data.isHost);
      setRoomHostName(data.hostName || '');
      if (data.isCustomRoom && data.roomCode && data.roomCode !== 'Local Network') {
        sessionStorage.setItem('transferase_room_code', data.roomCode);
        showToast(`Connected to room: ${data.roomCode}${data.isHost ? ' (You are Host)' : ''}`, 'success');
      } else {
        sessionStorage.removeItem('transferase_room_code');
        showToast(`Switched to: ${data.roomCode}`, 'info');
      }
    });

    newSocket.on('host-changed', (data) => {
      setRoomHostName(data.newHostName);
      if (data.newHostId === newSocket.id || data.newHostUserId === getStoredUserId()) {
        setIsHost(true);
        if (data.isRoomMaker) {
          showToast('Welcome back! Host privileges restored to you.', 'success');
        } else {
          showToast('Room host absent. You now have temporary host privileges.', 'success');
        }
      } else {
        setIsHost(false);
        if (data.isRoomMaker) {
          showToast(`Room maker (${data.newHostName}) is present and has host privileges.`, 'info');
        } else {
          showToast(`${data.newHostName} is now the room host.`, 'info');
        }
      }
    });

    newSocket.on('room-closed', (data) => {
      sessionStorage.removeItem('transferase_room_code');
      setIsHost(false);
      setRoomHostName('');
      showToast(data.reason || 'The room was closed by the host.', 'info');
    });

    newSocket.on('room-info', (data) => {
      if (data.hostName) setRoomHostName(data.hostName);
      if (data.hostUserId) setIsHost(data.hostUserId === getStoredUserId());
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
          caption: file.caption || '',
          isText: file.isText || false,
          fileType: file.fileType,
          size: file.size,
          totalChunks: file.totalChunks,
          senderName: senderName || 'Unknown',
          chunks: [],
          receivedCount: 0
        };
        setTransferProgress({
          fileName: file.name,
          percent: 5,
          status: `Receiving from ${senderName || 'Peer'}...`,
          isDownload: true
        });
      } else if (file.type === 'chunk') {
        const fileTransfer = incomingFiles.current[file.fileId];
        if (fileTransfer) {
          if (!fileTransfer.chunks[file.chunkIndex]) {
            fileTransfer.receivedCount++;
          }
          fileTransfer.chunks[file.chunkIndex] = file.data;
          const pct = Math.round((fileTransfer.receivedCount / fileTransfer.totalChunks) * 100);

          setTransferProgress({
            fileName: fileTransfer.name,
            percent: pct,
            status: `Receiving (${pct}%)...`,
            isDownload: true
          });
          
          if (fileTransfer.receivedCount === fileTransfer.totalChunks) {
            const blob = new Blob(fileTransfer.chunks, { type: fileTransfer.fileType });
            const blobUrl = URL.createObjectURL(blob);

            // Decode text preview if applicable
            let textPreview = '';
            if (fileTransfer.isText || (typeof fileTransfer.fileType === 'string' && fileTransfer.fileType.startsWith('text/'))) {
              try {
                const decoder = new TextDecoder('utf-8');
                textPreview = fileTransfer.chunks.map(c => decoder.decode(c, { stream: true })).join('');
              } catch (err) {
                console.error('Error decoding text preview', err);
              }
            }

            setReceivedFiles(prev => [{
              id: file.fileId || Math.random().toString(36).substring(2, 9),
              name: fileTransfer.name,
              caption: fileTransfer.caption,
              isText: fileTransfer.isText,
              textPreview,
              type: fileTransfer.fileType,
              size: fileTransfer.size,
              senderName: fileTransfer.senderName,
              data: blobUrl,
              blob: blob,
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

  // Global paste handler to add files or text directly to queue
  useEffect(() => {
    const handlePaste = (e) => {
      // Ignore if typing inside standard text input or textarea
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

      if (e.clipboardData) {
        const items = e.clipboardData.items;
        const pastedFiles = [];
        let pastedText = null;

        for (let i = 0; i < items.length; i++) {
          if (items[i].kind === 'file') {
            const file = items[i].getAsFile();
            if (file) pastedFiles.push(file);
          } else if (items[i].kind === 'string' && items[i].type === 'text/plain') {
            items[i].getAsString((str) => {
              if (str.trim()) {
                pastedText = str;
              }
            });
          }
        }

        if (pastedFiles.length > 0) {
          addFilesToQueue(pastedFiles);
        } else if (pastedText) {
          addTextSnippetToQueue(pastedText);
        }
      }
    };

    window.addEventListener('paste', handlePaste);
    return () => window.removeEventListener('paste', handlePaste);
  }, []);

  // Total Queue Size calculation
  const totalQueueSize = queue.reduce((acc, it) => acc + (it.size || 0), 0);
  const isOverLimit = totalQueueSize > MAX_SAFE_QUEUE_SIZE;

  // Add multiple files to queue with duplicate auto-renaming "(2)", "(3)"
  const addFilesToQueue = (fileList) => {
    if (!fileList || fileList.length === 0) return;
    // CRITICAL: Clone the fileList synchronously into an array immediately
    // so any e.target.value = '' does not empty the files list in memory!
    const filesArray = Array.from(fileList);
    if (filesArray.length === 0) return;

    setQueue(prevQueue => {
      const currentNames = prevQueue.map(item => item.name);

      const newItems = filesArray.map(file => {
        const uniqueName = resolveDuplicateName(file.name, currentNames);
        currentNames.push(uniqueName);

        let finalFile = file;
        if (uniqueName !== file.name) {
          try {
            finalFile = new File([file], uniqueName, { type: file.type });
          } catch {
            finalFile = file;
          }
        }

        return {
          id: Math.random().toString(36).substring(2, 9),
          file: finalFile,
          name: uniqueName,
          size: file.size,
          type: file.type || 'application/octet-stream',
          isText: file.type === 'text/plain' || uniqueName.endsWith('.txt'),
          textContent: null,
          caption: ''
        };
      });

      const nextTotal = prevQueue.reduce((acc, it) => acc + (it.size || 0), 0) + 
                        newItems.reduce((acc, it) => acc + it.size, 0);

      if (nextTotal > MAX_SAFE_QUEUE_SIZE) {
        showToast(`Warning: Total size (${formatBytes(nextTotal)}) exceeds the 2 GB safe limit. Devices with low RAM may fail.`, 'error');
      } else {
        showToast(`Added ${newItems.length} file(s) to queue`, 'info');
      }

      return [...prevQueue, ...newItems];
    });
  };

  // Add text snippet to queue with duplicate auto-renaming
  const addTextSnippetToQueue = (text) => {
    if (!text || !text.trim()) return;
    const trimmed = text.trim();
    const cleanSnippet = trimmed.slice(0, 16).replace(/[^a-zA-Z0-9_-]/g, '_');
    const rawFileName = `note_${cleanSnippet || Date.now().toString().slice(-4)}.txt`;

    setQueue(prevQueue => {
      const currentNames = prevQueue.map(item => item.name);
      const uniqueFileName = resolveDuplicateName(rawFileName, currentNames);

      const blob = new Blob([trimmed], { type: 'text/plain;charset=utf-8' });
      let textFile;
      try {
        textFile = new File([blob], uniqueFileName, { type: 'text/plain' });
      } catch {
        textFile = blob;
        textFile.name = uniqueFileName;
      }

      const newItem = {
        id: Math.random().toString(36).substring(2, 9),
        file: textFile,
        name: uniqueFileName,
        size: blob.size,
        type: 'text/plain',
        isText: true,
        textContent: trimmed,
        caption: ''
      };

      showToast(`Added text note to queue (${formatBytes(blob.size)})`, 'success');
      return [...prevQueue, newItem];
    });
  };

  // Handle clicking "Add Text to Queue" button or pressing Ctrl+Enter
  const handleAddTextInput = async () => {
    let text = textInput.trim();

    // If textarea is currently empty, attempt to read directly from system clipboard as a smart fallback
    if (!text) {
      try {
        const clipText = await navigator.clipboard.readText();
        if (clipText && clipText.trim()) {
          text = clipText.trim();
          showToast('Captured text from clipboard and added to queue!', 'success');
        }
      } catch {
        // clipboard access not permitted
      }
    }

    if (!text) {
      showToast('Please type or paste some text first', 'info');
      if (textareaRef.current) {
        textareaRef.current.focus();
      }
      return;
    }

    addTextSnippetToQueue(text);
    setTextInput('');
  };

  const handlePasteFromClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text && text.trim()) {
        setTextInput(prev => prev ? prev + '\n' + text : text);
        showToast('Pasted text from clipboard into text box', 'info');
        if (textareaRef.current) textareaRef.current.focus();
      } else {
        showToast('Clipboard is empty or has no text', 'info');
      }
    } catch {
      showToast('Clipboard permission blocked. Press Ctrl+V directly inside the box.', 'info');
      if (textareaRef.current) textareaRef.current.focus();
    }
  };

  const updateQueueCaption = (id, caption) => {
    setQueue(prev => prev.map(item => item.id === id ? { ...item, caption } : item));
  };

  const handleStartRename = (item) => {
    if (editingTextId) handleCancelEditText();
    const { baseName, extension } = splitFileName(item.name);
    setEditingItemId(item.id);
    setEditingItemBaseName(baseName);
    setEditingItemExt(extension);
  };

  const handleCancelRename = () => {
    setEditingItemId(null);
    setEditingItemBaseName('');
    setEditingItemExt('');
  };

  const handleSaveRename = (id) => {
    const trimmedBase = editingItemBaseName.trim();
    if (!trimmedBase) {
      showToast('File name cannot be empty', 'error');
      return;
    }

    const requestedName = `${trimmedBase}${editingItemExt}`;
    const otherNames = queue.filter(it => it.id !== id).map(it => it.name);
    const finalName = resolveDuplicateName(requestedName, otherNames);

    setQueue(prev => prev.map(it => {
      if (it.id === id) {
        let updatedFile = it.file;
        try {
          if (it.file instanceof Blob) {
            updatedFile = new File([it.file], finalName, { type: it.type });
          }
        } catch {}

        return {
          ...it,
          file: updatedFile,
          name: finalName
        };
      }
      return it;
    }));

    setEditingItemId(null);
    setEditingItemBaseName('');
    setEditingItemExt('');
    showToast(`Renamed file to "${finalName}"`, 'success');
  };

  // Text content editing handlers for text snippets in queue
  const handleStartEditText = (item) => {
    if (editingItemId) handleCancelRename();
    if (item.textContent !== null && item.textContent !== undefined) {
      setEditingTextId(item.id);
      setEditingTextContent(item.textContent);
    } else if (item.file) {
      const reader = new FileReader();
      reader.onload = (e) => {
        const text = e.target.result || '';
        setEditingTextId(item.id);
        setEditingTextContent(text);
      };
      reader.readAsText(item.file);
    }
  };

  const handleCancelEditText = () => {
    setEditingTextId(null);
    setEditingTextContent('');
  };

  const handleSaveEditText = (id) => {
    const updatedText = editingTextContent.trim();
    if (!updatedText) {
      showToast('Text content cannot be empty', 'error');
      return;
    }

    setQueue(prev => prev.map(item => {
      if (item.id === id) {
        const blob = new Blob([updatedText], { type: 'text/plain;charset=utf-8' });
        let updatedFile;
        try {
          updatedFile = new File([blob], item.name, { type: 'text/plain' });
        } catch {
          updatedFile = blob;
          updatedFile.name = item.name;
        }

        return {
          ...item,
          file: updatedFile,
          size: blob.size,
          textContent: updatedText
        };
      }
      return item;
    }));

    setEditingTextId(null);
    setEditingTextContent('');
    showToast('Updated text note content', 'success');
  };

  const removeFromQueue = (id) => {
    if (editingItemId === id) handleCancelRename();
    if (editingTextId === id) handleCancelEditText();
    setQueue(prev => prev.filter(item => item.id !== id));
  };

  const clearQueue = () => {
    handleCancelRename();
    handleCancelEditText();
    setQueue([]);
  };

  // Download all received files as a ZIP archive or direct download
  const handleDownloadAll = async () => {
    if (receivedFiles.length === 0) return;

    // Single item direct save
    if (receivedFiles.length === 1) {
      const single = receivedFiles[0];
      const link = document.createElement('a');
      link.href = single.data;
      link.download = single.name || 'received_file';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      showToast(`Downloading ${single.name}...`, 'success');
      return;
    }

    setIsZipping(true);
    showToast(`Archiving ${receivedFiles.length} items into ZIP...`, 'info');
    setTransferProgress({
      fileName: `Transferase_Archive.zip (${receivedFiles.length} files)`,
      percent: 10,
      status: `Packaging and compressing files for download...`,
      isDownload: true
    });

    try {
      const zip = new JSZip();
      const existingZipNames = [];

      for (const item of receivedFiles) {
        let blob = item.blob;
        if (!blob && item.data) {
          try {
            const resp = await fetch(item.data);
            blob = await resp.blob();
          } catch (e) {
            console.warn('Could not fetch blob from object URL', e);
          }
        }

        let fileName = item.name || (item.isText ? 'note.txt' : 'file');
        if (item.isText && !fileName.includes('.')) {
          fileName += '.txt';
        }
        const safeName = resolveDuplicateName(fileName, existingZipNames);
        existingZipNames.push(safeName);

        if (blob) {
          zip.file(safeName, blob);
        } else if (item.textPreview) {
          zip.file(safeName, item.textPreview);
        }
      }

      const now = new Date();
      const dateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}-${String(now.getMinutes()).padStart(2, '0')}`;
      const zipFileName = `Transferase_Files_${dateStr}.zip`;

      const zipBlob = await zip.generateAsync({ 
        type: 'blob',
        compression: 'DEFLATE',
        compressionOptions: { level: 6 }
      }, (metadata) => {
        setTransferProgress({
          fileName: zipFileName,
          percent: Math.max(10, Math.round(metadata.percent)),
          status: `Compressing and building ZIP archive (${Math.round(metadata.percent)}%)...`,
          isDownload: true
        });
      });

      const zipUrl = URL.createObjectURL(zipBlob);
      const downloadLink = document.createElement('a');
      downloadLink.href = zipUrl;
      downloadLink.download = zipFileName;
      document.body.appendChild(downloadLink);
      downloadLink.click();
      document.body.removeChild(downloadLink);

      setTimeout(() => URL.revokeObjectURL(zipUrl), 45000);
      showToast(`Downloaded all ${receivedFiles.length} items as ZIP archive!`, 'success');
    } catch (err) {
      console.error('Failed to generate ZIP archive', err);
      showToast('ZIP archiving failed. Downloading items individually...', 'error');

      // Sequential fallback
      receivedFiles.forEach((file, index) => {
        setTimeout(() => {
          const a = document.createElement('a');
          a.href = file.data;
          a.download = file.name;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
        }, index * 350);
      });
    } finally {
      setIsZipping(false);
      setTransferProgress(null);
    }
  };

  // Open recipient selection modal or handle send
  const handleOpenRecipientModal = () => {
    if (queue.length === 0) {
      showToast('Queue is empty. Select files or paste text first.', 'error');
      return;
    }
    if (peers.length === 0) {
      showToast('No devices connected. Wait for nearby peers or share room code.', 'error');
      return;
    }

    // Default select all connected peers
    setSelectedPeerIds(peers.map(p => p.id));
    setRecipientSearch('');
    setShowRecipientModal(true);
  };

  // Direct send from a peer card
  const handleSendQueueToSpecificPeer = (peerId) => {
    if (queue.length === 0) {
      showToast('Queue is empty. Select files or paste text first.', 'error');
      return;
    }
    startSendingQueue([peerId]);
  };

  // Start sending all queued items to selected peers
  const startSendingQueue = async (targetPeerIds) => {
    if (!socket || targetPeerIds.length === 0 || queue.length === 0) return;
    setShowRecipientModal(false);
    setIsSendingQueue(true);

    const itemsToSend = [...queue];
    const CHUNK_SIZE = 500000; // 500KB chunks

    for (let i = 0; i < itemsToSend.length; i++) {
      const item = itemsToSend[i];
      const totalChunks = Math.ceil(item.file.size / CHUNK_SIZE) || 1;
      const fileId = Math.random().toString(36).substring(2, 9);

      setTransferProgress({
        fileName: `(${i + 1}/${itemsToSend.length}) ${item.name}`,
        percent: 0,
        status: `Preparing to send to ${targetPeerIds.length} device(s)...`,
        isDownload: false
      });

      // Emit metadata first
      targetPeerIds.forEach(peerId => {
        socket.emit('file-transfer', {
          to: peerId,
          file: {
            type: 'meta',
            fileId,
            name: item.name,
            caption: item.caption || '',
            isText: item.isText || false,
            fileType: item.type,
            size: item.size,
            totalChunks
          }
        });
      });

      if (item.file.size > 0) {
        let offset = 0;
        let chunkIndex = 0;

        await new Promise((resolve) => {
          const sendNextChunk = () => {
            if (offset < item.file.size) {
              const chunk = item.file.slice(offset, offset + CHUNK_SIZE);
              const reader = new FileReader();
              reader.onload = (e) => {
                targetPeerIds.forEach(peerId => {
                  socket.emit('file-transfer', {
                    to: peerId,
                    file: {
                      type: 'chunk',
                      fileId,
                      chunkIndex,
                      data: e.target.result // ArrayBuffer
                    }
                  });
                });
                offset += CHUNK_SIZE;
                chunkIndex++;
                const pct = Math.round((chunkIndex / totalChunks) * 100);
                setTransferProgress({
                  fileName: `(${i + 1}/${itemsToSend.length}) ${item.name}`,
                  percent: pct,
                  status: `Sending (${pct}%) to ${targetPeerIds.length} peer(s)...`,
                  isDownload: false
                });
                setTimeout(sendNextChunk, 15);
              };
              reader.readAsArrayBuffer(chunk);
            } else {
              resolve();
            }
          };
          sendNextChunk();
        });
      }
    }

    setTransferProgress(null);
    setIsSendingQueue(false);
    setQueue([]);
    showToast(`Successfully transferred ${itemsToSend.length} item(s)!`, 'success');
  };

  const handleRename = () => {
    if (!editNameValue.trim() || editNameValue === myName) {
      setIsEditingName(false);
      return;
    }
    socket.emit('rename-device', editNameValue.trim(), (response) => {
      if (response.success) {
        setMyName(response.name);
        sessionStorage.setItem('transferase_device_name', response.name);
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
    sessionStorage.setItem('transferase_room_code', code);
    sessionStorage.setItem('transferase_creator_room_' + code, 'true');
    socket.emit('join-room', { roomCode: code, isCreator: true }, (res) => {
      if (res.success) {
        showToast(`Created secure room: ${code}`, 'success');
      }
    });
  };

  const handleJoinCustomRoom = (e) => {
    e.preventDefault();
    if (!socket) return;
    const clean = roomCodeInput.trim().toLowerCase();
    if (!clean) return;
    sessionStorage.setItem('transferase_room_code', clean);
    const isSavedCreator = sessionStorage.getItem('transferase_creator_room_' + clean) === 'true';
    socket.emit('join-room', { roomCode: clean, isCreator: isSavedCreator }, (res) => {
      if (res.success) {
        setRoomCodeInput('');
      }
    });
  };

  const handleLeaveRoom = () => {
    if (!socket) return;
    sessionStorage.removeItem('transferase_room_code');
    socket.emit('leave-room', () => {
      showToast('Left room and returned to local network', 'info');
    });
  };

  const handleCloseRoom = () => {
    if (!socket || !isHost) return;
    if (window.confirm('Are you sure you want to close this room? All participants will be returned to their local network.')) {
      const code = networkInfo.roomCode || sessionStorage.getItem('transferase_room_code');
      if (code) {
        sessionStorage.removeItem('transferase_creator_room_' + code);
      }
      sessionStorage.removeItem('transferase_room_code');
      socket.emit('close-room', (res) => {
        if (res && res.success) {
          showToast('Room closed successfully', 'info');
        } else if (res && res.error) {
          showToast(res.error, 'error');
        }
      });
    }
  };

  const handleCopyRoomLink = () => {
    const code = networkInfo.roomCode || sessionStorage.getItem('transferase_room_code');
    if (!code) return;
    const shareUrl = `${window.location.origin}${window.location.pathname}?room=${code}`;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(shareUrl).then(() => {
        showToast('Copied room invite link to clipboard!', 'success');
      }).catch(() => {
        showToast(`Room code: ${code}`, 'info');
      });
    } else {
      showToast(`Room code: ${code}`, 'info');
    }
  };

  const handleCopyText = (text, id) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    showToast('Copied text to clipboard!', 'success');
    setTimeout(() => setCopiedId(null), 2000);
  };

  // Filter peers for recipient modal
  const filteredPeers = peers.filter(p => 
    p.name.toLowerCase().includes(recipientSearch.toLowerCase()) ||
    p.deviceType.toLowerCase().includes(recipientSearch.toLowerCase())
  );

  const toggleSelectPeer = (id) => {
    setSelectedPeerIds(prev => 
      prev.includes(id) ? prev.filter(pId => pId !== id) : [...prev, id]
    );
  };

  const toggleSelectAllPeers = () => {
    if (selectedPeerIds.length === filteredPeers.length) {
      setSelectedPeerIds([]);
    } else {
      setSelectedPeerIds(filteredPeers.map(p => p.id));
    }
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

      {/* Header with Waves & Binary Data-Transfer Animation */}
      <div className="header glass-panel">
        <div className="header-data-bg" aria-hidden="true">
          {/* Undulating Signal Waveforms */}
          <div className="signal-waves-wrap">
            <svg className="signal-wave wave-primary" viewBox="0 0 1600 60" preserveAspectRatio="none">
              <path d="M0,30 Q100,6 200,30 T400,30 T600,30 T800,30 T1000,30 T1200,30 T1400,30 T1600,30" />
            </svg>
            <svg className="signal-wave wave-secondary" viewBox="0 0 1600 60" preserveAspectRatio="none">
              <path d="M0,30 Q100,54 200,30 T400,30 T600,30 T800,30 T1000,30 T1200,30 T1400,30 T1600,30" />
            </svg>
          </div>

          {/* Flowing Binary Streams */}
          <div className="binary-stream stream-top">
            <div className="binary-track">
              <span>01010100 01110010 01100001 01101110 01110011 01100110 01100101 01110010 01100001 01110011 01100101 00100000 01010000 00110010 01010000 00100000 </span>
              <span>01010100 01110010 01100001 01101110 01110011 01100110 01100101 01110010 01100001 01110011 01100101 00100000 01010000 00110010 01010000 00100000 </span>
            </div>
          </div>
          <div className="binary-stream stream-bottom">
            <div className="binary-track reverse">
              <span>11001010 10110011 00110101 11100010 01010110 10101011 00110011 11010101 01101100 10111001 01010101 11000011 10101100 01101010 10110101 11011010 </span>
              <span>11001010 10110011 00110101 11100010 01010110 10101011 00110011 11010101 01101100 10111001 01010101 11000011 10101100 01101010 10110101 11011010 </span>
            </div>
          </div>

          <div className="data-ambient-glow"></div>
        </div>
        <div style={{ position: 'relative', zIndex: 2 }}>
          <h1>Tranferase</h1>
        </div>
      </div>

      {/* Unified Network & Your Device Box (Single Card) */}
      <div className="network-unified-card glass-panel">
        <div className="network-unified-top">
          <div className="network-info-left">
            <div className="network-chip">
              {networkInfo.isCustom ? <Key size={16} /> : <Wifi size={16} />}
              <span>
                {networkInfo.isCustom 
                  ? `Custom Room: ${networkInfo.roomCode || networkInfo.room.replace('custom_', '')}` 
                  : 'Auto-Matched Local Wi-Fi / Hotspot'}
              </span>
              {networkInfo.isCustom && (
                isHost ? (
                  <span className="room-role-badge host-badge" title="You created this room and have room-closing privileges">
                    <Crown size={13} strokeWidth={2.5} /> Host (You)
                  </span>
                ) : (
                  roomHostName && (
                    <span className="room-role-badge guest-badge" title={`Room host: ${roomHostName}`}>
                      Host: {roomHostName}
                    </span>
                  )
                )
              )}
            </div>
            <span className="network-detail">
              {networkInfo.isCustom 
                ? 'Devices with the same room code exchange files directly. Your room is preserved on refresh.' 
                : 'Devices on the same Wi-Fi router or mobile hotspot appear automatically.'}
            </span>
          </div>

          <div className="room-controls-wrapper">
            {!networkInfo.isCustom ? (
              <>
                <button type="button" onClick={handleCreateRoom} className="room-btn create-btn">
                  <Key size={14} /> Create Room
                </button>
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
                </form>
              </>
            ) : (
              <div className="room-active-actions">
                <button 
                  type="button" 
                  onClick={handleCopyRoomLink} 
                  className="room-btn copy-link-btn"
                  title="Copy 1-tap invite link for other devices"
                >
                  <Copy size={14} /> Copy Room Link
                </button>
                <button 
                  type="button" 
                  onClick={handleLeaveRoom} 
                  className="room-btn leave-btn"
                  title="Leave this room and return to auto-detected local network"
                >
                  <LogOut size={14} /> Leave Room
                </button>
                {isHost && (
                  <button 
                    type="button" 
                    onClick={handleCloseRoom} 
                    className="room-btn close-room-btn"
                    title="Close this room for all participants"
                  >
                    <XCircle size={14} /> Close Room
                  </button>
                )}
              </div>
            )}
          </div>
        </div>

        <div className="network-unified-divider"></div>

        <div className="network-unified-bottom">
          <div className="your-device-left">
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
            <div className="your-device-badges">
              <span className="device-type-chip">
                {getDeviceType() === 'mobile' ? <Smartphone size={14} /> : getDeviceType() === 'tablet' ? <Tablet size={14} /> : <Monitor size={14} />}
                {getDeviceType()}
              </span>
              <span className="device-limit-chip" title="Estimated safe file transfer limit based on device browser RAM">
                <HardDrive size={13} />
                {getDeviceDataLimit(getDeviceType())}
              </span>
            </div>
          </div>

          <div className="your-device-right" style={{ display: 'flex', gap: '0.65rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <label className="action-btn select-files-btn">
              <Upload size={16} />
              Select File(s)
              <input 
                type="file" 
                multiple
                className="file-input" 
                onChange={(e) => {
                  if (e.target.files && e.target.files.length > 0) {
                    const files = Array.from(e.target.files);
                    addFilesToQueue(files);
                    e.target.value = ''; // reset so same files can be re-selected if desired
                  }
                }}
              />
            </label>
            <button 
              type="button" 
              className="action-btn select-files-btn"
              onClick={() => setShowTextModal(true)}
              title="Add or paste text notes"
            >
              <MessageSquare size={16} />
              Add / Paste Text
            </button>
            {peers.length > 0 && queue.length > 0 && (
              <button 
                type="button" 
                className="action-btn broadcast-btn"
                onClick={handleOpenRecipientModal}
              >
                <Send size={16} />
                Send Queue ({queue.length})
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Active Transfer Progress (if any) */}
      {transferProgress && (
        <div 
          className={`progress-banner ${transferProgress.isDownload ? 'is-download' : 'is-upload'}`}
          style={{
            background: '#110904',
            color: '#FFFFFF',
            border: '2px solid #C49267',
            borderLeft: transferProgress.isDownload ? '6px solid #10B981' : '6px solid #F59E0B',
            boxShadow: '0 10px 28px rgba(0, 0, 0, 0.75), inset 0 0 0 1px rgba(255, 255, 255, 0.12)',
            padding: '1.25rem 1.5rem',
            marginBottom: '0.85rem'
          }}
        >
          <div className="progress-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span className="file-transferring-name" style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', color: '#FFFFFF', fontWeight: 700 }}>
              {transferProgress.isDownload ? (
                <Download size={20} className="spin-slow transfer-icon" style={{ color: '#34D399', flexShrink: 0 }} />
              ) : (
                <Upload size={20} className="spin-slow transfer-icon" style={{ color: '#FBBF24', flexShrink: 0 }} />
              )}
              <span 
                className="transfer-type-tag"
                style={{
                  background: transferProgress.isDownload ? 'rgba(16, 185, 129, 0.25)' : 'rgba(245, 158, 11, 0.25)',
                  color: transferProgress.isDownload ? '#6EE7B7' : '#FDE68A',
                  border: transferProgress.isDownload ? '1px solid #10B981' : '1px solid #F59E0B',
                  padding: '0.2rem 0.55rem',
                  fontSize: '0.72rem',
                  fontWeight: 800,
                  letterSpacing: '0.08em'
                }}
              >
                {transferProgress.isDownload ? 'DOWNLOADING' : 'SENDING'}
              </span>
              <span className="file-name-text" style={{ color: '#FFFFFF', fontWeight: 700, fontSize: '1.05rem' }}>
                {transferProgress.fileName}
              </span>
            </span>
            <span 
              className="transfer-percentage"
              style={{
                fontFamily: "'Rajdhani', monospace, sans-serif",
                fontSize: '1.4rem',
                fontWeight: 800,
                color: transferProgress.isDownload ? '#34D399' : '#FDE68A',
                textShadow: transferProgress.isDownload ? '0 0 12px rgba(52, 211, 153, 0.7)' : '0 0 12px rgba(253, 230, 138, 0.7)'
              }}
            >
              {transferProgress.percent}%
            </span>
          </div>
          <div 
            className="progress-bar-track"
            style={{
              width: '100%',
              height: '14px',
              background: '#000000',
              border: '1.5px solid rgba(255, 255, 255, 0.35)',
              boxShadow: 'inset 0 2px 5px rgba(0, 0, 0, 0.8)',
              overflow: 'hidden',
              margin: '0.5rem 0'
            }}
          >
            <div 
              className="progress-bar-fill" 
              style={{ 
                width: `${transferProgress.percent}%`,
                height: '100%',
                background: transferProgress.isDownload 
                  ? 'linear-gradient(90deg, #10B981 0%, #34D399 35%, #6EE7B7 70%, #FFFFFF 100%)' 
                  : 'linear-gradient(90deg, #D97706 0%, #F59E0B 35%, #FBBF24 70%, #FFFFFF 100%)',
                boxShadow: transferProgress.isDownload 
                  ? '0 0 16px rgba(52, 211, 153, 0.95), 0 0 6px #FFFFFF' 
                  : '0 0 16px rgba(251, 191, 36, 0.95), 0 0 6px #FFFFFF',
                transition: 'width 0.15s ease-out'
              }}
            ></div>
          </div>
          <span 
            className="progress-status-text"
            style={{
              color: '#FFFFFF',
              fontSize: '0.92rem',
              fontWeight: 600,
              opacity: 0.95,
              display: 'block'
            }}
          >
            {transferProgress.status}
          </span>
        </div>
      )}

      {/* 3. Main Dashboard Layout: Left Column (Queue & Inputs) | Right Column (Connected Peers) */}
      <div className="dashboard-grid-layout">
        
        {/* Left Column: Uploads, Sending Queue, and Received Files */}
        <div className="dashboard-left-col">
          


          {/* Sending Queue Area - ALWAYS VISIBLE with Drag & Drop */}
          <div 
            className={`glass-panel queue-panel ${isQueueDragging ? 'drag-active' : ''}`}
            onDragOver={(e) => {
              e.preventDefault();
              setIsQueueDragging(true);
            }}
            onDragLeave={(e) => {
              e.preventDefault();
              setIsQueueDragging(false);
            }}
            onDrop={(e) => {
              e.preventDefault();
              setIsQueueDragging(false);
              if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                addFilesToQueue(e.dataTransfer.files);
              }
            }}
          >
            {/* Hidden file input for clicking to browse from queue */}
            <input 
              type="file" 
              multiple
              ref={queueFileInputRef}
              className="file-input"
              onChange={(e) => {
                if (e.target.files && e.target.files.length > 0) {
                  const files = Array.from(e.target.files);
                  addFilesToQueue(files);
                  e.target.value = '';
                }
              }}
            />

            <div className="queue-header-row">
              <div className="queue-title-wrap">
                <div className="queue-badge-count">
                  <Layers size={18} />
                  <span>Sending Queue ({queue.length})</span>
                </div>
                {queue.length > 0 && (
                  <span className="queue-total-size">
                    Total: <strong>{formatBytes(totalQueueSize)}</strong>
                  </span>
                )}
              </div>

              {queue.length > 0 && (
                <div className="queue-header-actions">
                  <button 
                    type="button" 
                    onClick={clearQueue} 
                    className="clear-link"
                    title="Remove all items"
                  >
                    Clear Queue
                  </button>
                  <button 
                    type="button" 
                    onClick={handleOpenRecipientModal} 
                    disabled={isSendingQueue}
                    className="action-btn send-queue-btn"
                  >
                    <Send size={16} /> Send All ({queue.length})
                  </button>
                </div>
              )}
            </div>

            {/* Empty state when queue has 0 items */}
            {queue.length === 0 ? (
              <div 
                className={`queue-empty-state ${isQueueDragging ? 'drag-active' : ''}`}
                onClick={() => queueFileInputRef.current?.click()}
                title="Drop files here or click to browse"
              >
                <div className="empty-state-icon-wrap">
                  <Upload size={28} className={isQueueDragging ? 'spin-slow' : ''} color="var(--accent-color)" />
                </div>
                <p>
                  <strong>Drop files here</strong> to add to sending queue, or click to browse
                </p>
                <span className="queue-empty-sub">You can also select files or paste text in the box above</span>
              </div>
            ) : (
              <>
                {/* Size Warning Banner if queue exceeds safe limit */}
                {isOverLimit && (
                  <div className="queue-size-warning">
                    <AlertTriangle size={20} className="warning-icon" />
                    <div>
                      <strong>High Transfer Size Warning:</strong> Total queued size ({formatBytes(totalQueueSize)}) exceeds the safe browser limit of 2 GB. Devices with low RAM may crash or terminate the transfer.
                    </div>
                  </div>
                )}

                {/* Queued Items List */}
                <div className="queue-items-list">
                  {queue.map((item, index) => (
                    <div key={item.id} className="queue-item-card">
                      <div className="queue-item-top">
                        <div className="queue-item-info">
                          <div className="queue-icon-box">
                            {item.isText ? <FileText size={20} color="#60a5fa" /> : <File size={20} color="var(--accent-color)" />}
                          </div>
                          <div className={`queue-file-details ${editingItemId === item.id ? 'editing' : ''}`}>
                            {editingItemId === item.id ? (
                              <form 
                                className="queue-rename-form"
                                onSubmit={(e) => {
                                  e.preventDefault();
                                  handleSaveRename(item.id);
                                }}
                              >
                                <span className="queue-rename-index">{index + 1}.</span>
                                <div className="queue-rename-input-wrap">
                                  <input
                                    type="text"
                                    className="queue-rename-input"
                                    value={editingItemBaseName}
                                    onChange={(e) => setEditingItemBaseName(e.target.value)}
                                    onKeyDown={(e) => {
                                      if (e.key === 'Escape') {
                                        handleCancelRename();
                                      }
                                    }}
                                    placeholder="Enter file name..."
                                    autoFocus
                                  />
                                  {editingItemExt && (
                                    <span className="queue-rename-ext-badge" title="File extension is locked">
                                      {editingItemExt}
                                    </span>
                                  )}
                                </div>
                                <button 
                                  type="submit" 
                                  className="queue-rename-action-btn save-btn" 
                                  title="Save file name (Enter)"
                                >
                                  <Check size={13} />
                                  <span>Save</span>
                                </button>
                                <button 
                                  type="button" 
                                  className="queue-rename-action-btn cancel-btn"
                                  onClick={handleCancelRename}
                                  title="Cancel rename (Esc)"
                                >
                                  <X size={13} />
                                </button>
                              </form>
                            ) : (
                              <div className="queue-file-name-row">
                                <span className="queue-file-name" title={item.name}>
                                  {index + 1}. {item.name}
                                </span>
                                <button 
                                  type="button" 
                                  className="queue-rename-btn"
                                  onClick={() => handleStartRename(item)}
                                  title="Rename this file before sending"
                                >
                                  <Pencil size={12} />
                                  <span>Rename</span>
                                </button>
                                {item.isText && (
                                  <button 
                                    type="button" 
                                    className="queue-rename-btn queue-edit-content-btn"
                                    onClick={() => handleStartEditText(item)}
                                    title="Edit the content of this text note"
                                  >
                                    <FileText size={12} />
                                    <span>Edit Text</span>
                                  </button>
                                )}
                              </div>
                            )}
                            <div className="queue-file-meta">
                              {formatBytes(item.size)} {item.isText ? '• Text Snippet' : `• ${item.type || 'File'}`}
                            </div>

                            {/* Inline text content editor */}
                            {editingTextId === item.id ? (
                              <div className="queue-text-editor-box">
                                <textarea
                                  className="queue-text-editor-textarea"
                                  value={editingTextContent}
                                  onChange={(e) => setEditingTextContent(e.target.value)}
                                  rows={4}
                                  placeholder="Edit note text..."
                                  autoFocus
                                />
                                <div className="queue-text-editor-actions">
                                  <button 
                                    type="button" 
                                    className="queue-rename-action-btn save-btn"
                                    onClick={() => handleSaveEditText(item.id)}
                                  >
                                    <Check size={13} />
                                    <span>Save Content</span>
                                  </button>
                                  <button 
                                    type="button" 
                                    className="queue-rename-action-btn cancel-btn"
                                    onClick={handleCancelEditText}
                                  >
                                    <X size={13} />
                                    <span>Cancel</span>
                                  </button>
                                </div>
                              </div>
                            ) : (
                              item.isText && item.textContent && (
                                <div 
                                  className="queue-text-preview" 
                                  onClick={() => handleStartEditText(item)}
                                  title="Click to edit content"
                                >
                                  <span>“{item.textContent.length > 100 ? item.textContent.slice(0, 100) + '...' : item.textContent}”</span>
                                  <span className="queue-text-preview-hint">(click to edit)</span>
                                </div>
                              )
                            )}
                          </div>
                        </div>

                        <button 
                          type="button" 
                          className="queue-remove-btn"
                          onClick={() => removeFromQueue(item.id)}
                          title="Remove item"
                        >
                          <X size={16} />
                        </button>
                      </div>

                      {/* Caption Input for each file */}
                      <div className="queue-caption-row">
                        <input
                          type="text"
                          className="queue-caption-input"
                          placeholder="Add an optional caption or note for this item..."
                          value={item.caption}
                          onChange={(e) => updateQueueCaption(item.id, e.target.value)}
                        />
                      </div>
                    </div>
                  ))}
                </div>

                {/* Drop more files footer when queue has items */}
                <div 
                  className={`queue-drop-footer ${isQueueDragging ? 'drag-active' : ''}`}
                  onClick={() => queueFileInputRef.current?.click()}
                  title="Drop more files here or click to browse"
                >
                  <Plus size={15} /> Drop more files here or click to browse
                </div>
              </>
            )}
          </div>

          {/* Received Files Area */}
          {receivedFiles.length > 0 && (
            <div className="glass-panel transfer-area">
              <div className="transfer-header-row">
                <h3 style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <Download size={22} className="accent-icon" />
                  Received Files & Text ({receivedFiles.length})
                </h3>
                <div className="transfer-header-actions" style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
                  <button 
                    type="button"
                    onClick={handleDownloadAll} 
                    className="action-btn download-all-btn"
                    disabled={isZipping}
                    title="Download all received items as a ZIP archive"
                  >
                    <Archive size={16} />
                    <span>{isZipping ? 'Archiving ZIP...' : `Download All (${receivedFiles.length})`}</span>
                  </button>
                  <button 
                    onClick={() => setReceivedFiles([])} 
                    className="clear-link"
                    title="Clear received list"
                  >
                    Clear all
                  </button>
                </div>
              </div>

              <div className="transfer-list">
                {receivedFiles.map((file) => (
                  <div key={file.id} className="transfer-item">
                    <div className="transfer-meta-row">
                      <div className="transfer-info">
                        <div className="file-icon-box">
                          {file.isText ? <FileText size={22} color="#60a5fa" /> : <File size={22} color="var(--accent-color)" />}
                        </div>
                        <div>
                          <div className="transfer-name">{file.name}</div>
                          <div className="transfer-size">
                            {formatBytes(file.size)} • from {file.senderName} • {file.time}
                          </div>
                        </div>
                      </div>

                      <div className="received-actions">
                        {file.textPreview && (
                          <button 
                            type="button"
                            onClick={() => handleCopyText(file.textPreview, file.id)}
                            className="action-btn small-btn secondary"
                          >
                            {copiedId === file.id ? <Check size={14} color="#10b981" /> : <Copy size={14} />}
                            {copiedId === file.id ? 'Copied!' : 'Copy Text'}
                          </button>
                        )}
                        <a 
                          href={file.data} 
                          download={file.name}
                          className="action-btn download-btn"
                        >
                          <Download size={16} /> Save to Device
                        </a>
                      </div>
                    </div>

                    {/* Display Caption if provided */}
                    {file.caption && (
                      <div className="received-caption-bubble">
                        <MessageSquare size={14} className="caption-icon" />
                        <span>{file.caption}</span>
                      </div>
                    )}

                    {/* Text preview */}
                    {file.textPreview && (
                      <div className="received-text-box">
                        <pre>{file.textPreview}</pre>
                      </div>
                    )}

                    {/* Image preview */}
                    {typeof file.type === 'string' && file.type.startsWith('image/') && (
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

        {/* Right Column: Connected Peers Sidebar */}
        <aside className="dashboard-right-col">
          <div className="glass-panel peers-sidebar-panel">
            <div className="peers-sidebar-header">
              <div>
                <h3>Connected Peers</h3>
                <p className="subtitle">
                  {networkInfo.isCustom 
                    ? '  ' 
                    : ' '}
                </p>
              </div>
              <div className="peer-counter">
                <strong>{peers.length} {peers.length === 1 ? 'device' : 'devices'} ready</strong>
              </div>
            </div>

            {peers.length === 0 ? (
              <div className="no-peers glass-panel">
                <div className="pulse-circle">
                  <Smartphone size={32} />
                </div>
                <h4>Waiting for nearby devices...</h4>
                <p>
                  Open this site on your mobile phone or laptop.
                  {networkInfo.isCustom 
                    ? ` Enter code "${networkInfo.room.replace('custom_', '')}" to connect.` 
                    : ' Devices on the same Wi-Fi discover each other automatically.'}
                </p>
              </div>
            ) : (
              <div className="peers-sidebar-list">
                {peers.map(peer => (
                  <div 
                    key={peer.id} 
                    className="peer-sidebar-card glass-panel"
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
                      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                        addFilesToQueue(e.dataTransfer.files);
                      }
                    }}
                  >
                    <div className="peer-card-top-row">
                      <div className="peer-sidebar-avatar">
                        {peer.deviceType === 'mobile' ? (
                          <Smartphone size={24} />
                        ) : peer.deviceType === 'tablet' ? (
                          <Tablet size={24} />
                        ) : (
                          <Monitor size={24} />
                        )}
                      </div>
                      <div className="peer-meta-text">
                        <div className="peer-name">{peer.name}</div>
                        <div className="peer-status-row">
                          <span className="peer-type-tag">{peer.deviceType}</span>
                          <span className="peer-online-tag">
                            <span className="status-dot online"></span> Ready
                          </span>
                        </div>
                      </div>
                    </div>

                    {/* Data Limit Badge for device type */}
                    <div className="peer-data-limit-box" title="Maximum recommended file size to transfer without exhausting this device's browser memory">
                      <HardDrive size={13} className="limit-icon" />
                      <span>{getDeviceDataLimit(peer.deviceType)}</span>
                    </div>

                    <p className="drag-hint">
                      drop files here to add to queue
                    </p>
                  </div>
                ))}
              </div>
            )}
          </div>
        </aside>

      </div>

      {/* Recipient Selection Popup Modal */}
      {showRecipientModal && (
        <div className="modal-backdrop" onClick={() => setShowRecipientModal(false)}>
          <div className="modal-content glass-panel" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div className="modal-title-wrap">
                <Send size={20} color="var(--accent-color)" />
                <h3>Select Recipients</h3>
              </div>
              <button 
                type="button" 
                className="modal-close-btn"
                onClick={() => setShowRecipientModal(false)}
              >
                <X size={20} />
              </button>
            </div>

            <p className="modal-subtitle">
              Choose which connected device(s) should receive your {queue.length} queued item(s) ({formatBytes(totalQueueSize)}).
            </p>

            {/* Peer Search Bar */}
            <div className="modal-search-bar">
              <Search size={16} className="search-icon" />
              <input
                type="text"
                placeholder="Search peers by name or device type..."
                value={recipientSearch}
                onChange={(e) => setRecipientSearch(e.target.value)}
                className="modal-search-input"
                autoFocus
              />
              {recipientSearch && (
                <button 
                  type="button" 
                  className="search-clear-btn" 
                  onClick={() => setRecipientSearch('')}
                >
                  <X size={14} />
                </button>
              )}
            </div>

            {/* Quick Toggle Select All */}
            <div className="modal-selection-controls">
              <button 
                type="button" 
                className="select-all-btn"
                onClick={toggleSelectAllPeers}
              >
                {selectedPeerIds.length === filteredPeers.length && filteredPeers.length > 0 ? (
                  <>
                    <CheckSquare size={16} /> Deselect All
                  </>
                ) : (
                  <>
                    <Square size={16} /> Select All ({filteredPeers.length})
                  </>
                )}
              </button>
              <span className="selected-counter">
                {selectedPeerIds.length} of {peers.length} selected
              </span>
            </div>

            {/* Peers List with Checkboxes */}
            <div className="modal-peers-list">
              {filteredPeers.length === 0 ? (
                <div className="modal-no-results">
                  No connected devices match "{recipientSearch}"
                </div>
              ) : (
                filteredPeers.map(peer => {
                  const isSelected = selectedPeerIds.includes(peer.id);
                  return (
                    <div 
                      key={peer.id} 
                      className={`modal-peer-item ${isSelected ? 'selected' : ''}`}
                      onClick={() => toggleSelectPeer(peer.id)}
                    >
                      <div className="peer-item-left">
                        <div className="modal-checkbox">
                          {isSelected ? <CheckSquare size={18} color="var(--accent-color)" /> : <Square size={18} color="#94a3b8" />}
                        </div>
                        <div className="modal-peer-avatar">
                          {peer.deviceType === 'mobile' ? (
                            <Smartphone size={20} />
                          ) : peer.deviceType === 'tablet' ? (
                            <Tablet size={20} />
                          ) : (
                            <Monitor size={20} />
                          )}
                        </div>
                        <div>
                          <div className="modal-peer-name">{peer.name}</div>
                          <div className="modal-peer-type">{peer.deviceType} • {getDeviceDataLimit(peer.deviceType)}</div>
                        </div>
                      </div>
                      <div className="modal-peer-status">
                        <span className="status-dot online"></span> Ready
                      </div>
                    </div>
                  );
                })
              )}
            </div>

            {/* Modal Footer */}
            <div className="modal-footer">
              <button 
                type="button" 
                className="room-btn secondary"
                onClick={() => setShowRecipientModal(false)}
              >
                Cancel
              </button>
              <button 
                type="button" 
                className="action-btn send-btn"
                disabled={selectedPeerIds.length === 0 || isSendingQueue}
                onClick={() => startSendingQueue(selectedPeerIds)}
              >
                <Send size={16} /> Send to {selectedPeerIds.length} Recipient{selectedPeerIds.length === 1 ? '' : 's'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add / Paste Text Modal Dialog */}
      {showTextModal && (
        <div className="modal-backdrop" onClick={() => setShowTextModal(false)}>
          <div className="modal-content glass-panel" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div className="modal-title-wrap">
                <MessageSquare size={20} color="var(--accent-color)" />
                <h3>Add / Paste Text Note</h3>
              </div>
              <button 
                type="button" 
                className="modal-close-btn"
                onClick={() => setShowTextModal(false)}
              >
                <X size={20} />
              </button>
            </div>
            
            <div style={{ margin: '1rem 0' }}>
              <div className="paste-text-header" style={{ marginBottom: '0.65rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span className="paste-label" style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
                  <MessageSquare size={16} /> Type or Paste Text
                </span>
                <button 
                  type="button" 
                  className="paste-clipboard-btn"
                  onClick={handlePasteFromClipboard}
                  title="Read clipboard text directly"
                >
                  <Clipboard size={14} /> Paste from Clipboard
                </button>
              </div>
              <textarea
                ref={textareaRef}
                className="paste-textarea"
                placeholder="Type or paste text, links, or notes here... Press Ctrl+Enter to add to queue."
                value={textInput}
                onChange={(e) => setTextInput(e.target.value)}
                onKeyDown={async (e) => {
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    await handleAddTextInput();
                    setShowTextModal(false);
                  }
                }}
                rows={5}
                autoFocus
              />
            </div>
            
            <div className="modal-footer" style={{ marginTop: '0', display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
              <button 
                type="button" 
                className="room-btn secondary"
                onClick={() => setShowTextModal(false)}
              >
                Cancel
              </button>
              <button 
                type="button" 
                className="action-btn send-btn"
                onClick={async () => {
                  await handleAddTextInput();
                  setShowTextModal(false);
                }}
              >
                <Plus size={16} /> Add to Queue
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}

export default App;
