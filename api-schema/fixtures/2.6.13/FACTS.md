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

---

# Fas 1: recept, sök, bilder, loggar, matsedel och skalning

> **Källa:** `Phase1ContractTest` (+ `contract-tests/web-scaling/golden.mjs`) i
> [`contract.yml`](../../../.github/workflows/contract.yml) mot samma tillfälliga 2.6.13-server,
> **efter** Fas 0-testet. Inspelad 2026-10-11 (körning 38094137329). Filerna ligger i
> [`fas1/`](fas1/). Testet asserterar raderna nedan ("mjukt": alla avvikelser listas tillsammans i
> sista steget `everyFactMatchesTheBaseline`), så en Tandoor som beter sig annorlunda gör
> `contract.yml` röd. **[O]/[K]/[S]** som ovan.

## Fas 1: upplägg och sanering

- **Konton:** Fas 0 kräver ett konto utan tokens och förbrukar inloggningsbudgeten, så
  arbetsflödet skapar Fas 1-tokens (`read write`, 1 dygn) direkt i databasen efter Fas 0, plus en
  andra medlem i samma space (gruppen `user`) för kontrollen av privata recept. I inspelningarna:
  `tda_…0001` = testanvändaren (superuser, ägare av allt), `tda_…0002` = den andra medlemmen.
- **Data:** tio påhittade recept seedas via API:t ([`Fas1Seed.kt`](../../../contract-tests/src/test/kotlin/casa/moller/tandem/contract/Fas1Seed.kt)),
  plus `Pytt i panna` som arbetsflödet skapar i databasen med `created_at`/`updated_at` 30 dagar
  bakåt (API:t kan inte sätta dem) och ett tillfälligt recept som raderas.
  Recept-id: Pytt i panna 1, Krämig svamppasta 2, Pannkakor 3, Kanelbullar 4, Köttbullar med
  potatismos 5, Shakshuka 6, Ärtsoppa 7 (privat), Grönsakssoppa 8, Smörgåstårta 9, Äppelkaka 10,
  Rårakor med lingon 11, (Tillfälligt recept 12, raderat).
- **Omvänd proxy:** varje anrop skickar `Host: tandoor.example.com` och `X-Forwarded-Proto: https`
  (servern har värden i `ALLOWED_HOSTS`), så URL:er i svaren ser ut som bakom en TLS-proxy.
- **Datum flyttas** i stället för att nollas: körningsdagen blir **2000-01-15** och alla datum
  (även i sökvägar som `from_date=`) flyttas lika mycket, så "i dag", "i morgon", "−60 dagar"
  syns. Klockslag behålls om de är hela minuter (`18:00:00`), annars blir de `00:00:00(.000000)`.
- Filnamnen är stabila (`<namn>.json`, ingen numrering). Bara utvalda anrop spelas in; resten
  sammanfattas i [`fas1/observed-facts.json`](fas1/observed-facts.json).

## Fas 1: filerna i `fas1/`

| Fil | Visar |
|---|---|
| `seed-unit-create-burk.json`, `seed-unit-patch-burk.json` | `POST /api/unit/` ignorerar `plural_name`; `PATCH` (med `name`) sätter den |
| `seed-food-create.json` | `POST /api/food/` (plural behålls), hela Food-objektet |
| `seed-unit-conversion-create.json`, `-food.json`, `-food-null.json` | Omräkning 1 dl = 100 ml, 1 dl vetemjöl = 60 g; `food: null` ger 500 |
| `seed-keyword-create.json`, `seed-keyword-move.json` | Nyckelord och `PUT /api/keyword/{id}/move/{parent}/` |
| `seed-meal-type-create.json` | Måltidstyp med `time` |
| `seed-recipe-create.json` | `POST /api/recipe/` med nästlade steg/ingredienser (201-svaret, `food_properties: {}`) |
| `recipe-image-put.json` | `PUT /api/recipe/{id}/image/` multipart, svaret `{image, image_url}` |
| `media-image-get-no-auth.json` | `GET /media/recipes/…png` utan token: 200, headers (kropp ej sparad) |
| `recipe-flat.json` | `GET /api/recipe/flat/`: array `{id, name, image}`, **relativ** bild-sökväg |
| `recipe-list-page-1/2/3.json`, `recipe-list-page-out-of-range.json` | Paginering `page_size=4`, absoluta `next`/`previous`, 404 `Invalid page.` |
| `recipe-list-default.json` | `GET /api/recipe/` utan parametrar (RecipeOverview, sorterat på namn) |
| `recipe-list-query-svamp.json` | `query=` |
| `recipe-list-keywords-or-parent.json` | `keywords_or=<förälder>` tar med barn-nyckelord |
| `recipe-list-books-or.json` | `books_or=<bok>` |
| `recipe-list-sort-times-cooked.json` | `sort_order=-times_cooked` → **500** |
| `recipe-list-sort-last-cooked.json`, `recipe-list-sort-rating.json` | Sortering; `rating` ifyllt bara vid rating-sortering |
| `recipe-list-timescooked-gte.json` | `timescooked_gte=1` (alternativet till -times_cooked) |
| `recipe-list-cookedon-lte.json` | `cookedon_lte=<i dag − 60>` ("Inte lagat på länge") |
| `recipe-list-viewedon-gte.json` | `viewedon_gte=<i dag − 14>&sort_order=-lastviewed` ("Nyligen visade") |
| `recipe-list-num-recent.json` | `num_recent=16`: fältet `recent` |
| `recipe-list-new.json` | `new=true` sorterar nya först men **filtrerar inte** |
| `recipe-list-updatedon-gte-today.json`, `-datetime.json` | `updatedon_gte=yyyy-MM-dd` fungerar; med klockslag **500** |
| `recipe-<slug>.json` (11 st) | `GET /api/recipe/{id}/` för varje recept (källan till skalningstabellen) |
| `recipe-private-as-other-member.json` | Privat recept för en annan medlem: **403** |
| `step-patch.json`, `ingredient-patch.json`, `food-patch.json`, `recipe-patch.json` | Redigeringarna i "updated_at" nedan |
| `recipe-delete.json`, `recipe-deleted-get.json` | `DELETE` 204, sedan 404 `No Recipe matches the given query.` |
| `cook-log-create.json`, `cook-log-create-backdated.json`, `cook-log-list-recipe.json` | Koklogg: skapa, bakåtdatera, lista per recept |
| `view-log-create.json`, `view-log-create-again.json` | Visningslogg; samma id inom 5 min |
| `meal-plan-create-today.json`, `-tomorrow.json`, `-title-only.json`, `meal-plan-list.json` | Matsedel i dag/i morgon, standardtider, rad utan recept |
| `meal-type-list.json` | Måltidstyper |
| `recipe-book-create.json`, `recipe-book-entry-create.json`, `recipe-book-list.json`, `recipe-book-entry-list.json` | Receptböcker |
| `keyword-list.json`, `food-list.json`, `unit-list.json`, `unit-conversion-list.json` | Små tabeller (`page_size=100`) |
| `user-preference.json`, `space-current.json` | Som i Fas 0, nu med data |
| `recipe-step-file.json` | Recept med en PNG och en PDF som stegfiler (`file` = UserFileView), se "Fas 1 m" |
| `scaling-golden.json` | Webbens skalning/visning för varje recept, se "Skalning" |
| `observed-facts.json` | Maskinläsbar sammanfattning, `mismatches: []` |

## Fas 1 a. Enheter, livsmedel, omräkning, nyckelord

- `POST /api/unit/` **ignorerar `plural_name`**: svaret har `plural_name` = namnet (`burk`/`burk`)
  [O, K: `UnitSerializer.create` skriver över den]. `PATCH /api/unit/{id}/` sätter den, men bara
  om `name` skickas med (update läser `validated_data['name']`) [O/K]. Enheter som skapats i
  webben eller nästlat i ett recept har därför ofta `plural_name == name`. [S] Visa ändå
  `plural_name` som den är; det ger samma text.
- `POST /api/food/` behåller `plural_name`. [O] Nästlade `{"name": …}` i recept, omräkningar
  och matsedel **återanvänder** befintliga livsmedel/enheter/nyckelord (skiftlägesokänsligt). [O/K]
- `POST /api/unit-conversion/` med `"food": null` ger **500**; en generell omräkning måste
  **utelämna** nyckeln. [O, K] `name` blir `"1 dl  = 100 ml"` (två mellanslag). [O]
- Nyckelordens `parent` är skrivskyddat; trädet ändras med `PUT /api/keyword/{id}/move/{parent}/`
  (kräver en kropp, t.ex. `{}`) → 200 `{"msg": "Pasta was moved successfully to parent Huvudrätt"}`. [O]
  `GET /api/keyword/` listar alla nivåer; barnet har `parent: 1`, `full_name: "Huvudrätt > Pasta"`,
  föräldern `numchild: 1`. `?root=0` = bara rotnivån, `?root=<id>` = barnen. [O]
- Små tabeller (`keyword`, `food`, `unit`, `unit-conversion`, `meal-type`, `recipe-book`,
  `cook-log`, `meal-plan`) är DRF-paginerade med `{count, next, previous, timestamp, results}`. [O]

## Fas 1 b. `GET /api/recipe/flat/`

- **Opaginerad JSON-array** `[{id, name, image}]` med **alla** recept användaren får se, även egna
  privata. Sorterad på namn. [O]
- `image` är en **relativ sökväg** (`/media/recipes/<uuid>_<id>.png`) eller `null`, eftersom
  serializern saknar request-kontext [O, K]. [S] Gör den absolut mot kontots bas-URL.
- Ett raderat recept försvinner direkt ur `flat` (och ur listorna). [O] Andra medlemmar ser
  inte någon annans privata recept här. [O]

## Fas 1 c. `GET /api/recipe/` (listor, paginering, sök, filter)

- Svar: `{count, next, previous, results}`, **ingen `timestamp`**. Standard 25 per sida,
  `page_size` max 100 [K]. [O]
- `next`/`previous` är **absoluta** URL:er byggda av förfrågans `Host` och `X-Forwarded-Proto`:
  `https://tandoor.example.com/api/recipe/?page=2&page_size=4`; föregående sida 1 saknar `page`:
  `…/api/recipe/?page_size=4`. Utan `X-Forwarded-Proto: https` blir det `http://…`; direkt mot
  containern `http://127.0.0.1:8080/…`. [O] [S] Följ inte `next` blint: plocka `page` och
  bygg URL:en mot kontots bas-URL (proxyn kan ge fel schema/värd).
- Sida bortom sista: **404** `{"detail": "Invalid page."}`. [O]
- Standardordning: namn, via databasens sortering (`Lower(name)`); i testservern (Postgres
  alpine) hamnar `Äppelkaka`, `Ärtsoppa` sist och `Krämig` före `Köttbullar`. [O] [S] Sortera
  lokalt med svensk Collator.
- **RecipeOverview** (fält): `id, name, description, image (absolut), keywords [{id, label}],
  working_time, waiting_time, created_by (User), created_at, updated_at, internal, private,
  servings, servings_text, rating, last_cooked, new` + `recent` **bara** när `num_recent` skickas. [O]
  - `rating` är `null` i listor **utom** vid `sort_order=±rating` (då användarens snittbetyg,
    avrundat: 5.0/4.0/3.0). [O]
  - `last_cooked` är **alltid `null`** i 2.6.13 (lista och detalj), även med
    `sort_order=-lastcooked` [O; K: annoteringen heter `lastcooked`]. [S] "Senast lagad" räknas
    lokalt från `cook-log`.
  - `new` = skapad för mindre än 7 dagar sedan. [O/K]
- `query=` är fuzzy (trigram) på namnet med standardinställningarna: `kottbullar` gav
  `Köttbullar med potatismos` **och** `Kanelbullar`; `ÄRTSOPPA` gav `Ärtsoppa` och
  `Grönsakssoppa`; `champinjon` (bara ingrediens) gav inget. [O]
- `keywords_or=<id>` tar med barn-nyckelord (`include_children` är `true` som standard);
  `include_children=false` stänger av det. [O] `books_or=<bok-id>` fungerar likadant. [O]
- `foods_and=<id>` (upprepas per livsmedel) kräver en ingrediens av **varje** livsmedel:
  `gul lök` gav svamppastan, köttbullarna och ärtsoppan, `gul lök` + `potatis` bara köttbullarna.
  Appens livsmedelsfilter (plan 6.2) använder det. [O]
- Sortering: `sort_order=-lastcooked` och `-rating` fungerar (null sist). **`sort_order=-times_cooked`
  ger 500** (HTML) trots att webben erbjuder den [O; K: ingen sådan annotering]. Använd
  `timescooked_gte=1&sort_order=-lastcooked` för "lagade" och räkna antal lokalt. [S]
- `cookedon_lte=<datum>` jämför senaste kokloggens **datum**; aldrig lagade recept kommer inte
  med. `viewedon_gte=<datum>&sort_order=-lastviewed` ger visade recept, senaste först. [O]
- `num_recent=n` sätter `recent` = id för senaste visningsloggen (sträng, `"0"` = inte bland
  de n senast visade) och lägger dem först. [O] (Webbens "Nyligen visade".)
- **`new=true` filtrerar inte**: alla recept kommer, nya (<7 dagar) först, sedan namn; det
  gamla receptet hade `new: false` och kom sist. [O] Webben filtrerar på `new` i klienten. [K]
- **`updatedon_gte` vill ha `yyyy-MM-dd`** och jämför `updated_at::date` (serverns tidszon):
  i dag → 11 (allt utom det 30 dagar gamla), i går → 11, i morgon → 0. Med klockslag
  (`…T00:00:00`) blir det **500**. [O]

## Fas 1 d. `GET /api/recipe/{id}/`

- Toppfält: `id, name, description, image (absolut), keywords (hela Keyword-objekt), steps,
  working_time, waiting_time, created_by, created_at, updated_at, source_url, internal,
  show_ingredient_overview, nutrition (null), properties ([]), food_properties ({} utan
  egenskaper), servings (heltal), file_path, servings_text, diameter, diameter_text, rating,
  last_cooked (null, se ovan), private, shared ([User])`. [O]
- `rating` i detaljen = **den inloggades** snittbetyg från kokloggen (Äppelkaka 5,4,5 → 5.0;
  Svamppasta 4 + inget betyg → 4.0); en annan medlem får `null`. [O]
- **Steg**: `id, name, instruction (rå Markdown + Jinja), instructions_markdown (färdig HTML),
  ingredients, time (min), order, show_as_header, show_ingredients_table, file, step_recipe,
  step_recipe_data`. [O]
- **Ingrediens**: `id, food (helt Food-objekt), unit ({id, name, plural_name, description,
  base_unit, open_data_slug}), amount, conversions, note, order, is_header, no_amount,
  original_text, used_in_recipes [{id, name}], checked (alltid false)`. [O]
  - Rubrik: `is_header: true`, `food`/`unit` `null`, `amount: 0.0`, texten i `note`. [O]
  - `no_amount: true`: `amount: 0.0`, `unit` oftast `null`. [O]
  - `amount` avrundas till **4 decimaler** i JSON (2/3 → `0.6667`, `0.333` → `0.333`), men
    lagras med 16 [K]; `"1,5"` som sträng accepteras (→ `1.5`). [O]
  - `conversions` (serverberäknat, bara när både `unit` och `food` finns, annars `[]`):
    **första posten är ingrediensen själv**, sedan egna omräkningar (även i flera steg), sedan
    alla basenheter i spacet som går att nå (`base_unit` g/kg/ml/l/tbsp/tsp/us_cup …). Formen är
    `{food: "<namn>", unit: "<namn>", amount}` (inga id). Ex. 2,5 dl vetemjöl →
    `dl 2.5, ml 250, g 150, cup 1.0566875, l 0.25, msk 16.907, tsk 50.721, kg 0.15`. [O/K]
    Webben visar inte `conversions` i receptvyn i 2.6.13 (bara för näringsberäkning). [K]
- **`instructions_markdown`** (servern renderar, i ordning): bleach → Markdown (`<p>`, `<br>`,
  `<em>`, tabeller) → Jinja (sandlåda) → bleach som tillåter två egna taggar → alla kvarvarande
  `{`/`}` tas bort. [K, O] Mallsyntax som stöds [K/O]:
  - `{{ scale(100) }}` → `<scalable-number v-bind:number="100.0" v-bind:factor="ingredient_factor"></scalable-number>`
    (talet som Python-float med full precision, t.ex. `0.6666666666666666`, medan
    ingrediensens `amount` i JSON är `0.6667`).
  - `{{ ingredients[i] }}` (stegets ingredienser i `order`) → `<scalable-number …mängd…> enhet <plural-name …>`:
    mängden skalbar, **enheten färdig text** (plural om `amount != 1`, skalas inte om), livsmedlet
    en `<plural-name singular="champinjon" plural="champinjoner" v-bind:amount="250.0" v-bind:factor="ingredient_factor" :no-amount="false"></plural-name>`
    eller ren text om livsmedlet saknar plural. `no_amount` ger ingen mängd.
  - `{{ ingredients[i].amount }}`, `.unit`, `.food`, `.note` var för sig (samma delar).
  - Attribut står inom **dubbla citattecken** efter bleach. [O]
  - Trasig mall (`{{ scale( }}`): hela `instructions_markdown` blir texten
    `Could not parse template code. Error: Template Syntax broken` (ingen HTML). [O] [S] Visa då
    `instruction` som vanlig Markdown.
- `show_as_header` påverkar bara webbens stegöversikt (`<b>1. Förbered</b>`);
  `show_ingredients_table` avgör om ingredienstabellen visas i steget. [K]

## Fas 1 e. Bilder och `/media`

- `PUT /api/recipe/{id}/image/` med multipart-fältet `image` (PNG 8×8 px) → **200**
  `{"image": "/media/recipes/<uuid>_<id>.png", "image_url": null}` – **relativ** sökväg i svaret. [O]
  Filnamnet byts vid varje uppladdning (UUID). [O/K]
- I listor, detalj, matsedel och böcker är `image` **absolut**: `https://tandoor.example.com/media/recipes/…png`,
  byggd av `Host` + `X-Forwarded-Proto` (utan https-headern `http://…`). [O]
- `GET /media/…` **kräver ingen inloggning** (nginx i containern serverar filen): 200
  `image/png`, `ETag`, `Last-Modified`, `Accept-Ranges: bytes`, **`Content-Disposition: attachment; filename=""`**,
  ingen `Cache-Control`. Med token: också 200. Saknad fil: 404 `text/html`. [O]
  [S] Bild-URL:er är oföränderliga (UUID); cacha länge, skicka gärna token ändå (plan §3.4),
  ignorera `Content-Disposition`. Bygg om absoluta URL:er till kontots bas-URL om värden skiljer.

## Fas 1 f. Koklogg och visningslogg

- `POST /api/cook-log/` `{recipe, servings, rating, comment}` → 201
  `{id, recipe, servings, rating, comment, created_by (User), created_at, updated_at}`.
  **`created_at` är skrivbart** (bakåtdatering fungerade, `1999-10-17T18:00:00+02:00` tillbaka). [O]
- `GET /api/cook-log/?recipe=<id>` → paginerat, **hela spacets** loggar för receptet (inte bara
  egna) [K], här 3. [O]
- Appens kropp (Fas 1b: `{recipe, servings, rating, comment, created_at}`, `created_at` i UTC med
  hela sekunder, `...Z`, tre dagar bakåt) → 201; `created_at` behålls som samma tidpunkt och
  listas med samma tidpunkt och författare. [O] Ett **raderat recept** ger **400**
  `{"recipe": ["Invalid pk \"<id>\" - object does not exist."]}`, inte 404. [O]
  [S] Utkorgen släpper posten vid 400 på `recipe` (som vid 404); annan 400 blir FAILED.
- `POST /api/view-log/` `{recipe}` → **201** `{id, recipe, created_by (id), created_at}`.
  Ett nytt anrop för samma recept inom 5 minuter ger **samma id** (och 201). [O/K]
  [S] Outboxen kan skicka visningar utan egen spärr; servern slår ihop dem.

## Fas 1 g. Matsedel och måltidstyper

- Fungerar med en vanlig `read write`-token (scope `mealplan` behövs inte för API:t). [O]
- `POST /api/meal-plan/` med `recipe: {id, name}`, `meal_type: {id, name}`, `servings`,
  `title`, `note`, `from_date` → 201. [O]
  - `from_date` **utan tid/offset vid midnatt** (`2026-10-11T00:00:00`) får måltidstypens `time`
    (`18:00:00`), annars **12:00**. Ett explicit klockslag behålls (`19:30:00+02:00`). [O/K]
  - `to_date` utelämnad = samma som `from_date`. [O]
  - Datum kommer tillbaka i serverns tidszon med offset, utan decimaler: `2000-01-15T18:00:00+02:00`. [O]
- `GET /api/meal-plan/?from_date=yyyy-MM-dd&to_date=yyyy-MM-dd` filtrerar på **`to_date`:s
  datum** (båda gränser inklusive): i dag + i morgon → 3, bara i dag → 1. Utan parametrar:
  −90 till +360 dagar. [O/K] Paginerat med `timestamp`.
- Matsedelspost: `id, title, recipe (RecipeOverview eller null), servings (decimal, `4.0`),
  note, note_markdown (HTML), from_date, to_date, meal_type (helt objekt), created_by (id),
  recipe_name, meal_type_name, shopping`. **`recipe_name` saknas helt** när posten saknar recept. [O]
- `MealType`: `{id, name, order, time ("18:00:00" eller null), color, created_by}`. [O]

## Fas 1 h. Receptböcker

- `POST /api/recipe-book/` kräver `shared: []` → 201. `POST /api/recipe-book-entry/` `{book, recipe}` → 201
  (med `book_content` och `recipe_content`). [O]
- `GET /api/recipe-book/` och `GET /api/recipe-book-entry/?book=<id>` är paginerade och visar
  bara egna och delade böcker; en annan medlem såg 0. [O]

## Fas 1 i. Privata recept

- Ägaren ser sitt privata recept överallt. En annan medlem i samma space: inte i `flat`
  eller listor (11 i st.f. 12), och **`GET /api/recipe/{id}/` → 403**
  `{"detail": "You do not have permission to perform this action."}` (inte 404). [O]

## Fas 1 j. Vad ändrar `Recipe.updated_at` (plan §3.5)

| Ändring | Ändrar receptets `updated_at`? |
|---|---|
| `PATCH /api/step/{id}/` (instruktion) | **nej** [O] |
| `PATCH /api/ingredient/{id}/` (mängd) | **nej** [O] |
| `PATCH /api/food/{id}/` (namnbyte) | **nej** [O] |
| `PATCH /api/recipe/{id}/` (beskrivning) | ja [O]; stegen behölls (samma id) när `steps` inte skickades |

[S] Planens stale-while-revalidate behövs: `updatedon_gte` missar sådana ändringar.

## Fas 1 k. Radering

- `DELETE /api/recipe/{id}/` → **204**, tom kropp. Därefter: borta ur `flat` och listor,
  `GET` → **404** `{"detail": "No Recipe matches the given query."}` (samma som ett id som aldrig
  funnits), ett nytt `DELETE` → 404. [O] Inga gravstenar: bara `flat`-jämförelsen visar raderingen.

## Fas 1 l. Skalning och visning i webben (plan §10 Fas 1, `scaling-golden.json`)

Hur Tandoor 2.6.13:s webb (vue3) visar mängder [K: `vue3/src/utils/number_utils.ts`,
`model_utils.ts`, `components/display/IngredientsTable.vue`, `RecipeView.vue`,
`ScalableNumber.vue`, `PluralName.vue`; körd i CI, se Verifiering]:

1. **Faktor** = `valda portioner / max(recipe.servings, 1)` (JavaScript-double). Portionerna
   börjar på `recipe.servings`.
2. **Mängden som visas** är `amount × faktor` (`x`):
   - **Bråk av** (`use_fractions: false`, standard): `Number(x.toFixed(ingredient_decimals))` som
     JavaScript skriver talet: `toFixed` avrundar det **exakta binära värdet** halvt uppåt
     (`0.125` → `0.13`, `1.005` → `1`), utan avslutande nollor (`2.50` → `2.5`, `100`), **alltid
     punkt** som decimaltecken (ingen lokal formatering).
   - **Bråk på**: närmaste bråk med nämnare ≤ 16 via Stern–Brocot-sökning mellan ⌊x⌋ och ⌊x⌋+1
     (algoritmen i npm-paketet `frac`, Apache-2.0), som blandat tal `[heltal, täljare, nämnare]`.
     HTML: heltalet (om > 0), sedan, om täljaren > 0, `" <sup>t</sup>&frasl;<sub>n</sub>"` (med
     inledande mellanslag): `1 <sup>1</sup>&frasl;<sub>2</sub>`, ` <sup>3</sup>&frasl;<sub>8</sub>`.
     Ger sökningen `[0, 0, 1]` (x under 1/32) används decimalvarianten. Heltal visas som `2`.
     Nämnarna kan bli udda: 13/12 portioner av 0,25 l gav `3⁄11`, av 0.6666… `5⁄7`.
   - `ingredient_decimals` (standard 2) används bara i decimalvarianten.
3. **Ingrediensrad** (IngredientsTable): rubrik (`is_header`) = bara `note` i fetstil. Annars:
   mängd och enhet visas bara om `!no_amount && amount != 0`; livsmedlet visas alltid.
   **Mängd 0 utan `no_amount`** ger alltså tom mängd och ingen enhet (men livsmedlet i plural).
   `note` visas som en ikon med tooltip.
4. **Plural** (enhet och livsmedel): singular om `no_amount`, om `plural_name` saknas/är tom,
   eller om `|amount × faktor − 1| < 0.0001`; annars `plural_name`. Alltså plural även för
   `1⁄2` och `0.25` (`1⁄4 gula lökar`).
5. **Mallar i instruktionerna:** varje `<scalable-number>` visar punkt 2 för sitt tal och
   faktorn; varje `<plural-name>` visar punkt 4 för `amount × faktor` (singular om
   `:no-amount="true"`). Enhetstexten från `{{ ingredients[i] }}` skalas **inte** om (fast i
   HTML:en). Den skalade texten markeras med CSS-klasser (upp/ned), som appen kan ignorera.

`scaling-golden.json` har för **varje** recept-fixtur (11 recept) och portionerna
`servings × {0.5, 1, 2, 3}`, `1`, `servings − 1`, `servings + 1` (unika, ≥ 1), med bråk av och på
(`ingredient_decimals` 2): per steg ingredientraderna (`header` eller `amount` (synlig text),
`amountHtml` (bara bråk på), `unit`, `food`, `note`), mallvärdena (`templates`: taggen, talet
eller singular/plural, `html`/`text`) och `instructionsHtml` (`instructions_markdown` med
taggarna ersatta av sin text, utan Vue-omslag). Synlig text = HTML utan taggar, `&frasl;` som
U+2044 `⁄`, blanktecken hopslagna och trimmade (`1 1⁄2`).

**Verifiering:**
- I `contract.yml` checkas TandoorRecipes/recipes ut vid **samma tagg** som imagen (gles:
  `vue3/src/utils`, `vue3/src/components/display`; aldrig incheckat), och
  `contract-tests/web-scaling/golden.mjs` kör webbens **egna** funktioner `roundDecimals`,
  `calculateFoodAmount`, `frac`, `isSingularAmount`, `pluralString`, `ingredientToUnitString`,
  `ingredientToFoodString` och `PluralName.resolve` (typerna strippas, körs i en Node-`vm`) på de
  nyss inspelade recepten. Mallreglerna ur `.vue`-filerna (villkoren i punkt 3, faktorn, hur
  `<scalable-number>`/`<plural-name>` anropar funktionerna) står i skriptet, som först kontrollerar
  att just de raderna finns ordagrant i källan. Resultatet jämförs med den incheckade tabellen;
  en skillnad gör jobbet rött (även för 2.6.15 i nattkörningen).
- I `pr.yml` räknar `ScalingGoldenTest` om hela tabellen från de incheckade
  `recipe-<slug>.json` med en **oberoende Kotlin-implementation** av punkterna ovan
  (`WebScalingReference`, skriven från beskrivningen) och kräver exakt likhet. Appens
  `:core:domain` ska testas mot samma fil.

## Fas 1 m. Stegfiler (kokläge, 0.2.0)

Inspelat 2026-10-25 (`Phase1ContractTest.stepWithAFile`, även 2.6.15 och 2.7.1 med samma fakta).

- `POST /api/user-file/` (multipart `name` + `file`, `.png` resp. `.pdf`) → **201**
  `{id, name, file_download, preview, file_size_kb, created_by, created_at}`. [O]
- Ett steg pekar på filen med `"file": {"id": <id>, "name": …}` i receptets `steps` (`UserFileView`
  är skrivskyddad: bara id:t används). Ett steg utan fil har `"file": null`. [O]
- I `GET /api/recipe/{id}/` är `steps[].file` en **UserFileView** med samma fält. `preview` är
  **bara ifylld för bilder** (PIL kan öppna filen): en **absolut** URL byggd av `Host` +
  `X-Forwarded-Proto`, `https://tandoor.example.com/media/files/<uuid>.png`; en PDF har
  `"preview": ""`. `file_download` är alltid absolut (`…/api/download-file/<id>/`). [O]
- `GET /media/files/…png` **utan token**: 200 `image/png` (som receptbilderna, "Fas 1 e").
  `file_download` utan token: **403**. [O]
- En fil som ett steg använder går inte att radera (`DELETE /api/user-file/{id}/` → 500
  `ProtectedError`), inte ens direkt efter att receptet raderats. [O]
  [S] Kokläget visar `preview` (via `MediaUrls.resolve`, som receptbilden) och aldrig
  `file_download` (inloggad nedladdning av valfri filtyp). Tom `preview` = ingen stegbild.

## Fas 1: konsekvenser för appen [S]

1. **Index:** `recipe/flat` (relativa bilder) för id-mängden och raderingar; detaljer via
   `recipe/{id}/`; ändringar via `updatedon_gte=yyyy-MM-dd` (aldrig med klockslag) minus en dag,
   plus stale-while-revalidate eftersom steg-/ingrediens-/livsmedelsändringar inte syns där.
2. **URL:er:** behandla `image`/`next` som opålitliga i värd och schema (proxyn bestämmer);
   gör relativa sökvägar absoluta och byt värd mot kontots bas-URL. `/media` kräver ingen token.
3. **Idag-sektioner:** "Ofta lagade" kan inte använda `-times_cooked` (500): använd
   `timescooked_gte=1&sort_order=-lastcooked` eller räkna lokalt från `cook-log`. "Inte lagat på
   länge": `cookedon_lte`. "Nyligen visade": `viewedon_gte` + `-lastviewed` eller `num_recent`.
   "Nya": `new=true` **och** filtrera på `new == true` i klienten.
4. **`last_cooked` är alltid null** och `rating` i listor bara vid rating-sortering: räkna
   senast lagad och betyg från kokloggen (detaljens `rating` går att lita på).
5. **Mallar:** rendera `instructions_markdown` och ersätt `<scalable-number>`/`<plural-name>`
   enligt skalningsreglerna; vid "Could not parse template code" visa `instruction`.
6. **Skalning/visning:** följ "Fas 1 l" exakt för webbparitet; svensk decimalkomma är ett
   medvetet appval ovanpå (webben skriver alltid punkt), testa paritet på siffrorna.
7. **Privata recept:** 403 på detaljen betyder "inte ditt privata recept" (inte "token ogiltig":
   kroppen är `You do not have permission…` och andra anrop fungerar).
8. **Matsedel:** läs `?from_date&to_date` (datum, filtrerar på `to_date`), tolka `from_date` i
   serverns offset; poster utan recept saknar `recipe_name`.
