# Observerade fakta: Tandoor 2.7.1 (skillnader mot 2.6.13)

> **Källa:** `Phase0ContractTest` och `Phase1ContractTest` i
> [`contract.yml`](../../../.github/workflows/contract.yml), körda av
> [`tandoor-watch.yml`](../../../.github/workflows/tandoor-watch.yml) mot en tillfällig
> `vabene1111/recipes:2.7.1` (och `latest`, som var 2.7.1) + Postgres 16 i GitHub Actions, aldrig
> produktion. Inspelad 2026-10-25, samma serverinställningar som 2.6.13.
> **[O]** = observerat, **[K]** = läst i Tandoors källkod (tagg 2.7.0/2.7.1), **[S]** = slutsats
> för appen. Allt som inte står här är som i [`../2.6.13/FACTS.md`](../2.6.13/FACTS.md); varje
> skillnad i `observed-facts.json` mot 2.6.13 står också, granskad, i
> [`contract-tests/watch/reviewed-changes.json`](../../../contract-tests/watch/reviewed-changes.json).

## Filerna

- Samma upplägg som 2.6.13: `NN-<namn>.json` (Fas 0, numrerade i den ordning 2.7-grenen anropar),
  `fas1/<namn>.json`, `observed-facts.json` på båda nivåerna. `scaling-golden.json` saknas:
  webbens skalningstabell för 2.7.1 var **identisk** med `../2.6.13/fas1/scaling-golden.json`. [O]
- Saneringen som i 2.6.13, plus: allauths `session_token`, headern `X-Session-Token` och
  användarens `email` blir `<redacted>`. `…0001` är här **enhetstoken** (det finns ingen login-token).

## Inloggning: `api-token-auth` är borta

- `POST /api-token-auth/` → **302** till `/account/login/?next=/api-token-auth/` (HTML). [O]
  Uppströms commit "deprecate old token view" (2.7.0). [K]
- I stället **allauth headless, app-klienten** (`HEADLESS_ONLY = True`) [K]:
  - `POST /_allauth/app/v1/auth/login` med JSON `{username, password}` → **200**
    `{"status": 200, "data": {"user": {id, display, email, has_usable_password, username}, "methods": […]},
    "meta": {"is_authenticated": true, "session_token": "<32 tecken>"}}`. [O] (`04-login-json`)
  - Fel lösenord → **400** `{"status": 400, "errors": [{"code": "username_password_mismatch", "param": "password", …}]}`;
    `{}` → **400** `errors` med `code: "required"`. [O]
  - `X-Session-Token: <session_token>` godtas av DRF (`XSessionTokenAuthentication` i
    `DEFAULT_AUTHENTICATION_CLASSES`) [K]: `POST /api/access-token/` med sessionen → **201**
    enhetstoken i klartext, som i 2.6. [O] (`07-access-token-create`)
  - `DELETE /_allauth/app/v1/auth/session` med sessionen → **401**
    `{"status": 401, "data": {"flows": [{"id": "login"}]}, "meta": {"is_authenticated": false}}`
    (allauths sätt att säga "utloggad"); sessionen ger sedan 403 + JSON, enhetstoken fungerar. [O]
  - Varje inloggning ger en **ny** session (ingen återanvändning som `api-token-auth`). [O]
  - **Spärr:** `ACCOUNT_RATE_LIMITS["login"] = "3/m/ip"`, varje försök räknas: det fjärde inom en
    minut → **429** `{"status": 429}` **utan `Retry-After`**. [O/K]
  - Ett inloggningsflöde som kräver ett steg till (e-postverifiering, andra faktor) svarar 401 med
    `data.flows` och `is_pending` (enligt allauths dokumentation, inte prövat i sviten).
- [S] `TandoorAuthenticator` väljer allauth från 2.7.0 (och när `api-token-auth` svarar
  omdirigering/404/405), skapar enhetstoken med sessionen och loggar ut sessionen direkt. 401 och
  404/405 från allauth = `PASSWORD_LOGIN_UNAVAILABLE` ("klistra in token i stället").

## Konto utan space

- Lösenordet godtas (200 och en session), men `ScopeMiddleware` nekar **alla** `/api/`-anrop för en
  användare utan aktiv UserSpace med **403** `{"detail": "Authentication credentials were not provided or access is forbidden."}`,
  även `POST /api/access-token/` och `GET /api/access-token/` (i 2.6 svarade de). [O/K]
- [S] Lösenordsinloggning: 403 + JSON på token-skapandet direkt efter en godkänd inloggning =
  "kontot har inget space". En inklistrad token för ett sådant konto går inte att skilja från en
  ogiltig (båda 403) och visas som "ogiltig token".

## Övrigt i Fas 0

- 403-texterna är nya ("…not provided or access is forbidden.", också med `Accept-Language: sv`);
  formen `{detail}` och statusen är desamma. [O] Appen matchar aldrig på texten.
- En **okänd `/api/`-väg** ger **200 med webbappens HTML** (2.6: 302 till `/accounts/login/`). [O]
  [S] HTML-vakten ger `NotTandoor(HTML_RESPONSE)`; kapabilitetssonderingen (bara för versioner som
  inte går att tolka) stannar där.
- `server-settings/current` har tre nya booleska fält: `email`, `enable_signup`,
  `socialaccount_only`. [O] Okända fält ignoreras.

## Fas 1

- Alla Fas 1-fakta är som i 2.6.13 (listor, filter, detaljer, mallar, bilder, `/media` utan
  inloggning, matsedel, böcker, privata recept, radering, stegfiler) utom: [O]
- **`CookLog.servings` är en DecimalField** (`max_digits=8, decimal_places=4`) [K]: svaret har
  `"servings": 8.0` (2.6: `8`). [O] (`fas1/cook-log-create.json`)
  [S] `CookLogDto.servings` är `Double?`; raden i `cook_log` avrundas (halva uppåt, minst 1) som
  "Klart!" gör. Appen skickar fortfarande heltal.

## Tandoor 2.7.0 (inte testad med flit)

- 2.7.0 hade `SessionAuthentication` **före** `OAuth2Authentication` [K]: varje skrivning med
  `Authorization: Bearer` nekas med **403** `{"detail": "CSRF Failed: CSRF cookie not set."}`
  (skapa token, radera token; läsningar fungerar). [O] 2.7.1 rättade ordningen ("fixed default
  auth classes for DRF"). [K]
- [S] 2.7.0 står **inte** i `TandoorVersionTable.TESTED`: appen kör den i säkert läge (outboxen
  pausad), så "Klart!" och visningar väntar tills servern uppgraderas.
