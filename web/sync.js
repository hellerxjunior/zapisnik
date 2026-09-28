// Formát souboru na Disku a sloučení dvou verzí. Musí odpovídat BackupJson.kt a SyncMerge.kt v Android aplikaci.

export const KIND_ENTRY = "entry";
export const KIND_CATEGORY = "category";

export function emptySnapshot() {
  return { entries: [], categories: [], deleted: [] };
}

/** Výchozí kategorie, stejné id jako v telefonu, aby se při prvním sloučení nezdvojily. */
export const DEFAULT_CATEGORIES = [
  { id: "auto", name: "Auto", color: 0, sortOrder: 0, updated: 0 },
  { id: "dum", name: "Dům", color: 2, sortOrder: 1, updated: 0 },
  { id: "prace", name: "Práce", color: 5, sortOrder: 2, updated: 0 },
  { id: "rodina", name: "Rodina", color: 6, sortOrder: 3, updated: 0 },
  { id: "napady", name: "Nápady", color: 4, sortOrder: 4, updated: 0 },
];

/** Id obrázku je zároveň název souboru, proto jen bezpečné znaky (UUID). */
export const validImageId = (x) => typeof x === "string" && /^[A-Za-z0-9-]{1,64}$/.test(x);

const num = (v, d = 0) => (typeof v === "number" && Number.isFinite(v) ? v : d);
const str = (v, d = "") => (typeof v === "string" ? v : d);

function entry(o) {
  return {
    id: str(o.id),
    title: str(o.title),
    date: str(o.date),
    categoryId: typeof o.categoryId === "string" ? o.categoryId : null,
    text: str(o.text),
    created: num(o.created),
    updated: num(o.updated),
    images: Array.isArray(o.images) ? o.images.filter(validImageId) : [],
  };
}

function category(o, i) {
  return { id: str(o.id), name: str(o.name), color: num(o.color), sortOrder: num(o.sortOrder, i), updated: num(o.updated) };
}

function tombstone(o) {
  return { kind: str(o.kind), id: str(o.id), at: num(o.at) };
}

/** Přečte soubor z Disku (verze 1 i 2). */
export function decode(json) {
  const root = typeof json === "string" ? JSON.parse(json) : json;
  if (!root || root.app !== "zapisnik") throw new Error("Soubor není záloha Zápisníku");
  return normalize({
    entries: (root.entries || []).map(entry),
    categories: (root.categories || []).map(category),
    deleted: (root.deleted || []).map(tombstone),
  });
}

export function encode(s, now = Date.now()) {
  return JSON.stringify(
    { app: "zapisnik", version: 2, exported: now, categories: s.categories, entries: s.entries, deleted: s.deleted },
    null,
    2,
  );
}

const byId = (a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

/** Seřadí vše podle id a sjednotí pořadí klíčů, aby šly dvě verze porovnat. */
export function normalize(s) {
  return {
    entries: s.entries.map(entry).sort(byId),
    categories: s.categories.map(category).sort(byId),
    deleted: s.deleted.map(tombstone).sort((a, b) => (a.kind === b.kind ? byId(a, b) : a.kind < b.kind ? -1 : 1)),
  };
}

export function same(a, b) {
  return JSON.stringify(normalize(a)) === JSON.stringify(normalize(b));
}

/** Id všech obrázků, na které odkazuje nějaký záznam. */
export function referencedImages(s) {
  return new Set(s.entries.flatMap((e) => e.images));
}

/** Pro každé id nechá položku s nejvyšší hodnotou klíče; při shodě první výskyt (tedy verzi z prvního argumentu). */
function newest(items, key, value) {
  const out = new Map();
  for (const it of items) {
    const k = key(it);
    const cur = out.get(k);
    if (!cur || value(it) > value(cur)) out.set(k, it);
  }
  return [...out.values()];
}

/**
 * Sloučí dvě verze:
 * - položky se párují podle id, vyhrává novější čas změny (při shodě první argument),
 * - záznam o smazání vyhrává, pokud není starší než poslední úprava položky,
 * - odkaz na kategorii, která už neexistuje, se vynuluje.
 */
export function merge(a, b) {
  const deleted = newest([...a.deleted, ...b.deleted], (t) => t.kind + "\u0000" + t.id, (t) => t.at);
  const deletedAt = new Map(deleted.map((t) => [t.kind + "\u0000" + t.id, t.at]));
  const alive = (kind, id, updated) => (deletedAt.get(kind + "\u0000" + id) ?? -1) < updated;

  const categories = newest([...a.categories, ...b.categories], (c) => c.id, (c) => c.updated).filter((c) =>
    alive(KIND_CATEGORY, c.id, c.updated),
  );
  const catIds = new Set(categories.map((c) => c.id));
  const entries = newest([...a.entries, ...b.entries], (e) => e.id, (e) => e.updated)
    .filter((e) => alive(KIND_ENTRY, e.id, e.updated))
    .map((e) => (e.categoryId !== null && !catIds.has(e.categoryId) ? { ...e, categoryId: null } : e));

  return normalize({ entries, categories, deleted });
}
