// Test synchronizace webu proti falešnému Google Disku v paměti (nic se neposílá do sítě): node web/store.test.mjs
import assert from "node:assert/strict";

/* ---------- prostředí prohlížeče ---------- */
class MemStorage {
  #m = new Map();
  getItem(k) { return this.#m.has(k) ? this.#m.get(k) : null; }
  setItem(k, v) { this.#m.set(k, String(v)); }
  removeItem(k) { this.#m.delete(k); }
}
globalThis.localStorage = new MemStorage();
globalThis.sessionStorage = new MemStorage();
globalThis.window = { addEventListener() {} };
sessionStorage.setItem("zapisnik.token", JSON.stringify({ token: "test-token", expires: Date.now() + 3_600_000 }));

/* ---------- falešný Google Disk ---------- */
const files = new Map(); // id -> { name, mimeType, parents, content, trashed }
let nextId = 1;
const calls = [];
let rejectToken = false;

globalThis.fetch = async (url, { method = "GET", headers = {}, body } = {}) => {
  const u = new URL(url);
  calls.push(method + " " + u.pathname);
  assert.ok(u.hostname === "www.googleapis.com", "jen Google API: " + url);
  const res = (status, data) => ({
    ok: status < 300,
    status,
    text: async () => (typeof data === "string" ? data : JSON.stringify(data)),
    blob: async () => new Blob([data]),
  });
  if (body instanceof Blob) body = await body.text();
  if (rejectToken || headers.Authorization !== "Bearer test-token") return res(401, "unauthorized");

  const path = u.pathname;
  if (path === "/drive/v3/about") return res(200, { user: { emailAddress: "test@example.com" } });
  if (path === "/drive/v3/files" && method === "GET") {
    const q = u.searchParams.get("q");
    const name = /name = '([^']*)'/.exec(q)?.[1];
    const parent = /'([^']*)' in parents/.exec(q)?.[1];
    const folder = q.includes("mimeType = 'application/vnd.google-apps.folder'");
    const hits = [...files.entries()].filter(
      ([, f]) => (!name || f.name === name) && !f.trashed && (!parent || f.parents?.includes(parent)) && (!folder || f.mimeType?.includes("folder")),
    );
    return res(200, { files: hits.map(([id, f]) => ({ id, name: f.name, modifiedTime: new Date(f.modified).toISOString() })) });
  }
  if (path === "/drive/v3/files" && method === "POST") {
    const meta = JSON.parse(body);
    const id = "f" + nextId++;
    files.set(id, { ...meta, modified: Date.now() });
    return res(200, { id });
  }
  if (path === "/upload/drive/v3/files" && method === "POST") {
    const parts = body.split(/--zapisnik\d+(?:--)?\r\n/).filter(Boolean);
    const meta = JSON.parse(parts[0].split("\r\n\r\n")[1]);
    const content = parts[1].split("\r\n\r\n").slice(1).join("\r\n\r\n").replace(/\r\n$/, "");
    const id = "f" + nextId++;
    files.set(id, { ...meta, content, modified: Date.now() });
    return res(200, { id });
  }
  const m = /^\/(upload\/)?drive\/v3\/files\/([^/]+)$/.exec(path);
  if (m) {
    const f = files.get(m[2]);
    if (!f) return res(404, "not found");
    if (method === "PATCH" && m[1]) return (f.content = body), res(200, { id: m[2] });
    if (method === "PATCH") return Object.assign(f, JSON.parse(body)), res(200, { id: m[2] });
    if (u.searchParams.get("alt") === "media") return res(200, f.content);
    return res(200, { id: m[2], trashed: !!f.trashed });
  }
  return res(400, "neznámý požadavek " + method + " " + path);
};

const images = await import("./images.js");
const mem = new Map();
images.setBackend({
  get: async (id) => mem.get(id),
  put: async (id, rec) => void mem.set(id, rec),
  del: async (id) => void mem.delete(id),
  keys: async () => [...mem.keys()],
});
const store = await import("./store.js");
const { decode, encode, merge } = await import("./sync.js");
const fileOnDrive = () => [...files.values()].find((f) => f.name === "zapisnik-zaloha.json" && !f.trashed);

// 1) první přihlášení: založí složku i soubor s daty z prohlížeče
store.saveEntry(null, { title: "Kotel servis", date: "2026-09-01", categoryId: "dum", text: "" });
await store.signIn("test-token");
assert.equal(store.getMeta().email, "test@example.com");
const folder = [...files.values()].find((f) => f.name === "Zápisník");
assert.ok(folder, "složka Zápisník existuje");
assert.equal(decode(fileOnDrive().content).entries[0].title, "Kotel servis");
assert.equal(store.getMeta().dirty, false);

// 2) telefon přidá záznam a smaže kategorii, web to po synchronizaci převezme
{
  const phone = decode(fileOnDrive().content);
  const now = Date.now() + 10;
  phone.entries.push({ id: "tel-1", title: "Pneu přezutí", date: "2026-10-15", categoryId: "auto", text: "zimní", created: now, updated: now });
  phone.categories = phone.categories.filter((c) => c.id !== "napady");
  phone.deleted.push({ kind: "category", id: "napady", at: now });
  fileOnDrive().content = encode(phone);
}
calls.length = 0;
await store.sync();
assert.deepEqual(store.getData().entries.map((e) => e.title).sort(), ["Kotel servis", "Pneu přezutí"]);
assert.ok(!store.getData().categories.some((c) => c.id === "napady"));
assert.ok(!calls.some((c) => c.startsWith("PATCH")), "beze změny se soubor znovu nenahrává");

// 3) smazání na webu dojde na Disk jako záznam o smazání, takže ho telefon nevrátí
store.deleteEntry("tel-1");
await store.sync();
const remote = decode(fileOnDrive().content);
assert.ok(!remote.entries.some((e) => e.id === "tel-1"));
assert.ok(remote.deleted.some((t) => t.kind === "entry" && t.id === "tel-1"));
const phoneOld = { ...remote, entries: [...remote.entries, { id: "tel-1", title: "Pneu přezutí", date: "2026-10-15", categoryId: "auto", text: "", created: 1, updated: 1 }] };
assert.ok(!merge(phoneOld, remote).entries.some((e) => e.id === "tel-1"));

// 4) soubor smazaný z Disku: web ho znovu najde podle jména, nebo založí nový
fileOnDrive().trashed = true;
await store.sync();
assert.ok(fileOnDrive(), "nový soubor po smazání starého");
assert.equal(decode(fileOnDrive().content).entries.length, 1);

// 5) obrázky: web nahraje svůj, stáhne obrázek z telefonu a po smazání záznamu uklidí
{
  const imageFolderId = () => [...files.entries()].find(([, f]) => f.name === "Obrázky")?.[0];
  const imageFile = (id) => [...files.values()].find((f) => f.name === id + ".jpg" && !f.trashed);

  await images.put("img-web", new Blob(["WEBJPEG"]));
  const webEntry = store.saveEntry(null, { title: "Faktura", date: "2026-09-20", categoryId: null, text: "", images: ["img-web"] });
  await store.sync();
  assert.ok(imageFolderId(), "podsložka Obrázky existuje");
  assert.equal(imageFile("img-web").content, "WEBJPEG");
  assert.deepEqual(decode(fileOnDrive().content).entries.find((e) => e.id === webEntry).images, ["img-web"]);

  // telefon nahraje fotku a pak seznam, který na ni odkazuje
  files.set("tel-img", { name: "img-tel.jpg", parents: [imageFolderId()], content: "TELJPEG", modified: Date.now() });
  const phone = decode(fileOnDrive().content);
  phone.entries.push({ id: "tel-2", title: "Brzdy", date: "2026-09-21", categoryId: null, text: "", created: 1, updated: Date.now() + 5, images: ["img-tel"] });
  fileOnDrive().content = encode(phone);
  await store.sync();
  assert.equal(await (await images.get("img-tel")).text(), "TELJPEG");

  // smazání záznamu: obrázek zmizí z prohlížeče hned, z Disku až po týdnu (do koše)
  store.deleteEntry(webEntry);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(await images.get("img-web"), null);
  await store.sync();
  assert.ok(imageFile("img-web"), "čerstvý obrázek na Disku zůstává");
  imageFile("img-web").modified = Date.now() - 8 * 24 * 3600 * 1000;
  await store.sync();
  assert.equal(imageFile("img-web"), undefined, "starý nepoužívaný obrázek jde do koše");
  assert.ok(imageFile("img-tel"), "používaný obrázek zůstává");

  // nebezpečné id obrázku ze souboru se zahodí
  assert.deepEqual(decode({ app: "zapisnik", entries: [{ id: "x", images: ["../x", "ok-1", 3] }] }).entries[0].images, ["ok-1"]);
}

// 6) vypršené přihlášení: chyba, zapomenutý token, data v prohlížeči zůstanou
rejectToken = true;
await assert.rejects(store.sync(), /vypršelo/);
assert.equal(sessionStorage.getItem("zapisnik.token"), null);
assert.equal(store.getData().entries.length, 2);
assert.match(store.getMeta().lastError, /vypršelo/);

console.log("store.js: vše v pořádku");
