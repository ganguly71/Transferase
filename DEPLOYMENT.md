# Tranferase Online - Peer-to-Peer Web File Relay

An online web application for instant, zero-setup file sharing between mobile phones, tablets, and computers.

---

## 🌟 How It Works
1. **Auto-Match on Same Wi-Fi:** Devices on the same Wi-Fi network are automatically matched via public IP hash. No registration or setup is required.
2. **Room Code Pairing (Cross-Network / Cellular Data):** If one mobile device is using 4G/5G mobile data and another is on home Wi-Fi, simply enter the same room code (e.g. `9922`) to connect immediately.
3. **No App or PC Required:** Anyone on mobile just opens the link in their web browser (Chrome, Safari, Firefox), taps to send or pick a file, and downloads it directly.

---

## 🚀 Free 1-Click Deployment (Hosting Online)

You can host this entire app completely free on cloud platforms like **Render**, **Railway**, or **Fly.io**.

### Option 1: Deploy on Render.com (Recommended - Free)
1. Push this project folder to your GitHub account.
2. Go to [Render.com](https://render.com) and click **New +** -> **Web Service**.
3. Connect your repository.
4. Set the following build settings:
   - **Environment:** `Node`
   - **Build Command:**
     ```bash
     npm install --prefix client && npm run build --prefix client && npm install --prefix server
     ```
   - **Start Command:**
     ```bash
     node server/index.js
     ```
5. Click **Create Web Service**. 
6. Render will provide a free public HTTPS URL (e.g. `https://my-transfer-relay.onrender.com`).
7. Open that link on your phones—it's ready!

---

## 🔄 Dual-Mode Support: Works Both Ways!

The codebase is built to dynamically support **both ways** without changing any code:

### 1. The Localhost Way (Your Original Setup)
- Works offline on your local Wi-Fi without uploading anything to the internet.
- Simply run:
  ```cmd
  start_web_transfer.bat
  ```
  or run `node server/index.js` and `npm run dev -- --host` in `client`.
- Open `http://localhost:5173` on your PC, and `http://<your-pc-ip>:5173` on your phone.
- The app automatically detects local subnets (`192.168.x.x`, `10.x.x.x`, `localhost`) and places all local devices in a shared LAN room immediately.

### 2. The Hosted Online Website Way (No PC Needed)
- Deploy the project to [Render.com](https://render.com), Railway, or Fly.io.
- Visit your website link (e.g. `https://your-app.onrender.com`) on any phone or device anywhere.
- If both phones are on the same Wi-Fi, they are automatically paired. If on cellular mobile data, simply enter the same Room Code.
- Files are transferred directly between devices via the web relay.

---

### Running Production Mode Locally
You can also run the unified production build on your PC by running:
```bash
cd server && node index.js
```
and opening `http://localhost:4000` (or `http://<your-pc-ip>:4000` from your phone).
