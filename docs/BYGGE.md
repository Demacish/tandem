# Bygga Tandem

> All kompilering, lint och alla tester körs i GitHub Actions eller på en utvecklardator,
> aldrig på en server som kör tjänster.

## Lokalt (utvecklardator)

Krav: JDK 21 (t.ex. Temurin 21) och Android SDK med plattform 37 (Android Studio installerar
den; AGP laddar annars ner en saknad plattform om licenserna är godkända). Ange SDK:n med
`ANDROID_HOME` eller `local.properties` (`sdk.dir=...`, checkas aldrig in).

```sh
./gradlew :app:assembleSideloadDebug     # debug-APK: app/build/outputs/apk/sideload/debug/
./gradlew ciLint ciTest                  # samma kontroller som CI
./gradlew :feature:onboarding:testDebugUnitTest   # en enskild modul
```

Debug-bygget har paketnamnet `casa.moller.tandem.dev` och versionsnamnet `0.0.1-dev`, så det
kan installeras bredvid release-bygget.

## Versioner

- Alla beroenden och plugins: `gradle/libs.versions.toml` (verifierade 2026-10-09).
- Gradle 9.7.1 via wrappern (med `distributionSha256Sum`). Kör alltid `./gradlew`.
- AGP 9.4.1 med **inbyggd Kotlin** (Kotlin 2.4.21). Pluginen `org.jetbrains.kotlin.android`
  används inte. Rotens `build.gradle.kts` deklarerar alla plugins med `apply false`, vilket
  lyfter AGP:s inbyggda Kotlin från 2.2.10 till katalogens version – ta inte bort raderna.
  CI kontrollerar versionen med `buildEnvironment`.
- Sätt inte `android.builtInKotlin`, `android.newDsl`, `android.disallowKotlinSourceSets` eller
  `android.enableJetifier` i `gradle.properties`.
- Bytekod: Java 17. CI kör på JDK 21 (krävs av Robolectric för SDK 36+).
- compileSdk 37 är ett krav (Compose 1.12, core 1.19, material3 1.5 och Navigation 3 kräver det).
  Det enda förhandsbiblioteket är material3 1.5.0-beta01 (Expressive), inkapslat i
  `core:designsystem`.

## Byggvarianter

- Build types: `debug` (`.dev`, `-dev`) och `release` (R8 + resurskrympning,
  `app/proguard-rules.pro` plus modulernas `consumer-rules.pro`). Release-signering läggs till av
  release-flödet och läses bara från miljövariabler/CI-secrets.
- `minified` (`.minified`, `-minified`, bara för `sideload`): `initWith(release)`, alltså exakt
  samma R8-konfiguration som release (full mode, samma regelfiler, resurskrympning), men signerad
  med den publika debug-nyckeln och med en egen nätverkskonfiguration som också litar på
  röktestets test-CA. Används bara av release-röktestet nedan; installeras bredvid den riktiga
  appen och kan aldrig ersätta den.
- Flavor-dimension `distribution`: `sideload` (Obtainium/GitHub Releases), `play`, `fdroid`.
  CI bygger och testar `sideloadDebug`.

## Moduler

| Modul | Typ | Får bero på |
|---|---|---|
| `:app` | applikation | allt den kopplar ihop |
| `:feature:onboarding`, `:feature:shell` | `tandem.android.feature` | `core:{data,domain,ui,designsystem,model,common}` – aldrig `core:network`/`core:database` direkt |
| `:core:data` | Android-bibliotek + Hilt | `core:{network,datastore,model,common}` |
| `:core:network` | Android-bibliotek + Hilt + serialization | `core:{common,model}` |
| `:core:datastore` | Android-bibliotek + Hilt + serialization | `core:{common,model}` |
| `:core:designsystem` | Android-bibliotek + Compose | – |
| `:core:ui` | Android-bibliotek + Compose | `core:{designsystem,model}` |
| `:core:model`, `:core:common`, `:core:domain` | ren Kotlin/JVM | `domain` → `model`, `common` |
| `:contract-tests` | ren Kotlin/JVM | – (pratar HTTP med en tillfällig Tandoor) |
| `:smoke` | `tandem.android.test` (test-APK) | – (svart låda: rör bara `:app`s minifierade APK via skärmen) |

Alla moduler finns redan i `settings.gradle.kts`. Nya beroenden läggs i modulens egen
`build.gradle.kts`; använd typsäkra projektreferenser (`projects.core.model`) och katalogen
(`libs.…`).

## Convention plugins (`build-logic/convention`)

| Plugin-id | Gör |
|---|---|
| `tandem.android.application` | `com.android.application`, compileSdk/minSdk/targetSdk från katalogen, Java 17, lint- och testinställningar |
| `tandem.android.library` | `com.android.library` med samma inställningar. Namespace blir automatiskt `casa.moller.tandem.<modulsökväg>` (`:core:network` → `casa.moller.tandem.core.network`). Registrerar `ciTest` (`testDebugUnitTest`) och `ciLint` (ktlint + `lintDebug`) |
| `tandem.android.compose` | Compose-kompilatorn, Compose BOM, `compose-ui`-bundeln (material3 1.5.0-beta01), tooling, Compose UI-test och Roborazzi-bibliotek för tester |
| `tandem.android.hilt` | KSP + Hilt (`hilt-android`, `ksp(hilt-compiler)`), ingen kapt |
| `tandem.android.feature` | library + compose + hilt, plus `core:{data,domain,ui,designsystem,model,common}`, `hilt-lifecycle-viewmodel-compose`, lifecycle-compose och Navigation 3 |
| `tandem.android.test` | `com.android.test` (test-APK mot en annan moduls APK) med samma SDK-nivåer och Java 17, ktlint. Registrerar `ciLint` (ktlint) |
| `tandem.jvm.library` | `org.jetbrains.kotlin.jvm`, Java 17, JUnit 5 (Jupiter) + Turbine + coroutines-test. Registrerar `ciTest` (`test`) och `ciLint` (ktlint) |

Alla moduler får **ktlint** (`org.jlleitschuh.gradle.ktlint`, motorn ktlint 1.8.0, plan §5.6).
Reglerna står i `.editorconfig` i roten: stilen `android_studio`, högst 140 tecken per rad, och
`@Composable`-funktioner får heta med stor bokstav. `ciLint` kör `ktlintCheck`; `./gradlew
ktlintFormat` rättar det som går att rätta automatiskt.

Gemensamt för Android-moduler:
- Lint: `abortOnError = true`, varningar är inte fel, men `MissingTranslation`,
  `ExtraTranslation` och `HardcodedText` är fel. Ingen baseline.
- Enhetstester: JUnit 4 + Robolectric + Turbine + coroutines-test + androidx.test,
  `isIncludeAndroidResources = true`, en test-JVM åt gången med 1,5 GB heap.
- Bara `debug` har enhetstester (AGP 9-standard).

## CI

`.github/workflows/pr.yml` körs vid varje push och pull request (inte när bara `docs/**` eller
`*.md` ändras). Jobbet:

1. validerar Gradle-wrappern,
2. sätter upp Temurin 21 och Gradle och hämtar Gradle-cachen med `actions/cache/restore`
   (nyckel efter katalog, wrapper och byggskript, med prefix-fallback). Bara körningen på `main`
   sparar cachen, så alla grenar kan läsa den; `contract.yml` och `release.yml` läser bara,
3. visar och kontrollerar Kotlin-/KSP-/AGP-versionerna på byggets classpath,
4. kör `./gradlew ciLint ciTest :app:assembleSideloadDebug`,
5. laddar upp rapporter vid fel och debug-APK:n som artefakt (3 dagar på grenar, 7 på `main`).
   Debug-APK:n signeras med den publika debug-nyckeln `app/debug.p12`, så en nyare installeras
   ovanpå en äldre (se `docs/RELEASE.md`).

Taggar startar inte `pr.yml` eller `contract.yml` (de kör bara på grenar). `release.yml`
torrkör på lane-grenar när byggkonfigurationen ändras, inte på `main`.

`.github/workflows/smoke.yml` kör release-röktestet (nästa avsnitt) på en emulator: vid varje
push till `main`, på lane-grenar bara när något som R8 ser eller själva testet ändras
(`proguard-rules.pro`, `consumer-rules.pro`, `build-logic/`, `gradle/libs.versions.toml`,
`app/build.gradle.kts`, `app/src/minified/`, `smoke/`, `scripts/ci/smoke-run.sh`, arbetsflödet),
manuellt, och för varje `v*`-tagg via `release.yml`, som inte publicerar om det fallerar.

Kontraktstesterna i `:contract-tests` hoppas över om miljövariabeln `TANDOOR_URL` saknas.
Ett eget arbetsflöde (`contract.yml`) startar en tillfällig Tandoor i Actions och sätter den.
Peka aldrig `TANDOOR_URL` mot en produktionsserver.

<!-- Fas 1 kontrakt -->
Fas 1 (`Phase1ContractTest`) seedar samma tillfälliga server via API:t och spelar in
`api-schema/fixtures/2.6.13/fas1/` (artefakten `contract-recordings-<tagg>`); fakta står i
`api-schema/fixtures/2.6.13/FACTS.md` under "Fas 1". Skalningstabellen
`fas1/scaling-golden.json` tas fram i `contract.yml` genom att köra Tandoors egen webbkod
(utcheckad vid samma tagg, aldrig incheckad) med `contract-tests/web-scaling/golden.mjs`, som
jämför med den incheckade filen. `ScalingGoldenTest` (körs i `ciTest`, utan server) räknar om
tabellen med en oberoende Kotlin-implementation. Förnya fixturerna genom att ladda ned
artefakten från en grön `contract.yml`-körning och ersätta mappen i samma ändring som testet.
<!-- /Fas 1 kontrakt -->

## Release-röktest

Enhetstester och Robolectric kör **ominifierad** kod och kan inte se fel i R8:s keep-regler.
v0.0.1 kraschade därför på första inloggningen trots grönt CI (se nedan). Röktestet kör i stället
den kod som skickas:

- **Appen:** `:app:assembleSideloadMinified` (build type `minified`, se Byggvarianter).
- **Testet:** modulen `:smoke` (`com.android.test`, `targetProjectPath = ":app"`,
  självinstrumenterande). En UiAutomator-test i svart låda: den känner inga appklasser, bara
  texterna på skärmen (engelska, emulatorns språk), så obfuskeringen spelar ingen roll.
- **Servern:** en falsk Tandoor i test-APK:n (MockWebServer över HTTPS på `localhost`) som svarar
  med de sanerade 2.6.13-fixturerna i `api-schema/fixtures/2.6.13` (server-settings,
  api-token-auth, access-token, space/current, user-preference, user). Certifikatet utfärdas
  vid körning av en fast **test-CA** (`smoke/src/main/assets/tandem-smoke-test-ca.pem`, med
  privat nyckel – publik, bara för test). Bara `minified` litar på den
  (`app/src/minified/res/xml/network_security_config.xml` + `res/raw/tandem_smoke_test_ca.pem`);
  `debug` och `release` är oförändrade.
- **Täcker:** intro (välkommen → serveradress → användarnamn/lösenord → Logga in) till skalet och
  "Tandoor 2.6.13" under Mer, med visningsnamn och space från servern (bevisar att
  user-preference och space avkodas, inte bara skickas); inloggning med inklistrad token (space,
  user-preference och token-listan måste nå servern); processdöd i bakgrunden (Navigation 3 sparar
  och läser tillbaka back stacken: Mer → Om Tandem, och Licenser → DM Sans, en nyckel med
  argument, `LicenseTextKey(BundledLicense)`, som återställs via `Companion`/`$$serializer` i
  stället för `INSTANCE`; licenstexten bevisar också att `assets/licenses` finns i APK:n);
  kallstart (kontot och den Tink/Keystore-krypterade token läses tillbaka); utloggning (enhetens
  token återkallas, en inklistrad token lämnas kvar). Utloggningens toast kontrolleras via
  tillgänglighetshändelser: "Logged out." kräver att DELETE:s 204 hanterades (`suspend fun ...:
  Unit`), annars hade appen sagt att token inte kunde återkallas.
- **CI:** `smoke.yml` bygger båda APK:erna, stoppar Gradle, startar en emulator (API 36,
  `google_apis`, x86_64, KVM) med `reactivecircus/android-emulator-runner` och kör
  `scripts/ci/smoke-run.sh`. Steget fallerar om ett test fallerar **eller** om appen kraschar.
  Ett separat steg kontrollerar att **varje** gränssnitt som deklareras under
  `core/network/.../api/` (alla underpaket) finns i mappningsfilen och inte är
  `R8$$REMOVED$$CLASS`, och att `AuthService.login` och `TokenAuthRequestDto` finns kvar. Vid fel laddas artefakten `smoke-results` upp: hela logcat, kraschen som den
  ser ut på en telefon (`crash.txt`) och **retracad** med byggets mappningsfil
  (`crash-retraced.txt`, R8:s egen `Retrace` ur Gradle-cachen), skärmbilder och
  UiAutomator-dumpar per steg, samt mappningsfilen. Den retracade kraschen står också i
  jobbets sammanfattning.
- Kör det manuellt: Actions → *Release smoke test* → *Run workflow*. Aldrig på servern: det kräver
  en emulator.

### v0.0.1: varför inloggningen kraschade

Retrofit skapar tjänsterna (`AuthService`, `UserService`, `SpaceService`, `ServerService`) med
`java.lang.reflect.Proxy`, så R8 ser ingen klass som implementerar dem. I full mode betyder det
för R8 att gränssnittet inte har några instanser: `retrofit.create(X::class.java) as X` kan bara
lyckas för `null`. Retrofits egen regel (`-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>`) skyddar ett gränssnitt bara så länge minst en av dess
annoterade metoder lever. `ServerService.serverSettings()` anropas aldrig (proben använder rå
OkHttp), så R8 tog bort metoden, regeln slutade gälla och R8 skrev om typkonverteringen i
`TandoorApiFactory.create()` till ett ovillkorligt `throw ClassCastException()`. Allt efter
`create()` i inloggningen blev död kod (därför saknades `AuthService.login` och
`TokenAuthRequestDto` i mappningsfilen), och undantaget kastades utanför `apiCall` och kraschade
appen från `viewModelScope` innan någon begäran skickades. Rättningen är regeln i
`core/network/consumer-rules.pro`, som behåller **alla** gränssnitt i `core.network.api` och dess
underpaket (`api.**`, oavsett namn) oavsett vilka metoder som används. (0.0.2 vidgade den från
`api.*Service`: en `RecipeApi` eller `api.recipes.X` som kopplas in i `TandoorApi` innan någon
funktion anropar den hade annars upprepat felet. `TandoorApiServicesTest` kontrollerar att
`TandoorApi`s tjänster ligger i paketet och att regeln finns.) Retrofit-tjänster hör hemma i det
paketet och ingen annanstans. Dessutom gör `catchingUnexpected` (core:model) och `apiCall`
att ett oväntat fel i inloggningen visar det allmänna felet i stället för att döda appen
(aldrig `CancellationException` eller `VirtualMachineError`).

Felinformation ("Visa detaljer", `TandemError.Unknown`, kraschloggen) använder
**fullständiga** klassnamn (`javaClass.name`), aldrig `simpleName`: R8 döper om app- och
biblioteksklasser, och bara ett fullständigt namn som `fe1` kan retracas entydigt med releasens
mappningsfil. Av samma skäl jämför koden aldrig klassnamn som strängar
(`ErrorClassifier.decodeDetail` använder `is MissingFieldException`).

Röktestets första körningar hittade också en latent bugg: `ServerProbe` läste svarskroppen på
huvudtråden (proben startas från `viewModelScope`). Android kastar då
`NetworkOnMainThreadException`, som blev "Det här ser inte ut som Tandoor" (`NOT_JSON`). Mot en
riktig server ligger den lilla kroppen oftast redan i OkHttps buffert, så felet syntes bara
ibland. Kroppen läses nu på `Dispatchers.IO`.

## Kraschlogg

`TandemApplication.attachBaseContext` installerar först av allt en `CrashLogHandler`
(core:common) som standardhanterare för ofångade undantag. Vid en krasch skriver den en liten
textfil i `noBackupFilesDir/crash-logs/` (bara de tre senaste sparas) och lämnar sedan över till
den tidigare hanteraren (plattformens, som loggar och avslutar processen). Loggen har appversion,
versionCode, build type, distribution, Android-version och enhetsmodell, och stackspåret med
klassnamn och rader. Varje undantagsmeddelande och trådnamnet tvättas (`CrashLogFormatter.redact`):
URL:er (med eller utan inloggningsuppgifter), token (`tda_…`, `Bearer …`, långa slumpsträngar),
e-post, citerade värden, JSON-kroppar och `nyckel=värde` för lösenord/token/användarnamn tas
bort. Hanteraren kan inte krascha eller loopa: skrivningen fångar allt, bara processens första
krasch skrivs, och den tidigare hanteraren anropas alltid.

Vid nästa start visar välkomststeget och Mer kortet *Tandem kraschade förra gången* med *Visa*,
*Kopiera*, *Dela* (systemets delningsdialog, `ACTION_SEND` text/plain) och *Ta bort*. Ingenting
skickas automatiskt. Obfuskerade namn i en delad logg från ett release-bygge går att översätta
med releasens mappningsfil (`retrace`).

## Backup och nätverk

- `android:allowBackup="false"` och `data_extraction_rules.xml` undantar alla domäner från både
  molnbackup och överföring mellan enheter: token (Tink, nyckel i Keystore), inställningar och
  offlinecache stannar på enheten. Efter telefonbyte loggar man in igen.
- `network_security_config.xml`: ingen klartexttrafik; systemets och användarens installerade
  CA:er godtas (för självhostade servrar med egen CA), ingen certifikat-pinning.
