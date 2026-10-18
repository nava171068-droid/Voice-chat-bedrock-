"use strict";
// VC Bridge relay server: rooms, 6-char codes, member limit, kick / ban, audio relay.
const http = require("http");
const crypto = require("crypto");
const { WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT) || 8080;
const MAX_ROOM = Math.min(Number(process.env.MAX_ROOM_SIZE) || 50, 200); // hard cap per room
const MAX_ROOMS = Number(process.env.MAX_ROOMS) || 1000;
const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

const rooms = new Map(); // code -> room
let nextNum = 1;

const httpServer = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("VC Bridge server OK\n");
});
const wss = new WebSocketServer({ server: httpServer, maxPayload: 8192, perMessageDeflate: false });

const send = (ws, obj) => { if (ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const cleanName = (n) =>
  String(n || "").replace(/[^\p{L}\p{N} _.-]/gu, "").trim().slice(0, 20) || "Player";
const allocNum = () => { const n = nextNum; nextNum = (nextNum % 65000) + 1; return n; };

function genCode() {
  let c;
  do {
    c = "";
    for (let i = 0; i < 6; i++) c += CODE_CHARS[crypto.randomInt(CODE_CHARS.length)];
  } while (rooms.has(c));
  return c;
}

function broadcastRoom(r) {
  const members = [...r.members.values()].map((m) => ({ u: m.num, n: m.name }));
  for (const m of r.members.values()) {
    send(m.ws, { t: "room", code: r.code, max: r.max, owner: r.owner, me: m.num, members });
  }
}

function leaveRoom(c) {
  const r = c.room;
  if (!r) return;
  r.members.delete(c.num);
  c.room = null;
  c.lastAudio = 0;
  if (r.members.size === 0) { rooms.delete(r.code); return; }
  if (r.owner === c.num) r.owner = r.members.keys().next().value; // hand over ownership
  broadcastRoom(r);
}

function joinRoom(c, r) {
  c.room = r;
  r.members.set(c.num, c);
  broadcastRoom(r);
}

function onText(c, raw) {
  let m;
  try { m = JSON.parse(raw); } catch { return; }
  if (!m || typeof m.t !== "string") return;

  if (m.t === "hello") {
    c.id = String(m.id || "").slice(0, 64) || crypto.randomUUID();
    c.name = cleanName(m.name);
    c.hello = true;
    return;
  }
  if (!c.hello) return send(c.ws, { t: "err", k: "no_hello" });

  switch (m.t) {
    case "name": {
      c.name = cleanName(m.n);
      if (c.room) broadcastRoom(c.room);
      break;
    }
    case "create": {
      if (rooms.size >= MAX_ROOMS) return send(c.ws, { t: "err", k: "busy" });
      leaveRoom(c);
      const max = Math.max(2, Math.min(MAX_ROOM, parseInt(m.max, 10) || 8));
      const r = { code: genCode(), max, owner: c.num, members: new Map(), banId: new Set(), banIp: new Set(), spk: "" };
      rooms.set(r.code, r);
      joinRoom(c, r);
      break;
    }
    case "join": {
      const code = String(m.code || "").toUpperCase().trim();
      const r = rooms.get(code);
      if (!r) return send(c.ws, { t: "err", k: "no_room" });
      if (c.room === r) return broadcastRoom(r);
      if (r.banId.has(c.id) || r.banIp.has(c.ip)) return send(c.ws, { t: "err", k: "banned" });
      if (r.members.size >= r.max) return send(c.ws, { t: "err", k: "full" });
      leaveRoom(c);
      joinRoom(c, r);
      break;
    }
    case "leave": {
      leaveRoom(c);
      send(c.ws, { t: "left" });
      break;
    }
    case "kick":
    case "ban": {
      const r = c.room;
      if (!r || r.owner !== c.num) return send(c.ws, { t: "err", k: "not_owner" });
      const target = r.members.get(Number(m.u));
      if (!target || target === c) return send(c.ws, { t: "err", k: "bad_target" });
      if (m.t === "ban") { r.banId.add(target.id); r.banIp.add(target.ip); }
      send(target.ws, { t: m.t === "ban" ? "banned" : "kicked" });
      leaveRoom(target);
      break;
    }
    case "setmax": {
      const r = c.room;
      if (!r || r.owner !== c.num) return send(c.ws, { t: "err", k: "not_owner" });
      r.max = Math.max(r.members.size, 2, Math.min(MAX_ROOM, parseInt(m.max, 10) || r.max));
      broadcastRoom(r);
      break;
    }
  }
}

function onAudio(c, data) {
  const r = c.room;
  if (!r || data.length < 2 || data.length > 1500) return;
  const now = Date.now();
  if (now - c.win > 1000) { c.win = now; c.frames = 0; }
  if (++c.frames > 100) return; // ~100 frames/s max per client
  c.lastAudio = now;
  const out = Buffer.allocUnsafe(data.length + 2);
  out.writeUInt16BE(c.num, 0);
  data.copy(out, 2);
  for (const m of r.members.values()) {
    if (m !== c && m.ws.readyState === 1 && m.ws.bufferedAmount < 65536) m.ws.send(out, { binary: true });
  }
}

wss.on("connection", (ws, req) => {
  const ip = String(req.headers["x-forwarded-for"] || req.socket.remoteAddress || "").split(",")[0].trim();
  const c = { ws, num: allocNum(), id: null, name: "Player", room: null, ip, hello: false, lastAudio: 0, win: 0, frames: 0 };
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });
  ws.on("message", (data, isBinary) => {
    if (isBinary) onAudio(c, Buffer.isBuffer(data) ? data : Buffer.concat(data));
    else onText(c, data.toString());
  });
  ws.on("close", () => leaveRoom(c));
  ws.on("error", () => {});
});

// "who is speaking" indicator, sent only when it changes
setInterval(() => {
  const now = Date.now();
  for (const r of rooms.values()) {
    const s = [];
    for (const m of r.members.values()) if (now - m.lastAudio < 350) s.push(m.num);
    const key = s.join(",");
    if (key !== r.spk) {
      r.spk = key;
      for (const m of r.members.values()) send(m.ws, { t: "speak", s });
    }
  }
}, 150);

// drop dead connections
setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

httpServer.listen(PORT, () => console.log(`VC Bridge server listening on :${PORT} (max room size ${MAX_ROOM})`));
