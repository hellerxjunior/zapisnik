// Přihlášení přes Google Identity Services (token client) a minimální klient Google Drive REST API v3.
// Odpovídá DriveClient.kt v Android aplikaci.
import { CLIENT_ID, DRIVE_SCOPE } from "./config.js";

const API = "https://www.googleapis.com/drive/v3";
const UPLOAD = "https://www.googleapis.com/upload/drive/v3";
const FOLDER_MIME = "application/vnd.google-apps.folder";
const TOKEN_KEY = "zapisnik.token";

export class UnauthorizedError extends Error {
  constructor() {
    super("Přihlášení ke Google účtu vypršelo");
  }
}

let gisReady = null;

/** Načte knihovnu Google Identity Services až ve chvíli, kdy je potřeba. */
function loadGis() {
  if (!gisReady) {
    gisReady = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = "https://accounts.google.com/gsi/client";
      s.async = true;
      s.onload = () => resolve(window.google.accounts.oauth2);
      s.onerror = () => {
        gisReady = null;
        reject(new Error("Nepodařilo se načíst přihlášení Google. Jsi online?"));
      };
      document.head.appendChild(s);
    });
  }
  return gisReady;
}

export function configured() {
  return CLIENT_ID.trim() !== "";
}

/** Platný token z této relace prohlížeče, nebo null. */
export function cachedToken() {
  try {
    const t = JSON.parse(sessionStorage.getItem(TOKEN_KEY) || "null");
    return t && t.expires > Date.now() + 60_000 ? t.token : null;
  } catch {
    return null;
  }
}

export function forgetToken() {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {}
}

/**
 * Získá přístupový token. Musí se volat z kliknutí, jinak prohlížeč zablokuje okno Google.
 * @param {boolean} consent true = vždy ukázat výběr účtu a souhlas (první přihlášení)
 * @param {string|null} hint e-mail posledního účtu, aby Google nemusel nabízet výběr
 */
export async function requestToken(consent, hint) {
  if (!configured()) throw new Error("Webová verze ještě nemá nastavené Client ID (soubor config.js).");
  const oauth2 = await loadGis();
  return new Promise((resolve, reject) => {
    const client = oauth2.initTokenClient({
      client_id: CLIENT_ID,
      scope: DRIVE_SCOPE,
      callback: (r) => {
        if (r.error || !r.access_token) return reject(new Error("Přihlášení se nepovedlo (" + (r.error || "bez tokenu") + ")."));
        if (!oauth2.hasGrantedAllScopes(r, DRIVE_SCOPE)) return reject(new Error("Bez povolení přístupu ke Google Disku synchronizace nepůjde."));
        try {
          sessionStorage.setItem(TOKEN_KEY, JSON.stringify({ token: r.access_token, expires: Date.now() + r.expires_in * 1000 }));
        } catch {}
        resolve(r.access_token);
      },
      error_callback: (e) => reject(new Error(e.type === "popup_closed" ? "Přihlašovací okno bylo zavřeno." : "Přihlášení se nepovedlo (" + e.type + ").")),
    });
    client.requestAccessToken({ prompt: consent ? "consent select_account" : "", login_hint: hint || undefined });
  });
}

export function revoke(token) {
  if (token && window.google?.accounts?.oauth2) window.google.accounts.oauth2.revoke(token, () => {});
  forgetToken();
}

export class Drive {
  constructor(token) {
    this.token = token;
  }

  async fetch(method, url, body, contentType) {
    const headers = { Authorization: "Bearer " + this.token };
    if (contentType) headers["Content-Type"] = contentType;
    const res = await fetch(url, { method, headers, body, cache: "no-store" });
    if (res.status === 401) throw new UnauthorizedError();
    if (!res.ok) throw new Error("Google Disk odpověděl chybou " + res.status + ". " + (await res.text()).slice(0, 300));
    return res;
  }

  async request(method, url, body, contentType) {
    return (await this.fetch(method, url, body, contentType)).text();
  }

  async json(method, url, body, contentType) {
    return JSON.parse(await this.request(method, url, body, contentType));
  }

  async email() {
    const r = await this.json("GET", API + "/about?fields=user(emailAddress)");
    return r.user?.emailAddress || null;
  }

  async findId(q) {
    const url = API + "/files?spaces=drive&fields=files(id)&orderBy=modifiedTime%20desc&q=" + encodeURIComponent(q);
    const r = await this.json("GET", url);
    return r.files?.[0]?.id || null;
  }

  /** Vrátí id složky (případně podsložky v parentId), a když neexistuje, vytvoří ji. */
  async ensureFolder(name, parentId = null) {
    const inParent = parentId ? ` and '${parentId}' in parents` : "";
    const found = await this.findId(`name = '${esc(name)}' and mimeType = '${FOLDER_MIME}' and trashed = false${inParent}`);
    if (found) return found;
    const meta = { name, mimeType: FOLDER_MIME, ...(parentId ? { parents: [parentId] } : {}) };
    const r = await this.json("POST", API + "/files?fields=id", JSON.stringify(meta), "application/json; charset=UTF-8");
    return r.id;
  }

  /** Všechny soubory ve složce (bez koše): [{ id, name, modified }]. */
  async listFiles(folderId) {
    const out = [];
    let page = null;
    do {
      const url =
        API + "/files?spaces=drive&pageSize=1000&fields=nextPageToken,files(id,name,modifiedTime)&q=" +
        encodeURIComponent(`'${folderId}' in parents and trashed = false`) + (page ? "&pageToken=" + encodeURIComponent(page) : "");
      const r = await this.json("GET", url);
      for (const f of r.files || []) out.push({ id: f.id, name: f.name, modified: Date.parse(f.modifiedTime) || 0 });
      page = r.nextPageToken || null;
    } while (page);
    return out;
  }

  async createImage(name, folderId, blob) {
    const boundary = "zapisnik" + Date.now();
    const meta = JSON.stringify({ name, parents: [folderId] });
    const body = new Blob([
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${boundary}\r\nContent-Type: image/jpeg\r\n\r\n`,
      blob,
      `\r\n--${boundary}--\r\n`,
    ]);
    const r = await this.json("POST", UPLOAD + "/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=" + boundary);
    return r.id;
  }

  async downloadBlob(id) {
    const blob = await (await this.fetch("GET", API + "/files/" + encodeURIComponent(id) + "?alt=media")).blob();
    return blob.type === "image/jpeg" ? blob : new Blob([blob], { type: "image/jpeg" });
  }

  /** Přesune soubor do koše na Disku (dá se odtud ještě 30 dní obnovit). */
  trash(id) {
    return this.request("PATCH", API + "/files/" + encodeURIComponent(id) + "?fields=id", JSON.stringify({ trashed: true }), "application/json; charset=UTF-8");
  }

  findFile(name, folderId) {
    return this.findId(`name = '${esc(name)}' and '${folderId}' in parents and trashed = false`);
  }

  /** true, když soubor pořád existuje a není v koši. */
  async exists(id) {
    try {
      const r = await this.json("GET", API + "/files/" + encodeURIComponent(id) + "?fields=id,trashed");
      return !r.trashed;
    } catch (e) {
      if (e instanceof UnauthorizedError) throw e;
      return false;
    }
  }

  download(id) {
    return this.request("GET", API + "/files/" + encodeURIComponent(id) + "?alt=media");
  }

  async createFile(name, folderId, content) {
    const boundary = "zapisnik" + Date.now();
    const meta = JSON.stringify({ name, parents: [folderId] });
    const body =
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n` +
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${content}\r\n--${boundary}--\r\n`;
    const r = await this.json("POST", UPLOAD + "/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=" + boundary);
    return r.id;
  }

  updateFile(id, content) {
    return this.request("PATCH", UPLOAD + "/files/" + encodeURIComponent(id) + "?uploadType=media", content, "application/json; charset=UTF-8");
  }
}

function esc(s) {
  return s.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}
