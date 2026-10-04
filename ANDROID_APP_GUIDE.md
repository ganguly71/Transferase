# Transferase Mobile Android APK Guide

**Transferase Mobile** allows your Android phone to act as a **Local File & Web Server**. 
When running, your phone hosts the complete Transferase web app so any PC, laptop, or other device can connect by simply typing your phone's IP address into their browser—**100% offline, with 0 internet required!**

---

## 📦 APK Location
The ready-to-install Android APK is available right in your project root:
- [`transferase-mobile.apk`](file:///c:/Users/adity/Downloads/web-transfer/transferase-mobile.apk)
- Or inside `android-app/app/build/outputs/apk/debug/app-debug.apk`

---

## 🚀 How to Install & Use (Step-by-Step)

### Step 1: Install APK on your Android Phone
1. Transfer [`transferase-mobile.apk`](file:///c:/Users/adity/Downloads/web-transfer/transferase-mobile.apk) to your Android phone (via USB cable, Bluetooth, or messaging app).
2. On your phone, tap the `.apk` file to install it.
   *(If prompted, allow "Install from Unknown Sources" for your file manager).*
3. Open the **Transferase** app.

### Step 2: Connect Phone & PC (No Internet Required)
Choose one of the following:
- **Method A (Mobile Hotspot - Recommended when away from Wi-Fi):**
  1. Turn on **Mobile Hotspot** on your phone (you do **not** need cellular data turned on).
  2. On your PC, connect to your phone's Wi-Fi Hotspot.
- **Method B (Home/Office Wi-Fi):**
  - Just ensure both your phone and PC are connected to the same Wi-Fi router.

### Step 3: Start Server & Connect from PC
1. In the Transferase app on your phone, tap **START SERVER**.
2. The app will display your local address, e.g.:
   ```
   http://192.168.43.1:4000
   ```
3. On your PC, open **Google Chrome, Microsoft Edge, or Firefox** and enter that exact URL:
   - The full Transferase web interface will load directly from your phone!
   - You can also tap **"📷 QR Code"** in the app to scan the URL with any camera.

---

## 📁 How File Transfers Work

### PC ➡️ Phone (Upload Files to Phone)
1. In your PC's browser at `http://192.168.43.1:4000`:
2. Drag & drop files into the **Sending Queue** or click to browse.
3. Click the green **"⚡ Send to Phone"** button.
4. Files stream directly over local Wi-Fi to your phone and are automatically saved in:
   ```
   Downloads/Transferase/
   ```
5. On the phone screen in the app, the received files appear instantly with **Open** and **Share** buttons!

### Phone ➡️ PC (Share Files to PC)
1. In the Transferase app on your phone:
2. Tap **"➕ Add Files"** to pick photos, videos, or documents from your phone.
3. On your PC's browser, the files appear immediately under **"Files Available from Phone"**.
4. Click **Download** to save them directly to your PC at full Wi-Fi speed.

---

## ⚡ Key Features
- **Zero Internet Required**: Data travels purely between phone and PC over the local radio link.
- **Background Foreground Service**: Keeps the server running even if you lock the phone or switch apps.
- **Native Material 3 UI**: Clean server toggle, live pulse indicator, one-tap copy, and QR code generator.
- **Blazing Fast**: Transfers at full Wi-Fi speed (50–100+ MB/s).
