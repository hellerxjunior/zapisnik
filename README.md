# Zápisník

Zápisník oprav, servisů a dalších důležitých událostí. Dvě verze se společnými daty:

- **Android aplikace** (Kotlin, Jetpack Compose), složka `app/`. Data v telefonu (databáze Room).
- **Webová verze** (statické HTML/JS, PWA), složka `web/`. Data v prohlížeči (localStorage), určená pro GitHub Pages.

Obě se po přihlášení účtem Google synchronizují přes soubor `zapisnik-zaloha.json` ve složce **Zápisník** na Google Disku.

## Synchronizace

- Každé zařízení stáhne soubor z Disku, sloučí ho se svými daty a výsledek nahraje zpět.
- Záznamy a kategorie se párují podle `id`, vyhrává novější čas změny (`updated`).
- Smazání se ukládá jako záznam v poli `deleted`, aby se smazaná položka nevrátila z jiného zařízení.
- Logika je dvakrát a musí zůstat stejná: `app/.../backup/SyncMerge.kt` a `web/sync.js`.
- Obrázky: záznam má pole `images` s id obrázků. Každý obrázek je samostatný soubor `<id>.jpg` v podsložce **Zápisník/Obrázky**. Při přidání se zmenší na nejvýš 1600 px (JPEG). Synchronizace nejdřív nahraje nové obrázky, pak seznam a nakonec stáhne chybějící. Obrázky, na které už nic neodkazuje, se z Disku přesunou do koše až po týdnu.
- Přílohy (libovolné soubory do 25 MB): záznam má pole `files` s položkami `{ id, name, type, size }`. Soubor je na Disku v podsložce **Zápisník/Soubory** pod názvem `<id>.<přípona>`, původní název je v seznamu. Synchronizují se stejně jako obrázky.
- Telefon synchronizuje při otevření aplikace a asi 20 s po změně. Web po otevření stránky a pár sekund po změně.
- Google Disk si u souboru pamatuje starší verze (Spravovat verze), takže se dá vrátit k dřívějšímu stavu.

Oprávnění je jen `drive.file`: aplikace vidí pouze soubory, které sama vytvořila. Soubor nebo složka nahraná na Disk ručně (přes web Disku) pro Zápisník neexistuje a aplikace si založí vlastní.

## Nastavení Google Cloud (jednou)

Vše v **jednom** projektu na console.cloud.google.com. Klienti z různých projektů by si navzájem neviděli soubor.

1. V APIs & Services → Library zapni **Google Drive API**.
2. V OAuth consent screen zvol typ External, vyplň název a e‑mail a svůj účet přidej do Test users.
3. Credentials → Create credentials → OAuth client ID, typ **Android**: balíček `cz.zapisnik.app`, SHA‑1 níže.
4. Credentials → Create credentials → OAuth client ID, typ **Web application**:
   - Authorized JavaScript origins: `https://<uživatel>.github.io` (bez cesty a bez lomítka na konci).
   - Pro zkoušení na počítači můžeš přidat i `http://localhost:8765`.
   - Redirect URI netřeba.
5. Client ID webového klienta (`…apps.googleusercontent.com`) vlož do `web/config.js` do `CLIENT_ID`. Client ID není tajné.

## Android

- Balíček: `cz.zapisnik.app`
- Podpisový klíč: `podpis/zapisnik-release.jks`, hesla jsou v `podpis/keystore.properties`. Klíč neztrať: aktualizace aplikace i přihlášení Google jsou na něj vázané. Složka `podpis/` nesmí do gitu (je v `.gitignore`).
- SHA‑1 otisk klíče: `1B:DE:C8:A7:D8:AC:B3:80:CA:1D:7F:37:5D:E5:FC:E7:2C:78:14:57`

```
./gradlew testDebugUnitTest assembleRelease
```

Výsledek: `app/build/outputs/apk/release/app-release.apk`. Potřebuje JDK 17–21 (s JDK 25 tento Gradle nefunguje) a Android SDK (`ANDROID_HOME` nebo `local.properties` se `sdk.dir`).

## Web

Soubory ve `web/`: `index.html`, `app.js` (obrazovky), `store.js` (data a synchronizace), `sync.js` (formát a slučování), `drive.js` (přihlášení Google Identity Services a Drive API), `config.js`, `sw.js` a `manifest.webmanifest` (instalace a offline režim).

Zkouška na počítači:

```
python -m http.server 8765 -d web
```

a otevřít http://localhost:8765. Testy (bez sítě, falešný Disk v paměti):

```
node web/sync.test.mjs
node web/store.test.mjs
```

### GitHub Pages

Stránky se publikují ze složky `web/` přes GitHub Actions (`.github/workflows/pages.yml`). V repozitáři: Settings → Pages → Source: **GitHub Actions**. Adresa pak bude `https://<uživatel>.github.io/<repozitář>/`.

Přihlášení v prohlížeči platí asi hodinu. Pak stačí kliknout na stav synchronizace a na Synchronizovat teď.
