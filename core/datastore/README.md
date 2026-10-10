# core:datastore

Lokal lagring på enheten: **inställningar** (Preferences DataStore) och **konton med
enhetstoken** (DataStore + Tink AEAD, nyckeln skyddad av Android Keystore).

## Publikt API

| Typ | Paket | Vad |
|---|---|---|
| `SecureAccountStore` | `casa.moller.tandem.core.datastore` | `accounts: Flow<List<ServerAccount>>`, `upsert(account, token)`, `update(account)`, `token(accountId)`, `remove(accountId)`, `clearAll()` |
| `SettingsStore` | `casa.moller.tandem.core.datastore` | `settings: Flow<AppSettings>`, `update { … }`, `setThemeMode`, `setOnboardingCompleted`, `setDemoMode`, `setActiveAccountId` |
| `StorageHealthMonitor` | `casa.moller.tandem.core.datastore` | `health: StateFlow<StorageHealth>`, `report(reason)`, `acknowledge()` |
| `SecureStorageException` | `casa.moller.tandem.core.datastore` | Kastas av skrivningar i `SecureAccountStore` när något inte kunde sparas |
| `DatastoreFiles` | `casa.moller.tandem.core.datastore` | Alla filnamn och nyckelalias nedan som konstanter |
| `AeadProvider` | `casa.moller.tandem.core.datastore.crypto` | Abstraktion av Tink-`Aead` (Keystore i appen, minnesnyckel i tester) |
| `DatastoreModule` | `casa.moller.tandem.core.datastore.di` | Hilt: alla fyra ovan som `@Singleton` |

Modelltyperna ligger i `:core:model`: `ServerAccount` (`…core.model.account`) samt
`AppSettings`, `ThemeMode`, `StorageHealth` och `RecoveryReason` (`…core.model.settings`).
`ServerAccount` innehåller **aldrig** token; den hämtas bara med `SecureAccountStore.token(id)`.

## Filer på enheten

Alla sökvägar är relativa till appens privata datakatalog (`/data/data/<applicationId>/`,
dvs. `casa.moller.tandem` eller `casa.moller.tandem.dev`).

| Fil / nyckel | Innehåll | Backupdomän |
|---|---|---|
| `files/datastore/secure_accounts.pb` | Konton + token som ett JSON-dokument, **helt krypterat** med Tink AES-256-GCM. Associated data: `casa.moller.tandem/datastore/secure_accounts.pb` | `file` |
| `files/datastore/secure_accounts.pb.tmp` | DataStores tillfälliga fil under en skrivning (krypterad som ovan) | `file` |
| `files/datastore/app_settings.preferences_pb` | Inställningar (tema, onboarding klar, demoläge, aktivt konto-id). Ingen hemlighet | `file` |
| `shared_prefs/tandem_secure_keyset.xml` | Tink-keysetet (nyckel `secure_accounts_keyset`), krypterat med Keystore-nyckeln | `sharedpref` |
| Android Keystore-alias `tandem_secure_master_key` | AES-256-GCM-huvudnyckel som skyddar keysetet. Lämnar aldrig enheten | – |

`app/src/main/res/xml/data_extraction_rules.xml` utesluter domänerna `root`, `file`,
`database`, `sharedpref` och `external` (sökväg `.`) från både `cloud-backup` och
`device-transfer`, och manifestet har `allowBackup=false`. Alla filer ovan täcks alltså. Den
som smalnar av reglerna måste fortsätta utesluta minst de tre filerna ovan: Keystore-nyckeln
följer aldrig med en återställning, så en återställd `secure_accounts.pb` eller ett återställt
keyset går ändå inte att läsa.

`DatastoreFilesTest` kontrollerar att sökvägarna stämmer med tabellen.

## Kryptering

- Hela dokumentet (alla konton och token) serialiseras till JSON och krypteras som en enhet.
  Ingenting i filen är läsbart utan nyckeln (`EncryptedAccountStoreTest` söker efter token,
  URL och användarnamn i filens bytes).
- Keysetet skapas av Tinks `AndroidKeysetManager` med `android-keystore://tandem_secure_master_key`.
- Tink faller annars tyst tillbaka till ett **okrypterat** keyset om Keystore inte fungerar.
  `AndroidKeystoreAeadProvider` vägrar det: keysetet raderas och lagringen räknas som
  otillgänglig. Token sparas aldrig med en nyckel som ligger i klartext bredvid den.
- Token loggas aldrig. Den interna `StoredAccount.toString()` maskerar token, och
  undantagsmeddelanden innehåller aldrig klartext (JSON-fel kedjas inte vidare).

## Återställning i stället för krasch (§4.2 p7, #37)

Läsning kastar aldrig. Vid problem återställs lagringen och orsaken rapporteras till
`StorageHealthMonitor`, så att appen kan visa återställningsskärmen:

| Situation | Resultat | `RecoveryReason` |
|---|---|---|
| Keystore-nyckeln eller keysetet går inte att använda (t.ex. efter byte av telefon, OEM-fel) | Nycklarna raderas och skapas på nytt, kontolistan blir tom | `KEY_UNUSABLE` |
| Filen går inte att dekryptera (fel nyckel, ändrad associated data) eller tolka (korrupt) | Filen ersätts med ett tomt dokument | `ACCOUNTS_UNREADABLE` |
| Ingen nyckel går att skapa alls | Läsning ger tom lista, `upsert`/`update`/`remove` kastar `SecureStorageException` | `SECURE_STORAGE_UNAVAILABLE` |
| Inställningsfilen är korrupt | Standardvärden | `SETTINGS_UNREADABLE` |

Tillståndet finns bara i minnet. Efter en återställning är filerna giltiga igen, så nästa
start är "frisk" men utan konton, och användaren hamnar på inloggningen. `AppSettings.activeAccountId`
kan då peka på ett konto som inte längre finns; datalagret måste kontrollera det mot
`accounts`. `clearAll()` är knappen "Rensa appdata": går filen inte att skriva förstörs
nycklarna i stället (kryptografisk radering) och undantaget kastas vidare.

## Tester

Robolectric (JUnit 4) i `src/test`. `TestAeadProvider` använder ett Tink-keyset i minnet,
eftersom Robolectric saknar Android Keystore. `AndroidKeystoreAeadProviderTest` kontrollerar
att Keystore-providern misslyckas säkert (ingen krasch, inget okrypterat keyset kvar).
Den riktiga Keystore-vägen kan bara provas på en enhet eller emulator (instrumenterat test,
senare fas).
