# Släppa en version av Tandem

> Allt byggs och signeras i GitHub Actions ([`release.yml`](../.github/workflows/release.yml)).
> Signeringsnyckeln finns bara i GitHub Secrets, i din lösenordshanterare och i en
> offline-backup. Den checkas aldrig in.

## 1. Skapa signeringsnyckeln (en gång)

Kör skriptet **själv**, på en betrodd dator med `openssl` och en inloggad `gh` (admin i repot):

```sh
scripts/create-signing-key.sh
```

Skriptet:

1. Skapar `~/tandem-signing/tandem-release.p12`, en PKCS12-nyckelfil med RSA 4096, alias
   `tandem` och 30 års giltighet, och ett slumpat lösenord i `~/tandem-signing/keystore-password.txt`.
   Mappen får rättigheterna 700 och filerna 600. Skriptet vägrar skriva över en befintlig nyckel.
2. Sätter de fyra secrets som `release.yml` läser, i GitHub-miljön (environment) **`release`**:
   `TANDEM_KEYSTORE_BASE64`, `TANDEM_KEYSTORE_PASSWORD`, `TANDEM_KEY_ALIAS` och
   `TANDEM_KEY_PASSWORD`. Miljön får bara användas av `v*`-taggar, så torrkörningar och grenar
   kommer aldrig åt nyckeln. Miljön måste finnas innan skriptet körs. Värdena skickas till
   `gh` via stdin och syns aldrig i terminalen eller i processlistan. PKCS12 har bara ett lösenord,
   så nyckellösenordet är samma som filens.
3. Sätter **variabeln** (inte secret) `TANDEM_CERT_SHA256`, certifikatets fingeravtryck.
   `release.yml` vägrar publicera en APK som är signerad med något annat certifikat.
4. Skriver bara ut certifikatets SHA-256-fingeravtryck. Det behövs också för Androids
   utvecklarverifiering (plan §4.4).

Ladda upp samma nyckel igen (t.ex. efter att en secret raderats av misstag):
`scripts/create-signing-key.sh --upload-only`. Andra platser eller repon:
`TANDEM_SIGNING_DIR=… TANDEM_REPO=ägare/repo scripts/create-signing-key.sh`.

### Offline-backup (gör det direkt)

**Försvinner nyckeln kan ingen uppdatering någonsin installeras över appen.** Alla måste då
avinstallera appen (och förlora lokal data) och installera om.

1. Lägg **både** `tandem-release.p12` och `keystore-password.txt` som bilagor i
   lösenordshanteraren.
2. Gör en offline-kopia: t.ex. en krypterad fil (`age`/`gpg` med ett separat lösenord) på ett
   USB-minne som förvaras hemma. Testa att kopian går att öppna.
   Lägg gärna R8-mappningsfilerna (`tandem-X.Y.Z-sideload-mapping.txt` från varje release) i
   samma backup: utan rätt mappningsfil går en krasch-stackspårning inte att läsa.
3. Ta bort `~/tandem-signing` från datorn när backuperna är kontrollerade, om datorn inte är din
   vanliga, betrodda dator.

## 2. Släppa en version

1. Höj `versionCode` (alltid +1) och `versionName` i `app/build.gradle.kts`. Taggen måste vara
   `v` + `versionName`, annars stoppar `release.yml` innan något byggs.
2. Skriv avsnittet för versionen i `CHANGELOG.md` (svenska, **krävs**), med rubriken
   `## [0.0.1] – 2026-10-10`, `## v0.0.1` eller `## 0.0.1`. Saknas avsnittet stoppar
   `release.yml` innan något byggs (plan §4.4: release med svensk changelog). En torrkörning
   varnar bara.
3. Committa till `main` när CI är grönt, och tagga:

   ```sh
   git tag -a v0.0.1 -m "Tandem 0.0.1"
   git push origin v0.0.1
   ```

## 3. Vad `release.yml` gör

Workflowet har två egna jobb och anropar ett tredje (`smoke`, se tabellen). **build** kör all byggkod (Gradle, AGP, KSP, beroenden) med en token
som bara får **läsa** repot, och `actions/checkout` sparar inga git-uppgifter
(`persist-credentials: false`). **publish** är det enda jobbet som får skriva (`contents: write`);
det checkar inte ut något och kör ingen byggkod, utan hämtar bara den kontrollerade artefakten och
kör `gh release create`. Det körs bara för en riktig release.

| Jobb / steg | Innehåll |
|---|---|
| mode | Tagg `v*` som pushas ⇒ riktig release. Allt annat ⇒ torrkörning (se nedan). Kontrollerar att taggen är `v<versionName>` och att taggens commit finns på `main`. Kör ingen byggkod. |
| build: Miljö | En riktig release körs i miljön `release` (secrets, bara `v*`-taggar), en torrkörning i den tomma miljön `dry-run`. |
| build: Secrets | Saknas någon av de fyra secrets stoppas jobbet **innan** något byggs, med ett tydligt felmeddelande. |
| build: Release notes | Avsnittet för versionen ur `CHANGELOG.md` blir `release-notes.md`. Saknas det stoppas en riktig release här. |
| build: Nyckelfil | `TANDEM_KEYSTORE_BASE64` avkodas till `$RUNNER_TEMP/tandem-release.p12` (PKCS12) och kontrolleras med `keytool -list`. Filen raderas när steget är klart. Lösenorden finns bara i det steget. |
| build: Bygge | `:app:assembleSideloadRelease` (R8 + resurskrympning) och `:app:bundlePlayRelease`, utan configuration cache (den skulle spara lösenorden). Signeringen läses i `app/build.gradle.kts` bara från `TANDEM_KEYSTORE_PATH`, `TANDEM_KEYSTORE_PASSWORD`, `TANDEM_KEY_ALIAS` och `TANDEM_KEY_PASSWORD`; utan dem blir release-bygget osignerat. Signeringsschema v3 (med minSdk 34 hoppar signeraren över v2 och v1). |
| build: Kontroll | `apksigner verify --print-certs --min-sdk-version 34`, kräver "Verifies", schema v3 och exakt en signerare, jämför fingeravtrycket med `TANDEM_CERT_SHA256` (saknas variabeln stoppas en riktig release). `SHA256SUMS` täcker APK:n och mappningsfilen. |
| build: Artefakt | `dist/` (APK, `tandem-X.Y.Z-sideload-mapping.txt`, `SHA256SUMS`, `release-notes.md`) och Play-AAB:n, 90 dagar. AAB:n laddas inte upp till Play ännu (Fas P). |
| smoke | **Krav före publicering.** Anropar `smoke.yml`: den R8-minifierade appen (samma R8-konfiguration som releasen) loggar in mot en falsk Tandoor på en emulator, överlever processdöd och kallstart och loggar ut (`docs/BYGGE.md`, "Release-röktest"). Fallerar den, eller kraschar appen, publiceras ingenting. Körs inte vid torrkörningar på lane-grenar; där kör `smoke.yml` själv när R8:s indata ändras. |
| publish | Körs bara om `smoke` gick igenom. Laddar ner artefakten, kontrollerar `SHA256SUMS` och skapar GitHub Release `vX.Y.Z` med `tandem-X.Y.Z-sideload.apk`, `tandem-X.Y.Z-sideload-mapping.txt` och `SHA256SUMS`, och changelog-avsnittet som text. Mappningsfilen ligger kvar i releasen så länge den finns, så kraschrapporter går att läsa även efter artefaktens 90 dagar. Den är publik, precis som källkoden. Publiceras bara om emulatorjobbet i `smoke.yml` faktiskt kördes och gick igenom. |

### Torrkörning

Torrkörningen går igenom **samma** väg (bygga, signera, `apksigner verify`), men med en
**engångsnyckel** som skapas i runnern med **samma** `openssl`-kommandon som
`scripts/create-signing-key.sh` (RSA 4096, PKCS12 med AES-256 och SHA256-MAC) och går genom samma
base64- och `keytool`-steg som en riktig nyckel. Bara källan till hemligheterna skiljer. Den skapar
ingen release och körs i miljön `dry-run`, som inte har några secrets.

APK:n byggs med `-Ptandem.dryRun=true`: paketnamnet blir **`casa.moller.tandem.dryrun`** och
versionen får suffixet `-dryrun`. Filen heter `tandem-X.Y.Z-DRYRUN-throwaway-key.apk` (aldrig
release-namnet som Obtainium letar efter) och sparas som artefakt i **2 dagar**. Installerar du
den hamnar den **bredvid** den riktiga appen och kan aldrig blockera dess uppdateringar; avinstallera
den när du är klar.

- Manuellt: Actions → Release → *Run workflow* (`dry_run` är förvalt), eller
  `gh workflow run release.yml --ref main -f dry_run=true`. Bygger både sideload-APK och Play-AAB.
- Automatiskt: vid push till en **lane-gren** (inte `main`, som får samma commit genom
  fast-forward) som ändrar något R8 ser: `release.yml`, någon `build.gradle.kts`,
  `proguard-rules.pro`/`consumer-rules.pro`, `build-logic/`, `gradle/libs.versions.toml`,
  wrapper-versionen, `gradle.properties` eller `settings.gradle.kts`. Då byggs sideload-APK:n med
  R8, så att saknade keep-regler upptäcks innan en tagg.
- `dry_run=false` fungerar bara när workflowet körs på en tagg.

### Debug-nyckeln (inte en hemlighet)

`app/debug.p12` är en **publik** nyckel som signerar debug-byggen (`casa.moller.tandem.dev`),
testbygget `minified` (`casa.moller.tandem.minified`) och röktestets test-APK, med
standarduppgifterna `android`/`androiddebugkey`. Eftersom nyckeln är publik kan vem som helst
bygga en APK med de paketnamnen som installeras ovanpå en befintlig `.dev`-app. Använd därför
`.dev`-byggen bara med testkonton, och installera aldrig en debug-APK från en okänd källa. Den gör att varje CI-byggd debug-APK har samma
signatur, så en nyare installeras ovanpå en äldre utan att inloggningen raderas. Den används aldrig
för release och påverkar inte release-nyckeln eller `TANDEM_CERT_SHA256`. Har du en `.dev`-app som
installerats från ett bygge före nyckeln: avinstallera den en gång.

## 4. Installera

Telefonerna installerar och uppdaterar via Obtainium: se [OBTAINIUM.md](OBTAINIUM.md).
