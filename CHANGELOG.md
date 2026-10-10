# Ändringslogg

Alla märkbara ändringar i Tandem. Avsnittet för en version blir texten i dess GitHub Release
(`release.yml` kräver ett avsnitt för versionen, se [docs/RELEASE.md](docs/RELEASE.md)).
Rubriken är `## [X.Y.Z] – ÅÅÅÅ-MM-DD`.

## [Unreleased]

## [0.0.2] – 2026-10-10

- **Rättat:** inloggningen kraschade i release-bygget (0.0.1) när man tryckte på *Logga in*.
  R8, som krymper release-bygget, hade av misstag gjort inloggningskoden oanvändbar, något som
  bara syntes i det minifierade bygget. Regeln som skyddar Tandoor-anropen gäller nu alla
  nuvarande och framtida anrop, så felet kan inte komma tillbaka med nästa funktion.
- **Kraschlogg:** kraschar Tandem sparas en kort logg på telefonen (appversion, Android-version,
  enhetsmodell och var i koden det hände; inga lösenord, token eller webbadresser). Nästa gång
  visar välkomstskärmen och Mer *Tandem kraschade förra gången* med *Visa*, *Kopiera*, *Dela* och
  *Ta bort*. Ingenting skickas automatiskt; bara de tre senaste loggarna sparas.
- **Release-röktest på emulator:** varje version loggar nu in (med lösenord och med inklistrad
  token), överlever processdöd och kallstart och loggar ut med just release-bygget på en
  Android-emulator innan den släpps.
- Ett oväntat fel under inloggningen visar nu ett felmeddelande i stället för att stänga appen.
- **Rättat:** loggade man ut med en inklistrad token stod det bara "Utloggad". Nu får man veta att
  token fortfarande gäller och kan raderas i Tandoor (Inställningar → API).
- *Visa detaljer* visar hela klassnamn, så att en felrapport från ett release-bygge går att spåra.
- **Rättat:** "Det här ser inte ut som Tandoor" kunde ibland visas för en riktig Tandoor när
  adressen kontrollerades (svaret lästes på fel tråd).

## [0.0.1] – 2026-10-10

Första versionen (Fas 0): grunden att bygga vidare på. Inga recept, ingen veckoplan och ingen
inköpslista än; de kommer i nästa version.

- **Anslut till din egen Tandoor** (2.6 testad; 2.0–2.5 och nyare versioner fungerar i säkert
  läge). Ange adressen, även med en undersökväg som `example.com/recipes`, eller klistra in en
  länk från webbläsaren. Tydliga fel för fel adress, självsignerade certifikat, blockerad åtkomst
  och för gamla versioner.
- **Inloggning** med användarnamn och lösenord: lösenordet sparas aldrig, appen skapar en egen
  token för den här enheten. Alternativt kan en token från Tandoor (Inställningar → API) klistras
  in; en inklistrad token raderas aldrig av appen.
- **Krypterad lagring** av konto och token (Android Keystore). Kan lagringen inte läsas visas en
  återställningsskärm i stället för en krasch; tillfälliga Keystore-fel raderar ingenting.
- **Appskal med fem flikar** (Idag, Recept, Veckan, Handla, Mer): bottenrad på telefonen,
  navigationsrail på surfplattan och när telefonen ligger ner.
- **Mer:** konto, server och Tandoor-version (kontrolleras igen när appen öppnas, högst var 15:e
  minut), språk (svenska eller engelska, eller systemets språk), tema (system, ljust, mörkt) och
  Om Tandem med licenser. Utloggning återkallar enhetens egen token om du vill.
- **Demoläge** för den som inte har någon Tandoor än.
- Helt på **svenska och engelska**.
