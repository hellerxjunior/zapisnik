// Obrázky v prohlížeči (IndexedDB) a jejich zmenšení při přidání. Odpovídá ImageStore.kt v Android aplikaci.

export const MAX_SIDE = 1600;
export const QUALITY = 0.82;
export const GRACE_MS = 60 * 60 * 1000;

/** Úložiště { id -> { blob, added } }. V testech se nahradí pamětí přes setBackend. */
let backend = null;

function idb() {
  let dbp = null;
  const open = () =>
    (dbp ??= new Promise((resolve, reject) => {
      const req = indexedDB.open("zapisnik", 1);
      req.onupgradeneeded = () => req.result.createObjectStore("images");
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }));
  const run = (mode, fn) =>
    open().then(
      (db) =>
        new Promise((resolve, reject) => {
          const tx = db.transaction("images", mode);
          const req = fn(tx.objectStore("images"));
          tx.oncomplete = () => resolve(req?.result);
          tx.onerror = () => reject(tx.error);
          tx.onabort = () => reject(tx.error || new Error("Úložiště obrázků odmítlo zápis (plné?)."));
        }),
    );
  return {
    get: (id) => run("readonly", (s) => s.get(id)),
    put: (id, rec) => run("readwrite", (s) => s.put(rec, id)),
    del: (id) => run("readwrite", (s) => s.delete(id)),
    keys: () => run("readonly", (s) => s.getAllKeys()),
  };
}

export function setBackend(b) {
  backend = b;
}
const db = () => (backend ??= idb());

const listeners = new Set();
export function onChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
const emit = () => listeners.forEach((fn) => fn());

export async function get(id) {
  return (await db().get(id))?.blob ?? null;
}
export async function has(id) {
  return !!(await db().get(id));
}
export async function put(id, blob) {
  await db().put(id, { blob, added: Date.now() });
  emit();
}
export async function remove(ids) {
  for (const id of ids) await db().del(id);
}
export async function keys() {
  return (await db().keys()).map(String);
}

/** Smaže obrázky, na které už nic neodkazuje. Čerstvé nechá, mohou patřit k rozepsanému záznamu. */
export async function cleanup(referenced, graceMs = GRACE_MS) {
  const limit = Date.now() - graceMs;
  for (const id of await keys()) {
    if (referenced.has(id)) continue;
    const rec = await db().get(id);
    if (!rec || rec.added < limit) await db().del(id);
  }
}

const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + "-" + Math.random().toString(36).slice(2));

/** Zmenší fotku (a otočí podle EXIF, to dělá prohlížeč), uloží ji a vrátí nové id. */
export async function importFile(file) {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" }).catch(() => {
    throw new Error(`„${file.name}“ se nepodařilo načíst jako obrázek.`);
  });
  const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#fff"; // průhledné PNG by v JPEG zčernalo
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();
  const blob = await new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Obrázek se nepodařilo zmenšit."))), "image/jpeg", QUALITY),
  );
  const id = uuid();
  await put(id, blob);
  return id;
}

/** URL pro <img>; uvolňuje se při překreslení obrazovky přes revokeAll. */
const urls = new Set();
export async function objectUrl(id) {
  const blob = await get(id);
  if (!blob) return null;
  const u = URL.createObjectURL(blob);
  urls.add(u);
  return u;
}
export function revokeAll() {
  urls.forEach((u) => URL.revokeObjectURL(u));
  urls.clear();
}
