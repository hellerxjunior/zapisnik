// Uživatelské rozhraní webové verze. Obrazovky odpovídají Android aplikaci: seznam, detail, úprava, kategorie, účet.
import * as store from "./store.js";
import * as images from "./images.js";
import { UnauthorizedError, cachedToken, configured, requestToken, revoke } from "./drive.js";

const root = document.getElementById("app");
const MONTHS = ["leden", "únor", "březen", "duben", "květen", "červen", "červenec", "srpen", "září", "říjen", "listopad", "prosinec"];
const ONBOARDED_KEY = "zapisnik.onboarded";

/* ---------- pomocné funkce ---------- */

/** Vytvoří element; text se vkládá vždy jako text, nikdy jako HTML. */
function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k === "style") Object.assign(el.style, v);
    else if (k in el && typeof v !== "string") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c.nodeType ? c : String(c));
  return el;
}

const ICONS = {
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  plus: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  account: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 4a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7zm0 14a8 8 0 0 1-6.2-2.95C7.1 15.4 9.4 14.5 12 14.5s4.9.9 6.2 2.55A8 8 0 0 1 12 20z",
  label: "M17.63 5.84A2 2 0 0 0 16 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h11c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z",
  del: "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  image: "M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3 3.5-4.5 4.5 6H5l3.5-4.5z",
  camera: "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM9 2 7.17 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-3.17L15 2H9z",
  close: "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  prev: "M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z",
  next: "M8.59 16.59 10 18l6-6-6-6-1.41 1.41L13.17 12z",
  sync: "M12 4V1L8 5l4 4V6a6 6 0 0 1 5.65 8.03l1.46 1.46A8 8 0 0 0 12 4zm0 14a6 6 0 0 1-5.65-8.03L4.89 8.51A8 8 0 0 0 12 20v3l4-4-4-4v3z",
};
const icon = (name) => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
  p.setAttribute("d", ICONS[name]);
  svg.append(p);
  return svg;
};
const iconBtn = (name, label, onclick) => h("button", { class: "icon-btn", type: "button", "aria-label": label, title: label, onclick }, icon(name));

const color = (index) => `var(--c${index === null || index === undefined ? 7 : ((index % 8) + 8) % 8})`;
const pad = (n) => String(n).padStart(2, "0");
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
const fmtDate = (iso) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || "");
  return m ? `${+m[3]}. ${+m[2]}. ${m[1]}` : iso || "";
};
const monthLabel = (iso) => {
  const m = /^(\d{4})-(\d{2})/.exec(iso || "");
  return m ? `${MONTHS[+m[2] - 1]} ${m[1]}` : "Bez data";
};
const fmtTimestamp = (ms) => {
  const d = new Date(ms);
  return `${d.getDate()}. ${d.getMonth() + 1}. ${d.getFullYear()} ${d.getHours()}:${pad(d.getMinutes())}`;
};
const fold = (s) => s.normalize("NFD").replace(/\p{Mn}+/gu, "").toLowerCase();
const countLabel = (n) => (n === 1 ? "1 záznam" : n >= 2 && n <= 4 ? `${n} záznamy` : `${n} záznamů`);

const sortedCategories = () => [...store.getData().categories].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, "cs"));
const categoryById = (id) => store.getData().categories.find((c) => c.id === id) || null;

let toastTimer = null;
function toast(msg) {
  document.querySelector(".toast")?.remove();
  const t = h("div", { class: "toast", role: "status" }, msg);
  document.body.append(t);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.remove(), 3500);
}

/* ---------- navigace (hash, aby fungovalo tlačítko Zpět) ---------- */

const go = (hash) => (location.hash = hash);
const back = () => (history.length > 1 ? history.back() : go("#/"));
function route() {
  const [, view = "", arg] = location.hash.replace(/^#/, "").split("/");
  return { view, arg: arg ? decodeURIComponent(arg) : null };
}

/* ---------- synchronizace z rozhraní ---------- */

async function syncNow() {
  if (!store.signedIn()) return go("#/ucet");
  try {
    // Kliknutí dovolí otevřít okno Google; token z této relace se použije bez okna.
    const token = cachedToken() || (await requestToken(false, store.getMeta().email));
    await store.sync(token);
    toast("Synchronizováno s Google Diskem.");
  } catch (e) {
    toast(e instanceof UnauthorizedError ? "Přihlášení vypršelo, klikni znovu na Synchronizovat." : e.message);
  }
}

async function signIn() {
  try {
    const token = await requestToken(true);
    await store.signIn(token);
    markOnboarded();
    toast("Přihlášeno a synchronizováno.");
    go("#/");
  } catch (e) {
    toast(e.message);
  }
  render();
}

function markOnboarded() {
  try {
    localStorage.setItem(ONBOARDED_KEY, "1");
  } catch {}
}
function onboarded() {
  try {
    return store.signedIn() || localStorage.getItem(ONBOARDED_KEY) === "1";
  } catch {
    return true;
  }
}

function statusBadge() {
  const m = store.getMeta();
  let cls = "", text;
  if (!m.email) text = "Jen v tomto prohlížeči";
  else if (m.busy) (cls = "warn"), (text = "Synchronizuji…");
  else if (m.lastError) (cls = "err"), (text = "Synchronizace selhala");
  else if (m.dirty) (cls = "warn"), (text = "Neodeslané změny");
  else (cls = "ok"), (text = m.lastSync ? "Synchronizováno " + fmtTimestamp(m.lastSync) : "Připojeno");
  return h("span", { class: "status " + cls, title: text }, h("span", { class: "pip" }), h("span", { class: "label" }, text));
}

/* ---------- obrázky ---------- */

/** Náhled obrázku; načte se z IndexedDB, dokud se nestáhne z Disku, ukazuje zástupný text. */
function thumb(id, onclick, onremove) {
  const img = h("img", { alt: "", loading: "lazy" });
  const box = h(
    "div",
    { class: "thumb" },
    h("button", { class: "thumb-open", type: "button", "aria-label": "Zobrazit obrázek", onclick }, img),
    onremove && h("button", { class: "thumb-remove", type: "button", "aria-label": "Odebrat obrázek", onclick: onremove }, icon("close")),
  );
  images.objectUrl(id).then((u) => {
    if (u) img.src = u;
    else box.classList.add("missing");
  });
  return box;
}

function gallery(ids, onremove) {
  return h("div", { class: "gallery" }, ids.map((id, i) => thumb(id, () => viewer(ids, i), onremove && (() => onremove(id)))));
}

/** Prohlížení přes celou obrazovku, šipky a Esc na klávesnici, na mobilu tlačítka. */
function viewer(ids, start) {
  let i = start;
  const img = h("img", { alt: "" });
  const counter = h("span", { class: "viewer-count" });
  const missing = h("p", { class: "viewer-missing", hidden: true }, "Obrázek se ještě stahuje z Disku…");
  let url = null;
  async function show() {
    counter.textContent = ids.length > 1 ? `${i + 1} / ${ids.length}` : "";
    const blob = await images.get(ids[i]);
    if (url) URL.revokeObjectURL(url);
    url = blob ? URL.createObjectURL(blob) : null;
    img.hidden = !url;
    missing.hidden = !!url;
    if (url) img.src = url;
  }
  const step = (d) => ((i = (i + d + ids.length) % ids.length), show());
  const close = () => {
    if (!dlg.isConnected) return;
    if (dlg.open) dlg.close();
    dlg.remove();
    if (url) URL.revokeObjectURL(url);
  };
  const nav = ids.length > 1;
  const dlg = h(
    "dialog",
    {
      class: "viewer",
      "aria-label": "Obrázek",
      onclose: close,
      oncancel: (e) => (e.preventDefault(), close()),
      onkeydown: (e) => (e.key === "ArrowLeft" ? step(-1) : e.key === "ArrowRight" ? step(1) : e.key === "Escape" ? close() : null),
      onclick: (e) => e.target === dlg && close(),
    },
    img,
    missing,
    h("div", { class: "viewer-bar" }, counter, iconBtn("close", "Zavřít", close)),
    nav && h("div", { class: "viewer-nav" }, iconBtn("prev", "Předchozí", () => step(-1)), iconBtn("next", "Další", () => step(1))),
  );
  document.body.append(dlg);
  dlg.showModal();
  show();
}

/** Obrázky ve formuláři: náhledy s odebráním, výběr souborů a na telefonu i fotoaparát. */
function imagesEditor(initial) {
  let ids = [...initial];
  let pending = 0;
  const list = h("div");
  const status = h("span", { class: "small muted" });
  const draw = () => list.replaceChildren(ids.length ? gallery(ids, (id) => ((ids = ids.filter((x) => x !== id)), draw())) : "");
  async function add(files) {
    pending += files.length;
    status.textContent = "Zpracovávám obrázky…";
    for (const f of files) {
      try {
        ids.push(await images.importFile(f));
        draw();
      } catch (e) {
        toast(e.message);
      }
      pending--;
    }
    status.textContent = "";
  }
  const input = (capture) =>
    h("input", {
      type: "file",
      accept: "image/*",
      multiple: !capture,
      capture: capture ? "environment" : undefined,
      hidden: true,
      onchange: (e) => {
        add([...e.target.files]);
        e.target.value = "";
      },
    });
  const pickInput = input(false);
  const cameraInput = input(true);
  const touch = matchMedia("(pointer: coarse)").matches;
  draw();
  return {
    ids: () => [...ids],
    busy: () => pending > 0,
    el: h(
      "div",
      { class: "field" },
      h("span", {}, "Obrázky"),
      list,
      h(
        "div",
        { class: "row" },
        h("button", { class: "btn", type: "button", onclick: () => pickInput.click() }, icon("image"), "Přidat obrázky"),
        touch && h("button", { class: "btn", type: "button", onclick: () => cameraInput.click() }, icon("camera"), "Vyfotit"),
        status,
      ),
      pickInput,
      cameraInput,
    ),
  };
}

/* ---------- obrazovky ---------- */

const ui = { query: "", filter: null }; // filter: null = vše, "" = bez kategorie

function welcomeView() {
  return h(
    "section",
    { class: "welcome" },
    h("div", { style: { width: "16px", height: "40px", borderRadius: "4px", background: "var(--accent)" } }),
    h("h1", {}, "Zápisník"),
    h(
      "p",
      { class: "muted" },
      "Zapisuj si opravy, servisy a další důležité události. Po přihlášení se záznamy přes tvůj Google Disk synchronizují s aplikací v telefonu.",
    ),
    h("button", { class: "btn accent wide", type: "button", onclick: signIn, disabled: !configured() }, "Přihlásit se účtem Google"),
    !configured() && h("p", { class: "small error" }, "Přihlášení zatím není nastavené: v souboru config.js chybí Client ID."),
    h("button", { class: "btn text", type: "button", onclick: () => (markOnboarded(), render()) }, "Pokračovat bez přihlášení (data jen v tomto prohlížeči)"),
  );
}

function listView() {
  const results = h("div");
  const chips = h("div", { class: "chips", role: "toolbar", "aria-label": "Filtr kategorií" });
  const status = h("button", { class: "btn text", type: "button", style: { padding: "0 6px" }, onclick: () => go("#/ucet") });

  function update() {
    const { entries } = store.getData();
    const cats = sortedCategories();
    status.replaceChildren(statusBadge());
    status.setAttribute("aria-label", "Synchronizace: " + status.textContent);

    const chip = (label, dotColor, n, value) =>
      h(
        "button",
        { class: "chip", type: "button", "aria-pressed": String(ui.filter === value), onclick: () => ((ui.filter = ui.filter === value ? null : value), update()) },
        dotColor && h("span", { class: "dot", style: { background: dotColor } }),
        label,
        h("span", { class: "n" }, n),
      );
    const uncategorized = entries.filter((e) => !e.categoryId || !categoryById(e.categoryId)).length;
    chips.replaceChildren(
      chip("Vše", null, entries.length, null),
      ...cats.map((c) => chip(c.name, color(c.color), entries.filter((e) => e.categoryId === c.id).length, c.id)),
      ...(uncategorized > 0 ? [chip("Bez kategorie", color(null), uncategorized, "")] : []),
    );

    const q = fold(ui.query.trim());
    const shown = entries
      .filter((e) => (ui.filter === null ? true : ui.filter === "" ? !e.categoryId || !categoryById(e.categoryId) : e.categoryId === ui.filter))
      .filter((e) => !q || fold(e.title + " " + e.text + " " + (categoryById(e.categoryId)?.name || "")).includes(q))
      .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : b.updated - a.updated));

    if (!shown.length) {
      results.replaceChildren(
        h("p", { class: "empty" }, entries.length ? "Nic neodpovídá hledání." : "Zatím tu nic není. Přidej první záznam tlačítkem Nový záznam."),
      );
      return;
    }
    const out = [h("p", { class: "small muted", style: { margin: "12px 4px 0" } }, countLabel(shown.length))];
    let month = null;
    for (const e of shown) {
      const m = monthLabel(e.date);
      if (m !== month) out.push(h("h2", { class: "month" }, (month = m)));
      const cat = categoryById(e.categoryId);
      out.push(
        h(
          "button",
          { class: "card", type: "button", onclick: () => go("#/zaznam/" + encodeURIComponent(e.id)) },
          h("span", { class: "stripe", style: { background: color(cat?.color) } }),
          h(
            "span",
            { class: "body" },
            h("span", { class: "title" }, e.title || "(bez názvu)"),
            h(
              "span",
              { class: "meta" },
              fmtDate(e.date),
              cat && h("span", { style: { color: color(cat.color), fontWeight: 600 } }, cat.name),
              e.images.length > 0 && h("span", { class: "img-count", title: "Obrázky: " + e.images.length }, icon("image"), e.images.length),
            ),
            e.text && h("span", { class: "snip" }, e.text),
          ),
        ),
      );
    }
    results.replaceChildren(...out);
  }

  const search = h("input", {
    class: "search",
    type: "search",
    placeholder: "Hledat v záznamech",
    "aria-label": "Hledat v záznamech",
    value: ui.query,
    oninput: (ev) => ((ui.query = ev.target.value), update()),
  });

  const view = h(
    "div",
    {},
    h(
      "header",
      { class: "bar" },
      h("h1", { class: "brand" }, "Zápisník"),
      status,
      iconBtn("label", "Kategorie", () => go("#/kategorie")),
      iconBtn("account", "Účet a synchronizace", () => go("#/ucet")),
    ),
    search,
    chips,
    results,
    h("button", { class: "fab", type: "button", onclick: () => go("#/upravit/novy") }, icon("plus"), "Nový záznam"),
  );
  update();
  view.update = update;
  return view;
}

function detailView(id) {
  const e = store.getData().entries.find((x) => x.id === id);
  if (!e) return notFound();
  const cat = categoryById(e.categoryId);
  return h(
    "div",
    {},
    h(
      "header",
      { class: "bar" },
      iconBtn("back", "Zpět", back),
      h("h1", {}, ""),
      iconBtn("edit", "Upravit", () => go("#/upravit/" + encodeURIComponent(e.id))),
    ),
    h(
      "article",
      { class: "panel" },
      h("div", { class: "row small" }, cat && h("span", { class: "detail-cat", style: { color: color(cat.color) } }, cat.name), h("span", { class: "muted" }, fmtDate(e.date))),
      h("h2", { class: "detail-title" }, e.title || "(bez názvu)"),
      e.text ? h("div", { class: "detail-text" }, e.text) : h("p", { class: "muted" }, "Bez poznámky."),
      e.images.length > 0 && gallery(e.images),
      h("p", { class: "small muted", style: { marginTop: "20px" } }, "Upraveno " + fmtTimestamp(e.updated)),
    ),
    h(
      "div",
      { class: "row end" },
      h(
        "button",
        {
          class: "btn danger",
          type: "button",
          onclick: () => {
            if (!confirm(`Smazat záznam „${e.title}“?`)) return;
            store.deleteEntry(e.id);
            toast("Záznam smazán.");
            location.replace("#/");
          },
        },
        "Smazat",
      ),
    ),
  );
}

function editView(id) {
  const existing = id === "novy" ? null : store.getData().entries.find((x) => x.id === id);
  if (id !== "novy" && !existing) return notFound();
  const title = h("input", { name: "title", required: true, maxlength: "200", value: existing?.title ?? "", autocomplete: "off" });
  const date = h("input", { name: "date", type: "date", required: true, value: existing?.date || today() });
  const category = h(
    "select",
    { name: "category" },
    h("option", { value: "" }, "Bez kategorie"),
    ...sortedCategories().map((c) => h("option", { value: c.id, selected: existing?.categoryId === c.id }, c.name)),
  );
  if (!existing && ui.filter) category.value = ui.filter;
  const text = h("textarea", { name: "text", placeholder: "Co se dělalo, cena, kdo opravoval, díly…" });
  text.value = existing?.text ?? "";
  const imageEditor = imagesEditor(existing?.images ?? []);

  const form = h(
    "form",
    {
      class: "edit",
      onsubmit: (ev) => {
        ev.preventDefault();
        if (!title.value.trim()) return title.focus();
        if (imageEditor.busy()) return toast("Počkej, obrázky se ještě zpracovávají.");
        const newId = store.saveEntry(existing, {
          title: title.value,
          date: date.value,
          categoryId: category.value || null,
          text: text.value,
          images: imageEditor.ids(),
        });
        location.replace("#/zaznam/" + encodeURIComponent(newId));
      },
    },
    h("label", { class: "field" }, "Název", title),
    h("div", { class: "row" }, h("label", { class: "field", style: { flex: "1 1 160px" } }, "Datum", date), h("label", { class: "field", style: { flex: "1 1 160px" } }, "Kategorie", category)),
    h("label", { class: "field" }, "Poznámka", text),
    imageEditor.el,
    h("div", { class: "row end" }, h("button", { class: "btn", type: "button", onclick: back }, "Zrušit"), h("button", { class: "btn accent", type: "submit" }, "Uložit")),
  );
  const view = h("div", {}, h("header", { class: "bar" }, iconBtn("back", "Zpět", back), h("h1", {}, existing ? "Upravit záznam" : "Nový záznam")), form);
  view.keep = true; // při synchronizaci nepřekreslovat rozepsaný formulář
  setTimeout(() => !existing && title.focus(), 0);
  return view;
}

function categoriesView() {
  let openPalette = null;
  const list = h("div", { class: "panel" });

  function draw() {
    const cats = sortedCategories();
    const entries = store.getData().entries;
    list.replaceChildren(
      ...cats.flatMap((c) => {
        const row = h(
          "div",
          { class: "cat-row" },
          h("button", {
            class: "swatch",
            type: "button",
            "aria-label": "Barva kategorie " + c.name,
            style: { background: color(c.color) },
            onclick: () => ((openPalette = openPalette === c.id ? null : c.id), draw()),
          }),
          h("input", {
            value: c.name,
            "aria-label": "Název kategorie",
            maxlength: "60",
            onchange: (ev) => {
              const name = ev.target.value.trim();
              if (name && name !== c.name) store.updateCategory({ ...c, name });
              else ev.target.value = c.name;
            },
          }),
          h("span", { class: "small muted" }, entries.filter((e) => e.categoryId === c.id).length),
          iconBtn("del", "Smazat kategorii " + c.name, () => {
            const n = entries.filter((e) => e.categoryId === c.id).length;
            if (!confirm(`Smazat kategorii „${c.name}“?` + (n ? ` ${countLabel(n)} zůstane bez kategorie.` : ""))) return;
            store.deleteCategory(c.id);
          }),
        );
        const palette =
          openPalette === c.id &&
          h(
            "div",
            { class: "palette" },
            [...Array(8).keys()].map((i) =>
              h("button", {
                class: "swatch",
                type: "button",
                "aria-label": "Barva " + (i + 1),
                "aria-pressed": String(c.color === i),
                style: { background: color(i) },
                onclick: () => store.updateCategory({ ...c, color: i }),
              }),
            ),
          );
        return [row, palette].filter(Boolean);
      }),
    );
    if (!cats.length) list.replaceChildren(h("p", { class: "muted" }, "Žádné kategorie."));
  }
  draw();

  const name = h("input", { class: "search", placeholder: "Nová kategorie", "aria-label": "Název nové kategorie", maxlength: "60", style: { flex: "1" } });
  const view = h(
    "div",
    {},
    h("header", { class: "bar" }, iconBtn("back", "Zpět", back), h("h1", {}, "Kategorie")),
    list,
    h(
      "form",
      {
        class: "row",
        onsubmit: (ev) => {
          ev.preventDefault();
          if (!name.value.trim()) return;
          store.addCategory(name.value);
          name.value = "";
        },
      },
      name,
      h("button", { class: "btn accent", type: "submit" }, "Přidat"),
    ),
  );
  view.update = draw;
  return view;
}

function accountView() {
  const m = store.getMeta();
  return h(
    "div",
    {},
    h("header", { class: "bar" }, iconBtn("back", "Zpět", back), h("h1", {}, "Účet a synchronizace")),
    h(
      "section",
      { class: "panel" },
      m.email
        ? [
            h("div", { class: "small muted" }, "Přihlášen jako"),
            h("div", { style: { fontWeight: 700, fontSize: "18px", overflowWrap: "anywhere" } }, m.email),
            h("p", { class: "muted", style: { margin: "6px 0 0" } }, m.lastSync ? "Poslední synchronizace: " + fmtTimestamp(m.lastSync) : "Zatím nesynchronizováno"),
            m.dirty && h("p", { class: "small", style: { margin: "4px 0 0" } }, "Některé změny ještě nejsou na Disku."),
            m.lastError && h("p", { class: "small error", style: { margin: "4px 0 0" } }, m.lastError),
          ]
        : [
            h("div", { style: { fontWeight: 700, fontSize: "18px" } }, "Nejsi přihlášen"),
            h("p", { class: "muted", style: { margin: "6px 0 0" } }, "Záznamy jsou jen v tomto prohlížeči. Po přihlášení se synchronizují přes Google Disk s aplikací v telefonu."),
          ],
    ),
    m.email
      ? h(
          "div",
          { class: "row" },
          h("button", { class: "btn primary", type: "button", onclick: syncNow, disabled: m.busy }, icon("sync"), m.busy ? "Synchronizuji…" : "Synchronizovat teď"),
          h(
            "button",
            {
              class: "btn text",
              type: "button",
              disabled: m.busy,
              onclick: () => {
                revoke(cachedToken());
                store.signOut();
                toast("Odhlášeno. Data v prohlížeči zůstala.");
              },
            },
            "Odhlásit se",
          ),
        )
      : h("button", { class: "btn accent wide", type: "button", onclick: signIn, disabled: !configured() }, "Přihlásit se účtem Google"),
    !configured() && h("p", { class: "small error" }, "Přihlášení zatím není nastavené: v souboru config.js chybí Client ID."),
    h(
      "p",
      { class: "small muted", style: { marginTop: "24px" } },
      "Data se ukládají do souboru zapisnik-zaloha.json ve složce Zápisník na tvém Google Disku, stejně jako v telefonu. " +
        "Web vidí jen soubory, které vytvořil Zápisník. Synchronizuje se po otevření stránky a pár sekund po každé změně; " +
        "přihlášení v prohlížeči platí asi hodinu, pak stačí kliknout na Synchronizovat teď.",
    ),
  );
}

function notFound() {
  return h("div", {}, h("header", { class: "bar" }, iconBtn("back", "Zpět", () => go("#/")), h("h1", {}, "")), h("p", { class: "empty" }, "Záznam nebyl nalezen, možná byl smazán."));
}

/* ---------- vykreslení ---------- */

let current = null;
let currentKey = null;

function render() {
  const { view, arg } = route();
  const key = view + "/" + arg;
  if (current && key === currentKey) {
    if (current.keep) return;
    if (current.update) return current.update();
  }
  let next;
  if (!onboarded()) next = welcomeView();
  else if (view === "zaznam") next = detailView(arg);
  else if (view === "upravit") next = editView(arg);
  else if (view === "kategorie") next = categoriesView();
  else if (view === "ucet") next = accountView();
  else next = listView();
  const scroll = key === currentKey ? window.scrollY : 0;
  images.revokeAll();
  current = next;
  currentKey = key;
  root.replaceChildren(next);
  window.scrollTo(0, scroll);
}

store.subscribe(render);
images.onChange(render);
window.addEventListener("hashchange", render);
render();

// Po otevření a návratu na stránku stáhnout změny z telefonu (jen když je platné přihlášení z této relace).
store.scheduleSync(0);
document.addEventListener("visibilitychange", () => document.visibilityState === "visible" && store.scheduleSync(0));
window.addEventListener("online", () => store.scheduleSync(0));

if ("serviceWorker" in navigator && (location.protocol === "https:" || location.hostname === "localhost")) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
