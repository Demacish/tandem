# core:data

Datalagret som feature-modulerna pratar med (plan §5.2/§5.3). I Fas 0 är det tunt: konton,
inloggning och session ovanpå `core:network` (sondering, inloggning) och `core:datastore`
(krypterade konton, inställningar). Offline-först med Room och synk kommer i Fas 1.

## Publikt API

| Typ | Paket | Vad |
|---|---|---|
| `AccountRepository` | `…core.data.account` | `probe(url)`, `login(server, användare, lösenord)`, `loginWithToken(server, token)` |
| `SessionRepository` | `…core.data.session` | `session: Flow<Session>`, `refreshServerInfo()`, `logout(revokeToken)`, `startDemo()`, `leaveDemo()`, `acknowledgeRecovery()`, `clearAppData()` |
| `Session` | `…core.data.session` | `NoAccount`, `Demo`, `LoggedIn(account, serverVersion, compatibility)`, `RecoveryNeeded(reasons)` |
| `TokenRevocation` | `…core.data.session` | Vad som hände med enhetens token vid utloggning |
| `ServerInfo`, `ServerCompatibility` | `…core.data.server` | Resultatet av en sondering (bas-URL med `/` på slutet, version, kompatibilitet) |
| `AppearanceRepository` | `…core.data.settings` | `themeMode: Flow<ThemeMode>`, `setThemeMode(mode)` (Mer → Tema) |
| `DataProblem`, `TandemError.dataProblem()` | `…core.data` | Fel från datalagret själv (lagring, inget space, för gammal server, inget konto, inställning ej sparad) |
| `TandoorRemote` | `…core.data.remote` | Serveranropen i datalagrets egna typer; fejkas i tester |

Hilt: `DataModule` binder `AccountRepository` och `SessionRepository` till samma
`@Singleton` (`DefaultAccountRepository`), `AppearanceRepository` till
`DefaultAppearanceRepository` och `TandoorRemote` till `NetworkTandoorRemote`.

## Regler

- **Ingen adress i koden.** Serveradressen kommer alltid från användaren (plan §4.5).
- **Lösenordet sparas aldrig.** Det skickas i ett enda anrop; bara enhetens egen token sparas,
  krypterad i `SecureAccountStore` (plan §3.1, §5.5).
- **Inga automatiska omförsök** av inloggningen (10 anrop per dag och IP, misslyckade inräknade).
- **För gammal server** (< 2.0.0) avvisas innan något anrop görs (`DataProblem.SERVER_TOO_OLD`).
  2.0.0–2.5.x (`UNTESTED_OLDER`) och ≥ 2.7 (`UNTESTED_NEWER`) får logga in men körs i säkert läge.
- **Tokenns ursprung** sparas på kontot (`TokenOrigin.CREATED` / `PASTED`). Bara en token som
  appen själv skapat återkallas: om kontot inte kan sparas (inget space, lagringsfel), när ett nytt
  inlogg ersätter kontot, och vid utloggning. En inklistrad token tillhör användaren och
  återkallas aldrig av appen (`TokenRevocation.NOT_OWNED`). Konton sparade innan fältet fanns
  räknas som inklistrade.
- **Återkalla först när inget lokalt pekar på tokenen.** Misslyckas inställningsskrivningen efter
  att kontot sparats rullas kontot tillbaka innan tokenen återkallas; misslyckas även det behålls
  tokenen. Vid utloggning tas kontot bort lokalt först; går det inte återkallas ingenting.
  Städanrop körs `NonCancellable`, så att en stängd skärm inte lämnar en giltig token kvar.
- **Samma användare på samma server** ersätter det befintliga kontot (samma lokala id).
- **Sessionen:** återställning går före allt, sedan demoläge, sedan aktivt konto. Ett
  `activeAccountId` som pekar på ett konto som inte finns faller tillbaka på det första kontot.
- **Utloggning:** loggar ut lokalt och återkallar sedan (valfritt) bara enhetens egen token, även
  om servern inte går att nå. Resultatet (`TokenRevocation`) visas för användaren. Ett 401 från en
  proxy framför Tandoor räknas som misslyckat (`FAILED`), inte som redan ogiltig.

## Tester

`DefaultAccountRepositoryTest` (fejkad `TandoorRemote`, `SecureAccountStore` och
`SettingsStore`) och `NetworkMappingTest` (mappning från `core:network`). Vanliga JVM-tester,
ingen Robolectric.
