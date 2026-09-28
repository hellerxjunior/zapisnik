// Data v prohlížeči (localStorage, obrázky v IndexedDB) a synchronizace s Google Diskem.
// Odpovídá Repository.kt a BackupManager.kt.
import { FILE, FILES, FOLDER, IMAGES } from "./config.js";
import * as images from "./images.js";
import { Drive, UnauthorizedError, cachedToken, forgetToken } from "./drive.js";
import { DEFAULT_CATEGORIES, KIND_CATEGORY, KIND_ENTRY, cleanFileName, decode, driveName, emptySnapshot, encode, merge, normalize, referencedFiles, referencedImages, same } from "./sync.js";

const DATA_KEY = "zapisnik.data";
const META_KEY = "zapisnik.meta";
export const CATEGORY_COLOR_COUNT = 8;
/** Obrázky, na které nic neodkazuje, jdou na Disku do koše až po týdnu (jiné zařízení je může ještě potřebovat). */
const REMOTE_GRACE_MS = 7 * 24 * 60 * 60 * 1000;
const imageName = (id) => id + ".jpg";

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v ? JSON.parse(v) : fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

const listeners = new Set();
let data = normalize(load(DATA_KEY, { ...emptySnapshot(), categories: DEFAULT_CATEGORIES }));
/** email, fileId, lastSync, lastError, dirty (změny, které ještě nejsou na Disku) */
let meta = { email: null, fileId: null, lastSync: 0, lastError: null, dirty: false, ...load(META_KEY, {}) };
let busy = false;
let timer = null;
/** Počítadlo úprav, podle něj sync pozná úpravu udělanou během nahrávání. */
let edits = 0;

export const getData = () => data;
export const getMeta = () => ({ ...meta, busy });
export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
function emit() {
  listeners.forEach((fn) => fn());
}

function setData(next, changedByUser) {
  data = normalize(next);
  if (!save(DATA_KEY, data)) meta.lastError = "Prohlížeč odmítl uložit data (plné úložiště?).";
  if (changedByUser) {
    edits++;
    meta.dirty = true;
    save(META_KEY, meta);
    scheduleSync(3);
  }
  emit();
}

function setMeta(patch) {
  meta = { ...meta, ...patch };
  save(META_KEY, meta);
  emit();
}

// Změna v jiné záložce téhož prohlížeče.
window.addEventListener("storage", (e) => {
  if (e.key === DATA_KEY) data = normalize(load(DATA_KEY, data));
  if (e.key === META_KEY) meta = { ...meta, ...load(META_KEY, {}) };
  if (e.key === DATA_KEY || e.key === META_KEY) emit();
});

const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + Math.random().toString(36).slice(2));
/** Čas změny vždy větší než předchozí, i když má jiné zařízení hodiny napřed. */
const after = (prev) => Math.max(Date.now(), (prev ?? 0) + 1);
const tomb = (list, kind, id, at) => [...list.filter((t) => !(t.kind === kind && t.id === id)), { kind, id, at }];

/* ---------- úpravy ---------- */

/** Id všech obrázků a příloh, na které odkazuje nějaký záznam. */
const usedBlobs = (s) => new Set([...referencedImages(s), ...referencedFiles(s).keys()]);

/** Smaže z prohlížeče odebrané obrázky a přílohy, pokud je nepoužívá jiný záznam. Na Disku je uklidí synchronizace. */
function dropUnused(removed) {
  const used = usedBlobs(data);
  const gone = removed.filter((id) => !used.has(id));
  if (gone.length) images.remove(gone).catch(() => {});
}

export function saveEntry(existing, { title, date, categoryId, text, images: imageIds = [], files = [] }) {
  const now = after(existing?.updated);
  const entry = {
    id: existing?.id ?? uuid(),
    title: title.trim(),
    date,
    categoryId: categoryId || null,
    text: text.trim(),
    created: existing?.created ?? now,
    updated: now,
    images: imageIds,
    files: files.map((f) => ({ ...f, name: cleanFileName(f.name) })),
  };
  setData({ ...data, entries: [...data.entries.filter((e) => e.id !== entry.id), entry] }, true);
  const kept = new Set([...imageIds, ...files.map((f) => f.id)]);
  dropUnused([...(existing?.images ?? []), ...(existing?.files ?? []).map((f) => f.id)].filter((id) => !kept.has(id)));
  return entry.id;
}

export function deleteEntry(id) {
  const old = data.entries.find((e) => e.id === id);
  const now = after(old?.updated);
  setData({ ...data, entries: data.entries.filter((e) => e.id !== id), deleted: tomb(data.deleted, KIND_ENTRY, id, now) }, true);
  dropUnused([...(old?.images ?? []), ...(old?.files ?? []).map((f) => f.id)]);
}

export function addCategory(name) {
  const used = new Set(data.categories.map((c) => c.color));
  let color = [...Array(CATEGORY_COLOR_COUNT).keys()].find((i) => !used.has(i));
  if (color === undefined) color = data.categories.length % CATEGORY_COLOR_COUNT;
  const sortOrder = Math.max(-1, ...data.categories.map((c) => c.sortOrder)) + 1;
  const c = { id: uuid(), name: name.trim(), color, sortOrder, updated: Date.now() };
  setData({ ...data, categories: [...data.categories, c] }, true);
}

export function updateCategory(c) {
  setData({ ...data, categories: data.categories.map((x) => (x.id === c.id ? { ...c, updated: after(x.updated) } : x)) }, true);
}

export function deleteCategory(id) {
  const now = after(data.categories.find((c) => c.id === id)?.updated);
  setData(
    {
      entries: data.entries.map((e) => (e.categoryId === id ? { ...e, categoryId: null, updated: Math.max(now, e.updated + 1) } : e)),
      categories: data.categories.filter((c) => c.id !== id),
      deleted: tomb(data.deleted, KIND_CATEGORY, id, now),
    },
    true,
  );
}

/* ---------- synchronizace ---------- */

export function signedIn() {
  return !!meta.email;
}

/** Po změně počká pár sekund, aby se víc úprav nahrálo najednou. Bez platného tokenu jen označí neodeslané změny. */
export function scheduleSync(seconds) {
  clearTimeout(timer);
  if (!signedIn() || !cachedToken()) return;
  timer = setTimeout(() => sync().catch(() => {}), seconds * 1000);
}

/**
 * Stáhne verzi z Disku, sloučí ji s daty v prohlížeči, nahraje nové obrázky, pak výsledek a nakonec stáhne chybějící obrázky.
 * Sloučení a uložení proběhne bez přerušení, takže úprava udělaná během synchronizace nezmizí.
 */
export async function sync(token = cachedToken()) {
  if (busy) return;
  if (!token) throw new UnauthorizedError();
  busy = true;
  emit();
  try {
    const drive = new Drive(token);
    const folderId = await drive.ensureFolder(FOLDER);
    let id = meta.fileId && (await drive.exists(meta.fileId)) ? meta.fileId : await drive.findFile(FILE, folderId);
    const remote = id ? decode(await drive.download(id)) : null;

    const editsBefore = edits;
    const merged = remote ? merge(data, remote) : data;
    if (!same(merged, data)) setData(merged, false);

    // Obrázky nahrát dřív než seznam, aby je ostatní zařízení našla, až na ně uvidí odkaz.
    const imageFolder = await drive.ensureFolder(IMAGES, folderId);
    const remoteImages = new Map((await drive.listFiles(imageFolder)).map((f) => [f.name, f]));
    const referenced = referencedImages(merged);
    for (const img of referenced) {
      if (remoteImages.has(imageName(img))) continue;
      const blob = await images.get(img);
      if (blob) await drive.createBinary(imageName(img), imageFolder, blob, "image/jpeg");
    }
    const fileFolder = await drive.ensureFolder(FILES, folderId);
    const remoteFiles = new Map((await drive.listFiles(fileFolder)).map((f) => [f.name, f]));
    const refFiles = referencedFiles(merged);
    for (const a of refFiles.values()) {
      if (remoteFiles.has(driveName(a))) continue;
      const blob = await images.get(a.id);
      if (blob) await drive.createBinary(driveName(a), fileFolder, blob, a.type);
    }

    const now = Date.now();
    if (!id) id = await drive.createFile(FILE, folderId, encode(merged, now));
    else if (!same(merged, remote)) await drive.updateFile(id, encode(merged, now));

    setMeta({ fileId: id });

    for (const img of referenced) {
      const f = remoteImages.get(imageName(img));
      if (f && !(await images.has(img))) await images.put(img, await drive.downloadBlob(f.id, "image/jpeg"));
    }
    for (const a of refFiles.values()) {
      const f = remoteFiles.get(driveName(a));
      if (f && !(await images.has(a.id))) await images.put(a.id, await drive.downloadBlob(f.id, a.type || undefined));
    }

    // Úklid obrázků a příloh, na které už nic neodkazuje (smazané záznamy).
    const stillUsed = new Set([...usedBlobs(merged), ...usedBlobs(data)]);
    await images.cleanup(stillUsed);
    for (const f of [...remoteImages.values(), ...remoteFiles.values()]) {
      if (!stillUsed.has(f.name.split(".")[0]) && f.modified > 0 && f.modified < now - REMOTE_GRACE_MS) {
        await drive.trash(f.id).catch(() => {});
      }
    }

    // Úprava během synchronizace zůstává označená a pošle se hned další synchronizací.
    const editedMeanwhile = edits !== editsBefore;
    setMeta({ lastSync: now, lastError: null, dirty: editedMeanwhile });
    if (editedMeanwhile) scheduleSync(1);
  } catch (e) {
    if (e instanceof UnauthorizedError) forgetToken();
    setMeta({ lastError: e.message || "Synchronizace se nepovedla." });
    throw e;
  } finally {
    busy = false;
    emit();
  }
}

export async function signIn(token) {
  const email = await new Drive(token).email();
  // Jiný účet než minule: nepoužívat id souboru z cizího Disku.
  if (email !== meta.email) setMeta({ email, fileId: null });
  await sync(token);
}

export function signOut() {
  clearTimeout(timer);
  setMeta({ email: null, fileId: null, lastError: null });
}
