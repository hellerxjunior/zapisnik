// Test sloučení: node web/sync.test.mjs
import assert from "node:assert/strict";
import { decode, encode, merge, same, DEFAULT_CATEGORIES } from "./sync.js";

const e = (id, updated, extra = {}) => ({ id, title: id, date: "2026-09-28", categoryId: "auto", text: "", created: 1, updated, ...extra });
const snap = (entries = [], categories = DEFAULT_CATEGORIES, deleted = []) => ({ entries, categories, deleted });

// novější úprava vyhrává, z obou stran se doplní chybějící
{
  const phone = snap([e("a", 10, { title: "telefon" }), e("b", 5)]);
  const pc = snap([e("a", 20, { title: "počítač" }), e("c", 7)]);
  const m = merge(phone, pc);
  assert.deepEqual(m.entries.map((x) => x.id), ["a", "b", "c"]);
  assert.equal(m.entries[0].title, "počítač");
}

// smazání vyhrává nad starší verzí, ale ne nad pozdější úpravou
{
  const phone = snap([], DEFAULT_CATEGORIES, [{ kind: "entry", id: "a", at: 15 }]);
  assert.equal(merge(phone, snap([e("a", 10)])).entries.length, 0);
  assert.equal(merge(phone, snap([e("a", 20)])).entries.length, 1);
}

// smazaná kategorie zmizí a záznamy v ní jsou bez kategorie
{
  const phone = snap([], DEFAULT_CATEGORIES.filter((c) => c.id !== "auto"), [{ kind: "category", id: "auto", at: 5 }]);
  const m = merge(phone, snap([e("a", 1)]));
  assert.ok(!m.categories.some((c) => c.id === "auto"));
  assert.equal(m.entries[0].categoryId, null);
}

// verze 1 ze staré aplikace se přečte, sloučení je idempotentní a formát projde tam a zpět
{
  const v1 = { app: "zapisnik", version: 1, exported: 1, categories: [{ id: "auto", name: "Auto", color: 0, sortOrder: 0 }], entries: [e("a", 3)] };
  const r = decode(JSON.stringify(v1));
  assert.equal(r.categories[0].updated, 0);
  assert.deepEqual(r.deleted, []);
  const m = merge(r, r);
  assert.ok(same(m, r));
  assert.ok(same(decode(encode(m)), m));
}

assert.throws(() => decode('{"app":"jina"}'));
console.log("sync.js: vše v pořádku");
