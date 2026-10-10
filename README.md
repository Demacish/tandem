# Tandem

Tandem är en inofficiell Android-app (telefon och surfplatta) för självhostade
[Tandoor Recipes](https://github.com/TandoorRecipes/recipes) 2.6 och senare: recept, kokläge,
matsedel och inköpslista, offline först. Appen är en generisk Tandoor-klient: serveradressen
anges i appen och inget i koden är knutet till en viss server. Tandem är inte associerad med
Tandoor-projektet.

**Status: tidig utveckling.** Inloggning, flikar och inställningar fungerar; recept, kokläge,
matsedel och inköpslista byggs nu. Vad som kommer i vilken ordning: [docs/ROADMAP.md](docs/ROADMAP.md).

## Installera

Tandem släpps som signerad APK under [Releases](https://github.com/Demacish/tandem/releases).
Enklast är att installera och uppdatera den med [Obtainium](https://github.com/ImranR98/Obtainium),
se [docs/OBTAINIUM.md](docs/OBTAINIUM.md). Du behöver en egen Tandoor-server (2.6 eller senare).
Ett demoläge med exempelrecept, utan server, kommer i Fas 1.

## Teknik

Kotlin, Jetpack Compose med Material 3 (Expressive), Navigation 3, Hilt, Retrofit/OkHttp,
kotlinx.serialization, DataStore + Tink. minSdk 34, targetSdk 36, compileSdk 37.
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
| `core/` | `model`, `common`, `domain` (ren Kotlin), `network`, `datastore`, `data`, `designsystem`, `ui` |
| `feature/` | `onboarding` (intro och inloggning), `shell` (flikar och Mer) |
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
