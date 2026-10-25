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

Debug-bygget har paketnamnet `casa.moller.tandem.dev` och versionsnamnet `<version>-dev`
(t.ex. `0.1.0-dev`), så det
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
- Flavor-dimension `distribution`: `sideload` (Obtainium/GitHub Releases) och `play`. Ingen
  F-Droid-flavor (beslut B10, 2026-10-10). CI bygger och testar `sideloadDebug`.

## Moduler

| Modul | Typ | Får bero på |
|---|---|---|
| `:app` | applikation | allt den kopplar ihop |
| `:feature:onboarding`, `:feature:recipes`, `:feature:home`, `:feature:cook`, `:feature:shopping`, `:feature:mealplan` (Fas 2) | `tandem.android.feature` | `core:{data,domain,ui,designsystem,model,common}` – aldrig `core:network`/`core:database`/`core:sync`/`core:datastore`/`core:timer` och aldrig en annan feature (timers nås via `TimerController` i `core:data`, se "Fas 1b – kontrakt") |
| `:feature:shell` | `tandem.android.feature` | som ovan **plus** `feature:recipes`, `feature:home`, `feature:cook`, `feature:shopping` och `feature:mealplan`: skalet är navigationsvärden (se Navigering) |
| `:core:data` | Android-bibliotek + Hilt | `core:{network,datastore,model,common}`; repository-gränssnitten för Fas 1 |
| `:core:database` | Android-bibliotek + Hilt + `tandem.android.room` | `core:{model,common}` |
| `:core:sync` | Android-bibliotek + Hilt + WorkManager/hilt-work | `core:{data,network,database,datastore,domain,model,common}` |
| `:core:testing` | Android-bibliotek, **bara för tester** | `core:{data,model}`; fakes, exempeldata, fixturer |
| `:core:timer` | Android-bibliotek + Hilt + serialization (Fas 1b) | `core:{data,domain,model,common}` (använder `data` och `model`); implementerar `TimerController`, se "Timers" |
| `:core:network` | Android-bibliotek + Hilt + serialization | `core:{common,model}` |
| `:core:datastore` | Android-bibliotek + Hilt + serialization | `core:{common,model}` |
| `:core:designsystem` | Android-bibliotek + Compose | – |
| `:core:ui` | Android-bibliotek + Compose | `core:{designsystem,model}` |
| `:core:model`, `:core:common`, `:core:domain` | ren Kotlin/JVM | `domain` → `model`, `common` |
| `:contract-tests` | ren Kotlin/JVM | – (pratar HTTP med en tillfällig Tandoor) |
| `:smoke` | `tandem.android.test` (test-APK) | – (svart låda: rör bara `:app`s minifierade APK via skärmen) |

Alla moduler för Fas 1a, 1b och 2 finns redan i `settings.gradle.kts`. Nya beroenden läggs i modulens egen
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
| `tandem.android.test` | `com.android.test` (test-APK mot en annan moduls APK: `:smoke`, `:benchmark`) med samma SDK-nivåer och Java 17, ktlint. Registrerar `ciLint` (ktlint + detekt) |
| `tandem.jvm.library` | `org.jetbrains.kotlin.jvm`, Java 17, JUnit 5 (Jupiter) + Turbine + coroutines-test. Registrerar `ciTest` (`test`) och `ciLint` (ktlint) |
| `tandem.android.room` | KSP + Room 3 (`androidx.room3`): runtime, paging, `ksp(room3-compiler)`, room3-testing för tester. Schemat exporteras till `<modul>/schemas/` (se Room) |
| `tandem.module-graph` | Uppgiften `checkModuleGraph` (modulgrafens regler, se nedan). Läggs på automatiskt av application-, library- och JVM-pluginerna; `ciLint` beror på den |
| `tandem.detekt` | detekt 2.0 (light mode) med Compose-reglerna, `config/detekt/detekt.yml` och `<modul>/detekt-baseline.xml`. Läggs på automatiskt av application-, library-, JVM- och test-pluginerna; `ciLint` beror på `detekt` (se "Fas 1b – CI") |

Alla moduler får **ktlint** (`org.jlleitschuh.gradle.ktlint`, motorn ktlint 1.8.0, plan §5.6).
Reglerna står i `.editorconfig` i roten: stilen `android_studio`, högst 140 tecken per rad, och
`@Composable`-funktioner får heta med stor bokstav. `ciLint` kör `ktlintCheck`; `./gradlew
ktlintFormat` rättar det som går att rätta automatiskt.

### Modulgrafen (`checkModuleGraph`)

Plan §5.2:s beroenderegler kontrolleras i varje modul av `checkModuleGraph` (en del av `ciLint`,
alltså av `pr.yml`). Den läser de projektberoenden modulen själv deklarerar och fallerar om:

1. en `:feature:*` beror på `:core:network`, `:core:database`, `:core:sync`, `:core:datastore`
   eller `:core:timer` (features pratar bara med `:core:data`),
2. en `:feature:*` beror på en annan `:feature:*` – utom `:feature:shell`, som är navigationsvärd,
3. en `:core:*` beror på en `:feature:*`,
4. något annat än en testkonfiguration (`testImplementation` m.fl.) beror på `:core:testing`.

Reglerna står i `build-logic/convention/src/main/kotlin/casa/moller/tandem/buildlogic/ModuleGraph.kt`.
Reglernas egna tester (`ModuleGraphRulesTest`, bl.a. att `:feature:*` aldrig når `:core:timer` i
någon konfiguration, inte heller i tester) körs i `pr.yml` som `:build-logic:convention:test`.

Gemensamt för Android-moduler:
- Lint: `abortOnError = true`, varningar är inte fel, men `MissingTranslation`,
  `ExtraTranslation` och `HardcodedText` är fel. Ingen baseline.
- Enhetstester: JUnit 4 + Robolectric + Turbine + coroutines-test + androidx.test,
  `isIncludeAndroidResources = true`, en test-JVM åt gången med 1,5 GB heap.
- Bara `debug` har enhetstester (AGP 9-standard).

## Room (`:core:database`)

- Room 3 (`androidx.room3`, KSP2) via `tandem.android.room`. Databasen heter `TandemDatabase`
  (filen `tandem.db`, undantagen från backup som allt annat).
- **Schemat checkas in:** bygget exporterar `core/database/schemas/<databasklass>/<version>.json`.
  `pr.yml` fallerar ("Room schemas are committed") om bygget skapade eller ändrade en schemafil
  som inte är incheckad, och laddar då upp artefakten `room-schemas`. Eftersom inget byggs lokalt
  på servern: hämta artefakten (`gh run download <id> -n room-schemas -D core/database/schemas`)
  och checka in filerna oförändrade.
- **Version:** schemaversion 1 är den som 0.1.0 (den första release som innehåller databasen)
  skickas med, och `1.json` är nu **fryst**. Varje ändring av en entitet kräver från och med nu
  en ny version (2, 3 …), en migrering i `TandemMigrations` och ett fall i
  `TandemMigrationTest`; `1.json` får aldrig skrivas om. Version 2 (0.2.0) lägger till
  `cook_log` (`TandemMigrations.MIGRATION_1_2`, testad från en fil skapad ur `1.json`), version 3
  (0.2.0) `step.image_url` och markerar detaljer med stegbild för ny hämtning (`MIGRATION_2_3`,
  testad 1 → 3; synksidan i `SyncEngineTest`), version 4 (0.3.0) hela Fas 2 (`MIGRATION_3_4`,
  se "Fas 2 – kontrakt"; testad 3 → 4 och 1 → 4).
- **Migreringstester** (Robolectric) använder room3-testings `MigrationTestHelper`. Den läser
  schemafilerna som assets; `tandem.android.room` kopierar `schemas/` till enhetstesternas assets
  (uppgiften `copyRoomSchemasToUnitTestAssets<Variant>`, samma koppling som Room-pluginen gör för
  instrumenterade tester). Mappen finns också i systemegenskapen `tandem.roomSchemaDir`.
  `TandemMigrationTest` visar redan nu att en fil skapad från den incheckade `1.json` öppnas av
  koden (Room jämför identity hash), så ett schema som inte checkats in fångas även av testerna.

### Tabeller (plan 5.7)

Alla tabeller utom `server_account` och `outbox` har `account_id` först i nyckeln, och varje
DAO-funktion tar konto-id:t. Det finns inga SQL-främmande nycklar: `AccountDao.deleteAccountData`
tar bort ett kontos rader i alla tabeller i en transaktion (en `INSERT OR REPLACE` på kontot kan
alltså aldrig kaskadradera allt).

| Tabell | Innehåll |
|---|---|
| `server_account` | Kontospegel utan hemligheter (bas-URL, användar-, space- och hushålls-id, version, gruppnivå, tokenens id) |
| `recipe_overview` | Listdata. Nyckel `(account_id, server_id)` (unikt index); `local_id` är rowid och delas med `recipe_fts` |
| `recipe_detail` | Rå JSON + utdragna kolumner + lokalt läge (`pinned`, `last_viewed_local`, `portions_chosen`). En rad utan JSON är en "stubbe" med bara lokalt läge; offline = JSON finns |
| `step`, `ingredient` | Byggs om när detaljen sparas. Nyckel position (serverns `order` är inte unik) |
| `recipe_keyword`, `keyword`, `food`, `unit`, `meal_type`, `user_preference` | Referensdata |
| `meal_plan` | `from_date`/`to_date` som `yyyy-MM-dd` (serverns zon) + rå datetime; `local_id = server_id` för serverposter |
| `home_section`, `home_section_recipe` | Idag-karusellernas senaste svar (typ, ordnade recept-id, `fetched_at`) |
| `sync_state` | En rad per entitet: tidsstämpel, datum, full omläsning, matsedelsfönster, hash |
| `outbox` | Köade skrivningar (plan 5.7-kolumnerna, `state` PENDING/PAUSED/FAILED/IN_FLIGHT) |
| `recipe_fts` | FTS4, `unicode61 remove_diacritics=2`, över namn, beskrivning, nyckelord och ingrediensnamn |
| `cook_log` | Kokloggen (från 0.2.0): serverns poster (`local_id = server_id`) och "Klart!" från enheten (negativt `local_id`, `outbox_id` till sin outbox-rad), `state` SYNCED/PENDING/SENT/FAILED/DROPPED, se "Koklogg" nedan |

- **FTS hålls i synk för hand** (ingen external content, eftersom innehållet kommer från fyra
  tabeller): `RecipeDao`s skrivfunktioner (`applyIndex`, `upsertOverviews`, `replaceDetail`,
  `deleteRecipes`, `evictDetail`, `replaceKeywords`) skriver om FTS-raderna i samma transaktion.
  Skriv aldrig till recepttabellerna förbi dem.
- **Sökning** (`RecipeDao.observeRecipeList`): varje ord måste matcha, antingen som ordprefix
  någonstans i FTS-indexet eller som delsträng i namnet (`search_name`, gemener utan diakritiska
  tecken), så att "pasta" hittar "Svamppasta". Användarens text blir citerade prefixtermer, så
  FTS-operatorer kan inte ge syntaxfel. Filtren körs i SQL; sorteringen i Kotlin med en Collator
  för språket (svenska: å, ä, ö efter z), samma regler som fejkrepositoryt i `:core:testing`.
- **Indexets raderingsregel** (`applyIndex`): recept som saknas i `recipe/flat` raderas med allt
  som hör till dem (även osända outbox-rader), utom fästa recept med osända redigeringar. En tom
  lista raderar ingenting om inte `allowEmpty = true`.
- **Outbox:** `nextReady` ger lägsta id vars `depends_on` är klar (raderad) och som inte har en
  äldre rad för samma objekt kvar. `markFailed` markerar även alla beroende rader. Flera osända
  VIEW_LOG för samma recept slås ihop (`enqueueViewLog`). En FAILED loggrad (`VIEW_LOG`,
  `COOK_LOG`) håller inte tillbaka senare rader för samma recept.
- **Två databasfiler** (plan 6.2 Demoläge): `TandemDatabaseFactory.get(DatabaseKind.REAL | DEMO)`
  ger `tandem.db` respektive `tandem-demo.db` med samma schema. Hilt binder den riktiga som
  `TandemDatabase` (och alla DAO:er) och demodatabasen som `@DemoDatabase TandemDatabase`.
  `clear(kind)` tömmer en fil, `clearAccount(kind, id)` ett konto (utloggning).
- `mapper/` mappar rader till `core:model` och tillbaka (`Recipe.toDetailWrite`,
  `RecipeOverview.toOverviewWrite`, `RecipeListItem.toModel`, `MealPlanRow.toModel` ...).

## Repository-kontrakt (`:core:data`) och fakes (`:core:testing`)

Fas 1:s skärmar och synken arbetar mot gränssnitt i `:core:data` (KDoc:en anger trådning,
offline-beteende och felsemantik): `RecipeRepository`, `HomeRepository`, `SyncRepository`,
`PreferencesRepository` och `ServerStatusRepository`. Modellerna ligger i `:core:model` (ren
Kotlin, `kotlinx-datetime` för datum, beslut B2).

`:core:testing` har en fake för varje gränssnitt (`FakeRecipeRepository` m.fl.), deterministisk
exempeldata (`SampleRecipes`: svenska namn med å/ä/ö, bråk, rubrikrader, steg med timers),
`MainDispatcherRule` och `TandoorFixtures`, som läser de inspelade svaren i
`api-schema/fixtures/<version>/` från klassvägen. Används bara med `testImplementation`.

De riktiga implementationerna ligger i `:core:sync` och binds i `core/sync/.../di/SyncModule.kt`
(se "Synk" nedan); grundens tillfälliga `TemporaryFas1DataModule` är borttagen.

<!-- Fas 1b kontrakt -->
## Fas 1b – kontrakt (0.2.0 "laga")

Grunden för 0.2.0 (kokläge, timers med larm, "Klart!" + koklogg, fler inställningar,
diagnostik) lades först, så att banorna kan bygga parallellt mot samma typer. Allt nedan finns
på `main`; de riktiga implementationerna kommer från banorna.

### Rena typer (`:core:model`)

| Paket | Typer |
|---|---|
| `timer` | `CookTimer` (id, etikett, recept-id + stegindex, längd, `TimerState` RUNNING/PAUSED/RINGING/FINISHED, `endsAt`, `pausedRemaining`, `createdAt`) med de enda tillståndsövergångarna (`pause`, `resume`, `plusOneMinute`, `ring`, `stopRinging`, `remaining`, `isDue`, `CookTimer.start`), `MAX_PARALLEL_TIMERS` = 8. `TimerMode` IN_APP/CLOCK_APP, `TimerStartResult`, `TimerCapabilities` (notiser tillåtna/frågat, larmkanal på, exakta larm tillåtna/går att begära, fullskärmslarm, klockapp finns; `shouldAskForNotifications`, `alarmMayBeSilent`, `alarmMayBeLate`), `TimerSuggestion` + `TimerSuggestionSource` STEP_TIME/TEXT (med `textRange`) |
| `cooklog` | `CookLogDraft` (recept-id, hela portioner eller null, betyg 0–5 eller null, kommentar, `cookedAt`); `servingsOf(Double)` avrundar halvt uppåt, minst 1 |
| `settings` | `AppSettings` + `dynamicColor`, `cookTextScale` (`CookTextScale.STEPS` 0,85–2,0, A−/A+), `cookModeAlwaysDark`, `timerMode`, `syncInterval` (`SyncInterval` 15 min/1 h/6 h/manuellt, standard 15 min), `downloadAllRecipes`, `notificationPermissionAsked`, `exactAlarmPromptDismissed` |
| `diagnostics` | `DiagnosticsSnapshot` (versioner, `ColdStartTiming`, `FullSyncTiming` med receptantal), `LogExport` (text, valfri `content://`-URI, filnamn) |
| `notification` | `TandemNotificationChannel` (kanal-id, se nedan), `TandemNotificationIds` |

**Timerns klocka:** `endsAt` är **väggklocka** (`kotlin.time.Instant`), inte `elapsedRealtime`:
den sparas och ska överleva processdöd **och omstart** (den monotona klockan börjar om på 0 vid
varje start), och `AlarmManager.setAlarmClock` tar bara väggklocktid. Tidszonsbyten spelar
ingen roll. En manuell klockändring rättas av implementationen (`ACTION_TIME_CHANGED`, monoton
ankarpunkt i minnet, nytt larm); efter en omstart gäller sparad `endsAt` (en timer vars slut
passerade medan telefonen var av ringer direkt). Injicera `kotlin.time.Clock`
(`core:data` `ClockModule`, `Clock.System`); testerna använder `TestClock`.

### Gränssnitt (`:core:data`)

| Gränssnitt | Implementeras av | Innehåll |
|---|---|---|
| `timer.TimerController` | timerbanan i `:core:timer` | `timers`/`capabilities` (StateFlow), `start` → `TimerStartResult`, `pause`, `resume`, `addOneMinute`, `cancel`, `stopRinging`, `clearFinished`, `onNotificationPermissionResult`, `refreshCapabilities` |
| `cooklog.CookLogRepository` | kokloggsbanan i `:core:sync` (outbox `COOK_LOG`) | `logCooked(CookLogDraft)` (lokalt direkt + outbox), `observePendingCount()` |
| `settings.SettingsRepository` | **klar** i `:core:data` (`DefaultSettingsRepository`, nycklarna i `PreferencesSettingsStore`) | `settings` + en setter per Fas 1b-inställning; tema finns kvar i `AppearanceRepository` |
| `diagnostics.DiagnosticsRepository` | inställnings-/diagnostikbanan | `observeDiagnostics`, `recordColdStart` (`:app`), `recordFullSync` (`:core:sync`), `exportLog` (tvättad som kraschloggen) |

**Behörighet i sammanhanget:** innan den första `start` tittar UI:t på
`capabilities.shouldAskForNotifications`; är den sann startar featuren själv systemdialogen
(`ActivityResultContracts.RequestPermission` för `POST_NOTIFICATIONS`, inga core:timer-typer),
rapporterar svaret med `onNotificationPermissionResult` och startar timern oavsett svar. Aldrig
vid första start, aldrig råa behörighetsnamn i UI:t.

### Timerförslag (`:core:domain`)

`StepTimerSuggestions().suggest(stepText, stepTimeMinutes, languageTag)` → `List<TimerSuggestion>`:
`Step.time` först (om texten inte redan har exakt samma tid), sedan texttimers i textordning från
`TimerDetector`. `stepText` är den **renderade** instruktionen (`StepDisplay.instruction`) och
`textRange` pekar in i den. KDoc:en listar de svenska och engelska formerna; `languageTag` bryter
bara oavgjort (`TimerDetector.detect(text, preferredLanguage)`). **Intervall** ("ca 20–25
minuter", "mellan 10 och 12 minuter") kör till den **övre** gränsen och etiketten säger det
("ca 20–25 minuter (25 min)"); `TimerSuggestion.minDuration` har den undre (timerbanans beslut,
`maxDuration` finns kvar som läsbar egenskap = `duration`).

### Navigering

- `CookKey(recipeId, servings: Double? = null, startStep: Int = 0)` och `TimersKey`
  (`:feature:cook`, alla löpande timers). `servings = null` = receptets sparade val. Kokläget
  kopplas in i skalet genom att `:feature:shell` får `implementation(projects.feature.cook)` och
  en `entry<CookKey>`; detaljen och Idag får `onStartCooking`.
- Inställningarnas undersidor (`:feature:shell`, `navigation/SettingsKeys.kt`, alla `ShellKey`
  på Mer-flikens stack): `AppearanceSettingsKey`, `CookingSettingsKey`, `SyncSettingsKey`,
  `PermissionsKey` (översikt, inget tvingat steg) och `DiagnosticsKey`.
- Själva larmet (ringer på låst skärm) är en egen aktivitet, ingen Navigation 3-nyckel.

### Fakes (`:core:testing`) och tillfälliga bindningar (`:app`)

`FakeTimerController` (med `TestClock`, `advanceTimeBy`, `setCapabilities`, `mode`,
`clockAppRequests`), `FakeCookLogRepository`, `FakeSettingsRepository`,
`FakeDiagnosticsRepository` och `time.TestClock`.

Under bygget av 0.2.0 band `app/.../di/TemporaryFas1bModule.kt` tillfälliga
minnesimplementationer så att appen gick att köra innan banorna var klara. **Filen och alla tre
modulerna är borttagna** (0.2.0): `TimerController` binds i `:core:timer` (`TimerModule`),
`CookLogRepository` och `DiagnosticsRepository` i `:core:sync` (`SyncModule`),
`SettingsRepository` i `:core:data` (`DataModule`). Inga tillfälliga bindningar finns kvar.

### Notiskanaler (plan 8.7, bestämda nu, byts aldrig)

| Id | Kanal | Viktighet | Ljud | Ägare |
|---|---|---|---|---|
| `timer_alarm` | Timers (larm) | hög | larmljud, `USAGE_ALARM` | `:core:timer` |
| `timer_ongoing` | Pågående timers (nedräkning, Live Update) | låg | tyst | `:core:timer` |
| `sync_problems` | Synkproblem (blockerad, utloggad, säkert läge) | låg | tyst | `:core:sync` |
| `server` | Server ("Tandoor uppdaterades", sedan 0.1.0) | standard | notis | `:core:sync` |
| `shopping`, `import_done`, `meal_reminders` | reserverade för Fas 2–5 | standard | notis | skapas inte än |

Varje modul skapar sina kanaler precis innan den postar, med värdena ur
`TandemNotificationChannel` och egna översatta namn. Dessutom skapar appen alla kanaler **en gång
vid varje start** (0.2.0): varje modul som postar binder en `NotificationChannelInstaller`
(`:core:data`, Hilt-set med `@IntoSet`; `:core:timer` för `timer_alarm`/`timer_ongoing`,
`:core:sync` för `server`) och `TandemApplication` anropar alla utanför huvudtråden. Då finns
kanalerna i systeminställningarna från första start och *Behörigheter* kan öppna larmkanalen
innan något har ringt. Att skapa en befintlig kanal ändrar bara namnet, aldrig användarens val.
`sync_problems` skapas inte än: inget postar i den. Ett id byts aldrig (användarens val i
systeminställningarna hänger på id:t); en ny kanal får ett nytt id. Notis-id:n:
`TandemNotificationIds` (server 1001, synkproblem 1101, ringtjänsten 2000, per timer
3000–3999 nedräkning och 4000–4999 larm).

### Behörigheter per flavor (timerbanan implementerar)

| Behörighet | `sideload` | `play` | Varför |
|---|---|---|---|
| `POST_NOTIFICATIONS` | ✅ | ✅ | Notiser och larm; frågas vid första timern |
| `USE_EXACT_ALARM` | ✅ | – | Exakt larm utan fråga (Play begränsar den till larm-/kalenderappar) |
| `SCHEDULE_EXACT_ALARM` | – | ✅ | Användaren tillåter i systeminställningarna (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, uppmaning i appen); annars inexakt larm (`setAndAllowWhileIdle`) med varning |
| `USE_FULL_SCREEN_INTENT` | ✅ | – | Larmet tar över låst skärm (kontrollera `canUseFullScreenIntent()`, går att återkalla); play får en heads-up-notis |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | ✅ | ✅ | Kort förgrundstjänst **bara medan larmet ringer**, typ `systemExempted` (tillåten för appar med exakta larm som fortsätter ett larm i bakgrunden). Ingen tjänst under nedräkningen |
| (typ `shortService`, ingen extra behörighet) | – | ✅ | Reserv i play när exakta larm saknas: ringer högst ca 3 min |
| `WAKE_LOCK` | ✅ | ✅ | Ljud/vibration medan det ringer med skärmen av |
| `VIBRATE` | ✅ | ✅ | Larmvibration |
| `RECEIVE_BOOT_COMPLETED` | ✅ | ✅ | Lägger tillbaka larmen efter omstart (även `ACTION_TIME_CHANGED`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`) |
| `POST_PROMOTED_NOTIFICATIONS` | ✅ | ✅ | Live Update för nedräkningen (Android 16) |
| `com.android.alarm.permission.SET_ALARM` + `<queries>` för `android.intent.action.SET_TIMER` | ✅ | ✅ | Läget "timer i klockappen" (`AlarmClock.ACTION_SET_TIMER`) och att se om en klockapp finns |

`:core:timer`s biblioteksmanifest deklarerar det gemensamma **och båda** exakta
larmbehörigheterna (Android Lint kräver en av dem för en `systemExempted`-tjänst);
`app/src/sideload/AndroidManifest.xml` tar bort `SCHEDULE_EXACT_ALARM` (`tools:node="remove"`) och
lägger till `USE_FULL_SCREEN_INTENT`, `app/src/play/AndroidManifest.xml` tar bort
`USE_EXACT_ALARM`. Fungerar `systemExempted` inte på en enhet byter timerbanan till
`mediaPlayback` och skriver varför här (se "Timers").

### Vem bygger vad

| Bana | Bygger på | Levererar |
|---|---|---|
| Timer (`:core:timer`) | `TimerController`, `CookTimer`, `TimerCapabilities`, `SettingsRepository.timerMode`, kanalerna, behörighetstabellen | Riktig `TimerController`, larm, notiser/Live Update, ringtjänst, omstart; tar bort `TemporaryFas1bTimerModule`; `TimerModule.MAX_PARALLEL_TIMERS` → `CookTimer.MAX_PARALLEL_TIMERS`; kompletterar `StepTimerSuggestions` |
| Kokläge (`:feature:cook`) | `CookKey`, `TimersKey`, `RecipeRepository`, `RecipeDisplay`, `StepTimerSuggestions`, `TimerController` (fake i tester), `SettingsRepository` (textstorlek, alltid mörkt) | Pager, ingredienser per steg, timerchips och -rad, surfplatta/vikbar, "Klart!"-sidan |
| Koklogg (`:core:sync`) | `CookLogRepository`, `CookLogDraft`, outboxens `COOK_LOG` | Riktig `CookLogRepository`; tar bort `TemporaryFas1bCookLogModule` |
| Inställningar och diagnostik (`:feature:shell`) | Inställningsnycklarna, `SettingsRepository`, `TimerController.capabilities`, `DiagnosticsRepository` | Undersidorna, behörighetsöversikten, diagnostik med export; tar bort `TemporaryFas1bDiagnosticsModule`; `SyncInterval` i `:core:sync` |
| CI | – | `tandoor-watch.yml`, Roborazzi-jämförelse, pseudospråk |
| Integration | allt ovan | kopplar ihop, tar bort `TemporaryFas1bModule.kt` |
<!-- /Fas 1b kontrakt -->

<!-- Fas 2 kontrakt -->
## Fas 2 – kontrakt (0.3.0 "handla & planera")

Grunden för 0.3.0 (inköpslista, butiker, matsedel, "Handla för veckan", böcker, favoriter, "Vill
laga", gästläge, kontobyte) lades först, så att fem banor kan bygga parallellt mot samma typer,
tabeller och nycklar. Allt nedan finns på `main`; de riktiga implementationerna kommer från
banorna. Fakta om servern: `api-schema/fixtures/2.6.13/FACTS.md` och `2.7.1/FACTS.md`, avsnitten
"Fas 2" (inspelade av `Phase2ContractTest`, se "Fas 2 – fakta" nedan).

### Rena typer (`:core:model`, `:core:domain`)

| Paket | Typer |
|---|---|
| `model.shopping` | `ShoppingEntry` (id negativt = bara på enheten, `isDelayed(now)`, `pending`), `ShoppingFood`, `ShoppingUnit`, `ShoppingRecipeLabel` (listrecept/måltid), `ShoppingList` (Tandoors namngivna listor), `ShoppingFilter`, `ShoppingEntryDraft`, `RecipeShoppingRequest`, `ShoppingChange` (Added/Checked/Removed), `ShoppingHistoryItem`, `ParsedIngredient`, `Supermarket` + `Aisle` + `SupermarketCategory` |
| `model.mealplan` | (finns) `MealType`, `MealPlanEntry` (+ `pending`); nya `MealPlanDraft` (`addToShopping`), `MealPlanUpdate` (`move` sätter båda datumen), `MealPlanConflict`, `MealTypeDraft`, `MealPlanDay`, `MealPlanWeek` (måndag först, `startOf`), `WeekShoppingPreview`/`WeekShoppingMeal`/`WeekShoppingIngredient`/`WeekShoppingSelection` |
| `model.book` | `RecipeBook` (`isOwn`, `canEdit`), `RecipeBookKind` BOOK/SMART/FAVORITES/WANT_TO_COOK, `RecipeBookEntry`, `RecipeBookStatus`, `WantToCook` (nyckelordet `vill-laga`, `filterSearch(id)`) |
| `model.account` | `SpaceRole` (`of(gruppnamn)`), `SpacePermissions` (`readOnly` för gäst utom i demoläget), `Household`, `AccountSummary` |
| `model.sync` | `ForegroundTarget` (ShoppingList, MealPlan(vecka)), `ForegroundSyncState`, `ForegroundPolling.intervalFor(serverns golv)` = max(golvet, 20 s), aldrig under 15 s |
| `domain.shopping` | `ShoppingWindowRule`: listans fönster (midnatt UTC − min(`shopping_recent_days`, 14) dagar), `missingEntry(...)` → KEEP/DELETED/AGED_OUT, `canDetectDeletions` (komplett svar under 1000 rader) |

### Gränssnitt (`:core:data`) och vem som implementerar

Alla är main-safe, följer det aktiva kontot, fungerar offline (skrivningar sparas direkt och köas)
och har en demoväg utan nät. **Gäst** (`SpacePermissions.readOnly`): varje skrivning ger
`DataProblem.READ_ONLY` innan något sparas; **någon annans bok**: `DataProblem.NOT_OWNER`.

| Gränssnitt | Bana | Innehåll |
|---|---|---|
| `shopping.ShoppingRepository` | inköp (`:core:sync`) | `observeEntries(filter)`, `observeLists`, `observePendingCount`, `observeChanges`, `add`/`addAll`, `addRecipe`, `setChecked`, `setDelayed`, `update`, `setLists`, `remove`, `removeListRecipe`, `clearChecked`, `refresh(full)`, `shareText`, `observeSuggestions`, `observeHistory` |
| `shopping.SupermarketRepository` | inköp | butiker med gångordning, kategorier, vald butik per enhet, `setFoodCategory`, `setFoodOnHand`, `reorderAisles` |
| `shopping.IngredientParser` | inköp | `parse(lines)` (servern, lokalt offline), `parseLocally(line)`; Tandoors parser **skapar livsmedel**, anropa den bara för text som läggs till |
| `mealplan.MealPlanRepository` | matsedel | `observeWeek`, `observeMeal`, `observeMealTypes`, `create`, `update`, `delete`, `createMealType`, `observeConflicts`/`resolveConflict`, `refresh`, `weekShoppingPreview`, `addWeekToShopping` |
| `book.RecipeBookRepository` | böcker | `observeBooks`/`observeBook`/`observeRecipes`, `observeFavorites` (allas, sammanslagna), `observeWantToCook`, `observeStatus`, `createBook`, `addToBook`, `removeFromBook`, `setFavorite` (egen favoritbok, skapas vid behov), `setWantToCook`, `refresh` |
| `account.AccountDirectory` | hushåll/konto | (utökar `AccountRepository` som eget gränssnitt) `observeAccounts`, `switchTo`, `observeHousehold`, `refreshHousehold` |
| `permissions.SpacePermissionsRepository` | hushåll/konto | `permissions` (grupp ur `/api/user-space/all_personal/`) |
| `sync.ForegroundSync` | synk | `keepFresh(target)`: samla medan skärmen är STARTED; pollar listan/matsedeln enligt fönsterregeln nedan |

`:core:sync` har kroken `engine/OutboxHandler` (+ `OutboxResult` Sent/Created/Dropped/Failed):
varje bana binder sin avsändare per outbox-entitet med `@IntoMap @StringKey(OutboxEntities.X)` i
en egen Hilt-modul (kartan deklareras i `di/OutboxHandlerModule`), så ingen bana behöver ändra
`OutboxSender`. `OutboxSender` sköter säkert läge, ordning, `refs`, id-omskrivningen efter en
CREATE och felreglerna (404 = raderat: släpps; 400 = FAILED med beroende rader; 401/403/429 och
kretsbrytaren stoppar; offline/5xx försöker igen).

### Room-schema 4 (`MIGRATION_3_4`, hela fasens enda migrering)

Tabellerna och migreringen är **slutliga för Fas 2**: banorna lägger till frågor i DAO:erna men
ingen tabell, kolumn eller version. Behövs ändå en ändring blir den 4 → 5 och samordnas här först.
`4.json` är incheckad; `TandemMigrationTest` prövar 3 → 4 och 1 → 4.

| Tabell | Nyckel | Innehåll |
|---|---|---|
| `shopping_entry` | `(account_id, local_id)`, unik `server_id` | livsmedel (id, namn, plural, gång), enhet, mängd, ordning, `checked`, `completed_at`/`delay_until`/`created_at`/`updated_at` (epoch ms), vem (id, namn), `list_recipe_id`, `ingredient_id`, `meal_plan_id`, recept (id, namn), `shopping_list_ids` (JSON), `fetched_at` |
| `shopping_list_recipe` | som ovan | namn, recept, måltid, portioner, skapare |
| `shopping_list` | `(account_id, id)` | Tandoors namngivna listor |
| `shopping_history` | `(account_id, entry_id)` | avbockade rader som åldrats ut (fönsterregeln), för "brukar köpa" |
| `supermarket`, `supermarket_category`, `supermarket_category_relation` | `(account_id, id)` | butiker, gångar, gångordning per butik (`relation_order`) |
| `recipe_book` | `(account_id, local_id)`, unik `server_id` | namn, ägare (id, namn), delad med (JSON), filter (id, `search`), ordning, `kind` (`RecipeBookKinds`) |
| `recipe_book_entry` | som ovan | `book_id` (server- eller lokalt id), `recipe_id` |
| `id_mapping` | `(account_id, entity, local_id)` | tillfälligt id → server-id (rensas inte i Fas 2) |

Nya kolumner: `outbox.refs`, `outbox.batch_key`, `outbox.updated_at`;
`server_account.household_name`, `server_account.shopping_min_autosync_seconds`;
`meal_plan.recipe_name`; `sync_state.delta_count` (default 0). Nya DAO:er: `ShoppingDao`,
`SupermarketDao`, `RecipeBookDao` (skelett) och `IdRewriteDao`; alla nya tabeller rensas av
`AccountDao.deleteAccountData`. Små tabeller (butiker, kategorier, listor, böcker) läses om helt
(plan 3.5); de har bara server-id (ingen skapas offline i Fas 2 utom böcker och poster).

### Outboxen: typer, beroenden och id-omskrivning (plan 5.7)

**Nyckelregeln:** serverposter har `local_id = server_id`. Ett objekt som skapas offline får ett
negativt id från `IdRewriteDao.nextLocalId` (aldrig återanvänt: lägre än allt tabellen, outboxen
och `id_mapping` haft), sparas med `server_id = null` och köas som `CREATE` med bara `local_id`.
Senare rader för samma objekt bär samma `local_id`; har objektet ett server-id bär raden bara
`server_id`. Rader för **andra** objekt som pekar på det (en post i ett nytt listrecept, en
måltid med en ny måltidstyp, en post i en ny bok) skriver det negativa id:t i payloaden, anger
fältet i `refs` (`OutboxRefs.encode(mapOf("list_recipe" to "shopping_list_recipe"))`, punkt för
nästlade fält, `meal_type.id`; en array som `ids` löses element för element) och sätter
`depends_on` till CREATE-raden.

**När CREATE besvaras** anropar avsändaren `IdRewriteDao.completeCreate` (via
`OutboxResult.Created`), som i **en** transaktion: sparar `id_mapping`, ger väntande rader för
objektet `server_id`, nycklar om den lokala raden till `local_id = server_id` (en kopia av samma
serverrad som en synk hunnit lagra tas bort), skriver om referenser i andra tabeller
(`shopping_entry.list_recipe_id`/`meal_plan_id`, `shopping_list_recipe.meal_plan_id`,
`meal_plan.meal_type_id`, `recipe_book_entry.book_id`) och tar bort CREATE-raden, vilket släpper
de beroende raderna. Payloadens referenser löses först precis före sändning (`OutboxRefs.resolve`
mot `id_mapping`). En skärm eller nyckel som håller ett gammalt negativt id löser det med
`IdRewriteDao.current`/`resolve`.

**Ordning:** lägsta `id` först; en rad väntar så länge dess `depends_on`-rad finns kvar (även
FAILED) och så länge en äldre rad för samma objekt finns. `OutboxDao.markFailed` (400) markerar
raden och alla som beror på den. `nextReadyBatch(batchKey)` ger redo rader med samma
`batch_key` (BULK_CHECK med samma värde → ett `POST .../bulk/`).

**Sammanslagning** (`OutboxDao.enqueue`, reglerna i `OutboxMerge`, gäller bara osända rader,
PENDING/PAUSED): PATCH efter CREATE → en CREATE med slutvärdena; PATCH efter PATCH → en PATCH
(första `base_snapshot` står kvar); DELETE efter CREATE → allt för objektet och allt som beror på
det försvinner, inget skickas; DELETE efter PATCH/BULK_CHECK → bara DELETE; BULK_CHECK efter
CREATE → `checked` i CREATE; BULK_CHECK/RECIPE_KEYWORDS efter samma op → sista värdet, och
tillbaka till `base_snapshot` (bocka av och på igen) tar ut varandra. CREATE, VIEW_LOG och
COOK_LOG slås aldrig ihop här.

| op | entitet | Anrop | Payload / `refs` / batch | Särskilt |
|---|---|---|---|---|
| CREATE | `shopping_entry` | `POST /api/shopping-list-entry/` | `{food:{name[,id]}, unit:{name}\|null, amount, shopping_lists:[{id}], list_recipe?, mealplan_id?}`; refs `list_recipe`, `mealplan_id` | `amount` krävs (400 utan), `food: null` ger 500; svaret har `id` → `Created` |
| PATCH | `shopping_entry` | `PATCH .../{id}/` | ändrade fält; `shopping_lists:[{id}]` för flytt (bumpar `updated_at`), `delay_until` (ångra = `1970-01-01T00:00:00Z`) | `completed_at` går inte att sätta (servern sätter den) |
| BULK_CHECK | `shopping_entry` | `POST .../bulk/` `{ids, checked}` | en rad per post, `base_snapshot {"checked": före}`, batch `shopping_entry:checked=true\|false`; refs `ids` | ett okänt/raderat id ger ändå 200 (ingen 404): inget att rensa |
| DELETE | `shopping_entry` | `DELETE .../{id}/` | – | 404 om redan raderad |
| CREATE | `shopping_list_recipe` | `POST /api/shopping-list-recipe/` `{recipe, servings, name, mealplan?}` (`mealplan` enligt schemat, inte prövat i sviten), sedan posterna som egna CREATE med `list_recipe` | refs `mealplan` | `recipe/{id}/shopping/` svarar bara `{msg}` (inga id) och kan inte kopplas till en måltid (500): använd inte för offline |
| PATCH/DELETE | `shopping_list_recipe` | `PATCH`/`DELETE .../{id}/` | `servings` skalar posterna; DELETE tar bort posterna | `PUT recipe/{id}/shopping/` med `servings: 0` ger 500; negativt tar bort |
| CREATE/PATCH/DELETE | `meal_plan` | `/api/meal-plan/` | `{title, recipe:{id,name}\|null, servings, note, from_date, to_date, meal_type:{id,name}, addshopping?}`; refs `meal_type.id`; PATCH med `base_snapshot` (fältjämförelse före sändning, plan 3.5) | flytt = båda datumen; DELETE tar bort måltidens poster |
| CREATE | `meal_type` | `POST /api/meal-type/` | `{name, time?, color?, order}` | delas av hela spacet |
| CREATE/DELETE | `recipe_book` | `/api/recipe-book/` | `{name, description, shared:[{id}], filter}` (`shared` krävs) | bara egna böcker |
| CREATE/DELETE | `recipe_book_entry` | `POST /api/recipe-book-entry/` `{book, recipe}`, `DELETE .../{id}/` | refs `book` | 400 "unique set" om receptet redan finns |
| RECIPE_KEYWORDS | `recipe` | `PATCH /api/recipe/{id}/` `{keywords:[...hela listan]}` | `{"keywords": {"vill-laga": true}}`, `base_snapshot` med före-värdet | läs receptet färskt före sändning; gäst får 403 |
| PATCH | `food` | `PATCH /api/food/{id}/` | `supermarket_category:{id,name}\|null`, `food_onhand` | gång och "har hemma" gäller hela spacet/hushållet |
| PATCH | `supermarket_category_relation` | `PATCH .../{id}/` `{order}` | – | butikens lista sorteras på `order` |
| PATCH | `user_preference` | `PATCH /api/user-preference/{user}/` | t.ex. `shopping_add_onhand` | `server_id` = användar-id; någon annans → 404 |

### Synk: fönsterregeln och polling (plan 3.5)

- **Inköpslistan:** delta med `updated_after=<serverns senaste timestamp>` (**URL-kodat**: ett
  okodat `+` i offseten blir mellanslag och Tandoor svarar då med hela listan) och `page_size=200`;
  var 10:e gång (`sync_state.delta_count`) och vid start en full omläsning. En lagrad rad som saknas
  i en full omläsning avgörs av `ShoppingWindowRule.missingEntry`: osänd/ändrad → behåll;
  oavbockad → raderad (outbox-raderna släpps utan att skickas); avbockad minst en dag inne i
  fönstret → raderad; äldre → `shopping_history`. Ett svar med 1000 rader (eller ofullständigt)
  raderar ingenting. Outboxen töms **före** omläsningen.
- **Polling medan listan är öppen:** `ForegroundSync.keepFresh(ShoppingList)` var
  `ForegroundPolling.intervalFor(server_account.shopping_min_autosync_seconds)` (20 s med
  Tandoors standard "5"), aldrig i demoläget eller när `SyncGuard` pausar. Två telefoner ser
  varandras avbockningar inom 30 s (Fas 2 "Klart när").
- **Matsedeln:** fönstret −14..+42 dagar läses om och jämförs (finns sedan Fas 1,
  `MealPlanDao.replaceWindow` lämnar poster med osända rader); med Veckan öppen högst var 60:e s.
- **Små tabeller** (butiker, kategorier, listor, böcker och poster): full omläsning med de andra
  små tabellerna (start högst 1/h, var 6:e timme).

### Navigering och moduler

| Modul | Nycklar | Routes (platshållare tills banan bygger skärmen) |
|---|---|---|
| `:feature:shopping` (ny) | `ShoppingListKey(listId?, supermarketId?)`, `StoreModeKey(listId?, supermarketId?)` (helskärm på alla bredder), `ShoppingListChoiceKey`, `ShoppingHistoryKey` | `ShoppingRoute`, `StoreModeRoute`, `ShoppingListChoiceRoute`, `ShoppingHistoryRoute` |
| `:feature:mealplan` (ny) | `WeekAgendaKey(weekStart?)`, `PlanMealKey(date, mealTypeId?, mealPlanId?, recipeId?)`, `WeekShoppingKey(weekStart)`; datum som `yyyy-MM-dd`-strängar | `WeekRoute`, `PlanMealRoute`, `WeekShoppingRoute` |
| `:feature:recipes` | `BooksKey`, `BookDetailKey(bookId)`, `FavoritesKey`, `WantToCookKey` | `books/BookRoutes.kt`: `BooksRoute`, `BookDetailRoute`, `FavoritesRoute`, `WantToCookRoute` (strängar i `strings_books.xml`) |

Skalet: flikarnas rötter är fortfarande `WeekKey` och `ShoppingKey`; `ShellFeatures` har en
funktion per route och `TandemShell` en `entry` per nyckel (recept → `RecipeDetailKey`, "Laga" →
`CookKey`, "Handla för veckan" → `WeekShoppingKey`, listan efter → Handla-fliken). Ingången till
böckerna (chips i Recept, raden under Mer) lägger böckernas bana till. Skalets tester visar
fortfarande `PlaceholderTab` för Handla och Veckan, så dess referensbilder är oförändrade.
Nyckeltester: `ShoppingKeysTest`, `MealPlanKeysTest`, `BookKeysTest`.

### Fakes (`:core:testing`) och tillfälliga bindningar (`:app`)

`Fas2Fakes.kt`: `FakeShoppingRepository`, `FakeSupermarketRepository`, `FakeIngredientParser`,
`FakeMealPlanRepository`, `FakeRecipeBookRepository`, `FakeAccountDirectory`,
`FakeSpacePermissionsRepository`, `FakeForegroundSync`, `SampleShopping` och `sampleMeal`
(negativa id för nya objekt, `readOnly`, `failure`). `Fas2FakesTest` prövar dem.

`app/.../di/TemporaryFas2Module.kt` binder tomma stand-ins (inget visas, varje skrivning ger
`TandemError.Unknown("tandem.temporary.fas2")`) i fem moduler; **varje bana tar bort sin modul i
samma ändring som den binder den riktiga** (annars dubbel Hilt-bindning), integrationen tar bort
filen.

### Fas 2 – fakta (2.6.13 och 2.7.1 beter sig likadant)

`Phase2ContractTest` körs i `contract.yml` efter Fas 1 (push kör nu **2.6.13 och 2.7.1**). Steget
före lägger kontraktsanvändaren och den andra medlemmen i ett hushåll, skapar en gästmedlem och
tre avbockade poster 30/10/3 dagar bakåt (API:t kan inte sätta `completed_at`). Testet jämför
`EXPECTED` (det appen bygger på) och fäller körningen vid avvikelse; resten spelas in och
jämförs nattligen av `tandoor-watch` (`report.py` läser nu också `fas2/observed-facts.json`).
Inspelningarna ligger i `api-schema/fixtures/<version>/fas2/`. Det viktigaste:

- Inköpslistan: posten har `food` (`FoodShopping` med nästlad `supermarket_category`), `unit`,
  `amount` (decimal), `created_by` (helt användarobjekt), `list_recipe_data` (med `meal_plan_data`).
  PATCH av `checked` och bulk bumpar `updated_at`; bulkens `shopping_lists_add` gör det **inte**,
  PATCH av `shopping_lists` gör det. Fönstret: midnatt UTC − min(dagar, 14).
- `mealplan_autoadd_shopping` gör **ingenting** på servern när en måltid skapas via API:t: bara
  `addshopping: true` lägger på listan (då med `shopping: true`). Appen följer webben: rutan
  "Lägg på listan" startar som inställningen och skickar `addshopping` när den är ikryssad.
- Tidszon: en måltid listas under serverns lokala datum för `to_date`; ett rent datum får
  måltidstypens tid; 23:30Z blir nästa dag. PATCH av bara `from_date` lämnar `to_date`.
- Böcker: en delad medlem kan **lägga till** poster i någon annans bok (201) men inte ta bort
  (403) eller ändra boken (403). Appen visar ändå skrivknappar bara för egna böcker (plan 3.7).
  `CustomFilter.search` måste vara id:n (`{"keywords_or":[8]}`), objekt ger 500 i receptlistan.
- Gäst: listan, matsedeln och böckerna går att skapa poster i (201), bulk och receptändringar ger
  403. Appen är ändå skrivskyddad för gäster (#42).
- 404-kroppar: `{"detail": "No <Modell> matches the given query."}` för GET/PATCH/DELETE av en
  raderad post, lista recept, måltid, bok eller bokpost.
- `shopping_min_autosync_interval` är en **sträng** ("5"); `shopping_share` finns inte i 2.6/2.7.
  Hushållet läses ur `/api/user-space/all_personal/` (`/api/household/` ger 403 för icke-admin).

### Vem bygger vad

| Bana | Bygger på | Levererar (tar bort) |
|---|---|---|
| Inköp (`:feature:shopping`, `:core:sync`, `:core:network`) | `ShoppingRepository`, `SupermarketRepository`, `IngredientParser`, `shopping_*`/`supermarket*`-tabellerna, `OutboxHandler` för `shopping_entry`, `shopping_list_recipe`, `food`, `supermarket_category_relation` | Listan grupperad efter gångordning, "köp senare", fritext, butiksläge, dela som text, listval, "Lägg på listan" i receptdetaljen (#9–#14); DTO:er och tjänster för sina endpoints (egna filer) med rader i `DtoFixturesTest`; `TemporaryFas2ShoppingModule` |
| Matsedel (`:feature:mealplan`, `:core:sync`, `:core:network`) | `MealPlanRepository`, `meal_plan`/`meal_type`, `OutboxHandler` för `meal_plan`, `meal_type` | Veckan (agenda, veckorutnät på surfplatta), planera/flytta/radera, konfliktdialogen, "Handla för veckan" utan dubbletter (#15–#16); `TemporaryFas2MealPlanModule` |
| Böcker (`:feature:recipes`, `:core:sync`, `:core:network`) | `RecipeBookRepository`, `recipe_book*`, `WantToCook`, `OutboxHandler` för `recipe_book`, `recipe_book_entry`, `recipe` (RECIPE_KEYWORDS) | Böcker, Favoriter per person, "Vill laga", knapparna i receptdetaljen, chips i Recept, raden under Mer (#17); `TemporaryFas2BookModule` |
| Hushåll och konton (`:feature:shell`, `:core:data`, `:core:sync`) | `AccountDirectory`, `SpacePermissionsRepository`, `server_account.household_name`, `SpaceRole` | Kontobyte (Stitch `konto_servrar`), hushållets namn på kontokortet, gästläget i hela appen (#42), `OutboxHandler` för `user_preference`; `TemporaryFas2AccountModule` |
| Synk (`:core:sync`) | `ForegroundSync`, `ShoppingWindowRule`, `OutboxDao.enqueue/nextReadyBatch`, `IdRewriteDao`, `sync_state.delta_count` | Delta + full omläsning med fönsterregeln och arkivering, polling, små tabeller (butiker, listor, böcker), rensning före tömning, demoinnehåll för Fas 2; `TemporaryFas2SyncModule` |
| Integration | allt ovan | kopplar ihop, tar bort `TemporaryFas2Module.kt`, röktest för Fas 2 |
<!-- /Fas 2 kontrakt -->

<!-- Fas 2 inköp-data -->
## Inköpslistan – data och synk (`:core:sync`, `:core:network`, Fas 2)

Inköpsbanans datadel (funktion #9 med datadelen av #10–#14, plan 3.5, 5.7, 8.2). Allt ligger i
`core/sync/.../shopping/` och binds i `di/ShoppingModule.kt`, som ersätter appens
`TemporaryFas2ShoppingModule` och `TemporaryFas2SyncModule` (borttagna):

| Gränssnitt (`:core:data`) | Implementation |
|---|---|
| `ShoppingRepository` | `OfflineFirstShoppingRepository` |
| `SupermarketRepository` | `DefaultSupermarketRepository` (vald butik per enhet och konto i `sync_state`, nyckeln `shopping_selected_supermarket`, id i `hash`) |
| `IngredientParser` | `DefaultIngredientParser` (servern online; `core:domain` `LocalIngredientParser` offline, i demoläget, för gäster och vid serverfel) |
| `ForegroundSync` | `DefaultForegroundSync` (listan och matsedeln, se nedan) |

**Nätverk:** `TandoorApi.shopping` (`ShoppingService`: listan med `updated_after`, skapa, PATCH,
DELETE, bulk, listrecept, namngivna listor) och `TandoorApi.supermarkets` (`SupermarketService`:
butiker, gångar, gångordning, livsmedel, parsern). Skrivningarnas kropp är outboxens JSON
(`JsonObject`), så det som slagits ihop offline skickas exakt som det sparats. DTO:er i
`dto/ShoppingDtos.kt`, rader i `used-endpoints.txt` och `DtoFixturesTest` (båda versionerna).

**Offline först:** varje ändring skrivs i `shopping_entry`/`shopping_list_recipe` och köas med
`OutboxDao.enqueue` i **en** transaktion (`ShoppingDao.inTransaction`, som andra DAO:er anropas
inom; Room binder transaktionens anslutning till korutinen). Payloaderna står i
`ShoppingPayloads`: CREATE `{food:{name[,id]}, unit, amount, shopping_lists[, list_recipe,
ingredient]}`, BULK_CHECK en rad per post (batch `shopping_entry:checked=…`), PATCH för mängd,
enhet, livsmedel, listor (`shopping_lists`) och "köp senare" (`delay_until`, ångra =
`1970-01-01T00:00:00Z`), DELETE. Ett recept på listan är ett listrecept (CREATE, `mealplan` i
`refs` om måltiden också är ny) och en CREATE per vald ingrediens som `depends_on` listreceptet
med `list_recipe` i `refs`; mängderna skalas med portionerna. En ändring av ett objekt vars CREATE
väntar osänd slås ihop med den (en avbockning köas då som PATCH `{checked}`, utan tillfälligt id i
payloaden) och får `depends_on` bara när CREATE redan skickas. Gäster får `READ_ONLY` innan något
sparas; demoläget skriver bara demodatabasen.

**Avsändare** (`ShoppingOutboxHandlers.kt`, bundna med `@IntoMap`): `shopping_entry`,
`shopping_list_recipe`, `food` och `supermarket_category_relation`.
- En `checked` som slagits in i en CREATE skickas inte med den (Tandoor sätter `completed_at`
  bara när `checked` slår om); posten bockas av med ett bulkanrop direkt efter, eller med en ny
  BULK_CHECK-rad om det misslyckas (och användaren inte bockat av eller på igen).
- **PATCH och DELETE av en post kontrolleras först** mot en full läsning av listan (högst en per
  minut, `ShoppingReader.stillOnServerLocked`): en post som saknas i ett komplett svar under 1000
  rader släpps **utan anrop** (`DroppedChanges.DELETED_ELSEWHERE`). Så töms outboxen utan en enda
  404 när partnern raderat raderna (Fas 2 "Klart när"). Nya poster från egna CREATE och deltan
  räknas som kända.
- En 404 som ändå kommer (raderad mellan läsningen och sändningen, listrecept, livsmedel, gång)
  är ett väntat svar: raden är klar, posten tas bort lokalt med det som köats för den, skälet
  hamnar i `DroppedChanges.NOT_FOUND`, och `RemoteCaller(expectedNotFound = true)` låter
  kretsbrytaren vara. Allt annat följer `OutboxSender`s regler (400 FAILED, 401/403/429 stoppar,
  säkert läge PAUSED, offline/5xx försöker igen).
- `POST` av en post är inte idempotent: en rad som försökts förut letar först efter en egen ny
  post med samma livsmedel, enhet och mängd.

**Läsningar** (`ShoppingReader`, med ett lås som avsändarna också håller, så att en läsning
aldrig jämförs med en ändring som skickas samtidigt):
- Delta med `updated_after` = serverns senaste `timestamp` − 2 s (överlapp), `page_size` 200;
  var 10:e läsning (`sync_state.delta_count`), den första och vid start/inloggning
  (`SyncEngine`, `APP_START`/`FIRST_SYNC`/`ACCOUNT_CHANGED`) en full läsning.
- En post med rader i outboxen behåller sitt lokala läge tills ändringen är skickad.
- Fönsterregeln vid en full, komplett läsning (`ShoppingWindowRule`): saknade oavbockade och
  nyligen avbockade poster raderas lokalt, och deras köade ändringar släpps osända (plan 3.5);
  äldre avbockade flyttas till `shopping_history`. Listrecept som ingen post pekar på tas bort.
- Andra medlemmars ändringar (ny post, avbockning, borttagning) går till `observeChanges()`,
  aldrig vid kontots första läsning.
- **Små tabeller** (`ShoppingTables`, med motorns övriga små tabeller): namngivna listor,
  butiker med gångordning, gångar och `shopping_min_autosync_interval` (en sträng) till
  `server_account.shopping_min_autosync_seconds` (`AccountMirror` behåller värdet). Köade
  ändringar av livsmedel och gångordning läggs på igen efter omläsningen.
- `SyncEngine` kör `ShoppingSyncStep` efter de små tabellerna och före outboxen.

**Polling** (`DefaultForegroundSync.keepFresh`): en poller per mål, delad av alla som samlar och
stoppad när den sista slutar (skärmen lämnade STARTED). Listan: `ShoppingSync.refresh` (outboxen,
sedan läsningen) var `ForegroundPolling.intervalFor(golvet)` = 20 s med Tandoors "5". Matsedeln:
`MealPlanRepository.refresh()` var 60:e s. Pausar (inget anrop, `paused` säger varför) offline,
efter avvisad token, blockerad/429 (`BLOCKED`), kretsbrytare och import; säkert läge läser men
skickar inte. Aldrig i demoläget.

**Demo:** `scripts/demo-content/generate_demo_shopping.py` skriver `<språk>/shopping-list-entries.json`,
`shopping-lists.json`, `supermarkets.json` och `supermarket-categories.json` (efter
`generate_demo_content.py`); `DemoContent` läser dem och `DemoShopping` fyller demodatabasen en
gång per innehållsversion och språk, så det användaren gör i demoläget står kvar.

**Tester:** `ShoppingSyncTest` (Robolectric, en tillståndsfull Tandoor i de inspelade formerna
för **2.6.13 och 2.7.1**, `FakeShoppingTandoor`): 20 ändringar offline med skapa + bocka av samma
vara, partnern raderar 15 rader (inga 404 i serverns logg), två telefoner med `updated_after` och
fönsterregeln, bulk, köp senare, recept på listan, rensa avbockade, 404 utanför kretsbrytaren,
429, säkert läge, polling, gäst, konton och demo. `ShoppingOrderingTest`, `ShoppingDaoTest`
(transaktionen, frågorna, 3 → 4 med en köad "Klart!"), `ShoppingServicesTest`,
`LocalIngredientParserTest`, `DemoShoppingContentTest`.

**Kontrakt:** `ShoppingWritesContractTest` (eget steg i `contract.yml` efter Fas 2, spelar inte in
något) prövar skrivningarna exakt som outboxen skickar dem mot 2.6.13 och 2.7.1: en post med
`list_recipe` och `ingredient` hör till listreceptet, ett listrecept med `mealplan` hör till
måltiden (`?mealplan=`), PATCH av en posts livsmedel och enhet med namn (och enheten bort med
`null`), `updated_after` i den `Z`-form appen skickar, och att posterna försvinner med sitt
listrecept.
<!-- /Fas 2 inköp-data -->

<!-- Fas 2 matsedel -->
## Matsedeln – data, synk och Veckan (`:core:sync`, `:feature:mealplan`, Fas 2)

Matsedelsbanan (funktion #15 och #16, plan 3.5, 5.7, 6.2 "Veckan", 8.3). Datadelen ligger i
`core/sync/.../mealplan/` och binds i `di/MealPlanModule.kt` (repositoryt och outbox-avsändarna
för `meal_plan` och `meal_type`); appens `TemporaryFas2MealPlanModule` är borttagen.

**Datum (bara dagar, `core:domain` `MealPlanDates`):** Tandoor svarar med datum-tider i serverns
offset; dagen är datumet i svaret (`2026-10-25T00:30:00+02:00` → 25 oktober), oavsett telefonens
zon. Appen skickar **naiva** serverlokala datum-tider (`2026-10-26T00:30:00`, ingen offset): Django
läser dem i serverns zon, så en måltid stannar på sin dag även över sommartidens slut (en offset
från en annan dag hade flyttat en måltid nära midnatt till grannen). En ny måltid skickar midnatt
(Tandoor sätter måltidstypens tid, samma form som kontraktsinspelningen), en flyttad behåller sin
tid på dagen. En flytt skickar **alltid båda** datumen och behåller måltidens längd
(`PATCH {from_date}` ensam lämnar `to_date`, FACTS "Fas 2 f"); en `to_date` före `from_date`
visas som en dag. `MealPlanDatesTest` prövar inspelningarna från 2.6.13 och 2.7.1 och en server i
Europe/Stockholm över båda sommartidsskiftena.

**Fönstret (`MealPlanWindowReader`, plan 3.5):** −14..+42 dagar med `?from_date&to_date`, som
filtrerar på serverlokalt datum för `to_date`; lagrade servermåltider vars `to_date` ligger i
fönstret ersätts (`MealPlanDao.replaceWindow`), måltider med osända ändringar lämnas som
användaren lämnade dem. Samma läsning används av synkkörningens steg 4 (`SyncEngine`) och av
`MealPlanRepository.refresh()` (dra-för-att-uppdatera i Veckan och `ForegroundSync` var 60:e s
medan Veckan syns), som läser fönstret först och sedan tömmer outboxen. **Raderad någon
annanstans:** en måltid med osända rader som ett komplett svar inte längre listar fast dess
senast kända server-`to_date` (`to_raw`, för en lokalt raderad måltid DELETE-radens `to_date`)
ligger i fönstret tas bort med sina outbox-rader **utan** anrop, så tömningen efteråt aldrig
möter en 404 (`MealPlanDao.deleteGone`).

**Offline först (`OfflineFirstMealPlanRepository`):** skapa, ändra, flytta, ta bort och nya
måltidstyper skrivs i `meal_plan`/`meal_type` och köas med `OutboxDao.enqueue` i en transaktion
(`MealPlanDao.inTransaction`); negativa id från `IdRewriteDao.nextLocalId`. En måltid med en ny
måltidstyp har `refs` `{"meal_type.id": "meal_type"}` och `depends_on` på typens CREATE. Skapa +
flytta + ändra offline blir **en** POST med slutvärdena; skapa + ta bort skickar ingenting.
`observeMeal(id)` följer ett tillfälligt id över omskrivningen (`MealPlanDao.observeRow` slår upp
`id_mapping`). `ReferenceDao.replaceMealTypes` lämnar osända (negativa) måltidstyper kvar.
`meal_plan.recipe_name` (schema 4) ger namnet innan receptets översikt finns lokalt.
Gäster får `DataProblem.READ_ONLY` innan något sparas; demoläget skriver i demodatabasen och köar
inget.

**Fältjämförelse före PATCH (`MealPlanOutboxHandler`, plan 3.5):** PATCH-raden bär bara de
ändrade fälten och i `base_snapshot` samma fält som de var (datum som `yyyy-MM-dd`). Före sändning
läses måltiden (`GET /api/meal-plan/{id}/`, `MealPlan` saknar `updated_at`) och varje fält
jämförs (`MealPlanPayloads.compare`): oförändrat på servern → skickas; redan användarens värde →
utelämnas; ändrat av någon annan → **konflikt**. Övriga fält skickas aldrig, så en anteckning
eller portioner som ändrats på webben skrivs inte över. De två datumen räknas som ett fält.
Konfliktfälten väntar i en egen outbox-rad (`FAILED`, `last_error = tandem.mealplan.conflict`,
payload = användarens värden, `base_snapshot` = serverns), som `observeConflicts()` visar och
`resolveConflict` löser: "Behåll min" köar fälten igen med serverns värde som bas, "Ta deras"
skriver serverns värde lokalt. En 404 (raderad mellan läsning och sändning) är ett väntat svar
(`expectedNotFound`, utanför kretsbrytaren). En POST som försöks igen letar först efter måltiden
på sin dag (POST är inte idempotent); en måltidstyp på namn.

**"Handla för veckan" (#16):** `weekShoppingPreview` tar veckans måltider med recept, markerar
dem som redan har poster på den lagrade listan (`shopping_entry.meal_plan_id`) eller `shopping`
som "redan på listan" (aldrig valbara) och ger ingredienserna ur den lagrade receptdetaljen,
skalade till måltidens portioner, med "har hemma" bortvalt. `addWeekToShopping` anropar
`ShoppingRepository.addRecipe` med `mealPlanId` per måltid (inköpsbanans listrecept + poster,
kopplade till måltiden) och hoppar över måltider som redan finns på listan. Ett recept utan
lagrad detalj har inga ingredienser att välja (öppna det en gång online).

**Nätverk:** `TandoorApi.mealPlanEdits` (`MealPlanEditService`: en måltid, skapa, PATCH, DELETE,
ny måltidstyp); läsningen av fönstret är Fas 1:s `MealPlanService`. Rader i `used-endpoints.txt`
och `DtoFixturesTest` (GET/PATCH av en måltid).

### Veckan (`:feature:mealplan`)

| Route (nyckel) | ViewModel | Skärm |
|---|---|---|
| `WeekRoute` (`WeekKey`, `WeekAgendaKey`) | `WeekViewModel` (assisted, veckans måndag) | `WeekScreen` |
| `PlanMealRoute` (`PlanMealKey`) | `PlanMealViewModel` (assisted) | `PlanMealScreen` |
| `WeekShoppingRoute` (`WeekShoppingKey`) | `WeekShoppingViewModel` (assisted) | `WeekShoppingScreen` |

- **Veckan:** veckonummer och datum, föregående/nästa vecka, "Idag" utanför den här veckan,
  "5 av 7 dagar planerade". Den visade veckan hålls färsk medan skärmen syns
  (`ForegroundSync.keepFresh(MealPlan(vecka))`). Veckan och vald dag ligger i `SavedStateHandle`.
- **Layout (plan 6.5, efter fönstrets storlek):** under 600 dp en agenda (en del per dag, rullad
  så att idag står först); från 600 dp två paneler (veckans dagar med måltidernas namn bredvid
  den valda dagen, idag som standard; surfplatta stående och telefon liggande); från 840 × 560 dp
  veckorutnätet (dagar som kolumner, måltidstyper som rader, "+" i tomma celler; rutnätet rullar i
  sidled hellre än att klippa söndagen, docs/design/README.md).
- **Måltider:** foto, måltidstyp, namn, tid, portioner, anteckning, "på inköpslistan" och "väntar
  på att synkas". Ett tryck öppnar receptet (en måltid utan recept öppnar "Ändra"); menyn och
  TalkBacks egna åtgärder har "Laga" (dagens måltider, kokläget med måltidens portioner), "Ändra",
  "Flytta" och "Ta bort". Ta bort och flytta visar "Ångra" (ta bort planerar måltiden igen med
  samma värden; Tandoor har då redan tagit bort dess poster på inköpslistan). Flytta är ett
  bottenark med två veckors dagar, "Annat datum…" (datumväljare) och måltidstyp (dra och släpp
  kommer i Fas 3). En konflikt visas som dialogen "Ändrad på två ställen".
- **Planera måltid:** en egen sida på alla bredder (innehållet i läsbar bredd på surfplatta):
  dag, måltidstyp (eller "Ny måltidstyp"), recept ur de lagrade recepten eller bara en titel,
  portioner, anteckning och "Lägg ingredienserna på inköpslistan" (bara nya måltider med recept;
  startar som `mealplan_autoadd_shopping` och skickar `addshopping` exakt när den är ikryssad).
  "Ändra" sparar bara det som ändrats; en måltid med recept kan byta recept men inte bli "bara
  titel" (`MealPlanUpdate` kan inte ta bort ett recept).
- **Lägen:** tom vecka, gäst (#42: inga planera-, ändra- eller flyttakontroller alls), demo
  (skalets banner, exempelveckan ur demoinnehållet), offline (statuschippet; ändringar väntar).
- **Tester:** `MealPlanDatesTest` (`:core:domain`), `MealPlanSyncTest` (`:core:sync`, en
  tillståndsfull `FakeMealPlanTandoor` i Europe/Stockholm med inspelningarnas form från både
  2.6.13 och 2.7.1: dagar från webben, sommartid, `to_date`-filtret, offline skapa/flytta/ta bort
  med tillfälliga id även för en ny måltidstyp, fältjämförelse och konflikter, raderat någon
  annanstans utan 404, synkkörningen och Idags "Ikväll", gäst, demo), `WeekViewModelTest`,
  `PlanMealViewModelTest`, `WeekShoppingViewModelTest`, `WeekScreenTest` (semantik, åtgärder,
  gäst, rutnät och två paneler, routen med ångra), `MealPlanAccessibilityTest` (ATF) och
  `MealPlanScreenshotTest`/`MealPlanPseudoLocaleScreenshotTest`
  (`feature/mealplan/src/test/screenshots/mealplan/`). Skärmarna och lägena står i `MealPlanShot`.
<!-- /Fas 2 matsedel -->

<!-- Fas 1b koklogg -->
### Koklogg ("Klart!", `:core:sync` + `:core:database`)

`OfflineFirstCookLogRepository` (bunden i `SyncModule`; `TemporaryFas1bCookLogModule` är
borttagen) implementerar `CookLogRepository` offline först (plan 5.3/5.7, funktion #8):

- **Lokalt direkt:** `logCooked` skriver i en transaktion (`CookLogDao.logLocally`) en
  `cook_log`-post (PENDING, negativt `local_id`), en outbox-rad `COOK_LOG` (`entity = recipe`,
  `server_id` = recept-id, kroppen till `POST /api/cook-log/` `{recipe, servings, rating,
  comment, created_at}` utan null-fält) och receptets statistik; sedan schemaläggs
  `SyncOutboxWorker`. `created_at` är när "Klart!" trycktes, i hela sekunder (`TandoorDates.format`),
  så en post som skickas dagar senare behåller sitt datum (bakåtdatering fungerar, FACTS "Fas 1 f").
- **Statistiken** ("antal gånger", "senast lagad", eget betyg för sortering, filter och stjärnor)
  räknas på ett ställe, `CookStatistics` (`:core:database`), samma avrundning som 0.1.0 och
  servern (eget snitt, halvt uppåt). Räknas: SYNCED (serverns lista), SENT (skickad efter att
  listan begärdes) och PENDING. Synkens kokloggssteg ersätter serverns del av `cook_log` med den
  kompletta listan och räknar om alla recept i en transaktion (`CookLogDao.reconcile`); en skickad
  post har serverns id, så listans kopia ersätter den och inget räknas två gånger. En
  detaljhämtning skriver serverns betyg; har receptet osända poster räknas de in igen
  (`reapplyUnsent`). Före den första kompletta listan efter uppdateringen från 0.1.0 är tabellen
  tom (`isComplete` falsk): då lägger "Klart!" bara till (+1, senare datum, betyg om inget fanns)
  och synken hämtar kokloggen i nästa körning oavsett intervall.
- **Skicka (`OutboxSender`):** 201 gör posten till serverns (SENT, id från svaret) och tar bort
  outbox-raden i samma transaktion. Recept som inte längre finns lagrat → DROPPED
  (`recipe_gone`) utan anrop; 404 → DROPPED (`not_found`); 400 på fältet `recipe` ("Invalid pk",
  så svarar Tandoor 2.6 för ett raderat recept) → DROPPED (`recipe_unknown`); receptet borttaget ur indexet →
  outbox-raden försvinner med receptet och posten blir DROPPED (`recipe_deleted`); 400 → posten
  och outbox-raden FAILED (finns kvar i "Väntande ändringar" och exporten, försöks inte igen
  automatiskt). 401/403, 429 och kretsbrytaren pausar som för VIEW_LOG (posten räknas fortfarande),
  säkert läge sätter raden i PAUSED. `POST /api/cook-log/` är inte idempotent: en rad som försökts
  förut slår först upp `GET /api/cook-log/?recipe=` och letar efter egen post med samma
  `created_at`, så ett svar som tappades vid timeout aldrig blir en dubblett.
- **Demoläge:** posten hamnar bara i demodatabasen, köas aldrig och räknas direkt (demons kokhistorik
  ligger i `cook_log` via `DemoSeeder`; demodetaljernas betyg står kvar utom för recept som fått betyg
  i demoläget). "Återställ demodata" tömmer demodatabasen, posterna med.
- **Konto:** raderna har kontots id; utloggning tar bort dem (`AccountDao.deleteAccountData`), "Logga
  in igen" som samma användare och space skickar dem. `observePendingCount()` = kontots
  `COOK_LOG`-rader i outboxen (0 i demoläget). Återställningsskärmens export
  (`RoomPendingChangesExporter`) tar med dem som alla outbox-rader.
- **Tester:** `CookLogDaoTest` (`:core:database`: inkrementellt före första listan, omräkning,
  SENT/listan utan dubbelräkning, DROPPED/FAILED, indexradering, demoregler, avrundning),
  `TandemMigrationTest` (1 → 2), `AccountIsolationTest`, `OutboxDaoTest` och `CookLogRepositoryTest`
  (`:core:sync`, HTTPS-MockWebServer med `cook-log-create*.json`: offline → online med bevarat
  datum, synkens avstämning, detaljhämtning, 404, 400 på `recipe`, 404-storm, 400 + export, 429, 403 + ny
  inloggning, tappat svar, säkert läge, demo, konton). Kontraktstestet `cookLogBackdatedAsTheAppSends`
  kontrollerar att Tandoor tar emot och lämnar tillbaka appens exakta kropp med `created_at` bakåt i
  tiden, listar den med samma tidpunkt och författare och svarar 400 på `recipe` för ett raderat
  recept (2.6.13 och 2.7.1 vid push, hela matrisen nattligen).
<!-- /Fas 1b koklogg -->

<!-- Fas 1b timer -->
## Timers (`:core:timer`, Fas 1b)

Den riktiga `TimerController` (plan 8.1, funktion #6/#7) är `engine/DefaultTimerController`,
bunden i `di/TimerModule.kt` (grundens `TemporaryFas1bTimerModule` är borttagen). Features når den
bara via `core:data`.

- **Ett lås, en sanning:** varje ändring körs under en mutex: läs filen en gång, ändra med
  `CookTimer`s övergångar, **spara först**, publicera `timers`, och ställ sedan in systemet
  (larm, notiser, ringtjänst). En process som dödas direkt efter en ändring hittar den på disk.
- **Lagring – DataStore, inte Room:** högst 8 små poster som alltid läses och skrivs som helhet
  och som en mottagare måste kunna läsa snabbt efter omstart. En typad DataStore-fil
  (`files/datastore/cook_timers.json`, JSON med stabila `@SerialName`, okända nycklar ignoreras,
  en oläsbar fil ersätts med en tom) ger atomiska skrivningar utan databasschema: ingen
  Room-migrering i `core:database` som krockar med andra banors versionsnummer, och timers hör
  till enheten, inte till ett konto (inget för `deleteAccountData` att städa).
- **Larm (`alarm/AndroidAlarmScheduler`):** en löpande timer har ett larm vid `endsAt`. Med
  exakta larm `setAlarmClock` (skjuts aldrig upp av Doze, får starta ringtjänsten från
  bakgrunden och visar timern i systemets klock-UI; `setExactAndAllowWhileIdle` är
  kvoterad i Doze, så två timers nära varandra kunde ringa sent). Utan (play utan
  `SCHEDULE_EXACT_ALARM`) `setAndAllowWhileIdle`, och `TimerCapabilities.alarmMayBeLate` låter
  UI:t varna. En ringande timer har ett eget "tysta"-larm vid `endsAt + 5 min`.
- **När det ringer:** alla förfallna timers blir RINGING. En timer som ringer mer än en minut sent
  (telefonen var av, inexakt larm) ringer från nu och får sina fulla 5 minuter. Larmnotisen
  (`timer_alarm`, larmljud med `USAGE_ALARM`, vibration, `FLAG_INSISTENT` så att ljudet upprepas)
  har "+1 min" och "Stopp", och fullskärm till `ring/TimerAlarmActivity` (`showWhenLocked`,
  `turnScreenOn`, knappar på 64 dp, vanliga vyer för snabb kallstart) när bygget får
  (`canUseFullScreenIntent`, bara sideload). Play får en heads-up-notis. Efter 5 min tystnar
  larmet av sig självt och lämnar en tyst notis "Ringde 18:45".
- **Ringtjänsten (`ring/TimerRingingService`)** körs **bara medan något ringer**, aldrig under
  nedräkningen: typ `systemExempted` när exakta larm är tillåtna (Android 14+, appar med exakta
  larm som fortsätter ett larm i bakgrunden), annars `shortService` (systemet stoppar den efter
  ca 3 min, `onTimeout`). Den håller ett partial wake lock, visar en egen tyst notis ("2 timers
  ringer", "Stoppa alla") och stoppar sig själv senast efter 5,5 min (då tystar den först det som
  ringt för länge via motorn). Ljudet kommer från larmnotisen; **när den inte kan låta**
  (`TimerSystem.alarmNotificationAudible`: ingen notisbehörighet, eller kanalen `timer_alarm`
  avstängd, sänkt under "standard" – skuggans "Tyst" – eller utan ljud) spelar tjänsten
  larmljudet och vibrationen själv, och `TimerCapabilities.alarmMayBeSilent` varnar i kokläget,
  inställningarna och Behörigheter. Går tjänsten inte att starta (play, inexakt larm från
  bakgrunden) ringer notisen ensam.
  - *Granskningsfixar 0.2.0 (fix-data):* tjänstens notis är **ingen gruppsammanfattning** (tar
    appen bort en sammanfattning tar systemet barnen med sig, alltså larmen och "Ringde 18:45").
    Den ligger kvar på `timer_alarm` (kanaltabellen 8.7 är fast) men är tyst; tystar användaren
    kanalen via den märks det ovan. Tjänsten **stoppas aldrig utifrån** (`stopService` direkt efter
    `startForegroundService` kan krascha appen innan `startForeground`): `AndroidRinger.stop`
    skickar ett startkommando och tjänsten stoppar sig själv med sitt start-id när inget ringer.
    Android spelar **ett notisljud åt gången**: när ett av två ringande larm stoppas postas det
    nyaste som fortfarande ringer om (`realertAlarm`, avbryt + ny post), så att dess ljud börjar
    igen. Låsskärmslarmet uppdaterar raderna på plats (TalkBack-fokus stannar) och sätter
    fönstertiteln "Pasta: tiden är ute". En skrivning som misslyckas (full disk) görs om vid nästa
    ändring, en fil som inte går att läsa skrivs aldrig över och läses igen, och varje ringlarm
    bär sin timer (`EXTRA_TIMER_SNAPSHOT`), så att den ringer även i en ny process vars fil saknar
    den. *Borttaget förblir borttaget (komplettering 0.2.0):* ett larm som redan avfyrats kan vänta
    på motorns lås medan användaren avbryter timern, och dess ögonblicksbild får då inte väcka den
    igen. `DefaultTimerController.mayRestore` återställer därför bara en LÖPANDE timer som saknas,
    vars id inte tagits bort i processen (gravstenar i minnet för allt som `cancel`, `clearFinished`
    eller annat tagit bort) och som aldrig fanns i en fil processen läst eller skrivit (id ≥ filens
    `next_id`; varje skrivning innehåller hela listan, så en fil med högre `next_id` utan timern
    betyder att den togs bort, även i en tidigare process). En förlorad fil läses som tom med
    `next_id` 1, så dess timers ringer ändå. *Inte verifierat på en riktig enhet eller emulator ännu* (röktestet kör inga
  timers); fungerar inte `systemExempted` byter vi till `mediaPlayback` och skriver varför här.
- **Nedräkningen (`notification/AndroidTimerNotifier`):** en pågående, tyst notis per löpande eller
  pausad timer (`timer_ongoing`): nedräknande kronometer till `endsAt`, "Klar 18:45", Pausa/+1 min
  (pausad: "Pausad · 07:00 kvar", Fortsätt/Avbryt). Den ber om att bli *promoted ongoing*
  (`setRequestPromotedOngoing`), vilket Android 16 visar som en Live Update med nedräkningen i
  statusraden; äldre versioner visar samma notis. Notiserna tas bort när timern inte längre
  löper. Kanalerna skapas precis innan något postas.
- **Omstart, uppdatering, klockbyte (`alarm/TimerSystemEventReceiver`):** `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` och
  `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (alla skyddade broadcasts; allt annat ignoreras)
  lägger tillbaka alla larm och notiser. Varje ny process gör samma sak när motorn skapas. Vid en
  manuell klockändring behåller en löpande timer sin återstående tid, mätt med
  `SystemClock.elapsedRealtime` (ankare i minnet); efter en omstart gäller sparad `endsAt`.
  Larmen läggs tillbaka efter första upplåsningen (`BOOT_COMPLETED`, filen ligger i
  credential-skyddad lagring).
- **Notisknappar** går till `alarm/TimerAlarmReceiver` (inte exporterad, bara våra egna
  `PendingIntent`, unika per timer via `tandem-timer://timer/<id>`).
- **Öppna appen från en timer:** `TimerIntents.openTimer(context, timer)` startar appens
  startaktivitet med `ACTION_OPEN_TIMER` och `EXTRA_TIMER_ID`, `EXTRA_RECIPE_ID`,
  `EXTRA_STEP_INDEX` (`CLEAR_TOP | SINGLE_TOP`, alltså `onNewIntent` i en körande app).
  `MainActivity` läser det i `onCreate` (bara en ny aktivitet, inte en återskapad) och
  `onNewIntent`; `navigation/TimerLaunch` tolkar extras försiktigt (aktiviteten är exporterad:
  ogiltiga id:n släpps, trasiga extras ger bara "öppna appen"). `AppViewModel` håller begäran i
  `SavedStateHandle` tills skalet har öppnat den (och glömmer den vid utloggning), och
  `ShellNavigationState.openTimer` lägger `CookKey(recipeId, startStep = stepIndex)` på den
  aktuella fliken, eller `TimersKey` för en timer utan recept; ett redan öppet kokläge för samma
  recept lämnas orört (sidan och Klart!-utkastet ligger i dess post).
- **Klockappen:** `TimerMode.CLOCK_APP` skickar `AlarmClock.ACTION_SET_TIMER` med längd (1 s–24 h),
  etikett och `EXTRA_SKIP_UI`; Tandem följer inte timern. Ingen klockapp →
  `ClockAppUnavailable`. En timer över 24 h (`TimerSystem.MAX_CLOCK_APP_DURATION`, t.ex.
  "Marinera 48 timmar") körs i Tandem i stället, så att den inte ringer ett dygn för tidigt.
- **Behörighet i sammanhanget:** `onNotificationPermissionResult` sparar att frågan ställts
  (`AppSettings.notificationPermissionAsked`) och postar notiserna för timers som redan löper.
  `TimerCapabilities` läses vid körning (`platform/AndroidTimerSystem`): manifestet avgör vilka
  behörigheter bygget har, så `:core:timer` behöver aldrig känna till flavorn.
- **Manifest:** behörigheterna (båda exakta larmbehörigheterna, se tabellen ovan), mottagarna,
  tjänsten (`systemExempted|shortService`) och aktiviteten ligger i
  `core/timer/src/main/AndroidManifest.xml`; sideload tar bort `SCHEDULE_EXACT_ALARM` och lägger
  till `USE_FULL_SCREEN_INTENT`, play tar bort `USE_EXACT_ALARM`. R8: AAPT2 behåller manifestets komponenter;
  `core/timer/consumer-rules.pro` håller dessutom deras namn stabila, eftersom väntande larm och
  notisknappar pekar på dem efter en uppdatering.
- **Tester (Robolectric):** `DefaultTimerControllerTest` kör motorn med de riktiga Android-delarna
  (`ShadowAlarmManager`, `ShadowNotificationManager`), `TestClock` och en falsk monoton klocka:
  exakt larmklocka och nedräkningsnotis (kronometer, Live Update-begäran, knappar), kanalerna,
  inexakt larm i play, pausa/fortsätt-matematik, +1 min (löpande och ringande), larmnotisen
  (kanal, kategori, `FLAG_INSISTENT`, fullskärm bara när det är tillåtet, knappar), två timers som
  ringer samtidigt och stoppas en i taget, "Stoppa alla", automatisk tystnad, sent larm, avbryt,
  utan notisbehörighet, svaret på behörighetsfrågan, klockappen, en ny motor på samma
  DataStore-fil, en skadad fil, omstart (nytt larm, förfallen timer ringer, gammalt larm noteras
  bara), klockbyte och att en ny process lägger tillbaka larmen. `TimerPlatformTest`:
  `AndroidTimerSystem` (behörigheter, avstängd larmkanal, klockappens intent), systemhändelserna,
  tjänstetypen och manifestet. `TimerStoreTest`: filformatet. I `:app` kontrollerar
  `TimerPermissionsManifestTest` det sammanslagna manifestet per flavor (sideload: `USE_EXACT_ALARM`
  och `USE_FULL_SCREEN_INTENT`, inte `SCHEDULE_EXACT_ALARM`; play tvärtom). CI kör båda:
  `:app:ciTest` kör alla appens tester i sideload och dessutom `testPlayDebugUnitTest`, filtrerat
  till just det testet (Release-flödets torrkörning bygger play men granskar inte manifestet).
<!-- /Fas 1b timer -->

<!-- Fas 1b kokläge -->
## Kokläge (`:feature:cook`, Fas 1b)

Kokläget (plan 6.2 "Kokläge", 6.5, 8.1, funktion #6–#8). `CookRoute(key, onClose)` →
`CookViewModel` (assisted injection med `CookKey`) → den tillståndslösa
`CookScreen(state, onAction)`. Bara gränssnitt i `:core:data`: `RecipeRepository`,
`PreferencesRepository`, `SettingsRepository`, `TimerController`, `CookLogRepository`,
`SyncRepository` och `kotlin.time.Clock` (`ClockModule`).

- **Sidor:** en sida per steg (`HorizontalPager`), sist "Klart!". Ett recept utan steg får en
  sida med alla ingredienser. Sidan, portionerna, den öppna ingredienssektionen, avbockade rader,
  valda enheter, betyg, kommentar, loggade portioner och en timer som väntar på notisfrågan ligger
  i `SavedStateHandle` (Bundle-typer: timern som en sträng, `PendingTimer.encode`), så allt
  överlever rotation, storleksbyte och processdöd; timers lever i `TimerController`.
- **Mängder:** `RecipeDisplay.build` i de valda portionerna, samma regler som receptvyn och
  Tandoors webb (mallar i stegtexten skalas). Portionerna börjar som `CookKey.servings` (detaljens
  val, Idags planerade portioner) eller receptets sparade val; en ändring i kokläget sparas som
  receptets val, precis som i detaljen.
- **Timers:** `StepTimerSuggestions.suggest(instruction, Step.time, språk)` ger stegets knappar
  ("Starta 5 min", 64 dp). Tiderna i texten är understrukna och går att trycka på: stegets
  Markdown visas av `ui/StepInstruction` (`core:ui`s `Markdown`), som letar upp varje förslags
  text (`textRange` i källtexten) i den synliga texten (`timerSpans`, ren funktion). En träff som
  korsar en Markdown-markör blir inte tryckbar, knappen finns ändå. De tryckbara orden är ingen
  egen TalkBack-post (knapparna gör samma sak). Etiketten är "<stegets namn eller receptets> ·
  <förslaget>".
- **Timerkomponenter (`:core:ui` `timer/`):** `TimerStartButton`, `TimerBar` (ringande först,
  sedan äldst först, rullar inuti när de inte får plats), `TimerBarItem`, `TimerCard`
  (surfplatta, timerlistan), `KitchenIconButton` (64 dp), `TimerActions`, `formatRemaining`
  ("7:39", avrundat uppåt), `timerDurationText`, `timerDescription` (TalkBack) och
  `rememberTimerNow()`, som tickar en gång per sekund med `LocalTimerClock` (tester sätter en
  `TestClock` och `LocalTimerTicks = false`). Ringande timer: felbehållaren, larmikon, "Stoppa"
  och en `liveRegion`.
- **Notiser i sammanhanget (plan 8.7):** första timern med `capabilities.shouldAskForNotifications`
  (och timer i appen) visar först en förklaring ("Vill du höra timern när skärmen är låst?");
  "Fortsätt" startar systemdialogen (`RequestPermission` i routen), svaret går till
  `onNotificationPermissionResult` och timern startar oavsett svar. "Inte nu" startar timern och
  frågar inte igen under samma kokläge. Därefter: `alarmMayBeSilent` → kort med "Inställningar"
  (appens notisinställningar); `alarmMayBeLate` (play utan exakta larm) → "Timern kan larma lite
  för sent" med "Tillåt" (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`) och "Inte nu"
  (`exactAlarmPromptDismissed`, sedan bara en rad). Inga råa behörighetsnamn. Behörigheterna
  läses om vid varje `ON_RESUME`.
- **Layout (`CookLayout`, från fönstret, aldrig från enhetstypen):** under 600 dp `PHONE`
  (ingredienserna och portionerna i en utfällbar sektion på sidan, timerraden och
  Föregående/Nästa längst ned); från 600 dp `TWO_PANE` (surfplatta i båda orienteringarna,
  telefon liggande: portioner, stegets ingredienser och aktiva timers som kort till vänster, steget
  till höger; från 840 dp större stegtext); bordsläge (`currentWindowAdaptiveInfoV2()
  .windowPosture.isTabletop`) `TABLETOP`: steget i övre halvan, timers och knappar under vecket.
- **Övrigt:** skärmen hålls på (`View.keepScreenOn` medan kokläget visas), A−/A+ sparas med
  `setCookTextScale` och gäller stegtexten (`cookStep`, 28 sp, 34 sp brett), "Kokläge alltid
  mörkt" lägger ett mörkt `TandemTheme` (och ljusa systemikoner) runt bara kokläget. Tangentbord:
  pil höger/vänster, Page Down/Up och mellanslag (Skift+mellanslag bakåt), via en `focusTarget`
  utan semantik. Alla tryckytor i kokläget är minst 64 dp. Volymknapparna som bläddrare (plan 8.1
  "Handsfree") är inte med.
- **Rättelser från 0.2.0-granskningen (fix-ui):**
  - *Två paneler:* sidopanelen delas i en rullande övre del (portioner, ingredienser) och en fast
    nedre del med timers (ringande först, högst 60 % av panelen, rullar inuti) och varningar, så
    "Stopp" och "+1 min" alltid syns. Kortare paneler än 560 dp (telefon liggande) visar timerna
    som rader.
  - *Bordsläge:* det vågräta gångjärnet läses från `windowPosture.hingeList` (fönsterpixlar) och
    räknas om till kolumnens läge (`onGloballyPositioned`), så övre delen (rubrik och steg) slutar
    vid gångjärnets överkant och knapparna börjar under det (`tabletopSplit`). Utan gångjärn delas
    skärmen jämnt. `CookScreen(fold = …)` låter testerna ge ett eget gångjärn.
  - *Korta fönster (delad skärm):* varningar och timerraden delar ett område på högst 40 % (33 %
    under 480 dp) som rullar inuti, så steget behåller plats och Föregående/Nästa mäts alltid i
    full höjd; under 480 dp blir varningarna en rad. Vid teckenskala 1,5+ i en smal panel staplas
    Föregående/Nästa utan ikoner.
  - *Fönsterflaggor:* skärm-på och ljusa systemikoner räknas per fönster (`CookWindowState`), så
    när ett kokläge ersätter ett annat (Navigation 3 håller kvar det gamla under övergången)
    stänger det gamla inte av dem för det nya. "Kokläge alltid mörkt" har ett enda anrop av
    innehållet (`TandemDarkTheme`), så inställningen byter färger utan att kasta tillståndet.
  - *Varning efter orsak:* ringtjänsten spelar larmet själv när notisen inte kan låta (fix-data),
    så varningen säger vad som faktiskt saknas. Utan notiser ringer larmet men kan bara stoppas i
    Tandem → appens notisinställningar; tystad eller avstängd larmkanal → larmet visas inte över
    låsskärmen → kanalens egen sida (`ACTION_CHANNEL_NOTIFICATION_SETTINGS`, `timer_alarm`).
  - *TalkBack:* "Steg 3 av 6: <namn>" är en artig live-region, en startad timer läses upp
    (`CookEffect.Announce`), paus/fortsätt är samma knapp så fokus stannar.
  - *Tidsdetektering* läser den synliga texten (`stepPlainText`, utan Markdown-tecken, radbrytningar
    som mellanslag), så "Koka **10** minuter" hittas och etiketter aldrig får radbrytningar.
  - *"Klart!"* visar var posten tar vägen efter synkens paus (`CookLogSendState`: nu, offline,
    säkert läge, logga in igen, synken pausar) och felsidan skiljer offline, borttaget recept,
    nekad inloggning och övriga fel.
- **"Klart!" (Stitch kokl_ge_klart, utan snabbintryck och "Dela bild", som Tandoor saknar):**
  portioner (hela, `servingsOf`, ändringsbara), 1–5 stjärnor (radiogrupp, samma stjärna igen tar
  bort betyget), kommentar och "Spara i kokloggen" → `CookLogRepository.logCooked` med
  `cookedAt = clock.now()`, sedan `TimerController.clearFinished()`, en toast ("Sparat i
  kokloggen", "… skickas när du är online igen", "Sparat i demot") och tillbaka. Fel: snackbar och
  knappen går att trycka igen.
- **Timerlistan (`TimersKey`):** `TimersRoute` / `TimersScreen` med alla timers som kort, "Rensa
  klara", en timer med recept öppnar kokläget vid sitt steg.
- **Ingångar:** "Börja laga" i receptdetaljen (`RecipeDetailRoute(onStartCooking)`, visas när
  detaljen finns, med portionerna som visas) och "Laga" på Ikväll-kortet
  (`TodayRoute(onStartCooking)`, matsedelns portioner). Skalet: `feature:cook` som beroende,
  `ShellFeatures.Cook/Timers`, `entry<CookKey>` och `entry<TimersKey>` på den flik kokläget
  öppnades från (`CookKey` döljer navigeringen på alla bredder).
- **Från en timer:** notisen, larmet och timerraden i skalet öppnar kokläget vid timerns steg
  (se "Öppna appen från en timer" under Timers). Är kokläget för samma recept redan öppet
  (var som helst i den visade stacken) visas det i stället för ett nytt, och det bläddrar till
  timerns steg (`ShellNavigationState.openCook` + `CookPageRequest`); portioner och "Klart!"
  behålls. En timer från en annan källa (demoläget eller ett annat konto, `CookTimer.sourceKey`
  / `TimerSource`) öppnar timerlistan, aldrig samma recept-id i den aktiva källan.
- **Stegbilder** (fix-ui, 0.2.0): Tandoors `Step.file.preview` (bara satt för bilder;
  `file_download` är en inloggad nedladdning av vilken fil som helst och används inte) blir
  `Step.imageUrl` → kolumnen `step.image_url` (Room-schema 3, `MIGRATION_2_3`) → `StepDisplay` →
  `CookStep`, och ritas under stegtexten. Synken hämtar stegbilderna för samma recept som
  receptbilderna (fästa, planerade, senast visade; alla med "Ladda ner alla recept"). Detaljer
  som 0.1.0 sparade och vars server-JSON har en stegbild (`file.preview` ej tom) markerar
  `MIGRATION_2_3` för ny hämtning (`TandemMigrations.MARK_DETAILS_WITH_STEP_PHOTOS`:
  `fetched_at = NULL`, `raw_json` kvar så att receptet går att läsa offline); synken hämtar dem
  en gång (`RecipeDao.getIdsMarkedForRefetch`, inom taket per körning) och mappningen fyller
  `step.image_url`. URL:en kan inte fyllas i SQL: den går genom `MediaUrls.resolve` med kontots
  bas-URL. Fakta: FACTS 2.6.13 "Fas 1 m" (`fas1/recipe-step-file.json`).
- **"Inte nu"** på notisfrågan sparas i `SavedStateHandle` (frågan kommer inte igen i samma
  kokläge, även efter rotation) och visar varningen "Notiser är avstängda" medan en timer går.
- **Tester:** `CookViewModelTest` (sidor, portioner i ingredienser och mallar, sparat val, förslag,
  timers från knappar, två timers som ringer, notisfrågan, klockappen, varningar, textstorlek,
  Klart! online/offline/demo/fel, processdöd), `CookLogicTest` (`timerSpans`, layoutval),
  `CookScreenTest` (Compose: knappar, tangentbord, timerraden, ingredienssektionen, portioner,
  Klart!, surfplattans paneler), `CookScreenshotTest` (telefon, hög telefon, telefon liggande,
  surfplatta liggande och stående, bordsläge; ljust/mörkt, svenska, textstorlek 2; ringande,
  pausad, notisfrågan, varningar, Klart!, timerlistan; artefakten `screenshots`, `cook/`),
  `CookAccessibilityTest` (ATF för samma tillstånd) och i `core:ui` `TimerComponentsTest` och
  `TimerFormatTest`.
<!-- /Fas 1b kokläge -->

<!-- fix-bygg: tillgänglighetskontroller -->
### Tillgänglighetskontroller (ATF) i UI-testerna

Plan 5.6 och Fas 1:s "Klart när" kräver att tillgänglighetskontrollerna i UI-testerna är gröna.
`:core:testing` har `assertAccessible()` (`accessibility/AccessibilityChecks.kt`), som kör
Googles Accessibility Test Framework på varje fönster i kompositionen (även ett öppet bottenark
eller en dialog) med förvalet `LATEST`: tryckytor på minst 48 dp, etikett på allt som går att
trycka på, kontrast för text och ikoner mätt på en riktig skärmbild (Robolectrics NATIVE-grafik),
dubbla eller överlappande klickytor m.m. Testet fallerar på ATF:s fel **och** varningar.
Composes egen `enableAccessibilityChecks()` gör ingenting under Robolectric (b/332778271), därför
Roborazzis ATF-integration (`roborazzi-accessibility-check`).

Testerna som kör ATF: `CoreUiAccessibilityTest` (`core:ui`: receptkort och -rader, metaraden,
ingrediensrader, stegkort, statusbanners, säkert läge, tomt/fel/laddar, demo; ljust och mörkt),
`RecipeAccessibilityTest` (`feature:recipes`: rutnät, lista, textstorlek 2, offline, filter,
nyckelordsarket, receptvyn med omräkningar och bråk, ej nedladdat offline, surfplattans
list-detalj), `TodayAccessibilityTest` (`feature:home`: Idag med alla karuseller, inget planerat,
offline, demo, laddar, textstorlek 2, surfplatta) och `RecoveryAccessibilityTest` (`:app`).
`CoreUiAccessibilityTest.theChecksRunAndFail` kräver att en liten knapp utan etikett fallerar,
eftersom Roborazzi annars hoppar över kontrollen tyst (API under 34 eller äldre grafikläge).

Undantag beslutas på ett ställe, `TandemAccessibility`, smalt och med motivering: kontrast för
**inaktiverade** kontroller (WCAG 1.4.3 undantar dem; Material ritar dem med 38 % med flit) och
samma **kryssruta** två gånger (Tandoor visar ett stegs ingredienser igen under steget, båda
raderna delar samma bockade läge). Ett nytt fynd rättas i komponenten; undantag kräver en
kommentar som säger varför fyndet är avsiktligt.

Skärmbilderna (Roborazzi) spelas in och granskas för hand så länge
`tandem.screenshots.verify=false`; jämförelsen mot incheckade referensbilder och pseudospråken
beskrivs under "Fas 1b – CI" → "Skärmbilder".
<!-- /fix-bygg: tillgänglighetskontroller -->

## Navigering

Navigation 3, en back stack per flik (`ShellNavigationState` i `:feature:shell`), nycklar
`@Serializable` så att stackarna överlever processdöd (R8-reglerna i `app/proguard-rules.pro`
täcker alla `@Serializable`-klasser under `casa.moller.tandem`).

- **Mönstret:** en feature exponerar *routes*, composables med callbacks, t.ex.
  `RecipesRoute(onOpenRecipe)`, `RecipeDetailRoute(recipeId, onBack, onOpenRecipe, onOpenOnWeb)`
  och `TodayRoute(onOpenRecipe, onOpenRecipes, onOpenWeek)`. Featuren känner inte till andra
  features eller skalet. Destinationer inom en feature har egna nycklar i featuren
  (`RecipeDetailKey` i `:feature:recipes`, `CookKey` i `:feature:cook`).
- **Värden är `:feature:shell`** (undantaget från regeln feature → feature, kontrolleras av
  `checkModuleGraph`). Skalet visar routerna via `ShellFeatures` och gör callbacks till nycklar.
  Skalets tester använder platshållare i stället för routerna, så de behöver aldrig Hilt.
- **ViewModels:** varje post får en egen `ViewModelStore` (per flik), så `hiltViewModel()` i en
  route lever lika länge som posten ligger i sin fliks stack, även när fliken är dold.
- **Helskärm ovanför flikraden:** nycklar som implementerar `FullScreenDestination` (`:core:ui`)
  döljer bottenraden på kompakta fönster (telefon stående). På bredare fönster står railen kvar,
  om inte nyckeln sätter `hidesNavigationOnAllWidths` (kokläget).
- **List-detalj:** fliken Recept är list-panelen i en Navigation 3 list-detail-scen
  (`ListDetailSceneStrategy` ur `material3-adaptive-navigation3`). På medium/expanded visas ett
  recept som öppnas från listan i detaljpanelen bredvid listan; ett nytt val ersätter det
  öppna. Innan något valts visas `RecipeDetailPanePlaceholder`. Ett recept som öppnas från en
  annan flik (Idag) fyller innehållsytan bredvid railen. Tillbaka tar bort en post i taget.
- **Statuschip och demobanner:** skalet tillhandahåller `LocalAppChrome` (`:core:ui`); skärmarna
  visar `AppChromeStatusChip()` i toppfältet och `AppChromeDemoBanner()` överst.

<!-- Fas 1 recept-ui -->
## Recept (`:feature:recipes`)

Receptlistan och receptvyn (plan 6.2, funktion #3–#5). Skärmarna är tillståndslösa
(`RecipeListScreen(state, onAction)`, `RecipeDetailScreen(state, onAction)`); routerna i
`RecipesRoutes.kt` hämtar ViewModel med `hiltViewModel()` och visar engångshändelser som snackbar.

- **Lista** (`list/`): `RecipeListViewModel` söker lokalt direkt vid varje tangenttryck
  (`RecipeRepository.observeRecipes`) och, när enheten är online och frågan smalnar av listan,
  även på servern efter 400 ms paus (`searchServer`, aldrig i demoläget eller med "bara
  nedladdade"). Serverträffar som inte finns lokalt läggs efter de lokala, utan dubbletter.
  Frågan sparas i `SavedStateHandle`; rutnät/lista sparas per enhet i en egen liten
  Preferences-DataStore (`RecipeListPreferences`, filen `recipe_list_ui`) – ett UI-val, inte
  kontodata, därför inte i `core:datastore`. Dra-för-att-uppdatera begär en synk
  (`SyncReason.USER_REFRESH`) och visar indikatorn tills synken är klar (högst 2 s om den aldrig
  startar). Offline tonas recept utan sparad detalj ned med molnikon.
  Granskningen av 0.1.0 (fix-ui): listans indata har inga platshållarvärden (`onStart`), så när
  skärmen prenumererar igen efter 5 s står den visade listan, layouten och scrollpositionen kvar
  tills Room svarat. `RecipeListUiState.syncError` (synkens `lastError`, utom offline och
  "Logga in igen") visar `ErrorState` med "Försök igen" i stället för "Inga recept än", och en
  dra-för-att-uppdatera vars synk misslyckas ger snackbaren "Kunde inte synka: <fel>". Scaffolden
  räknar tangentbordet (`WindowInsets.ime`) till innehållets insets. På expanded-fönster (från
  840 dp) är nyckelords- och livsmedelsväljaren en sidopanel (`FilterPickerPanel`) bredvid
  recepten, eller i deras ställe i den smala listpanelen; annars bottenark (`FilterPickerSheet`).
  Komplettering 0.1.0: **livsmedelsfilter** (plan 6.2 "livsmedel"): `RecipeQuery.foodIds` kräver
  en ingrediens av varje valt livsmedel – lokalt en `EXISTS` per livsmedel på `ingredient.food_id`
  (bara recept med sparad detalj har ingredienser), på servern `foods_and` (kontraktstestet
  `foods.and*`). Väljaren listar `RecipeRepository.observeFoods()`: livsmedelslistan från synken
  plus livsmedel i sparade recept som listan saknar (`ReferenceDao.observeFilterFoods`; demoläget
  har ingen livsmedelslista). Valda alternativ står först när väljaren öppnas. Dra-för-att-uppdatera
  ersätter en pågående synk (`REPLACE`), som då slutar avbruten; indikatorn väntar därför på en
  körning som startat **efter** begäran (`SyncStatus.runsStarted`), och interna stoppmarkörer
  (`SyncStop`, `TandemError.isTransientSyncStop`: avbruten, kontot bytt) visas aldrig som "Kunde
  inte synka" eller felläge. Samma filter gäller receptdetaljen (`refreshRecipe` efter en
  utloggning ger varken meddelande eller felläge) och introts första synk (en avbruten körning
  ger inget "Försök igen" utan går vidare som "Fortsätt i bakgrunden").
- **Detalj** (`detail/`): `RecipeDetailViewModel` (assisted injection med recept-id) visar
  sparad kopia direkt, tyst uppdatering, "Visar sparad version" offline och felläge med
  "Försök igen" när inget finns sparat. Portioner sparas per recept i repositoryt;
  avbockade rader och vald enhet gäller bara sessionen (`SavedStateHandle`). Ingrediensrader och
  stegtexter byggs i ViewModel med `core:domain`s `RecipeDisplay.build(recipe, servings,
  preferences, style)` – samma regler som Tandoors webb (skalning, `use_fractions` /
  `ingredient_decimals`, plural utom för exakt 1, alltså även `1⁄2 lökar`, rubriker, `no_amount`,
  anteckningar, mallar), se "Receptvisning" nedan. `style` (decimalkomma, bråktecken) skickar
  skärmen från sin locale med `RecipeDetailAction.SetNumberStyle`, så texten följer ett byte av
  appspråk. Tryck på en mängd växlar mellan enheterna i serverns `conversions`
  (`IngredientRow.Item.unitAt`, dubbletten av den egna enheten tas bort).
  TalkBack: en ingrediensrad är en post "<mängd> <livsmedel>, <anteckning>" med kryssruta, och
  enhetsbytet finns bara som radens egen åtgärd "Visa i annan enhet" (pillret går att trycka på
  men är ingen egen post). Mängden läses via `core:domain`s `spokenNumbers` (`³⁄₁₆ tsk` →
  `3/16 tsk`); `MarkdownText(spokenText = …)` gör samma sak för stegtexter med sådana bråk.
  "Öppna på webben" öppnar `<server>/recipe/<id>/` i en Custom Tab (skalet, `androidx.browser`,
  vanlig webbläsare som reserv).
- **Steg och Markdown:** stegtexten är Markdown. Appen har en egen liten läsare i
  `core:ui` (`casa.moller.tandem.core.ui.markdown.Markdown`, ren Kotlin utan Android-typer, så
  den kan flyttas till en ren Kotlin-modul vid en iOS-port) och `MarkdownText` bredvid, i
  stället för ett bibliotek: stegtexterna använder bara fet,
  kursiv, listor, rubriker och länkar, och ett fullt CommonMark-bibliotek för Compose skulle
  följa egna Compose-/Material-versioner ovanpå vår pre-release av Material 3. Länkar blir bara
  klickbara för `http`, `https` och `mailto`.
- **Mallar** (`{{ scale(100) }}`, `{{ ingredients[0] }}`): renderas av `core:domain`s
  `TemplateRenderer` (via `RecipeDisplay.build`) för de valda portionerna. Kan Tandoor inte tolka
  mallen (`hasBrokenTemplate`) visas steget som det är skrivet med en rad om att mängderna inte
  skalas.
- **Webbparitet i vyn:** `RecipeDetailWebParityTest` kör den riktiga `RecipeDetailViewModel` med
  `core:testing`s fakes på fyra inspelade recept (`api-schema/fixtures/2.6.13/fas1/`, katalogen
  via systemegenskapen `tandem.fixturesDir` som i `core:domain`) vid halva och dubbla portioner,
  bråk av och på, och kräver exakt webbens rader och stegtexter ur `scaling-golden.json`.
- **Komponenter i `core:ui`** (`casa.moller.tandem.core.ui.recipe`): `RecipeCard`,
  `RecipeListItem`, `RecipeImage` (Coil 3 `AsyncImage` med appens gemensamma `ImageLoader`,
  övertoning och platshållare), `RatingStars`, `RecipeTimeLabel`, `OfflineBadge`,
  `NotDownloadedBadge`, `KeywordPill`, `ServingsStepper`, `IngredientRow`, `IngredientHeader`,
  `StepCard` och `MarkdownText`. Exempel för förhandsvisning ligger i `src/debug`; appens egna ord
  där kommer från `strings_samples.xml` (engelska och svenska), bara receptinnehållet är svenskt.
- **Ikoner:** receptskärmarnas ikoner är tillägg till `TandemIcons` i en egen fil
  (`icon/TandemIconsRecipes.kt`, `import casa.moller.tandem.core.designsystem.icon.Search`),
  så att parallella ändringar inte krockar i samma rader.
- **Surfplatta:** list-detalj enligt navigeringskontraktet; skalet skickar `selectedRecipeId`
  till `RecipesRoute`, så det öppna receptet markeras i listan. Detaljen visar två paneler som
  rullar var för sig när den är minst 720 dp bred: till vänster foto (högst 240 dp,
  `cappedAspectRatio` i `core:ui`), portioner och ingredienser, till höger titel, fakta,
  beskrivning och steg (plan 6.5: ingredienserna står kvar medan stegen rullar). Beskrivning och
  stegtext har läsbar radlängd (`maxReadingWidth`). `tablet_detail_steps_scrolled_light` visar
  stegpanelen rullad till slutet med ingredienserna kvar.
- **Skärmbilder:** `RecipesScreenshotTest` (telefon 412×915, hög telefon, medium 700×1000 stående,
  surfplatta 1280×800 liggande; ljust/mörkt, engelska/svenska, textstorlek 2,0) och
  `RecipeComponentsScreenshotTest` i `core:ui` sparas i artefakten `screenshots`
  (`build/outputs/roborazzi/recipes/` resp. `ui/`). `phone_tall_detail_templates_*` visar det
  inspelade receptet Krämig svamppasta med mallar, halverat med bråk och dubblerat på svenska.
<!-- /Fas 1 recept-ui -->

## Idag, första synken, serverstatus och återställning (Fas 1)

- **Idag (`:feature:home`):** `TodayRoute` → `HomeViewModel` (`HomeRepository` + `SyncRepository`)
  → den tillståndslösa `TodayScreen(state, onAction)`. "Ikväll" är dagens måltid vars måltidstyp
  börjar från 15:00 (annars dagens sista måltid utan tid); övriga måltider idag och imorgon visas
  som rader, sedan karusellerna som har recept. `HomeRepository.refresh()` körs en gång när fliken
  öppnas och sedan bara vid dra-för-att-uppdatera/"Synka nu" (som också ber synken om
  `USER_REFRESH`; synkens Idag-steg delar `homeMutex` och 2-minutersgolvet med `refreshHome`,
  så matsedeln och karusellerna hämtas en gång per drag). Når den inte servern (`TandemError.isConnectivityProblem` i `:core:model`)
  visas det som hämtats senast med en offlineruta. Från 840 dp bredd: två kolumner
  (idag_surfplatta_*), annars en kolumn.
- **Första synken (`:feature:onboarding`):** efter "Fortsätt" i ansluten-steget följer
  `OnboardingViewModel` `SyncRepository.firstSync()` (steg `FIRST_SYNC`). "Fortsätt i
  bakgrunden" avslutar bara bevakningen, inte synken. Vid fel: "Försök igen" eller "Fortsätt
  ändå"; nekar Tandoor inloggningen (`Unauthorized`) "Logga in igen" (tillbaka till
  inloggningssteget) i stället för "Försök igen", utan löfte om nya försök i bakgrunden. På Samsung (`OnboardingConfig.showBatteryTip`, sätts i appens `OnboardingConfigModule`)
  kommer ett tips om batterioptimering med en länk till appens systeminställningar (bara en
  Intent, ingen behörighet). Introt avslutas med händelsen `OnboardingEvent.Finished`.
- **Serverstatus (`:core:ui` `status/`, kopplat i skalet):** `LoginExpiredBanner` (Tandoor
  godtar inte längre enhetens token, `SyncStatus.pause == UNAUTHORIZED`: "Logga in igen" öppnar
  introt vid serversteget med kontots adress ifylld via `AppViewModel.logInAgain`; samma
  användare och space behåller recept och utkorg), `SafeModeBanner` (otestad version,
  blockerar inget), `SafeModeInfoScreen` (vad säkert läge innebär, länk till releaserna på
  GitHub), `ServerTooOldNotice` (för gammal Tandoor: ersätter innehållet i alla flikar utom Mer)
  och `OfflineNotice`. Kroken i skalet är `ServerStatusFrame` (`:feature:shell` `status/`), som
  lägger bannern ovanför flikinnehållet och förbrukar statusradens inset; statuschipet visar
  "Offline" när den senaste serverkontrollen inte nådde servern. Där ligger också
  `ServerUpdatedBanner` (versionsbyte utan notis, se "Versionsbyte" nedan).
- **Återställning (`:app`):** `RecoveryRoute` + `RecoveryViewModel`. "Exportera väntande
  ändringar" delar utkorgen som JSON-text via delningsarket genom gränssnittet
  `PendingChangesExporter` (`:core:data`). Det injiceras som `Optional` (`@BindsOptionalOf` i
  `app/.../di/PendingChangesModule.kt`); `:core:sync` binder `RoomPendingChangesExporter`, så
  exporten finns i appen.
- **Ikoner:** nya ikoner för de här skärmarna ligger i `TandemIconsStatus.kt` (samma källa och
  kontroll som övriga, se `assets/licenses/NOTICE.txt`).
- **Skärmdumpar:** `TodayScreenshotTest`, `OnboardingFirstSyncScreenshotTest`,
  `ServerStatusScreenshotTest` och `RecoveryScreenshotTest` (telefon 412×915 dp, medium stående
  800×1280 dp, surfplatta liggande 1280×800 dp; ljust/mörkt, engelska/svenska, textskala 2).

<!-- Fas 1b inställningar -->
## Inställningar, diagnostik och skalet (Fas 1b)

Plan 6.2 "Inställningar" och 1a:s öppna punkter i skalet. Allt UI ligger i `:feature:shell`;
datadelen i `:core:datastore` och `:core:sync`.

- **Mer** (`more/MoreScreen`): kontot (namn, server, space, Tandoor-version, "Logga ut"), sedan
  raderna Synk och lagring, Utseende, Kokläge och timers, Behörigheter och Språk med en kort
  sammanfattning var (`ShellUiState.settings`, `SettingsSummary`: `ShellViewModel` läser
  `SettingsRepository` och `TimerController.capabilities`), och Diagnostik och Om Tandem. Temat
  ligger inte längre direkt under Mer utan under Utseende. Kontokortet visar också *Funktioner på
  servern* (`ShellUiState.serverFeatures` från `ServerStatus.capabilities`, med egna namn, aldrig
  API-namnen; fix-ui). Hushållets namn visas inte än (kontot sparar bara space-namnet; namnet
  kräver `/api/user-space/` i `core:network`), se ROADMAP. **Logga ut** med ändringar i outboxen
  (`ShellUiState.pendingChanges` = `SyncStatus.pendingChanges`) säger "N ändringar har inte
  skickats" och att utloggningen raderar dem (plan 4.2 p4), med "Skicka först" (`retrySync`)
  när en synk kan skicka dem; utloggningen är fortfarande möjlig. Bara en vald rad har
  `selected` i semantiken (annars läser TalkBack "inte vald" på varje rad).
- **Undersidorna** (`settings/`): tillståndslösa `XScreen(state, onAction)` och en
  `SettingsViewModel` per navigeringspost (`hiltViewModel()`, varje sida samlar bara sitt eget
  `StateFlow`, `WhileSubscribed`). Varje ändring sparas direkt; ett misslyckat skrivande ger en
  snackbar (`SettingsEffect.Message`). Skalet visar dem via `SettingsPages` (som `ShellFeatures`,
  egen fil): produktionen `RouteSettingsPages`, testerna `FixedSettingsPages` med fasta
  tillstånd, så skalets tester aldrig behöver Hilt.
- **Surfplatta** (mer_inst_llningar_surfplatta): `MoreKey` är listpanel och alla Mer-sidor
  (`NavKey.isMorePage`: inställningarna, Språk, Om, Licenser, licenstext) detaljpaneler i samma
  `ListDetailSceneStrategy` som recepten. En rad i listan ersätter den öppna sidan (och det som
  öppnats från den) och markeras (`SettingsRow(selected)`); innan något valts visas
  `SettingsPanePlaceholder`. På telefon en sida i taget som förut.
- **Utseende:** tema system/ljust/mörkt (`AppearanceRepository`, appen sätter
  `UiModeManager.setApplicationNightMode`, gäller direkt i hela appen), "Dynamisk färg"
  (`AppViewModel` → `TandemTheme(dynamicColor)` i `MainActivity`; minSdk 34 har alltid
  Material You), kokläges textstorlek (`CookTextScale`, samma steg som A−/A+ i kokläget, med
  ett provexempel) och "Kokläget alltid mörkt". Kokläget läser de tre (även dynamisk färg) ur
  `SettingsRepository` och lägger själv sitt mörka tema (`CookTheme` i `:feature:cook`).
  `core:designsystem` har dessutom `TandemThemeChoice` (det närmaste temats val, så ett inre
  `TandemTheme` ärver dynamisk färg) och `TandemDarkTheme(enabled)` för samma sak i andra skärmar.
- **Kokläge och timers ("Beteende"):** timer i Tandem eller i klockappen
  (`SettingsRepository.setTimerMode`; klockappen går inte att välja utan klockapp). Varnar med
  länk till behörigheterna när `TimerCapabilities.alarmMayBeSilent`/`alarmMayBeLate`.
- **Synk och lagring:** senaste synk, paus, väntande ändringar och "Synka nu"; intervallet
  15 min/1 h/6 h/"bara när jag öppnar appen" (`SyncStarter` följer `SyncInterval` och kontot:
  `WorkManagerSyncScheduler.schedulePeriodic(interval)` med `UPDATE`, `MANUAL`, utloggning och
  demoläget avbryter den periodiska synken). "Ladda ner alla recept" (`downloadAllRecipes`): varje
  synk hämtar även detaljer och bilder för recept som saknar dem (och, sedan granskningen av
  0.2.0, detaljer som är äldre än receptets `updated_at` på servern, med 5 min marginal för
  klockskillnad; bilder som redan ligger i Coils diskcache hoppas över även i en ny process), högst
  `SyncSchedule.MAX_DOWNLOAD_ALL_PER_RUN` (400) per körning och fortfarande två åt gången; att slå
  på den startar en synk direkt. Förloppet och en uppskattning (genomsnittlig storlek per sparat
  recept med bild) kommer från `OfflineStorageRepository` (`DefaultOfflineStorageRepository`:
  antal, databasfilernas storlek, bildcachens storlek, mäts om medan sidan visas). "Rensa
  bildcache" tömmer Coils minnes- och diskcache; bilder till sparade recept hämtas igen vid nästa
  synk.
- **Behörigheter** (k_kets_beh_righeter_systemguide med korrigeringarna): en översikt, aldrig ett
  tvingat steg och aldrig råa behörighetsnamn. Rader för notiser, larmljudet (kanalen
  `timer_alarm`), larm i rätt tid (exakta larm; i sideload alltid tillåtet), larm på låsskärmen
  (bara sideload) och batterisparläge (bara på tillverkare som stoppar appar i bakgrunden,
  `BatteryAdvice`). Varje rad öppnar rätt systemskärm (`SystemSetting`: appens notiser, kanalen,
  `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, appens sida),
  med appens sida som reserv. Läses om när skärmen visas igen (`refreshCapabilities`).
- **Diagnostik:** versioner, uppmätt kallstart och senaste fulla synk mot målen i Fas 1 "Klart
  när" (under 1,5 s; ungefär 300 recept på under 60 s), och "Exportera logg" via delningsarket
  (`ACTION_SEND` text/plain, inget skickas av appen själv). `DefaultDiagnosticsRepository`
  (`:core:sync`, ersätter den tillfälliga bindningen; `TemporaryFas1bModule.kt` är borttagen)
  bygger loggen bara av värden appen själv valt: versioner, kapabiliteter, timerbehörigheter,
  inställningar, synkstatus, utkorgen som antal per typ/läge och rader med försök (inga
  kroppar), feltyper (`describe`, aldrig serverns fritext), mätningarna och den senaste
  kraschloggen (tvättad när den skrevs). Fritext som ändå kommer med går genom
  `CrashLogFormatter.redact`. Ingen serveradress, inget användar- eller space-namn, ingen token,
  ingen e-post (`SettingsAndDiagnosticsTest` kontrollerar det).
- **Mätningarna** sparas i en egen liten Preferences-fil (`DiagnosticsStore`,
  `diagnostics.preferences_pb`). Kallstart: från `Process.getStartRequestedUptimeMillis` till
  första skärmen med innehåll (Idag eller receptlistan anropar `reportFullyDrawn` via `core:ui`s
  `ReportContentReady`), bara när processen startades för att visa appen (`ColdStart` i `:app`)
  och högst 30 s. Full synk: en lyckad körning som läste alla översikter **för första gången**
  (inget tidigare synkdatum, alltså första synken eller ett nytt konto), från start till slutet av
  de vanliga detaljerna (index, översikter, fästa/planerade/visade detaljer), med antalet recept i
  indexet. "Ladda ner alla"-detaljer, bilder och utkorgen räknas inte, och en senare omläsning
  (ett recept blev synligt) skriver inte över värdet (granskningen av 0.2.0).
- **Banner för hållen synk** (`status/SyncHeldBackBanner`, 1a:s öppna punkt): `SyncPause.BLOCKED`
  ("Servern nekar Tandem just nu") och `CIRCUIT_BREAKER` ("Synken tar en kort paus") ovanför
  flikarna med "Försök igen" (en synk som användaren bad om; den går förbi kretsbrytaren, en
  blockering väntar på serverns tid). Aldrig i demoläget, aldrig tillsammans med "Logga in igen".
- **Timerraden på alla flikar** (`timer/ShellTimerHud`, plan 8.1 "Timer-HUD"): så länge en timer
  löper, är pausad eller ringer visar skalet en smal rad under flikinnehållet (ovanför
  bottenraden, eller ovanför systemets navigeringsfält när bottenraden är dold). Den visar den
  mest brådskande timern (en som ringer, annars den äldsta, `core:ui`s `sortedForDisplay`) med
  namn och nedräkning (`rememberTimerNow`, `formatRemaining`; namnet kortas, tiden aldrig, med
  tabellsiffror), "Alla (3)" för timerlistan (`TimersKey`, inte medan timern ringer, så inget
  liknar larmets "+1 min") och "Stopp" när den ringer (`ShellActions.stopTimer` →
  `TimerController.stopRinging`). Ett tryck öppnar kokläget vid timerns recept och steg
  (`ShellNavigationState.openTimerOf` → `openCook`: ett redan öppet kokläge för receptet återanvänds
  och bläddrar dit), en timer utan recept eller från en annan källa öppnar timerlistan. Raden visas inte i kokläget eller i
  timerlistan, som visar timers själva. Timers kommer från `ShellUiState.timers`
  (`TimerController.timers` via `ShellViewModel`). TalkBack läser timern som en knapp
  (`timerDescription`), inte nedräkningen varje sekund. Tester fryser klockan (`FrozenTimers`:
  `LocalTimerClock`, `LocalTimerTicks = false`).
- **Tester:** `SettingsViewModelTest`, `ShellViewModelTest` (sammanfattningar, hållen synk,
  timerraden), `ShellScaffoldTest` (varje rad öppnar sin sida, länkar mellan sidor, surfplattans
  list-detalj med vald rad, bannern, timerraden), `SettingsScreenshotTest` och
  `TimerHudScreenshotTest` (telefon, hög telefon, surfplatta;
  ljust, mörkt, svenska, textstorlek 2) och ATF i `SettingsAccessibilityTest` (alla sidor och
  lägen) och `ShellAccessibilityTest` (skalets Fas 0-skärmar: Mer, Språk, Om, Licenser,
  licenstext, utloggningsdialogen, säkert läge, alla banners och timerraden). I `:core:sync`
  `SettingsAndDiagnosticsTest` (ladda ner allt, full synk, intervallet, lagring, loggens innehåll).
<!-- /Fas 1b inställningar -->

<!-- Fas 1 domain -->
<!-- Fas 2 inköp-ui -->
## Handla (`:feature:shopping`, Fas 2)

Fliken Handla (plan 6.2 "Handla", #10, #11, #13, #14, gästläget #42; Stitch `ink_pslista_*`,
`butiksl_ge`, `ink_pslista_handla_surfplatta`). Bygger bara på gränssnitten i `:core:data`
(`ShoppingRepository`, `SupermarketRepository`, `IngredientParser`, `SpacePermissionsRepository`,
`ForegroundSync`, `SyncRepository`, `PreferencesRepository`); implementationerna kommer från
inköpets databana.

| Route (nyckel) | ViewModel | Skärm |
|---|---|---|
| `ShoppingRoute` (`ShoppingKey`, `ShoppingListKey`) | `ShoppingViewModel` (assisted, `ShoppingListKey`) | `ShoppingScreen` |
| `StoreModeRoute` (`StoreModeKey`) | `StoreModeViewModel` (assisted) | `StoreModeScreen` |
| `ShoppingListChoiceRoute` (`ShoppingListChoiceKey`) | `StoreChoiceViewModel` | `StoreChoiceScreen` |
| `ShoppingHistoryRoute` (`ShoppingHistoryKey`) | `ShoppingHistoryViewModel` | `ShoppingHistoryScreen` |

- **Data:** `ShoppingSource` slår ihop listan, butikerna, kategorierna, behörigheten, inställningarna
  och synkläget. Att samla den startar `ForegroundSync.keepFresh(ShoppingList)`, så listan pollas
  bara medan listan eller butiksläget syns (`WhileSubscribed` + `collectAsStateWithLifecycle`).
  En "köp senare"-rad flyttar tillbaka till sin gång när tiden har gått, utan databasändring.
- **Ordning (`ShoppingGrouping`, ren):** ej avbockade rader per kategori i den valda butikens
  gångordning, sedan kategorier som butiken saknar (efter namn) och sist "Övrigt" (ingen eller okänd
  kategori). Utan butik: efter kategorinamn. Inom en gång efter livsmedlets namn. Fördröjda rader
  hamnar i "Köp senare" (först förfallna först), avbockade i "Avbockat" (senast först, hopfällt).
- **Butik:** nyckelns `supermarketId` gäller tills användaren väljer butik; valet sparas per enhet
  (`SupermarketRepository.selectSupermarket`, också för gäster). `ShoppingListChoiceKey` visar bara
  butiksvalet och anropar `onChosen(null, null)` när valet är sparat; de namngivna listorna är chips
  på själva listan (valet ligger i `SavedStateHandle`, nya rader hamnar på den valda listan).
- **Rader:** en rad är **en** nod för TalkBack ("2 dl grädde, Mejeri, avbockad"); tryck eller svep
  åt slutet bockar av, svep åt början = "köp senare"/"köp nu", lång tryckning = ändra. "Köp senare"
  och "Ändra" finns också som TalkBack-åtgärder. Ändringar visar en snackbar med "Ångra" (bocka av,
  bocka av alla i en gång, köp senare, lägg till, ta bort; ta bort lägger tillbaka varan som en ny
  rad utan receptetikett).
- **Lägg till (#11):** fältet tolkas lokalt (`IngredientParser.parseLocally`) medan man skriver och
  förhandsvisas; Tandoors parser (som skapar livsmedel) anropas först vid "Lägg till", och misslyckas
  den används den lokala tolkningen. Flera varor per rad, komma eller semikolon (decimalkomma
  stannar). Förslag (`observeSuggestions`) för varan som skrivs och "Köpt nyligen" ur historiken.
  Bottenark på telefon, fast sidopanel på bredare fönster.
- **Ändra:** livsmedel, mängd och enhet (`ShoppingRepository.update`) och livsmedlets gång
  (`SupermarketRepository.setFoodCategory`, gäller hela spacet; inte för ett livsmedel som skapats
  offline). Gränssnittet har ingen anteckning per rad, så den går inte att ändra.
- **Köp senare (#14):** nu + Tandoors `default_delay` timmar; "köp nu" ångrar med `null`.
- **Butiksläge (#13):** bara det som är kvar att köpa, rader på minst 64 dp med stor text, gångarnas
  rubriker står kvar överst (`stickyHeader`) och skärmen hålls tänd medan det visas.
- **Dela som text:** byggs i appen ur samma gruppering som skärmen (rubrik, gångar, "- 2 dl grädde",
  vanliga bråk), inte med `ShoppingRepository.shareText`, och öppnar Androids delningsark.
- **Lägen:** tomt (med "Köpt nyligen"), offline/pausad/inloggning/säkert läge med antal väntande
  ändringar, gäst (inga redigeringskontroller alls, skrivningar ignoreras i ViewModel också), demo
  (skalets banner; demodatan kommer från repositoryt).
- **Surfplatta (plan 6.5):** från 600 dp fönsterbredd en sidopanel (lägg till, listor, butiker,
  butiksläge, historik) bredvid listan; från 640 dp listbredd står gångarna i två kolumner
  (staggered grid). Gäller båda orienteringarna och telefon i liggande läge.
- **Tester:** `ShoppingGroupingTest` (ordning, delning av fritext, format, delningstext),
  `ShoppingViewModelTest` och `StoreModeViewModelTest` (fakes ur `:core:testing`),
  `ShoppingScreenTest` (semantik, åtgärder, gäst, surfplatta, routen med ångra),
  `ShoppingAccessibilityTest` (ATF) och `ShoppingScreenshotTest`/`ShoppingPseudoLocaleScreenshotTest`
  (`feature/shopping/src/test/screenshots/shopping/`). Skärmarna och lägena står i `ShoppingShot`.
<!-- /Fas 2 inköp-ui -->

<!-- Fas 2 böcker -->
## Böcker, favoriter och Vill laga (`:feature:recipes`, `:core:sync`, Fas 2)

Funktion #17 (plan 3.7, 6.2 "Böcker/Favoriter", B17) och gästläget #42 (plan 8.6). Stitch
`receptbok_master_detail_surfplatta`, `recept_s_k_filter_samlingar`, `tandem_g_stl_ge_skrivskyddat`.

**Data (`core/sync/.../book/`, bundet i `di/BookModule.kt`, som ersätter appens
`TemporaryFas2BookModule` och tar `SpacePermissionsRepository` ur `TemporaryFas2AccountModule`):**

| Gränssnitt | Implementation |
|---|---|
| `RecipeBookRepository` | `OfflineFirstRecipeBookRepository` (+ `observeFavoriteIds()`: egna favoriter, hjärtan) |
| `SpacePermissionsRepository` | `DefaultSpacePermissionsRepository` (`server_account.group_level`) |

- **Vilken bok är vad** (`core:domain` `RecipeBookRules`, rent): favoriter = en bok utan filter som
  heter "Favoriter"/"Favorites"/"Favourites", ev. med ägaren i parentes, eller har Kitshns
  `#KTSHNFAV` i beskrivningen; "Vill laga" = en smart bok vars filter bara söker nyckelordet
  `vill-laga` (eller heter "Vill laga"/"Want to cook"); annan bok med filter = smart; resten vanlig.
  `SmartFilter` utvärderar ett filter med bara `keywords_or`/`keywords_and` på enheten.
- **Bara egna böcker ändras** (plan 3.7): en skrivning till någon annans bok eller en smart bok ger
  `DataProblem.NOT_OWNER` innan något sparas (servern skulle svara 403; FACTS: en delad medlem kan
  *lägga till* men inte ta bort, appen skriver ändå aldrig). Gäst: `READ_ONLY`.
- **Favoriter per person:** "Favorit" lägger receptet i användarens egen favoritbok; finns ingen
  skapas "Favoriter (<namn>)" (namnet ur `core:sync` `strings_books.xml`) offline med tillfälligt id,
  och posten `depends_on` bokens CREATE med `book` i `refs`. Bokens CREATE bär appens nyckel
  `tandem_share_with_space`: avsändaren läser `GET /api/user/` och skickar `shared` = spacets andra
  användare (nyckeln skickas aldrig). Vyn Favoriter slår ihop allas favoritböcker.
- **Vill laga:** `RECIPE_KEYWORDS` på receptet (`{"keywords":{"vill-laga":true}}`, `base_snapshot`
  med motsatsen, så att av och på igen tar ut varandra). Avsändaren läser receptet färskt, skickar
  **hela** nyckelordslistan med eller utan `{name: "vill-laga"}` (servern återanvänder nyckelordet
  på namn) och sparar svaret som receptets detalj. Första gången skapas också filtret
  (`POST /api/custom-filter/` med nyckelordets **id**) och den smarta boken "Vill laga" (filtret som
  servern svarade), delade med spacet, om ingen syns än. Listan i appen = recept med nyckelordet,
  med köade ändringar ovanpå.
- **Läsning** (`BookSync`, med de små tabellerna i `SyncEngine` efter inköpslistan, via
  `BookSyncStep`): rollen (`SpaceRoleSync`: `user-space/all_personal`, spacets post, `group_level`
  admin/user/guest), sedan alla synliga böcker och poster; serverraderna ersätts, lokalt skapade
  rader står kvar och köade DELETE läggs på igen. Smarta böcker som inte går att utvärdera lokalt
  läses med `GET /api/recipe/?filter=<id>` (högst 5 × 100) och hålls i minnet (`SmartBookResults`).
- **Avsändare** (`BookOutboxHandlers.kt`): `recipe_book` (CREATE/DELETE; en CREATE som försökts förut
  letar först efter en egen bok med samma namn), `recipe_book_entry` (CREATE; 400 "unique set" eller
  ett förlorat svar blir den befintliga posten via `?recipe=`; 400 på `book`/`recipe` = borta,
  posten släpps; DELETE 404 = redan borta) och `recipe` (`RECIPE_KEYWORDS`).
- **Gästläget (#42):** rollen läses vid första synken efter inloggning och sedan med de små
  tabellerna. En gäst köar ingenting: alla Fas 2-repositorier ger `READ_ONLY`, `logCooked` också,
  och `markViewed` sparar bara lokalt (ingen `VIEW_LOG`). Demoläget är aldrig skrivskyddat.
- **Demo:** `DemoBooks` (efter `DemoShopping`) gör demoinnehållets "Favoriter" till demoanvändarens
  favoritbok, "Vill laga" till den smarta boken på nyckelordet (recepten får nyckelordet) och lägger
  till Alex favoritbok, delad och skrivskyddad. Seedas en gång per version och språk.

**Gemensamma gästdelar (`core:ui` `readonly/ReadOnly.kt`) – för alla banor:** `ReadOnlyBanner()`
(en gång överst på en skärm med redigering; `compact = true` för listor), `ReadOnlySheet` /
`ReadOnlySheetContent` ("Det här kan en gäst göra", öppnas från bannern) och `ReadOnlyNote(text)`
(en rad med lås i stället för en kontroll). Skärmarna **döljer** skrivkontrollerna när
`SpacePermissions.readOnly`; ViewModels ignorerar skrivningar också. Används på Idag, i böckerna,
i receptets knappar och på Klart!-sidan (`CookUiState.readOnly`: varken betyg, kommentar eller
koklogg). Handla har sin egen gästruta sedan inköpsbanan.

**Skärmar (`feature/recipes/.../books/`):**

| Route (nyckel) | ViewModel | Skärm |
|---|---|---|
| `BooksRoute` (`BooksKey`) | `BooksViewModel` | `BooksScreen`: Favoriter och Vill laga, Mina böcker, Delade med mig (lås, "Från Alex · bara läsning"), "Ny bok" |
| `BookDetailRoute` (`BookDetailKey`), `FavoritesRoute`, `WantToCookRoute` | `BookRecipesViewModel` (assisted, `BookSource`) | `BookRecipesScreen`: förklaring (delad bok: varför den är skrivskyddad), rader med ta bort och i Vill laga "Planera" |
| `RecipeBookButtonsRoute` (i `RecipeDetailRoute`, slot `bookActions`) | `RecipeBookButtonsViewModel` (assisted, recept-id) | `RecipeBookButtons`: Favorit, Vill laga, Lägg i bok (ark med egna böcker och ny bok), "Favorit hos Alex" |
| i `RecipesRoute` | `RecipeCollectionsViewModel` | `RecipeCollectionsRow` ovanför filtren och hjärtat på korten (`core:ui` `RecipeFavorite`) |

Idag: `TodayBooksViewModel` (`:feature:home`) ger raden "Vill laga" (före karusellerna, "Visa alla"
→ `WantToCookKey`) och gästbannern. Mer: raden Receptböcker → `BooksKey`.

**Navigering och surfplatta:** `BooksKey` är listpanel och `BookDetailKey`/`FavoritesKey`/
`WantToCookKey` detaljpanel i skalets `ListDetailSceneStrategy` (`isBookPage`; en ny bok ersätter den
öppna). Öppnas en samling från receptlistans chips står den bredvid receptlistan. `ShellFeatures`
fick `onOpenWantToCook` (Today), `onOpenCollection` (Recipes) och `selectedBookId` (Books).

**Tester:** `RecipeBookRepositoryTest` (Robolectric, `FakeBookTandoor` i de inspelade formerna för
**2.6.13 och 2.7.1**: delad bok aldrig skriven, favoritbok offline med id-omskrivning och delning,
"unique set", köade ändringar efter omläsning, Vill laga med hela listan och den smarta boken, gäst,
demo), `RecipeBookRulesTest`, `BookServicesTest`, `DtoFixturesTest` (custom-filter, all_personal),
`BooksViewModelsTest`, `BooksScreenshotTest`/`BooksAccessibilityTest` (`BookShot`, referensbilder i
`feature/recipes/src/test/screenshots/books/`), Idag, Klart! och `core:ui` (hjärtan, gästdelar).
**Kontrakt:** `BookWritesContractTest` (eget steg i `contract.yml`) prövar som vanlig medlem mot
2.6.13 och 2.7.1: `GET /api/user/` är en lista, en egen favoritbok delad med spacet syns för
ägaren, poster (`?recipe=`, "unique set", 204/404), nyckelordslistan och den smarta boken med
filtret som servern svarade, och rollerna i `all_personal`.
<!-- /Fas 2 böcker -->

## Receptvisning (`:core:domain`, Fas 1)

Ren Kotlin (JVM, JUnit 5) utan Android-typer. Allt som receptvyn och senare kokläget visar och
som beror på portionerna räknas här, likadant som Tandoors webb (plan §10 Fas 1, FACTS.md
"Fas 1 d" och "Fas 1 l"):

- **Ett anrop för UI:t:** `RecipeDisplay.build(recipe, servings, preferences, style)` ger
  `RecipeDisplayModel` med faktorn, alla ingrediensrader (`ingredients`, rubriker inräknade;
  `ingredientGroups` grupperade under rubrikerna) och stegen (`StepDisplay`: nummer, namn,
  instruktionen som Markdown med mallarna renderade, `hasBrokenTemplate`, stegets rader, tid,
  `show_as_header`, `show_ingredients_table`, delrecept). `style` kommer från UI:ts locale:
  `Locale.toNumberStyle()` (decimalkomma på svenska, typografiska bråk `1 ½`, `³⁄₁₁`).
- **Skalning och format** (`scaling/`): `ServingsScaling.factor` = valda portioner /
  max(`servings`, 1); `servings_text` är bara en etikett (`ServingsText.label`).
  `WebAmountFormat` avrundar som JavaScripts `toFixed` (det exakta binära värdet, `1.005` → `1`)
  eller ger bråk med nämnare ≤ 16 (Stern–Brocot som webbens `frac`); `PluralRule` väljer singular
  när |mängd − 1| < 0,0001, vid `no_amount` eller utan pluralnamn. Det äldre `FormatAmount` (egen
  avrundning, inte webbens) är borttaget; all mängdformatering går genom `WebAmountFormat`.
- **Ingrediensrader** (`IngredientDisplay`): rubrik = `note`; mängd och enhet bara när
  `!no_amount && amount != 0`; enhetsbyte i serverns `conversions`-ordning utan dubbletter
  (`Item.unitAt(index)` går runt i båda riktningarna).
- **Mallar** (`TemplateRenderer`): `{{ scale(N) }}`, `{{ ingredients[i] }}` och `.amount`,
  `.unit`, `.food`, `.note`. Enhetstexten skrivs för receptets egen mängd (som servern), livsmedlet
  för den skalade. JSON:en avrundar mängder till 4 decimaler; mallarna tar det exakta värdet ur
  radens första `conversions`-post (annars blir t.ex. 13/12 portioner av ⅔ `8⁄11` i stället för
  webbens `5⁄7`). Okända mallar står kvar som de skrevs; trasig syntax ger `hasBrokenTemplate`
  och instruktionen oförändrad. Insatt text Markdown-escapas.
- **Metadata** (`RecipeMeta.kt`): `DurationText.format(75)` = "1 h 15 min" (hårt mellanslag mellan
  tal och enhet), `RatingDisplay.of(rating, style)` (halva stjärnor, text med en decimal),
  `ServingsText`.
- **Webbparitet:** `RecipeDisplayGoldenTest` läser `api-schema/fixtures/2.6.13/fas1/` (sökvägen
  skickas som systemegenskapen `tandem.fixturesDir` från `core/domain/build.gradle.kts`) och
  kräver att varje ingrediensrad, varje mallvärde och varje stegtext är identisk med
  `scaling-golden.json` för alla 11 recept, portioner och bråk av/på (stegtexterna jämförs som
  synlig text: webbens HTML mot appens Markdown). Saknas filen hoppas testet över med ett
  tydligt meddelande.
- **KMP (B2):** de publika signaturerna är ren Kotlin; enda JVM-beroendena är
  `Locale.toNumberStyle()` och `java.math` internt i `WebAmountFormat.toFixed`.
<!-- /Fas 1 domain -->

## CI

`.github/workflows/pr.yml` körs vid varje push och pull request (inte när bara `docs/**` eller
`*.md` ändras). Jobbet:

1. validerar Gradle-wrappern,
2. sätter upp Temurin 21 och Gradle och hämtar Gradle-cachen med `actions/cache/restore`
   (nyckel efter katalog, wrapper och byggskript, med prefix-fallback). Bara körningen på `main`
   sparar cachen, så alla grenar kan läsa den; `contract.yml` och `release.yml` läser bara,
3. visar och kontrollerar Kotlin-/KSP-/AGP-versionerna på byggets classpath,
4. kör `./gradlew --continue ciLint ciTest :app:assembleSideloadDebug :build-logic:convention:test`
   (`ciLint` = ktlint, detekt, `checkModuleGraph` och Android Lint; `ciTest` = enhetstester och
   skärmbilder, inspelade eller jämförda, se "Fas 1b – CI"),
5. kontrollerar att Room-schemat i `core/database/schemas/` är incheckat (se Room),
6. laddar upp rapporter vid fel och debug-APK:n som artefakt (3 dagar på grenar, 7 på `main`).
   Debug-APK:n signeras med den publika debug-nyckeln `app/debug.p12`, så en nyare installeras
   ovanpå en äldre (se `docs/RELEASE.md`).

Taggar startar inte `pr.yml` eller `contract.yml` (de kör bara på grenar). `release.yml`
torrkör på lane-grenar när byggkonfigurationen ändras, inte på `main`.

`.github/workflows/smoke.yml` kör release-röktestet (nästa avsnitt) på en emulator: vid varje
push till `main`, vid varje push till en lane-gren **utom** när alla ändrade filer ligger på en
kort lista som varken når den minifierade appen eller testet (`docs/`, `*.md`, `LICENSE`,
`*/src/test/`, `contract-tests/`, de andra arbetsflödena utom `smoke.yml`/`release.yml`,
OpenAPI-schemat, `api-schema/used-endpoints.txt`, skripten som bara körs för hand, detekts
konfiguration och baslinjer, `renovate.json` och `:benchmark`), manuellt, och för varje
`v*`-tagg via `release.yml`, som inte publicerar om det fallerar. Listan är en undantagslista och
inte en lista över vad som triggar: Fas 1 visade att R8-känslig kod (Retrofit-tjänster,
`@Serializable`-DTO:er och navigeringsnycklar, Room-entiteter, `@HiltWorker`-arbetare,
modulberoenden), fixturerna som den falska servern serverar och texterna som testet letar efter
ligger överallt i `core/` och `feature/`. Jobbet *Decide whether to run* skriver ut de ändrade
filerna och `run=true/false`.

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

<!-- Fas 1b ci -->
## Fas 1b – CI (detekt, skärmbilder, Tandoor-bevakning, Renovate, prestanda)

Plan §10 "I slutet" av Fas 1. Översikt över arbetsflödena:

| Arbetsflöde | När | Gör |
|---|---|---|
| `pr.yml` | varje push | ktlint, **detekt**, modulgrafen, Android Lint, enhetstester, skärmbilder, build-logic-tester, debug-APK |
| `smoke.yml` | push (utom undantagslistan), `main`, taggar | release-röktestet på emulator |
| `contract.yml` | push av kontraktstester; anropas av `tandoor-watch.yml` | kontraktssviten mot en tillfällig Tandoor |
| `tandoor-watch.yml` | varje natt 02:23 UTC, manuellt | nya Tandoor-versioner, kontraktssviten, ett ärende vid behov |
| `benchmark.yml` | måndagar 03:17 UTC, manuellt | Macrobenchmark och baslinjeprofilen på emulator |
| `screenshots.yml` | manuellt | spelar in referensbilderna och kontrollerar dem |

Emulatorn körs bara i `smoke.yml` och `benchmark.yml`. Alla läser Gradle-cachen som `main`:s
`pr.yml` skriver; ingen annan skriver den.

### detekt

`tandem.detekt` (alla moduler) kör detekt **2.0.0-alpha.6** (den enda linjen byggd på Kotlin 2.4,
se katalogen) i *light mode*, utan typupplösning och utan kompilering, över hela `src/` (main,
test, varianter), med Compose-reglerna `io.nlopez.compose.rules:detekt`. Bara den vanliga
uppgiften `detekt` används (`detekt.android.disabled=true`). `ciLint` beror på den; rapporter
(HTML, SARIF, checkstyle) hamnar i `build/reports/detekt/` och i artefakten `reports` vid fel.

- **Regler:** detekts standard plus `config/detekt/detekt.yml`. Formatering är ktlints jobb;
  regler som bara stör i Compose (långa parameterlistor, `@Composable` med stor bokstav, magiska
  tal i `@Preview`, ...) är avstängda eller undantar `@Composable`/`@Preview`, och ett antal
  Compose-korrekthetsregler (`RememberMissing`, `ModifierReused`, `MutableParams`, ...) är på.
  Varje avvikelse i filen har en motivering.
- **Baslinje:** `<modul>/detekt-baseline.xml` innehåller det som fanns när detekt infördes
  (inklusive de parallella Fas 1b-banornas kod, mest `TooGenericExceptionCaught` i `:core:sync`:s
  husstil "fånga allt utom `CancellationException` och gör det till ett `Outcome`"). **Ny kod ska
  vara ren.** Lägg aldrig till ny kod i en baslinje.
- **Undertrycka ett fynd:** bara där det sitter och med motivering, t.ex.

  ```kotlin
  // Kraschloggen ska fånga allt, även fel från tredjepartskod.
  @Suppress("TooGenericExceptionCaught")
  ```

  Gäller samma sak i hela kodbasen ändras regeln i `detekt.yml` (med motivering) i stället.
- **Ny baslinje** (bara när en regel slås på): `./gradlew detektBaseline` i CI (aldrig på
  servern), ladda ned filerna och checka in dem.

### Skärmbilder (Roborazzi: inspelning, jämförelse och pseudospråk)

En enda Gradle-egenskap i `gradle.properties` styr: **`tandem.screenshots.verify`**.

- `false`: `ciTest` **spelar in** varje skärmbild (`recordRoborazzi<Variant>`) och `pr.yml`
  laddar upp dem som artefakten `screenshots`.
- `true` (**på sedan 0.2.0**): `ciTest` **jämför** (`verifyRoborazzi<Variant>`) med referensbilderna i
  `<modul>/src/test/screenshots/` (kopieras först till `build/outputs/roborazzi/`). En ändrad
  eller **saknad** referensbild fäller testet och lämnar `<namn>_actual.png` och
  `<namn>_compare.png` i artefakten `screenshots`.

Referensbilder spelas bara in i CI (typsnitt och rendering skiljer mellan maskiner):
**Actions → Screenshots → Run workflow** kör `updateRoborazziGoldens` i alla moduler, laddar upp
`screenshot-goldens.tar` (artefakten `screenshot-goldens`, med sökvägar från roten) och kör sedan
samma tester igen med `-Ptandem.screenshots.verify=true` mot de nya bilderna, så att körningen
också visar att inspelning och jämförelse är överens på löparen.

**Jämförelsen är på** (0.2.0): 368 referensbilder (ca 34 MB) i åtta moduler, inspelade av
*Screenshots* på integrationsgrenen, granskade i urval och incheckade tillsammans med
`tandem.screenshots.verify=true`. Roborazzis tröskel är standard (0): en enda ändrad pixel fäller
`pr.yml`, vilket prövades med en avsiktligt ändrad pixel i en referensbild på en kastgren.

**Den som ändrar en skärm** (eller ett typsnitt, en färg, ett bibliotek som ritar):

1. Kör *Screenshots* på sin gren (Actions → Screenshots → Run workflow) och vänta på grönt.
2. Ladda ned artefakten `screenshot-goldens`, granska de ändrade bilderna och packa upp i roten:
   `tar -xf screenshot-goldens.tar` (ersätter alla referensbilder; `git status` visar vilka som
   ändrats).
3. Checka in bilderna i **samma** ändring som skärmen. En röd jämförelse lämnar
   `<namn>_actual.png` och `<namn>_compare.png` i artefakten `screenshots`.

**Pseudospråk** (plan 5.6/6.7): bibliotekens debug-bygge har `isPseudoLocalesEnabled`, så tester
kan använda `@Config(qualifiers = "en-rXA-...")` (lång, accentuerad text) och
`"ar-rXB-ldrtl-..."` (höger-till-vänster). `TodayPseudoLocaleScreenshotTest` (`:feature:home`),
`RecipesPseudoLocaleScreenshotTest` (`:feature:recipes`) och `CookPseudoLocaleScreenshotTest`
(`:feature:cook`) tar bilder av Idag, receptlistan/-vyn och kokläget (`*_en_xa.png`,
`*_ar_xb.png`); varje test kontrollerar också att pseudospråket verkligen genererades. Appens
`localeFilters` håller dem utanför alla APK:er.

### Tandoor-bevakning (`tandoor-watch.yml`)

Varje natt (och manuellt, med valfria extra taggar):

1. `contract-tests/watch/discover.py` läser de publika taggarna för `vabene1111/recipes` på Docker
   Hub (utan inloggning) och `TandoorVersionTable.TESTED` (`:core:network`). Matrisen blir 2.6.13
   (referensen, de incheckade fixturerna), 2.6.15, den nyaste releasen av varje linje från 2.6
   (t.ex. 2.7.x) och `latest`.
2. `contract.yml` körs som återanvändbart arbetsflöde (`workflow_call` med `tags`) med hela
   sviten: Fas 0, Fas 1 och webbens skalningstabell.
3. `contract-tests/watch/report.py` jämför varje taggs observerade fakta (`observed-facts.json`)
   med 2.6.13 i samma körning (UUID:er, sekunder och versionen räknas bort) och skriver tabellen
   och diffen i jobbsammanfattningen.
4. Bara på `main` och bara det jobbet har `issues: write` (`GITHUB_TOKEN`): en röd svit, en
   release som är nyare än `TESTED` eller ett ändrat faktum öppnar eller uppdaterar **ett**
   ärende, "Tandoor watch: upstream needs attention" (en kommentar bara när något nytt hänt, via
   ett fingeravtryck); en helt grön natt stänger det. Ärendet hittas på exakt titel **och**
   författaren `app/github-actions` (det äldsta vinner): repot är publikt, så vem som helst kan
   öppna ett ärende med samma titel, och det får aldrig ta över rapporten.

<!-- Fas 1b komplettering-1 -->
**Granskade skillnader per linje** (`contract-tests/watch/reviewed-changes.json`, `report.py
--reviewed`): när en ny linje (t.ex. 2.7) läggs i `TESTED` skrivs varje faktum som skiljer sig
från 2.6.13 in där, granskat mot appen: nyckel → förväntat nytt värde (normaliserad JSON-text
som i diffen), `null` för ett faktum som försvunnit, `"*"` för ett värde som ändras med varje
bygge (webbappens HTML med hashade filnamn). Rapporten visar dem i en hopfälld lista och de
kräver ingen åtgärd; ett faktum som inte står där, eller får ett annat värde, gör det. Filen
skrivs ur en körnings `contract-recordings-*`-artefakter (diffen mellan 2.6.13 och den nya
taggen), aldrig för hand utan granskning.

**Tandoor 2.7 (komplettering 2026-10-25):** 2.7.0 tog bort `api-token-auth` (302 till
`/account/login/`). Appen loggar in via allauth headless (`TandoorAuthenticator`, se "Nätverk"),
och sviten har en 2.7-gren i `Phase0ContractTest` (inloggning, enhetstoken med `X-Session-Token`,
utloggning, konto utan space, spärren 3 per minut). Fixturerna och skillnaderna står i
`api-schema/fixtures/2.7.1/` (`FACTS.md`); `DtoFixturesTest` avkodar dem också. 2.7.1 står i
`TESTED`, och dess skillnader i `reviewed-changes.json` ("2.7"). **2.7.0 är inte testad med
flit**: den nekar varje skrivning med token ("CSRF Failed", `SessionAuthentication` före
OAuth2), så appen kör den i säkert läge; bevakningen testar bara den nyaste i varje linje, och
en körning med `extra-tags=2.7.0` är röd av just det skälet.

`contract.yml` körs också vid push som ändrar `core/network/**` eller `core/model/**` (det
appen läser från Tandoor), inte bara `contract-tests/**` och `api-schema/**`. Sviten
kompilerar inte mot `:core:network` (den är fristående med rå OkHttp), men en ändring i DTO:er
eller modeller ska prövas mot en riktig server samtidigt som mot de incheckade fixturerna.
<!-- /Fas 1b komplettering-1 -->

Ny Tandoor-version som är grön utan ändrade fakta: lägg till den i `TandoorVersionTable.TESTED` i
nästa appversion. Push till grenar som ändrar bevakningen kör allt men rör inga ärenden.

### Renovate (`renovate.json`)

Renovates GitHub-app är installerad för repot (av ägaren); konfigurationen ligger i
`renovate.json`. En gång i veckan (måndag före 07, svensk tid), bara versioner som är minst 14
dagar gamla, grupperat: AndroidX, Compose (BOM, Material 3, adaptive), byggverktygen (AGP,
Kotlin, KSP, Hilt – måste följas åt), testbibliotek, statisk analys, GitHub Actions och
Gradle-wrappern. GitHub Actions pinnas med digest och versionen som kommentar. **Automerge** bara
för patch-/digestuppdateringar av testbibliotek och Actions, och bara när CI är grönt
(Renovate slår ihop själv med rebase, så `main` förblir linjär). `rebaseWhen` är `auto`: en
automerge-gren som ligger efter `main` rebasas först och slås ihop först när CI är grönt mot det
**aktuella** `main` (med `conflicted` slogs den ihop på en bas den aldrig testats mot). Grenar
utan automerge rebasas bara vid konflikt, så att de inte kör om röktestet varje gång `main` rör
sig. Allt annat granskas för hand.
Dependency Dashboard-ärendet visar vad som väntar. Fixturer och skript undantas.

### Prestanda (`:benchmark`, `benchmark.yml`)

`:benchmark` är som `:smoke` en självinstrumenterande test-APK mot `:app`:s minifierade
sideload-bygge (paketet `casa.moller.tandem.minified`, som bara i den byggtypen är
`profileable`, `app/src/minified/AndroidManifest.xml`). Allt i demoläget, utan nät:

- `StartupBenchmark`: kallstart till Idag, `StartupMode.COLD`, 10 iterationer, med
  baslinjeprofilen (`CompilationMode.Partial(BaselineProfileMode.Require)`) och utan kompilering (`CompilationMode.None`).
- `RecipeListScrollBenchmark`: svep i receptlistan, bildtider (`FrameTimingMetric`).
- `BaselineProfileGenerator`: kallstart, receptlistan och ett recept som baslinjeprofil.

`benchmark.yml` (veckovis och manuellt, plus push som ändrar `benchmark/`) kör två jobb på var sin
emulator (API 36): mätningarna, med median/min/max och P50–P99 i jobbsammanfattningen
(`scripts/ci/benchmark-summary.py`), och generatorn mot ett minifierat bygge **utan** R8
(`-Ptandem.baselineProfile.generate=true`, så profilen har riktiga klassnamn som AGP sedan mappar
om i varje R8-bygge). Profilen laddas upp som artefakten `baseline-profile`; checka in den som
`app/src/main/baselineProfiles/baseline-prof.txt` (`androidx.profileinstaller` installerar den
även vid sidladdning). Den incheckade profilen (35 473 regler, 5 713 för Tandems egna klasser)
genererades efter integrationen av 0.2.0 (körning 40031229148 på integrationsgrenen); förnya den när startvägen, listan eller receptvyn ändrats mycket
(Actions → Benchmark → Run workflow, ladda ned, ersätt filen). Mätjobbet kontrollerar att
profilen når AGP:s sammanslagna ART-profil (antalet regler för `casa/moller/tandem` står i
sammanfattningen) och att den R8-omskrivna binära profilen verkligen ligger i APK:n som mäts:
`assets/dexopt/baseline.prof` och `baseline.profm` måste finnas och `baseline.prof` vara minst
1 KB (storlekarna står i sammanfattningen), annars blir jobbet rött. På enheten kör
`StartupBenchmark` och `RecipeListScrollBenchmark` med `BaselineProfileMode.Require`, så en
APK utan installerbar profil fäller mätningen i stället för att tyst mäta utan profil.

**Senaste mätningen (0.2.0-integrationen, 2026-10-23, körning 40031229148, emulator API 36):**

| Mätning | median | min | max |
|---|---|---|---|
| Kallstart med baslinjeprofil, första bildrutan (`timeToInitialDisplayMs`) | 1 875 ms | 1 279 ms | 3 434 ms |
| Kallstart med baslinjeprofil, Idag färdig (`timeToFullDisplayMs`) | 4 457 ms | 3 237 ms | 4 941 ms |
| Kallstart utan kompilering, första bildrutan | 1 495 ms | 1 170 ms | 2 343 ms |
| Kallstart utan kompilering, Idag färdig | 3 448 ms | 3 114 ms | 5 023 ms |

Svep i receptlistan (5 iterationer, median 160 bildrutor): `frameDurationCpuMs` P50 97 ms, P90
161 ms, P99 294 ms; `frameOverrunMs` P50 124 ms, P90 217 ms, P99 426 ms. Som i ci-banans
körning är profilen inte snabbare på emulatorn och bildtiderna är långt över 16 ms: det är
programvarurenderingen, inte appen, och siffrorna duger bara för att se trender mellan körningar.

**Emulatorns siffror är bara en trend** (programvaru-GPU, 2 vCPU, delad löpare). Kravet i plan
§10 (kallstart under 1,5 s) mäts på telefonen: appen mäter själv sin kallstart och visar den
senaste under **Mer → Diagnostik** (`DiagnosticsRepository.recordColdStart`).

### CI-tid

Gradle-cachen skrivs bara av `main`; emulatorjobben bygger först och stoppar Gradle innan
emulatorn startar; `smoke.yml` hoppar över ändringar som inte når appen; kontraktsmatrisen på
push kör 2.6.13 och 2.7.1 (Fas 2); benchmark och bevakning körs schemalagt, inte vid varje push.
<!-- /Fas 1b ci -->

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
- **Fas 1 (0.1.0, `Phase1SmokeTest`):** `FakeTandoor` svarar också på Fas 1-anropen med
  fixturerna i `fas1/`: `recipe/flat`, `recipe/` (synkens översikter, Idags karuseller efter
  frågeparametrarna och en sökning `query`, filtrerad på namn med vikta versaler och diakritiska
  tecken som Tandoors sökning), receptdetaljer, nyckelord, livsmedel, enheter, måltidstyper,
  matsedel, kokloggen, användarinställningar och `POST view-log/`. Den inspelade adressen
  `tandoor.example.com` byts mot den falska serverns egen, så receptbilder (`/media/...`, en
  genererad PNG) hämtas därifrån. Testet loggar in med lösenord, väntar ut den riktiga första
  synken, ser fixturrecepten i Recept, söker på "äppel" (bara Äppelkaka, lokalt och på servern),
  öppnar receptet (bilden laddad), ökar portionerna 8 → 9 och kräver exakt webbens mängd ur
  `scaling-golden.json` ("168.75 g" smör), väljer "Download for offline use", slår av nätet och
  kallstartar: flygplansläge (`cmd connectivity airplane-mode enable` från
  instrumenteringens shell, med `svc wifi/data disable` som reserv; testet kontrollerar via
  `ConnectivityManager` att inget standardnätverk finns kvar, tre sekunder i följd: på
  emulatorn försvinner wifi först och mobildata blir standardnätverk några hundra millisekunder
  innan radion också stängs). Ett sådant flimmer får inte heller ge nya anrop: receptlistan
  frågar inte servern igen om en sökning vars svar redan visas (bara ny text eller
  dra-för-att-uppdatera gör det). Loopback påverkas inte av
  flygplansläget, så `FakeTandoor.offline` stänger dessutom varje anslutning; testet kräver att
  inget `GET /api/recipe…` kom fram medan nätet var av. Offline: Offline-chippet, sökningen
  "Offline: searching recipes on this device", receptet med bild ur diskcachen och de sparade
  portionerna (9, "168.75 g"). Nätet slås alltid på igen av testets yttersta regel (efter att
  ett fel har fångats med nätet avslaget). Demotestet startar demoläget,
  kräver "Tonight" med middagen och dess bild på Idag, öppnar receptet (bild, ingrediensraden
  "600 g salmon fillet…" via radens beskrivning) och att den falska servern inte fick en enda begäran. Att en bild verkligen
  laddats syns utifrån genom testtaggen `recipe_image_loaded` (`RecipeImageTags` i `:core:ui`);
  appen exponerar testtaggar som resurs-id (`testTagsAsResourceId` i `TandemApp`).
- **Fas 1b (0.2.0, `Phase1bSmokeTest`, plan §10 "Klart när"):** `FakeTandoor` ger Shakshukas
  sista steg två korta tider i texten ("50 seconds", "55 seconds") och svarar `POST
  /api/cook-log/` med 201 och sparar kroppen. `offlineCookMode_twoTimersRingWhileLocked_klartIsSentOnceOnline`:
  inloggning och första synk, Shakshuka nedladdad, flygplansläge, "Start cooking" från receptet,
  båda stegen; den första timern visar frågan i sammanhanget och systemdialogen, och **först
  därefter** ger instrumenteringen `POST_NOTIFICATIONS` (`pm grant`; före testet återkallas den
  och dess flaggor rensas, som en ny installation); två timers samtidigt (två
  nedräkningsnotiser på `timer_ongoing`); rotation till liggande (två paneler, timrarna i
  sidopanelen) och tillbaka med samma steg och båda timrarna; strömknappen (`KEYCODE_SLEEP`).
  Båda larmen måste komma **medan skärmlåset är på**: två larmnotiser på `timer_alarm` med
  fullskärms-intent (sideload), ringtjänsten i förgrunden, fortfarande offline, larmaktiviteten
  "Time's up" på låsskärmen och skärmen påslagen. "Stop" på båda, "Done!" med 4 stjärnor och en
  kommentar offline (toasten "skickas när du är online"), inget skickat; nätet tillbaka och
  **exakt en** `POST /api/cook-log/` med recept, portioner, betyg, kommentar och `created_at`
  från när "Spara" trycktes. `demoMode_cookTonightWithATimer_klartStaysOnTheDevice`: demoläget,
  "Cook" på Ikväll, en timer med "Not now" (varningen "Notifications are off" syns), stäng
  kokläget, timerraden på Idag (`shell_timer_hud`) öppnar kokläget igen, Klart! sparas i demot
  och den falska servern får inte en enda begäran. `DeviceState` läser notiser
  (`dumpsys notification`), tjänster, skärm och skärmlås. Testets yttersta regel lämnar alltid
  enheten upplåst, i naturlig orientering och med nät.
- **Stabilitet:** på CI-emulatorn försvinner ett tryck ibland medan en skärm fortfarande landar.
  `TandemApp.tapUntil(text, expected)` trycker igen (högst tre gånger, bara medan knappen finns
  kvar) tills målskärmen syns, och `findInList` söker åt båda hållen där en skärm behåller sitt
  rullningsläge (Mer). 0.2.0 krävde tre gröna körningar i rad på samma commit.
- **CI:** `smoke.yml` bygger båda APK:erna, stoppar Gradle, startar en emulator (API 36,
  `google_apis`, x86_64, KVM) med `reactivecircus/android-emulator-runner` och kör
  `scripts/ci/smoke-run.sh`. Steget fallerar om ett test fallerar, om appen kraschar, om ingen
  av `core:sync`s WorkManager-arbetare blev klar (`Worker result SUCCESS` för
  `casa.moller.tandem.core.sync.work.*` i appens logcat, `workers.txt`: Hilt-fabriken och
  klassnamnen fungerar under R8) **eller** om appens processer loggade
  `ClassNotFoundException`, `NoClassDefFoundError`, `NoSuchMethodError`/`-Exception`,
  `NoSuchFieldError` eller WorkManagers "Could not instantiate/create Worker"
  (`missing-classes.txt`), även om appen fångade felet. CI-loggen listar arbetarnas resultat och
  den falska serverns begäranden.
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

<!-- Fas 1 network -->
## Nätverk (`:core:network`, Fas 1)

Allt som pratar HTTP med Tandoor. Repositories och synken (`core:data`, `core:sync`) använder
det; features aldrig (`checkModuleGraph`).

- **DTO:er** (`dto/`): toleranta enligt plan 3.6: `TandoorJson` (`ignoreUnknownKeys`,
  `explicitNulls = false`, `coerceInputValues`), standardvärde på varje fält, enum-värden som
  `String`, okända eller otypade former som `JsonElement` (näring, `properties`,
  `food_properties`, ingrediensernas `conversions`, `step_recipe_data`). Fas 1:
  `RecipeFlatDto`, `RecipeOverviewDto`, `RecipeDto` (`StepDto`, `IngredientDto`, `FoodDto`,
  `UnitDto`, `KeywordDto`), `MealPlanDto`, `MealTypeDto`, `RecipeBookDto`,
  `RecipeBookEntryDto`, `CookLogDto`, `ViewLogDto`, `ShareLinkDto`, hela `UserPreferenceDto`,
  utökad `SpaceDto` och `PageDto<T>` (DRF-sidor). Datum är råa strängar; tolka med
  `TandoorDates`.
- **Tjänster** (`api/`, Retrofit, alla gränssnitt under `core.network.api` så att R8-regeln
  täcker dem): `TandoorApi.recipes` (`flat`, `list`, `recipe`, `recipeJson`),
  `taxonomy` (nyckelord, livsmedel, enheter, måltidstyper, böcker; `page_size` 200),
  `mealPlans`, `preferences` och `logs`. Receptsök via `RecipeListQuery` och
  `RecipeService.list(query, page)`: listparametrar upprepas, datum skickas som rena datum
  (med klockslag svarar 2.6.13 med 500), `page_size` högst 100, och `RecipeSortOrder` saknar
  `times_cooked` (500).
- **Paginering**: `Paginator.fetchAll { page -> … }` läser bara sidnumret ur `next` och
  begär nästa sida med samma anrop mot kontots bas-URL. Värd, sökväg och övriga parametrar i
  länken används aldrig (proxyn bygger den, och en fientlig server kan peka vart som helst).
  Högst 100 sidor, sidnumret måste öka och avbrott kontrolleras före varje sida.
  `PagedResult.complete` är `false` om listan avbröts; då får synken inte tolka saknade poster
  som raderade.
- **Versioner och kapabiliteter** (`capabilities/`): `TandoorVersionTable` har de testade
  versionerna (2.6.0–2.6.15 och 2.7.1; 2.7.0 med flit inte, se "Tandoor-bevakning") och
  stödintervallet `>= 2.6.0 < 2.8.0`. `assess(version)` ger
  `ServerCompatibility` och en egen `writePolicy`: otestat inom intervallet ger säkert läge och
  pausad outbox, över intervallet stängs skrivningar av. `CapabilityResolver` bygger
  `Capabilities` av versionen och `space/current` (`ai_enabled`, `household_setup_completed`).
  Bara när versionen inte går att tolka görs högst 3 sonderingar mot list-endpoints
  (`page_size=1`). En okänd sökväg ger då 302 till inloggningen (2.6) eller 200 med webbappens
  HTML (2.7), inte 404. Varje fel utom en omdirigering stoppar sonderingen (HTML-vakten är ett
  fel). Sökvägarna står i `api-schema/used-endpoints.txt`.
- **Inloggning** (`auth/TandoorAuthenticator`): 2.6 loggar in med `api-token-auth`; från 2.7.0
  (och när `api-token-auth` svarar omdirigering, 404 eller 405) med allauth headless
  (`AllauthService`: `POST _allauth/app/v1/auth/login`, aldrig omförsök, 3 per minut och IP).
  Sessionen (`meta.session_token`) skapar enhetstoken (`createAccessTokenWithSession`,
  `X-Session-Token`) och loggas ut direkt (`DELETE _allauth/app/v1/auth/session`, svarar 401),
  innan enhetstoken används. 403 + JSON på token-skapandet efter en godkänd inloggning = konto
  utan space. 401 (ett steg till krävs) och 404/405 från allauth blir
  `TandoorAuthenticator.PASSWORD_LOGIN_UNAVAILABLE` → `DataProblem.PASSWORD_LOGIN_UNAVAILABLE`
  → "klistra in token i stället". R8-kontrollen i `smoke.yml` kräver båda inloggningarna.
- **Bilder** (`image/`): `TandoorImageClientFactory.create(baseUrl, tokenProvider)` ger en
  OkHttp-klient per konto för Coil (`OkHttpNetworkFetcherFactory(callFactory = { client })`).
  Token skickas bara till kontots origin och bassökväg, och OkHttp tar bort den vid en
  omdirigering till en annan värd. Bilderna har en egen dispatcher (3 per värd, 6 totalt) och
  en egen token-bucket (8/s), så de tar aldrig API:ts två platser. `ImageErrorBreakerInterceptor`
  pausar bilder i 5 min efter 5 svar med 400/403/404 inom 100 s (plan 4.1).
  `MediaUrls.resolve(baseUrl, image)` gör relativa sökvägar från `recipe/flat` absoluta och
  flyttar `http://…/media/…` från en proxy utan `X-Forwarded-Proto` till kontots bas-URL.
- **Tester** läser `api-schema/` på plats via systemegenskapen `tandem.apiSchemaDir` (satt i
  `core/network/build.gradle.kts`), utan kopior. `DtoFixturesTest` avkodar **varje** lyckat
  JSON-svar i `api-schema/fixtures/2.6.13` med sin DTO. Den kontrollerar också att alla fält
  stämmer med fixturen och att varje deklarerat fält finns i någon fixtur, vilket fångar
  felstavade `@SerialName`. En ny fixtur kräver en rad i testets routetabell eller ett skäl i
  `notModelled`; samma test går över `api-schema/fixtures/2.7.1` (2.7: allauth, decimaler).
  `UsedEndpointsTest` kräver att varje Retrofit-anrop står i `used-endpoints.txt` och att varje
  rad där finns i OpenAPI-schemat, utom allauths två (inte Tandoors schema; sviten prövar dem).
<!-- /Fas 1 network -->

## Demoinnehåll (`:core:data`, Fas 1)

Demoläget (plan 6.2) har inbyggd exempeldata i `core/data/src/main/assets/demo/` (se README.md
där): 14 egna recept på svenska och engelska, nyckelord, måltidstyper, en matsedel för
igår–nästa vecka, en kokhistorik (ger "Ofta lagade", "Inte lagat på länge" och betyg),
nyligen visade recept, två receptböcker, användarinställningar (bråk på) och 14 bilder.

- **Samma form som Tandoor 2.6.13.** Varje fil är ett riktigt API-svar (receptdetalj,
  listelement, matsedel osv.), så demosessioner går genom samma DTO → modell → entitet-mappning
  som en riktig server. `DemoContentTest` avkodar allt strikt med core:networks DTO:er och
  jämför nycklarna med fixturerna i `api-schema/fixtures/2.6.13/fas1/`.
- **`DemoContent`** (`casa.moller.tandem.core.data.demo`, en `@Singleton` via Hilt) läser
  filerna: `recipes(locale, today)`, `recipe(id, locale, today)`, `recipeJson(…)`,
  `recipeOverviews`, `recipeFlat`, `keywords`, `mealTypes`, `mealPlan(locale, today)`,
  `cookLog(today)`, `viewLog(today)`, `recipeBooks`, `recipeBookEntries`,
  `userPreferences(locale)`, `pinnedRecipeIds`, `imageUris`. Svenska för `sv`-locales, annars
  engelska. Bara en laddare: synken kopplar in den som datakälla för demosessioner.
- **Datum:** filerna är skrivna som om idag vore 2000-01-15 (`REFERENCE_DATE`, samma dag som
  fixturerna). `DemoContent` flyttar alla `created_at`, `updated_at`, `from_date`, `to_date`
  och `last_cooked` lika många dagar som det är till `today`. Resultatet är deterministiskt
  för ett givet datum.
- **Bilder** är `file:///android_asset/demo/images/<namn>.webp` (Coil läser dem ur APK:n). De
  är inga Tandoor-media-URL:er och får inte gå genom `MediaUrls.resolve`.
- **Ändra innehållet:** redigera `scripts/demo-content/demo_source.py` och kör
  `python3 scripts/demo-content/generate_demo_content.py` (JSON) och vid behov
  `python3 scripts/demo-content/build_images.py` (bilder; kräver Pillow med WebP och pycairo).
  Checka in både källan och resultatet. Testerna kräver bl.a. en timer i varje steg med tid
  (`TimerDetector`), samma struktur på båda språken, bilder på högst 70 kB och högst 1,2 MB
  totalt.
<!-- /Fas 1 demo -->

<!-- Fas 1 sync -->
## Synk och offline (`:core:sync`, Fas 1)

Room är den enda sanningskällan (plan 5.3). `:core:sync` implementerar alla Fas 1-gränssnitt i
`:core:data` och binder dem med Hilt (`di/SyncModule.kt`): `OfflineFirstRecipeRepository`,
`OfflineFirstHomeRepository`, `DefaultSyncRepository`, `DefaultPreferencesRepository`,
`DefaultServerStatusRepository`, `RoomPendingChangesExporter` (återställningsskärmens export),
`DefaultImportGate` och `DemoSeeder` (`DemoDataRepository`, "Återställ demodata"). Appen lägger
bara till `HiltWorkerFactory` (`TandemApplication` är `Configuration.Provider`, WorkManagers
standardinitierare är borttagen i manifestet) och anropar `SyncStarter.start()` i `onCreate`.

- **Aktiv källa:** `ActiveSourceProvider` gör sessionen till `ActiveSource`: `Remote` (riktig
  databas + kontots server), `Demo` (demodatabasen, aldrig nätverk) eller `None`. Alla flöden
  byter källa när sessionen ändras; varje rad har kontots `account_id`.
- **Synkkörning (`engine/SyncEngine`, plan 3.5):** serverkoll (bara i den periodiska synken;
  start/återupptagning kollar skalet) → små tabeller (nyckelord, livsmedel, enheter,
  måltidstyper, användarinställningar; högst 1/h vid start, var 6:e timme periodiskt) →
  receptindex `recipe/flat` (raderingar) och översikter `recipe/` med 100 per sida och
  `updatedon_gte` = senaste synkdag − 1 dag (allt vid första synken) → kokstatistik ur
  `cook-log` (antal, senast lagad och inloggade användarens eget snittbetyg avrundat som
  servern, eftersom listorna saknar betyg; allt i en transaktion, recept utan loggposter
  nollställs) → matsedel −14..+42 dagar och Idags karuseller (var 15:e minut, 2 min vid
  dra-för-att-uppdatera; steget delar lås med Idags egen uppdatering så att inget hämtas två
  gånger) → detaljer för fästa (äldre än 24 h), planerade (igår..+7) och nyligen visade recept,
  högst 40 per körning och 2 åt gången med paus → bilder för fästa, planerade och nyligen visade
  till diskcachen (bara laddade bilder bockas av; tillfälliga fel försöks igen nästa körning) →
  outboxen. Dra-för-att-uppdatera och första synken struntar i intervallen (utom de små
  tabellerna). En tid som ligger mer än 5 min i framtiden (klockan gick före) räknas som
  förfallen, och `updatedon_gte` blir aldrig senare än i dag − 1.
- **Skydd (`guard/SyncGuard`, plan 4.1):** alla anrop går genom `RemoteCaller`. 401 och 403 med
  Tandoors JSON-`detail` stoppar kontots anrop tills en ny token laddas (outboxen behålls), annan
  403 (brandvägg, proxy med egen JSON) pausar 5 min och dubblar upp till 1 h, 429 stoppar alla
  anrop till `Retry-After` (5 min utan rubrik, högst 1 h; utkorgen försöker inte igen själv),
  kretsbrytaren pausar bakgrundsanrop 5 min efter fler än 5 fel på
  60 s eller 5 svar 404/400 på 100 s, och en import (`ImportGate.whileImporting`) pausar
  bakgrunden. En JSON-403 på ett enskilt recept kontrolleras med ett billigt anrop innan den
  räknas som utloggning (annans privata recept ger också 403).
- **Detaljer (stale-while-revalidate):** `observeRecipe` visar den lagrade kopian direkt, hämtar
  om den är äldre än 5 minuter (online) och visar `refreshing`. 404 tar bort receptet lokalt
  (`isDeleted`). Rå JSON sparas i `recipe_detail`.
- **Outbox (`engine/OutboxSender`):** `VIEW_LOG` (`markViewed`, sammanslagen per recept) och från
  0.2.0 `COOK_LOG` ("Klart!", se "Koklogg" nedan). Säkert läge (otestad version) sätter raderna i
  PAUSED; 404 slängs, 400 blir FAILED, recept som inte längre finns skickas aldrig.
- **WorkManager (`work/`):** `tandem.sync.now` (`SyncWorker`, expedited, nätverkskrav; app-start,
  återupptagning, dra-för-att-uppdatera, kontobyte; en automatisk begäran medan en körning väntar
  eller pågår släpps (`KEEP`), dra-för-att-uppdatera ersätter den (`REPLACE`) så att användarens
  uppdatering alltid läser receptindexet; den ersatta körningen slutar med markören
  `SyncStop.CANCELLED`, inget fel), `tandem.sync.periodic`
  (`PeriodicSyncWorker`, 15 min, nätverk och ej låg batterinivå) och `tandem.sync.outbox`
  (`SyncOutboxWorker`, exponentiell backoff från 30 s). Taggar `tandem.sync` och
  `tandem.outbox`. Arbetarnas klassnamn skyddas mot R8 i `core/sync/consumer-rules.pro`. Den
  periodiska synkens första körning kommer en period efter inloggningen (introts `FIRST_SYNC`
  gör första synken; annars gjordes allt två gånger).
- **Versionsbyte (plan B18):** `AccountMirror` speglar kontot i `server_account`. När
  serverversionen skiljer sig från den sparade (aldrig vid första inloggningen) sänds
  `observeVersionChanges()` och en notis i kanalen "Server" (bara med `POST_NOTIFICATIONS`, som
  appen frågar efter först vid första timern i 0.2, och med kanalen påslagen). Kunde notisen
  inte visas och är den nya versionen testad sparar `AccountMirror` meddelandet i
  `AppSettings.versionNotice` (DataStore, så det överlever processdöd och hittas även när
  bakgrundssynken upptäckte bytet). `ServerStatusRepository.observeVersionNotice()` lämnar det
  bara för det aktiva kontot (aldrig i demoläget); skalet visar `ServerUpdatedBanner` ("Tandoor
  uppdaterades till 2.6.15", stängbar) och anropar `markVersionNoticeShown` direkt, så det visas
  en gång. En otestad version får inget eget meddelande: bannern för säkert läge räcker.
- **Utloggning:** ett konto som inte längre finns får alla sina rader borttagna, i alla tabeller
  och även rader med okända konto-id (ett svar som kom efter utloggningen; städas senast vid nästa
  appstart via `AccountDao.getStoredAccountIds`), och bildcachen töms (även efter "Rensa
  appdata"). Svar som kommer efter ett kontobyte skrivs inte. Ett utgånget token tar inte bort
  något (skalet visar "Logga in igen", som går att avbryta). Inloggning på ett riktigt konto
  tömmer demodatabasen. "Återställ demodata" under Mer i demoläget anropar
  `DemoDataRepository.resetDemoData()`. Byts appens språk i demoläget skrivs demodatan om på det
  nya språket direkt (`SyncStarter`, konfigurationsändring).
- **Bilder:** en enda Coil-`ImageLoader` (Coils singleton) med kontots bildklient från
  `:core:network` (token bara till kontots origin), 250 MB diskcache i `noBackupFilesDir` (töms
  vid utloggning, `image/ImageCache`), och `file:///android_asset/demo/...` för demobilderna.
- **Offline-chippet** i skalet visar "Offline" både när serverkollen inte nådde servern och när
  synken pausar för att nätet saknas (`SyncStatus.pause == OFFLINE`).
- **Tester** (Robolectric, Room på fil, ny databas per test, HTTPS-MockWebServer med fixturerna i
  `api-schema/fixtures/2.6.13/fas1`): indexradering (och att ett misslyckat index inte raderar
  något), `updatedon_gte`, intervall, SWR-ordning, högst 2 parallella detaljhämtningar,
  outboxens sammanslagning, 404-kretsbrytare, säkert läge, utloggning/kontoisolering,
  demoisolering och att alla demorecept på båda språken renderas utan trasiga mallar. Inte Room
  i minnet: om ett flöde avbryts mitt i databasens första öppning stänger Room anslutningen, och
  en ny `:memory:`-anslutning är en tom databas utan tabeller.
  `:smoke`s `FakeTandoor` svarar också på Fas 1-anropen så att första synken blir klar.
<!-- /Fas 1 sync -->

## Backup och nätverk

- `android:allowBackup="false"` och `data_extraction_rules.xml` undantar alla domäner från både
  molnbackup och överföring mellan enheter: token (Tink, nyckel i Keystore), inställningar och
  offlinecache stannar på enheten. Efter telefonbyte loggar man in igen.
- `network_security_config.xml`: ingen klartexttrafik; systemets och användarens installerade
  CA:er godtas (för självhostade servrar med egen CA), ingen certifikat-pinning.
