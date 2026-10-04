# ⚡ Transferase

> **Fast, zero-setup, local and cloud file sharing between phones, tablets, and computers.**

Transferase is an all-in-one file transfer solution supporting:
- 📱 **Android Native App (Local Server Mode)**: Your phone acts as the Wi-Fi/Hotspot host and web server — transfer files between PC and Phone with **0 internet connection**!
- 🌐 **Online Web Relay**: Host on Render/Railway for instant cross-device sharing via Room Codes or local IP pairing.
- 💻 **Local PC Server**: Run on your computer over local Wi-Fi.

---

## 📥 How to Download & Install the Android APK on Your Phone

The pre-compiled APK is included directly in this repository:
- **File:** [`transferase-mobile.apk`](transferase-mobile.apk) *(12.5 MB)*

### Method 1: Download from GitHub on your Phone (Easiest)
1. Open this GitHub repository on your phone's browser (Chrome, Samsung Internet, etc.):
   ```
   https://github.com/ganguly71/Transferase
   ```
2. Tap on [`transferase-mobile.apk`](transferase-mobile.apk).
3. Tap **Download** or **View Raw** to download the APK file directly to your phone.
4. Once downloaded, open your phone's notification panel or **Downloads** folder and tap `transferase-mobile.apk` to install!
   *(If prompted, tap **Settings** and enable "Allow from this source" / "Install Unknown Apps").*

---

### Method 2: Transfer from PC via USB Cable
1. Connect your Android phone to your PC with a USB cable.
2. On your phone, set the USB mode to **File Transfer (MTP)**.
3. On your PC, open File Explorer, find `transferase-mobile.apk`, and copy it into your phone's **Download** or **Internal Storage** folder.
4. On your phone, open your **Files** / **File Manager** app, go to **Downloads**, and tap `transferase-mobile.apk` to install.

---

### Method 3: One-Click Quick Download via Local Wi-Fi
If your PC and phone are currently on the same Wi-Fi network, you can serve the APK instantly from your computer terminal:
```bash
# In the project directory, run:
npx serve -l 8000
# OR if Python is installed:
python -m http.server 8000
```
Then on your phone's browser, open `http://<your-pc-ip>:8000/transferase-mobile.apk` to download directly!

---

## 🚀 How to Use: Phone as Local Server (100% Offline)

Transfer files between your PC and Phone even with **no internet, no router, and no mobile data**:

```
 ┌──────────────────────┐                     ┌──────────────────────┐
 │    Android Phone     │                     │     PC / Laptop      │
 │  (Hotspot + Server)  │ ~ ~ ~ Wi-Fi ~ ~ ~ > │  (Web Browser Only)  │
 │ http://192.168.43.1  │                     │ http://192.168.43.1  │
 └──────────────────────┘                     └──────────────────────┘
```

1. **Enable Mobile Hotspot** on your Android phone *(cellular data does NOT need to be on)*.
2. **Connect your PC** to your phone's Wi-Fi hotspot.
3. Open the **Transferase** app on your phone and tap **START SERVER**.
4. The app shows your address (e.g. `http://192.168.43.1:4000`) and a **QR Code**.
5. On your PC, open Chrome, Edge, or Firefox and go to `http://192.168.43.1:4000`:
   - **Send to Phone:** Drag & drop files on your PC browser and click **"⚡ Send to Phone"**. Files save directly to your phone's `Downloads/Transferase/` directory!
   - **Download from Phone:** Tap **"➕ Add Files"** in the phone app to select photos, videos, or documents; they appear on your PC browser with 1-click **Download** buttons!

---

## 🌐 Other Running Modes

### 1. Online Deployment (Render.com / Cloud)
Host this app online so you can transfer files anywhere over 4G/5G or separate Wi-Fi networks:
1. Connect your repository to [Render.com](https://render.com).
2. **Build Command:**
   ```bash
   npm install --prefix client && npm run build --prefix client && npm install --prefix server
   ```
3. **Start Command:**
   ```bash
   node server/index.js
   ```
4. Open the Render URL on both devices and connect via Room Code or automatic local network pairing!

### 2. Run Locally on PC
Double-click `start_web_transfer.bat` or run:
```bash
# Start backend relay
cd server && npm start

# Start frontend dev server
cd client && npm run dev
```

---

## 📂 Project Structure

```
├── transferase-mobile.apk    # Pre-built ready-to-install Android APK
├── ANDROID_APP_GUIDE.md      # Detailed Android setup and troubleshooting guide
├── android-app/              # Native Android App (Kotlin, Jetpack Compose, Embedded Server)
│   ├── app/src/main/java/    # Server, UI, NetworkUtils, and Service logic
│   └── app/src/main/assets/  # Bundled Transferase web client
├── client/                   # React + Vite frontend application
├── server/                   # Node.js + Express + Socket.io signaling server
└── start_web_transfer.bat    # 1-click local PC startup script
```

---

## 🔒 Permissions & Privacy
- All transfers in Local/Hotspot mode happen **strictly over your local Wi-Fi radio waves**.
- No data is uploaded to third-party cloud servers or logs.
- Files uploaded to the phone are placed in standard `Downloads/Transferase/` for easy access.
