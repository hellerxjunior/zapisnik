// Data v prohlížeči (localStorage) a synchronizace s Google Diskem. Odpovídá Repository.kt a BackupManager.kt.
import { FILE, FOLDER } from "./config.js";
import { Drive, UnauthorizedError, cachedToken, forgetToken } from "./drive.js";
import { DEFAULT_CATEGORIES, KIND_CATEGORY, KIND_ENTRY, decode, emptySnapshot, encode, merge, normalize, same } from "./sync.js";

const DATA_KEY = "zapisnik.data";
const META_KEY = "zapisnik.meta";
export const CATEGORY_COLOR_COUNT = 8;

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

export function saveEntry(existing, { title, date, categoryId, text }) {
  const now = after(existing?.updated);
  const entry = {
    id: existing?.id ?? uuid(),
    title: title.trim(),
    date,
    categoryId: categoryId || null,
    text: text.trim(),
    created: existing?.created ?? now,
    updated: now,
  };
  setData({ ...data, entries: [...data.entries.filter((e) => e.id !== entry.id), entry] }, true);
  return entry.id;
}

export function deleteEntry(id) {
  const now = after(data.entries.find((e) => e.id === id)?.updated);
  setData({ ...data, entries: data.entries.filter((e) => e.id !== id), deleted: tomb(data.deleted, KIND_ENTRY, id, now) }, true);
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
 * Stáhne verzi z Disku, sloučí ji s daty v prohlížeči a výsledek nahraje zpět.
 * Sloučení a uložení proběhne bez přerušení, takže úprava udělaná během synchronizace nezmizí.
 */
export async function sync(token = cachedToken()) {
  if (busy) return;
  if (!token) throw new UnauthorizedError();
  busy = true;
  emit();
  try {
    const drive = new Drive(token);
    let id = meta.fileId && (await drive.exists(meta.fileId)) ? meta.fileId : null;
    let folderId = null;
    if (!id) {
      folderId = await drive.ensureFolder(FOLDER);
      id = await drive.findFile(FILE, folderId);
    }
    const remote = id ? decode(await drive.download(id)) : null;

    const editsBefore = edits;
    const merged = remote ? merge(data, remote) : data;
    if (!same(merged, data)) setData(merged, false);

    const now = Date.now();
    if (!id) id = await drive.createFile(FILE, folderId, encode(merged, now));
    else if (!same(merged, remote)) await drive.updateFile(id, encode(merged, now));

    // Úprava během nahrávání zůstává označená a pošle se při další synchronizaci.
    const editedMeanwhile = edits !== editsBefore;
    setMeta({ fileId: id, lastSync: now, lastError: null, dirty: editedMeanwhile });
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
