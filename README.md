# VC Bridge — real voice chat for Minecraft Bedrock

Three parts:

| Part | What it does |
|---|---|
| `addon/` | Bedrock add-on (`.mcaddon`): menus, HUD mic button. English by default (also Thai, Spanish). |
| `android/` | Android app (APK): real microphone, local server for the game, online rooms. |
| `server/` | Node.js relay server: rooms, 6-character codes, member limit, kick / ban. |

```
Minecraft + add-on  <-- /connect 127.0.0.1:3000 -->  Android app  <-- wss:// -->  server  <--> friends' apps
```

## 1. Deploy the server (once)
Needs Node 18+. Any host works (Render, Railway, Fly.io, a VPS). On Render/Railway use `server/` as the
root, start command `npm start`. Use the `wss://your-host` URL (the host provides TLS).

Env vars (optional): `PORT`, `MAX_ROOM_SIZE` (default 50), `MAX_ROOMS` (default 1000).

## 2. Build with GitHub
1. Push this folder to a GitHub repo.
2. (Optional) Settings > Secrets and variables > Actions > **Variables** > add `VC_SERVER_URL` = `wss://your-host`
   so the APK has your server pre-filled.
3. Actions > **Build** > download `VCBridge-apk` (APK) and `VCBridge-addon` (`.mcaddon`).
   Tag `v1.0.0` to publish both as a GitHub Release.

## 3. Play
1. Install the APK, tap **Start** (allow microphone). Enter the server URL if it is not pre-filled.
2. Import the `.mcaddon`, enable both packs and the world's **Beta APIs / scripting** experiments if asked.
3. In the game (cheats on, *Require Encrypted WebSockets* off) run: `/connect 127.0.0.1:3000`
4. Open the menu: right-click a clock, tap the HUD mic button while sneaking, or `/scriptevent vc:menu`.
5. **Create room** (choose max members) -> share the 6-character code. Friends use **Join room**.
   The creator can kick / ban members (in the app or in the game menu). Ban uses device id + IP.

## Notes / limits
- Minecraft and the app must be on the same phone (127.0.0.1). Use headphones to avoid echo.
- Audio is 16 kHz mu-law (about 16 KB/s per active speaker), sent only while you talk.
- Voice is room-wide (not proximity based).
- Add a language: `addon/behavior_pack/scripts/main.js` (`T`, `LANGS`, `LANG_NAMES`) and `android/.../I18n.kt`.
- The add-on talks to the app by sending a hidden `titleraw` starting with `VC|`. If your game build does not
  forward it, typing `VC|<yourname>|mute|0` in chat works as a fallback for testing.
- Debug APK is signed with the debug key (fine for sideloading).

## ภาษาไทย (สรุป)
1. ติดตั้งเซิร์ฟเวอร์จากโฟลเดอร์ `server/` ลงโฮสต์ แล้วได้ URL แบบ `wss://...`
2. อัปโหลดโปรเจกต์ขึ้น GitHub แล้วไปที่ Actions > Build เพื่อโหลด APK และ `.mcaddon`
3. เปิดแอป กด **เริ่ม** ใส่ URL เซิร์ฟเวอร์ แล้วในเกมพิมพ์ `/connect 127.0.0.1:3000`
4. สร้างห้อง เลือกจำนวนคนสูงสุด ส่งรหัส 6 ตัวให้เพื่อน เจ้าของห้องเตะ/แบนได้
5. ข้อความทั้งในแอปและม็อดเป็นภาษาอังกฤษโดยค่าเริ่มต้น เปลี่ยนภาษาได้ (อังกฤษ/ไทย/สเปน)
