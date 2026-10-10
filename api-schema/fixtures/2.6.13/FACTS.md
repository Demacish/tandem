# Observerade fakta: Tandoor 2.6.13

> **Källa:** `Phase0ContractTest` i [`contract.yml`](../../../.github/workflows/contract.yml) mot en
> tillfällig `vabene1111/recipes:2.6.13` + Postgres 16 i GitHub Actions (aldrig produktion).
> Inspelad 2026-10-09. Servern: `TZ=Europe/Stockholm`,
> `GUNICORN_WORKERS=1`, `DEBUG=0`, en superuser i ett eget space
> (`create_space_for_user`).
> **[O]** = observerat i körningen, **[K]** = läst i Tandoors källkod (tagg 2.6.13),
> **[S]** = slutsats för appen.
> Testerna asserterar de flesta raderna nedan, så en ny Tandoor-version som beter sig annorlunda
> gör `contract.yml` röd.

## Filerna

- `NN-<namn>.json`: varje anrop i ordning, `{name, request{method,path,headers,body}, response{status,headers,isJson,body|bodyText}}`.
- `observed-facts.json`: maskinläsbar sammanfattning (fälttyper per svar, statuskoder, flaggor).
- **Sanering** (görs av testet innan något skrivs):
  - Riktiga tokens blir fasta låtsasvärden i den ordning de dök upp:
    `tda_00000000_0000_0000_0000_000000000001` = login-token (från `api-token-auth`),
    `…0002` = enhetstoken, `…0003` = den utgångna testtoken. `…00000000dead` är en påhittad,
    aldrig utfärdad token.
  - Maskerade tokens behåller formen, med de 4 sista tecknen från låtsasvärdet.
  - Datum-tider behåller **exakt format** (decimaler, `Z`/offset) men får värdet
    `2000-01-01T00:00:00…`. Lösenord och användarnamn i anropen är `<password>`/`<username>`.
  - Multipart-boundary och throttle-sekunderna varierar mellan körningar; i övrigt är filerna stabila.

## a. `GET /api/server-settings/current/`

- **Ingen inloggning krävs.** 200, `application/json`. [O]
- Med en ogiltig `Authorization: Bearer …` blir det **också 200** (endpointen har inga
  permission-klasser och autentiseringen ger inte 403 när ingen permission kräver inloggning). [O]
  En probe kan alltså skicka med token utan risk.
- `version` = `"2.6.13"` (sträng). [O]
- Fält och JSON-typer [O]: `shopping_min_autosync_interval` **sträng** (`"5"`, inte tal),
  `disable_external_connectors` bool, `terms_url`/`privacy_url`/`imprint_url` sträng (tomma),
  `hosted` bool, `debug` bool, `version` sträng, `unauthenticated_theme_from_space` tal,
  `force_theme_from_space` tal, `logo_color_32|128|144|180|192|512|svg`, `custom_space_theme`,
  `nav_logo`, `nav_bg_color`: `null` på en ny server (schemat har fler fält än så; okända fält
  måste ignoreras).

## b. `POST /api-token-auth/`

- **JSON, form-urlencoded och multipart fungerar alla** (200). [O]
- Svar 200 [O]: `{"id": number, "token": "tda_…", "scope": "read write app", "expires": string, "user_id": number, "test": number}`.
  - `test` = användarens id (samma som `user_id`). Schemat dokumenterar bara `{token}`. [O/K]
  - `expires` = nu + 5 år (1824 dagar), **UTC med `Z` och 6 decimaler**:
    `2031-10-08T05:46:12.345678Z`-format. [O]
  - Token-format: `tda_` + UUID med `_` i stället för `-` (40 tecken). [O/K]
- **Återanvändning** [O, K: `CustomAuthToken`]: servern returnerar användarens **första
  (lägsta id) icke-utgångna token vars scope innehåller både `read` och `write`**. Bara om ingen
  sådan finns skapas en ny (`read write app`, 5 år).
  - Form- och multipart-inloggningarna efter JSON-inloggningen gav **samma token och id**. [O]
  - **Viktigt:** när login-token (id 1) hade raderats gav nästa lösenordsinloggning
    **enhetstoken (id 2, scope `read write`) i klartext**. [O] Se "Konsekvenser" nedan.
- Fel lösenord: **400** `{"non_field_errors": ["Unable to log in with provided credentials."]}`. [O]
- Saknade fält (`{}`): **400** `{"username": ["This field is required."], "password": ["This field is required."]}`. [O]
- Ingen av dem har `WWW-Authenticate`. Inga `Set-Cookie`. [O]
- **Throttle:** 10 anrop per dygn och klient-IP, **oavsett utfall** (lyckade, 400 och form
  räknas lika). Anrop 11 ger **429** `{"detail": "Request was throttled. Expected available in 86380 seconds."}`
  med headern **`Retry-After: 86380`** (sekunder). [O] Räknaren ligger i processminnet
  (LocMemCache) per gunicorn-worker [K]; med 1 worker är gränsen exakt 10.

## c. `POST /api/access-token/` (enhetstoken)

- Med login-token som Bearer, JSON `{"scope": "read write", "expires": "2027-10-09T05:46:13Z"}`:
  **201** [O] `{"id", "token", "expires", "scope", "created", "updated"}`.
  - `token` är **klartext** i svaret (`tda_…`). [O]
  - `expires` kommer tillbaka i **serverns tidszon utan decimaler** om den skickades utan:
    `2027-10-09T07:46:13+02:00`. `created`/`updated` har 6 decimaler och offset (`+02:00`). [O]
  - Med andra ord: datum från Tandoor kan vara `Z` eller `±hh:mm`, med eller utan decimaler.
    Tolka med en ISO-8601-parser som klarar alla (t.ex. `OffsetDateTime.parse`). [S]
- `GET /api/access-token/` är en **lista utan paginering** (JSON-array). [O]
  - Direkt efter skapandet visas båda tokens i klartext; **efter 16 s är alla maskerade**:
    `tda_************_******_***********` + de 4 sista tecknen. [O] (Regeln är "skapad för
    mindre än 15 s sedan", utom scope `bookmarklet`/`mealplan` som alltid visas [K].)
- En token med `expires` i det förflutna **går att skapa** (201) men avvisas sedan som alla
  ogiltiga tokens (403). [O]
- `DELETE /api/access-token/{id}/` → **204**, tom kropp, ingen `Content-Type`. En token kan
  radera sig själv. [O]

## d. Vem är inloggad (med enhetstoken)

- Det finns ingen `/api/user/me/`. [K/schema]
- `GET /api/user/{user_id}/` → 200 `{"id", "username", "first_name", "last_name", "display_name", "is_staff", "is_superuser", "is_active"}`. `display_name` = för- + efternamn, annars användarnamnet. [O/K]
- `GET /api/user-preference/` → 200, **JSON-array med exakt ett element** (den inloggades).
  Där är **`user` ett helt User-objekt** (samma fält som ovan), inte ett id. Övriga fält: se
  `observed-facts.json` → `userPreference.fieldTypes` (bl.a. `default_meal_type: null`,
  `shopping_auto_sync` tal, `food_inherit_default` array). [O] Det är vägen till "vem är jag"
  för en inklistrad token.
- `GET /api/space/current/` → 200, bl.a. `id` (1), `name`, `created_by` (User-objekt),
  `ai_enabled`, `ai_credits_balance`, `space_setup_completed` (false),
  **`household_setup_completed` (false på ett nytt space)**. [O]
- `GET /api/user-space/all_personal/` → 200, JSON-array; per element `{"id", "user" (objekt),
  "space" (id, tal), "groups": [{"id": 3, "name": "admin"}], "household": null, "active": true, "internal_note": null, "invite_link": null, "created_at", "updated_at"}`.
  **`household` är `null` när inget hushåll är satt.** [O] Endpointen finns **inte** i
  `used-endpoints.txt` än; nätverksspåret lägger till den om hushållskontrollen (plan §3.1)
  byggs.
- Login-token (`read write app`) fungerar också mot `/api/` (200). [O]

## e. Ogiltig, saknad, utgången och återkallad token

Alla fall nedan gav **exakt samma svar**, för både `GET /api/space/current/` och
`POST /api/access-token/` [O]:

| Fall | Status | Kropp | `WWW-Authenticate` |
|---|---|---|---|
| Ingen `Authorization` | **403** | `{"detail": "Authentication credentials were not provided."}` | saknas |
| `Bearer tda_…` som aldrig utfärdats | **403** | samma | saknas |
| `Bearer not-a-token` | **403** | samma | saknas |
| `Bearer` utan värde | **403** | samma | saknas |
| Utgången token | **403** | samma | saknas |
| Återkallad (raderad) token, GET och POST | **403** | samma | saknas |

- **Tandoor svarar aldrig 401** för token-fel; 403 + JSON med `detail` betyder "token saknas
  eller är ogiltig". Ett 401 kommer i så fall från något framför servern (proxy/SSO). [O/S]
- `Content-Type: application/json`. [O]
- **`detail` är översatt efter `Accept-Language`:** med `Accept-Language: sv` blev det
  `"Autentiseringsuppgifter ej tillhandahållna."` och `Content-Language: sv`. [O]
  Matcha aldrig på texten. [S]
- Andra headers på API-svar: `Vary: Accept, Accept-Language, Cookie`, `Allow: …`,
  `X-Frame-Options`, `Referrer-Policy`, `Cross-Origin-Opener-Policy`, `Server: nginx`. [O]

## f. Återkallning

- `DELETE /api/access-token/{login-token-id}/` med enhetstoken → 204. Login-token avvisas sedan
  (403 enligt tabellen) och **enhetstoken fungerar fortfarande** (200). [O]
- `DELETE /api/access-token/{enhetstoken-id}/` med enhetstoken själv → 204; därefter 403 på GET och POST. [O]

## g. 404 och okända sökvägar

- Okänt id på en befintlig resurs: **404** JSON, t.ex.
  `{"detail": "No Recipe matches the given query."}` (`/api/recipe/999999/`) och
  `{"detail": "No AccessToken matches the given query."}` (`/api/access-token/999999/`). [O]
- **Okänd sökväg under `/api/`** (`/api/this-endpoint-does-not-exist/`, med giltig token):
  **302** till `Location: /accounts/login/?next=/api/this-endpoint-does-not-exist/`, `text/html`,
  tom kropp. **Inte** en JSON-404. [O]

## h. Konto utan space

Användaren `contract-nospace` skapas med `User.objects.create_user` (ingen `UserSpace`), som en
användare en administratör lagt upp men som aldrig öppnat webbgränssnittet. Anropen sparas inte
som numrerade filer (det skulle numrera om baslinjen) utan saneras in i `observed-facts.json`
under `noSpace.*`. Observerat i körningarna 38009810699 och 38010760972 (2026-10-10).

- `POST /api-token-auth/` → 200 (`scope` `read write app`) och `POST /api/access-token/` → 201 som
  vanligt: inloggning och token kräver inget space. [O]
- `GET /api/access-token/` → 200 (ägarkontroll, inget space behövs). [O]
- `GET /api/space/current/` och `GET /api/user/{id}/` → **403** JSON
  `{"detail": "You do not have permission to perform this action."}`, alltså samma form som en
  ogiltig token. [O]
- `GET /api/user-preference/` → **500** (`text/html`, "Server Error (500)"). [O]
- `DELETE /api/access-token/{id}/` med tokenen själv → 204. [O]
- [S] Lösenordsinloggning läser därför `space/current` **före** användaren: 403 + JSON direkt
  efter att enhetstoken skapats betyder "kontot saknar space" (`DataProblem.NO_SPACE`, plan 6.2),
  inte "logga in igen", och `user-preference` frågas aldrig då. Appen återkallar sin nya token.
- [S] Inklistrad token: 403 på `space/current` kan vara en ogiltig token eller ett konto utan
  space. `GET /api/access-token/` skiljer dem åt (403 respektive 200). En inklistrad token lämnas
  alltid kvar på servern.

## Konsekvenser för appen (nätverks- och datalagret) [S]

1. **ErrorClassifier:** 403 med JSON-objekt som har `detail` ⇒ "token ogiltig" (stoppa synken,
   be om ny inloggning). 401 ⇒ också "token ogiltig", men från något framför Tandoor. 403 utan
   JSON ⇒ "blockerad av brandvägg/proxy" (plan §4.1). 404 med JSON `detail` ⇒ objektet finns inte.
   429 ⇒ läs `Retry-After` (sekunder) och försök inte igen automatiskt.
2. **Följ inte redirects på API-anrop** (`followRedirects(false)` för `/api/` och
   `/api-token-auth/`). En 302 till `/accounts/login/` betyder "ingen sådan endpoint" (fel
   version eller fel bas-sökväg, t.ex. Tandoor under en undermapp). Med OkHttps standard
   (följ redirects) skulle appen få 200 med en HTML-inloggningssida.
3. **ServerProbe:** `GET /api/server-settings/current/` utan token; kräv 200 + JSON + `version`.
   HTML eller redirect ⇒ "inte en Tandoor-server/fel adress".
4. **Inloggning:** skicka JSON. 400 ⇒ fel användarnamn/lösenord (`non_field_errors`) eller
   saknade fält (fältnamn som nycklar). Gör aldrig automatiska omförsök: alla anrop, även
   misslyckade, räknas mot 10/dygn.
5. **Plan §3.1 steg 4 ska inte byggas:** radera **aldrig** token som `api-token-auth` returnerar.
   Så länge login-token (lägst id med read+write) finns får alla lösenordsinloggningar den, och
   inga enhetstokens lämnas ut. Raderas den, lämnar nästa inloggning ut den äldsta kvarvarande
   read+write-token, t.ex. en annan telefons enhetstoken. Appen ska som planerat alltid skapa
   en egen enhetstoken direkt, glömma token från inloggningen och bara återkalla sin egen
   enhetstoken vid utloggning (steg 3 och 5 gäller oförändrat).
6. **Utgångsvarning:** `expires` och `id` finns i create-svaret. För inklistrade tokens fungerar
   matchning på de 4 sista tecknen mot den maskerade listan (formatet ovan).
7. **DTO:er:** `shopping_min_autosync_interval` är en sträng; `user-preference.user` är ett
   objekt; datum kan vara `Z` eller offset, med eller utan decimaler; många fält är `null` på
   en ny server. Tolerant JSON (plan §3.6) krävs.
