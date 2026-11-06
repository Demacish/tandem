# Snabb push för inköpslistan / Fast push for the shopping list

*Svenska först, English below.*

## Svenska

Tandem behöver **ingen** extra tjänst. Utan den kontrollerar appen själv listan i bakgrunden
(Inställningar → Synk och lagring → "Notis när någon ändrar listan", var 15:e minut som snabbast).
Den här valfria följeslagartjänsten gör att en vara som någon i hushållet lägger till syns som
notis inom en minut, även när appen är stängd. Den är **experimentell** och avstängd i appen tills
du själv slår på den.

### Så fungerar det

1. Tjänsten (`push-service/`, en liten Go-binär i en container, ca 10 MB) kontrollerar
   inköpslistan i **ett** hushåll var 30–60:e sekund med en egen Tandoor-token. Den läser bara
   antal poster (`page_size=1`, `updated_after`), aldrig vad som står på listan.
2. När något ändrats skickar den en **väckning** till hushållets telefoner via
   [UnifiedPush](https://unifiedpush.org/) (t.ex. appen ntfy). Väckningen innehåller bara
   `{"tandem":"sync","id":"<slumpat id>"}`, krypterad enligt RFC 8291 när telefonen skickat sina
   nycklar. Distributören ser alltså inget om listan.
3. Telefonen synkar listan som vanligt och visar **sin egen** notis ("Anna lade till mjölk").

### Köra tjänsten

Imagen publiceras som `ghcr.io/demacish/tandem-push` när en version taggas (den första med
versionen 0.6.0; tills dess finns den inte att hämta). Exempel med Docker Compose:

```yaml
services:
  tandem-push:
    image: ghcr.io/demacish/tandem-push:<version>
    restart: unless-stopped
    environment:
      TANDOOR_URL: https://recept.exempel.se
      TANDOOR_TOKEN_FILE: /run/secrets/tandoor_token
      POLL_SECONDS: "45"
    secrets: [tandoor_token]
    volumes: [tandem-push-data:/data]
    ports: ["127.0.0.1:8080:8080"]   # lägg en HTTPS-proxy framför
secrets:
  tandoor_token:
    file: ./tandoor_token.txt
volumes:
  tandem-push-data:
```

| Variabel | Standard | Betydelse |
|---|---|---|
| `TANDOOR_URL` | – (krävs) | Tandoor-serverns adress |
| `TANDOOR_TOKEN` / `TANDOOR_TOKEN_FILE` | – (krävs) | En API-token för en användare i hushållet (läsrätt räcker). Skapa den i Tandoor under Inställningar → API |
| `POLL_SECONDS` | 45 | Hur ofta listan kontrolleras (30–600) |
| `LISTEN_ADDR` | `:8080` | Var tjänstens API lyssnar |
| `DATA_DIR` | `/data` | Där `registrations.json` sparas |
| `ALLOW_HTTP_ENDPOINTS` | `false` | Tillåt `http://`-adresser för väckningar (bara för test eller ett lokalt ntfy) |
| `MAX_ENDPOINTS` | 20 | Högsta antal telefoner |
| `REGISTRATION_TTL_DAYS` | 60 | Telefoner som inte hörts av på så länge glöms |
| `TEST_PUSH` | `true` | Tillåt "Skicka testnotis" från appen |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

En tjänst = ett hushåll (det som tokenens användare tillhör). Har du flera hushåll kör du en
container per hushåll. Lägg tjänsten bakom HTTPS (Caddy, nginx, Traefik …): appen godtar bara
`https://`.

### I appen

Mer → Synk och lagring → **Snabb push (egen tjänst, experimentell)**: ange tjänstens adress, välj
distributör (t.ex. ntfy, installera den först), tryck **Slå på**. "Skicka testnotis" visar en
notis om allt fungerar. Gäster och demoläget kan inte använda funktionen.

### Säkerhet och data

- **Registrering:** appen skickar sin UnifiedPush-adress (och sina Web Push-nycklar) med **sin egen
  Tandoor-token**. Tjänsten frågar Tandoor en gång (`/api/user-space/all_personal/`) vilket space och
  hushåll token hör till, jämför med sitt eget hushåll och **glömmer token direkt**. Gäster och andra
  hushåll nekas. Inga lösenord, inga tokens sparas. Token skickas när du slår på snabb push och när
  väckningsadressen ändras; vid varje appstart förnyas registreringen i stället med dess slumpade id,
  utan token. Det betyder att medlemskapet inte kontrolleras om vid förnyelsen: den som tas bort ur
  hushållet kan få tomma väckningar tills telefonen avregistreras eller glöms.
- **Väckningar:** adressen är ingen hemlighet (distributörens server känner den). Appen bryr sig
  därför bara om en väckning som går att dekryptera och som bär registreringens eget id, och läser
  listan högst var 25:e sekund. Annat som skickas till adressen gör ingenting.
- **Sparat per telefon:** väckningsadressen, dess publika nycklar, ett slumpat id, hushållets id och
  när telefonen senast registrerade sig. Inget annat. Filen skrivs med rättigheterna `600`.
- **Avregistrering:** med registreringens id (appen har det) eller en giltig token. Appen
  avregistrerar sig när du stänger av snabb push, byter konto eller loggar ut. Lyckas det inte (inget
  nät) glöms telefonen efter `REGISTRATION_TTL_DAYS` eller när distributören svarar 404/410.
- **Gränser:** en kontroll per intervall och hushåll, aldrig när ingen telefon är registrerad;
  vid fel väntar den dubbelt så länge varje gång (högst 15 min), vid 429 minst `Retry-After`, vid
  en avvisad token 15 min. API:t har en gräns per klient-IP och testnotiser högst var 10:e sekund.
- **Loggar:** strukturerade JSON-rader utan tokens, utan listinnehåll och med bara värdnamnet för
  väckningsadresser.

## English

Tandem needs **no** extra service: without it the app checks the list in the background itself.
This optional companion makes a household member's new item arrive as a notification within a
minute even when the app is closed. It is **experimental** and off in the app until you turn it on.

**How it works:** the service (`push-service/`, a small Go binary in a ~10 MB container) polls one
household's shopping list every 30–60 s with its own Tandoor token, reading only counts
(`page_size=1`, `updated_after`). When something changed it sends a **wake-up** to the household's
phones through [UnifiedPush](https://unifiedpush.org/) (e.g. the ntfy app). The wake-up is only
`{"tandem":"sync","id":"<random id>"}`, encrypted with RFC 8291 when the phone sent its keys. The
phone then syncs and shows its own notification.

**Running it:** see the Compose example and the variables in the Swedish table above
(`TANDOOR_URL`, `TANDOOR_TOKEN`/`TANDOOR_TOKEN_FILE` required; `POLL_SECONDS` 30–600, default 45;
`LISTEN_ADDR`, `DATA_DIR`, `ALLOW_HTTP_ENDPOINTS`, `MAX_ENDPOINTS`, `REGISTRATION_TTL_DAYS`,
`TEST_PUSH`, `LOG_LEVEL`). One container serves one household (the token user's). Put it behind
HTTPS: the app only accepts `https://`. The image is published as
`ghcr.io/demacish/tandem-push` when a version is tagged (the first with version 0.6.0; until then
it cannot be pulled).

**In the app:** More → Sync and storage → **Fast push (own service, experimental)**: enter the
service URL, pick a distributor, tap **Turn on**, then "Send test notification".

**Security and data:** a phone registers with its own Tandoor token, which the service uses once to
ask Tandoor for the space and household and then forgets; guests and other households are refused.
The token is sent when you turn fast push on and when the push address changes; every app start
renews the registration with its random id instead, without the token (so the membership is not
checked again then: someone removed from the household may get empty wake-ups until the phone
unregisters or is forgotten). The address is no secret, so the app only acts on a wake-up it can
decrypt and that carries the registration's own id, and reads the list at most every 25 seconds.
Per phone the service keeps the endpoint, its public Web Push keys, a random id, the household id
and when it was last seen; nothing else. Unregistering needs the registration id or a valid token;
the app unregisters when you turn fast push off, switch accounts or log out, and a phone that could
not reach the service then is forgotten after `REGISTRATION_TTL_DAYS` or on a 404/410 from its
distributor.
Polling happens once per interval and never without registered phones, backs off exponentially on
errors (at most 15 min, at least `Retry-After` on 429). Logs are JSON lines without tokens, list
content or endpoint paths.

### API (version 1)

| Request | Body | Answer |
|---|---|---|
| `GET /healthz` | – | `200 {healthy, last_wake, phones}` |
| `GET /v1/info` | – | `200 {service: "tandem-push", version, api: 1, test, encryption, poll_seconds}` |
| `POST /v1/register` + `Authorization: Bearer <Tandoor token>` | `{endpoint, p256dh?, auth?}` | `200 {id, poll_seconds, test}`; `400 invalid_endpoint/invalid_keys`, `401 unauthorized`, `403 guest/other_household`, `409 too_many`, `429 rate_limited`, `502 tandoor_unavailable` |
| `POST /v1/register` (renewal, no token) | `{endpoint, id, p256dh?, auth?}` | as above; `401 unauthorized` when the id does not match: the app then registers with the token |
| `POST /v1/unregister` | `{endpoint, id}` (or the token header instead of `id`) | `204` |
| `POST /v1/test` | `{endpoint, id}` | `204`; `404 not_registered/test_disabled`, `429`, `502 push_failed` |

Wake-up payload: `{"tandem":"sync"|"test","id":"<registration id>"}` (`Content-Encoding:
aes128gcm` when encrypted), headers `TTL: 300`, `Urgency: high`.
