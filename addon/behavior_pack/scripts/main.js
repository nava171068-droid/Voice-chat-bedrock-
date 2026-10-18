// VC Bridge add-on (Bedrock) — menus for the real voice-chat app.
//
// How it works
//   1. The Android app runs a WebSocket server on 127.0.0.1:3000.
//   2. In game run:  /connect 127.0.0.1:3000   (cheats on, "Require Encrypted WebSockets" off)
//   3. App -> add-on:  `scriptevent vc:state {...}` / `scriptevent vc:msg {...}`
//      Add-on -> app:  a hidden titleraw whose text is  VC|<player>|<command>|<args>
//   The app owns the real state (mic, room, members); this script only draws menus and sends commands.
import { world, system, ItemStack, ItemLockMode } from "@minecraft/server";
import { ActionFormData, ModalFormData, FormCancelationReason } from "@minecraft/server-ui";

// ---------- Translations: English is the default (index 0). Add a language = add an object + 2 list entries ----------
const LANGS = ["en", "th", "es"];
const LANG_NAMES = ["English", "ไทย", "Español"];

const T = {
  en: {
    title: "Voice Chat", mic_on: "Microphone on", mic_off: "Microphone muted",
    deaf_on: "Sound disabled", deaf_off: "Sound enabled",
    vols: "Adjust Volumes", settings: "Settings", room: "Room", back: "Back", close: "Close",
    st_title: "Voice Chat Settings", vol: "Voice Chat Volume (%)", gain: "Microphone Gain (dB)",
    gain_warn: "Above 6 dB may distort the audio!", thr: "Voice Activation Threshold (dB)",
    lang: "Language / ภาษา", saved: "Settings saved",
    nc_title: "Voice app not connected",
    nc_body: "1. Open the VC Bridge app and tap Start.\n2. In the game, run:\n§e/connect 127.0.0.1:3000§r\n\n§7Cheats must be on and \"Require Encrypted WebSockets\" must be off.",
    no_server: "The app is not connected to the voice server.",
    create_room: "Create Room", join_room: "Join Room", max_members: "Max members",
    room_code: "Room Code", enter_code: "Enter the room code", join: "Join", create: "Create",
    members: "Members", leave: "Leave room", manage: "Manage members (kick / ban)",
    kick: "Kick", ban: "Ban", you: "(you)", no_others: "No other members",
    m_no_room: "Room not found.", m_banned: "You are banned from this room.", m_full: "The room is full.",
    m_not_owner: "Only the room creator can do that.", m_bad_target: "Invalid player.",
    m_busy: "Server is busy, try again later.", m_kicked: "You were kicked from the room.",
    m_you_banned: "You were banned from the room.", m_no_server: "Not connected to the voice server.",
    m_mic_denied: "Could not start the microphone.", m_no_hello: "Not ready yet, try again.",
  },
  th: {
    title: "แชทเสียง", mic_on: "เปิดไมโครโฟน", mic_off: "ปิดไมโครโฟน",
    deaf_on: "ปิดเสียงฟัง", deaf_off: "เปิดเสียงฟัง",
    vols: "ปรับระดับเสียง", settings: "ตั้งค่า", room: "ห้อง", back: "กลับ", close: "ปิด",
    st_title: "ตั้งค่าแชทเสียง", vol: "ระดับเสียงแชทเสียง (%)", gain: "การขยายเสียงไมค์ (dB)",
    gain_warn: "เกิน 6 dB เสียงอาจเพี้ยน!", thr: "เกณฑ์เปิดไมค์อัตโนมัติ (dB)",
    lang: "ภาษา / Language", saved: "บันทึกการตั้งค่าแล้ว",
    nc_title: "ยังไม่ได้เชื่อมต่อแอปเสียง",
    nc_body: "1. เปิดแอป VC Bridge แล้วกด เริ่ม\n2. ในเกมให้พิมพ์:\n§e/connect 127.0.0.1:3000§r\n\n§7ต้องเปิดโกง และปิด \"Require Encrypted WebSockets\"",
    no_server: "แอปยังไม่ได้เชื่อมต่อเซิร์ฟเวอร์เสียง",
    create_room: "สร้างห้อง", join_room: "เข้าห้อง", max_members: "จำนวนคนสูงสุด",
    room_code: "รหัสห้อง", enter_code: "ใส่รหัสห้อง", join: "เข้าร่วม", create: "สร้าง",
    members: "สมาชิก", leave: "ออกจากห้อง", manage: "จัดการสมาชิก (เตะ / แบน)",
    kick: "เตะ", ban: "แบน", you: "(คุณ)", no_others: "ไม่มีสมาชิกอื่น",
    m_no_room: "ไม่พบห้องนี้", m_banned: "คุณถูกแบนจากห้องนี้", m_full: "ห้องเต็มแล้ว",
    m_not_owner: "เฉพาะผู้สร้างห้องเท่านั้น", m_bad_target: "ผู้เล่นไม่ถูกต้อง",
    m_busy: "เซิร์ฟเวอร์ไม่ว่าง ลองใหม่ภายหลัง", m_kicked: "คุณถูกเตะออกจากห้อง",
    m_you_banned: "คุณถูกแบนออกจากห้อง", m_no_server: "ยังไม่ได้เชื่อมต่อเซิร์ฟเวอร์เสียง",
    m_mic_denied: "เปิดไมโครโฟนไม่ได้", m_no_hello: "ยังไม่พร้อม ลองอีกครั้ง",
  },
  es: {
    title: "Chat de voz", mic_on: "Micrófono activado", mic_off: "Micrófono silenciado",
    deaf_on: "Sonido desactivado", deaf_off: "Sonido activado",
    vols: "Ajustar volúmenes", settings: "Ajustes", room: "Sala", back: "Atrás", close: "Cerrar",
    st_title: "Ajustes del chat de voz", vol: "Volumen del chat de voz (%)", gain: "Ganancia del micrófono (dB)",
    gain_warn: "¡Más de 6 dB puede distorsionar el audio!", thr: "Umbral de activación por voz (dB)",
    lang: "Idioma / Language", saved: "Ajustes guardados",
    nc_title: "App de voz no conectada",
    nc_body: "1. Abre la app VC Bridge y pulsa Iniciar.\n2. En el juego, ejecuta:\n§e/connect 127.0.0.1:3000§r\n\n§7Los trucos deben estar activados y \"Require Encrypted WebSockets\" desactivado.",
    no_server: "La app no está conectada al servidor de voz.",
    create_room: "Crear sala", join_room: "Unirse a sala", max_members: "Máx. de miembros",
    room_code: "Código de sala", enter_code: "Introduce el código de sala", join: "Unirse", create: "Crear",
    members: "Miembros", leave: "Salir de la sala", manage: "Gestionar miembros (expulsar / vetar)",
    kick: "Expulsar", ban: "Vetar", you: "(tú)", no_others: "No hay otros miembros",
    m_no_room: "Sala no encontrada.", m_banned: "Estás vetado de esta sala.", m_full: "La sala está llena.",
    m_not_owner: "Solo el creador de la sala puede hacerlo.", m_bad_target: "Jugador no válido.",
    m_busy: "Servidor ocupado, inténtalo más tarde.", m_kicked: "Te expulsaron de la sala.",
    m_you_banned: "Te vetaron de la sala.", m_no_server: "No conectado al servidor de voz.",
    m_mic_denied: "No se pudo iniciar el micrófono.", m_no_hello: "Aún no está listo, inténtalo de nuevo.",
  },
};

const langIndex = (p) => {
  const v = p.getDynamicProperty("vc_lang");
  return typeof v === "number" && v >= 0 && v < LANGS.length ? v : 0; // default: English
};
const tr = (p) => T[LANGS[langIndex(p)]];

// ---------- State pushed by the app ----------
const states = new Map(); // player name -> { ...state, ts }
const STALE_TICKS = 700;  // app sends a heartbeat every 10 s (200 ticks)
const st = (p) => {
  const s = states.get(p.name);
  return s && system.currentTick - s.ts < STALE_TICKS ? s : null;
};

// ---------- Add-on -> app ----------
function toApp(p, ...args) {
  const text = ["VC", p.name, ...args].join("|");
  try {
    p.runCommand(`titleraw @s title {"rawtext":[{"text":${JSON.stringify(text)}}]}`);
  } catch {}
  system.runTimeout(() => syncIcon(p, true), 2); // the hidden title also carries the HUD icon state
}

const sleep = (ticks) => new Promise((res) => system.runTimeout(res, ticks));

async function show(p, form) {
  for (let i = 0; i < 20; i++) {
    const r = await form.show(p);
    if (r.cancelationReason !== FormCancelationReason.UserBusy) return r;
    await sleep(10);
  }
  return { canceled: true };
}

// ---------- Screens ----------
async function notConnected(p) {
  const t = tr(p);
  const r = await show(
    p,
    new ActionFormData().title(t.nc_title).body(t.nc_body).button(t.lang).button(t.close)
  );
  if (r.canceled || r.selection === 1) return;
  await languageForm(p);
  return notConnected(p);
}

async function languageForm(p) {
  const t = tr(p);
  const r = await show(
    p,
    new ModalFormData().title(t.lang).dropdown(t.lang, LANG_NAMES, { defaultValueIndex: langIndex(p) })
  );
  if (!r.canceled) p.setDynamicProperty("vc_lang", r.formValues[0]);
}

async function mainMenu(p) {
  const s = st(p);
  if (!s) return notConnected(p);
  const t = tr(p);
  const f = new ActionFormData()
    .title(`§l${t.title}`)
    .button(s.mute ? `§c${t.mic_off}` : `§a${t.mic_on}`, s.mute ? "textures/vc/ui/icon_mic_off" : "textures/vc/ui/icon_mic_on")
    .button(s.deaf ? `§c${t.deaf_on}` : `§a${t.deaf_off}`, s.deaf ? "textures/vc/ui/icon_speaker_off" : "textures/vc/ui/icon_speaker_on")
    .button(t.vols, "textures/vc/ui/icon_volumes")
    .button(t.settings, "textures/vc/ui/icon_settings")
    .button(s.room ? `${t.room}: ${s.room}` : t.room, "textures/vc/ui/icon_group");
  const r = await show(p, f);
  if (r.canceled) return;
  switch (r.selection) {
    case 0: toggleMic(p); await sleep(5); return mainMenu(p);
    case 1: s.deaf = s.deaf ? 0 : 1; toApp(p, "deaf", s.deaf); await sleep(5); return mainMenu(p);
    case 2: return volsMenu(p);
    case 3: return settingsMenu(p);
    case 4: return roomMenu(p);
  }
}

async function settingsMenu(p) {
  const s = st(p);
  if (!s) { await languageForm(p); return notConnected(p); }
  const t = tr(p);
  const f = new ModalFormData()
    .title(t.st_title)
    .slider(t.vol, 0, 200, { valueStep: 5, defaultValue: s.vol ?? 100 })
    .slider(`${t.gain}\n§7${t.gain_warn}`, -10, 10, { valueStep: 1, defaultValue: s.gain ?? 0 })
    .slider(t.thr, -100, 0, { valueStep: 1, defaultValue: s.thr ?? -50 })
    .dropdown(t.lang, LANG_NAMES, { defaultValueIndex: langIndex(p) })
    .submitButton(t.settings);
  const r = await show(p, f);
  if (r.canceled) return mainMenu(p);
  const [vol, gain, thr, lang] = r.formValues;
  p.setDynamicProperty("vc_lang", lang);
  toApp(p, "set", vol, gain, thr);
  Object.assign(s, { vol, gain, thr });
  p.sendMessage(`§a${T[LANGS[lang]].saved}`);
  return mainMenu(p);
}

async function volsMenu(p) {
  const s = st(p);
  if (!s) return notConnected(p);
  const t = tr(p);
  const others = (s.mem ?? []).filter((m) => m[0] !== s.me);
  const f = new ModalFormData().title(t.vols).submitButton(t.settings);
  if (!others.length) f.label(`§7${t.no_others}`);
  for (const o of others) f.slider(`${o[1]} (%)`, 0, 200, { valueStep: 5, defaultValue: s.uv?.[o[0]] ?? 100 });
  const r = await show(p, f);
  if (r.canceled) return mainMenu(p);
  const vals = r.formValues.filter((x) => typeof x === "number");
  others.forEach((o, i) => {
    if (vals[i] !== undefined && vals[i] !== (s.uv?.[o[0]] ?? 100)) toApp(p, "uvol", o[0], vals[i]);
  });
  return mainMenu(p);
}

async function roomMenu(p) {
  const s = st(p);
  if (!s) return notConnected(p);
  const t = tr(p);

  if (!s.room) {
    const f = new ActionFormData()
      .title(t.room)
      .body(s.srv ? "" : `§c${t.no_server}`)
      .button(`§l§2+ ${t.create_room}`, "textures/ui/color_plus")
      .button(t.join_room)
      .button(t.back);
    const r = await show(p, f);
    if (r.canceled) return;
    if (r.selection === 0) return createRoomForm(p);
    if (r.selection === 1) return joinRoomForm(p);
    return mainMenu(p);
  }

  const isOwner = s.me >= 0 && s.me === s.ow;
  const lines = (s.mem ?? []).map(
    (m) => `§f${m[1]}${m[0] === s.me ? " " + t.you : ""}${m[0] === s.ow ? " §6★" : ""}`
  );
  const f = new ActionFormData()
    .title(t.room)
    .body(`§7${t.room_code}: §e§l${s.room}§r\n\n§l${t.members} (${(s.mem ?? []).length}/${s.max}):§r\n${lines.join("\n")}`);
  const actions = [];
  if (isOwner) { f.button(t.manage, "textures/vc/ui/icon_group"); actions.push("manage"); }
  f.button(`§c${t.leave}`, "textures/vc/ui/icon_leave"); actions.push("leave");
  f.button(t.back); actions.push("back");
  const r = await show(p, f);
  if (r.canceled) return;
  const a = actions[r.selection];
  if (a === "manage") return manageMenu(p);
  if (a === "leave") { toApp(p, "leave"); await sleep(20); return mainMenu(p); }
  return mainMenu(p);
}

async function createRoomForm(p) {
  const t = tr(p);
  const r = await show(
    p,
    new ModalFormData().title(t.create_room)
      .slider(t.max_members, 2, 50, { valueStep: 1, defaultValue: 8 })
      .submitButton(t.create)
  );
  if (r.canceled) return roomMenu(p);
  toApp(p, "create", r.formValues[0]);
  await sleep(30);
  return roomMenu(p);
}

async function joinRoomForm(p) {
  const t = tr(p);
  const r = await show(
    p,
    new ModalFormData().title(t.join_room)
      .textField(t.room_code, t.enter_code, { defaultValue: "" })
      .submitButton(t.join)
  );
  if (r.canceled) return roomMenu(p);
  const code = String(r.formValues[0]).toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 6);
  if (code) toApp(p, "join", code);
  await sleep(30);
  return roomMenu(p);
}

async function manageMenu(p) {
  const s = st(p);
  if (!s) return notConnected(p);
  const t = tr(p);
  const others = (s.mem ?? []).filter((m) => m[0] !== s.me);
  const f = new ActionFormData().title(t.manage).body(others.length ? "" : `§7${t.no_others}`);
  for (const o of others) f.button(o[1], "textures/vc/ui/icon_group");
  f.button(t.back);
  const r = await show(p, f);
  if (r.canceled) return;
  if (r.selection >= others.length) return roomMenu(p);
  return memberAction(p, others[r.selection]);
}

async function memberAction(p, m) {
  const t = tr(p);
  const r = await show(
    p,
    new ActionFormData().title(m[1]).button(`§6${t.kick}`).button(`§c${t.ban}`).button(t.back)
  );
  if (r.canceled) return;
  if (r.selection === 0) { toApp(p, "kick", m[0]); await sleep(20); }
  else if (r.selection === 1) { toApp(p, "ban", m[0]); await sleep(20); }
  return manageMenu(p);
}

// ---------- App -> add-on ----------
system.afterEvents.scriptEventReceive.subscribe((e) => {
  if (e.id === "vc:state") {
    try {
      const o = JSON.parse(e.message);
      if (o && typeof o.p === "string") states.set(o.p, { ...o, ts: system.currentTick });
    } catch {}
    return;
  }
  if (e.id === "vc:msg") {
    try {
      const o = JSON.parse(e.message);
      const p = world.getAllPlayers().find((x) => x.name === o.p);
      const text = p && tr(p)["m_" + o.k];
      if (text) p.sendMessage(`§c${text}`);
    } catch {}
    return;
  }
  // manual entry points (need a player as the source): /scriptevent vc:menu
  const p = e.sourceEntity;
  if (!p || p.typeId !== "minecraft:player") return;
  switch (e.id) {
    case "vc:menu": return mainMenu(p);
    case "vc:settings": return settingsMenu(p);
    case "vc:room": return roomMenu(p);
    case "vc:volumes": return volsMenu(p);
  }
});

world.afterEvents.playerLeave.subscribe((e) => states.delete(e.playerName));

// Right-click a clock to open the menu
world.afterEvents.itemUse.subscribe((e) => {
  if (e.itemStack.typeId === "minecraft:clock") system.run(() => mainMenu(e.source));
});

// ---------- One floating HUD button: tap = mic on/off, tap while sneaking = settings ----------
// The resource pack maps the on-screen button to "select hotbar slot 9". A locked item sits in that
// slot; when the selected slot jumps onto it we treat it as a tap and snap back to the previous slot.
const BTN_SLOT = 8, BTN_ITEM = "vc:mic_button";
const lastSlot = new Map(), lastIcon = new Map();

function ensureBtn(p) {
  const inv = p.getComponent("minecraft:inventory")?.container;
  if (!inv) return;
  const cur = inv.getItem(BTN_SLOT);
  if (cur?.typeId === BTN_ITEM && cur.lockMode === ItemLockMode.slot) return;
  if (cur) {
    let dest = -1;
    for (let i = 0; i < inv.size; i++) if (i !== BTN_SLOT && !inv.getItem(i)) { dest = i; break; }
    if (dest >= 0) inv.setItem(dest, cur); else p.dimension.spawnItem(cur, p.location);
  }
  const item = new ItemStack(BTN_ITEM, 1);
  try { item.lockMode = ItemLockMode.slot; item.keepOnDeath = true; item.nameTag = " "; } catch {}
  inv.setItem(BTN_SLOT, item);
}

// HUD icon (sent as a hidden title): gear while sneaking, otherwise mic on/off
function syncIcon(p, force = false) {
  const s = st(p);
  const state = p.isSneaking ? "vc:set" : s && !s.mute ? "vc:on" : "vc:off";
  if (!force && lastIcon.get(p.id) === state) return;
  lastIcon.set(p.id, state);
  try { p.onScreenDisplay.setTitle(state, { fadeInDuration: 0, stayDuration: 1000000, fadeOutDuration: 0 }); } catch {}
}

function toggleMic(p) {
  const s = st(p), t = tr(p);
  if (!s) { system.run(() => notConnected(p)); return; }
  s.mute = s.mute ? 0 : 1; // optimistic; the app echoes the real state right after
  toApp(p, "mute", s.mute);
  p.onScreenDisplay.setActionBar(s.mute ? `§c${t.mic_off}` : `§a${t.mic_on}`);
  p.playSound(s.mute ? "random.pop2" : "random.pop");
}

system.runInterval(() => {
  for (const p of world.getAllPlayers()) {
    const cur = p.selectedSlotIndex, prev = lastSlot.get(p.id) ?? cur;
    if (cur !== prev && cur === BTN_SLOT) {
      try { p.selectedSlotIndex = prev; } catch {}
      lastSlot.set(p.id, prev);
      if (p.isSneaking) system.run(() => settingsMenu(p)); else toggleMic(p);
    } else lastSlot.set(p.id, cur);
    syncIcon(p);
  }
}, 1);

// keep the button item pinned (join, respawn, /clear) and re-send the icon state
system.runInterval(() => {
  for (const p of world.getAllPlayers()) { try { ensureBtn(p); syncIcon(p, true); } catch {} }
}, 100);
world.afterEvents.playerSpawn.subscribe((e) => { try { ensureBtn(e.player); syncIcon(e.player, true); } catch {} });
