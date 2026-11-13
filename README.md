# Tandem

Tandem är en inofficiell Android-app (telefon och surfplatta) för självhostade
[Tandoor Recipes](https://github.com/TandoorRecipes/recipes) 2.6 och senare: recept, kokläge,
matsedel och inköpslista, offline först. Appen är en generisk Tandoor-klient: serveradressen
anges i appen och inget i koden är knutet till en viss server. Tandem är inte associerad med
Tandoor-projektet.

**Status: aktiv utveckling, 0.7.0.** Det här finns i dag:

- **Recept:** sök offline och på servern, portioner och enheter som i Tandoors webb, skapa och
  redigera, importera från en länk, en text eller ett foto (texten läses i telefonen), näringsvärden.
- **Laga:** kokläge med stor text, timers som hittas i stegen och larmar med låst skärm, två recept
  samtidigt, handsfree (volymknapparna, röstkommandon i telefonen och uppläsning), pannstorlek,
  köksskärm för surfplattan på bänken, "Klart!" med betyg.
- **Handla och planera:** inköpslista som fungerar offline och synkas i hushållet, samma vara i olika
  enheter på en rad, rutnät med stora rutor, butiksläge med inlärd ordning, "Dags igen?", veckans
  matsedel med förslag och dra och släpp.
- **Hemma:** skafferi per plats, "Kan laga nu", "Använd upp", streckkod, kvitto, drycker.
- **Notiser, alla valfria:** när någon ändrar listan, när något går ut, påminnelse kvällen innan
  ("Tina ut kycklingen"), och snabb push via en egen
  liten tjänst ([docs/PUSH.md](docs/PUSH.md), experimentell).
- Widgets, snabbinställningsruta, surfplatta i båda orienteringarna, gästläge och demoläge.

Vad som kommer härnäst: [docs/ROADMAP.md](docs/ROADMAP.md). Vad som ändrats: [CHANGELOG.md](CHANGELOG.md).

## Installera

Tandem släpps som signerad APK under [Releases](https://github.com/Demacish/tandem/releases).
Enklast är att installera och uppdatera den med [Obtainium](https://github.com/ImranR98/Obtainium),
se [docs/OBTAINIUM.md](docs/OBTAINIUM.md). Du behöver en egen Tandoor-server (2.6 eller senare).
Vill du bara titta finns ett demoläge med exempelrecept, utan server.

## Teknik

Kotlin, Jetpack Compose med Material 3 (Expressive), Navigation 3, Hilt, Retrofit/OkHttp,
kotlinx.serialization, Room, WorkManager, DataStore + Tink. minSdk 34, targetSdk 36, compileSdk 37.
Engelska är grundspråk (`values/`) och svenska (`values-sv/`) ska alltid vara komplett.

## Bygge och CI

Allt byggs i GitHub Actions. Varje push och pull request kör
[`.github/workflows/pr.yml`](.github/workflows/pr.yml): Android Lint, enhetstester (JUnit 5 i
rena Kotlin-moduler, Robolectric/Compose i Android-moduler) och en `sideload`-debug-APK som
laddas upp som artefakt. Så här bygger du lokalt och hur modulerna och convention-pluginsen
hänger ihop: [docs/BYGGE.md](docs/BYGGE.md). Releaser: [docs/RELEASE.md](docs/RELEASE.md).

## Innehåll

| Sökväg | Vad |
|---|---|
| `app/` | Application, MainActivity, Hilt-roten |
| `core/` | `model`, `common`, `domain` (ren Kotlin), `network`, `database`, `datastore`, `data`, `sync`, `timer`, `ai`, `designsystem`, `ui` |
| `feature/` | `onboarding`, `shell` (flikar och Mer), `home`, `recipes`, `cook`, `shopping`, `mealplan`, `pantry`, `scan`, `widgets` |
| `push-service/` | Den valfria push-tjänsten (Go, en container), [docs/PUSH.md](docs/PUSH.md) |
| `contract-tests/` | Kontraktstester mot en tillfällig Tandoor i Actions |
| `smoke/` | Röktest av release-bygget på en emulator mot en falsk Tandoor |
| `build-logic/` | Convention plugins (`tandem.*`) |
| `api-schema/` | Tandoors OpenAPI-schema 2.6.13 (referens, AGPL-3.0), fixtures och listan över använda endpoints |
| `docs/` | [Färdplan](docs/ROADMAP.md), [bygge](docs/BYGGE.md), [release](docs/RELEASE.md), [design](docs/design/README.md) (Stitch-skisser) |

Kommentarer i koden som hänvisar till "plan §x.y" eller beslut "Bx" syftar på utvecklarens interna
planeringsanteckningar; det publika sammandraget är [docs/ROADMAP.md](docs/ROADMAP.md).

## Licens

Tandem är licensierat under [Apache License 2.0](LICENSE). Filerna i `api-schema/` är genererade
av Tandoor (AGPL-3.0) och ligger bara som referens för tester, se
[api-schema/README.md](api-schema/README.md). Typsnitt och ikoner har egna licenser, som visas
under *Mer → Om Tandem → Licenser* i appen.
