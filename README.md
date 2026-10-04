# ⚡ Transferase

> **Fast, zero-setup, local and cloud file sharing between phones, tablets, and computers.**

Transferase is the ultimate, all-in-one file transfer solution designed for lightning-fast sharing across all your devices. Whether you are completely offline in a remote area, on your home Wi-Fi, or across the globe, Transferase has a mode for you.

## ✨ Features
- 🌐 **Global Cloud Relay (transferase.onrender.com)**: Instantly share files across the internet using secure Room Codes, or automatically discover devices on the same Wi-Fi network. No installation required—just open the website!
- 📱 **Android Native App (100% Offline Mode)**: Your phone acts as the host and web server. Transfer files between your PC and phone with **zero internet connection** via Mobile Hotspot.
- 💻 **Local PC Server**: Run the relay server locally on your computer for private, ultra-fast transfers over your home Wi-Fi.
- 📋 **Clipboard Sync**: Instantly share text snippets, links, and code blocks between devices.
- 🎨 **Beautiful UI**: A highly polished, modern, and elegant interface with smooth animations and dynamic layouts.

---

## 🌍 The Main Web App: `transferase.onrender.com` (Recommended)

The easiest and most powerful way to use Transferase is through our hosted web application. There's no need to install anything on your PC or phone—simply use your browser!

### How to use the Online Web App:
1. Open **[transferase.onrender.com](https://transferase.onrender.com)** on both of the devices you want to connect (e.g., your PC and your phone).
2. **Auto-Discovery (Same Wi-Fi)**: If both devices are connected to the same Wi-Fi network, they will automatically discover each other! You'll see the devices instantly pop up on your screen.
3. **Room Codes (Different Networks)**: If your devices are on different networks (e.g., PC on Ethernet, phone on 5G), simply enter a **Room ID** (like "1234") on both devices to instantly pair them securely over the internet.
4. Drag and drop files, paste text, or click to send!

---

## 📱 Installing the Android App (For Offline/Local Use)

If you want to transfer files when you have **no internet connection** or want a dedicated app experience, install the Android app. 

The pre-compiled APK is included directly in this repository:
- **File:** [`transferase-mobile.apk`](transferase-mobile.apk) *(12.5 MB)*

### Installation Steps:
1. Open this GitHub repository on your Android phone's browser (Chrome, Samsung Internet, etc.):
   ```text
   https://github.com/ganguly71/Transferase
   ```
2. Tap on [`transferase-mobile.apk`](transferase-mobile.apk) from the file list.
3. Tap **Download** or **View Raw** to download the APK file directly to your phone.
4. Once downloaded, open your phone's notification panel or **Downloads** folder and tap `transferase-mobile.apk` to install!
   *(If prompted, tap **Settings** and enable "Allow from this source" or "Install Unknown Apps").*

### Using the App (100% Offline Mode):
1. **Enable Mobile Hotspot** on your Android phone *(cellular data does NOT need to be on)*.
2. **Connect your PC** to your phone's Wi-Fi hotspot.
3. Open the **Transferase** app on your phone and tap **START SERVER**.
4. The app shows your address (e.g., `http://192.168.43.1:4000`) and a **QR Code**.
5. On your PC, open Chrome/Edge and navigate to that address to instantly send and receive files directly to your phone's local storage!

---

## 💻 Setting Up the Local PC App (Advanced)

If you prefer to run the entire backend relay server locally on your own PC (for maximum privacy and local network speeds without using the online website):

### Prerequisites:
- **Node.js** (v16 or higher) installed on your PC.

### Quick Start (Windows):
Simply double-click the `start_web_transfer.bat` file in the root directory. This script will automatically install dependencies and start both the backend server and the frontend client.

### Manual Start (Mac/Linux/Windows):
Open your terminal and run the following commands:
```bash
# 1. Start backend relay server
cd server
npm install
npm start
```
In a new terminal:
```bash
# 2. Start frontend web app
cd client
npm install
npm run dev
```
Your local web app will be available at `http://localhost:5173`. Open this URL on your PC, and use your PC's local IP address (e.g., `http://192.168.1.15:5173`) on your phone's browser to connect.

---

## 🔒 Permissions & Privacy
- **Offline/Local Mode**: All transfers happen **strictly over your local Wi-Fi radio waves**. No data leaves your room.
- **Online Mode (`transferase.onrender.com`)**: Files are transferred via secure WebRTC peer-to-peer connections whenever possible, or temporarily relayed through the server. No files are permanently stored on the cloud.
- Files uploaded to the Android app are safely placed in the standard `Downloads/Transferase/` folder for easy access.
