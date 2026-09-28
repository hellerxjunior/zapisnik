// Nastavení webové verze. CLIENT_ID je OAuth klient typu „Webová aplikace“ z Google Cloud,
// ze STEJNÉHO projektu jako Android klient, jinak web neuvidí soubor, který vytvořil telefon (oprávnění drive.file).
// Vypadá jako 123456789-abc...apps.googleusercontent.com. Není tajný, může být veřejně na GitHubu.
export const CLIENT_ID = "697561300499-u13m4t5u7ok4m0hdbtt70s6bqa22nhkn.apps.googleusercontent.com";

export const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
export const FOLDER = "Zápisník";
export const FILE = "zapisnik-zaloha.json";
export const IMAGES = "Obrázky";
export const FILES = "Soubory";
