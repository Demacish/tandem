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

Imagen publiceras som `ghcr.io/demacish/tandem-push` för linux/amd64 och linux/arm64 när en version
taggas (den första med versionen 0.6.0; tills dess finns den inte att hämta). Taggen är versionen
utan `v`, till exempel `0.6.0`, eller `latest`.

1. **Skapa en token.** I Tandoor, Inställningar → API, för en användare i hushållet (läsrätt
   räcker). Spara den i `tandoor_token.txt` bredvid Compose-filen.
2. **Ge tjänsten rätt att läsa filen.** Tjänsten kör som användare 65532, och en Compose-hemlighet
   behåller filens rättigheter från värden. Annars stannar den med `TANDOOR_TOKEN_FILE cannot be
   read`:

   ```sh
   sudo chown 65532:65532 tandoor_token.txt
   sudo chmod 400 tandoor_token.txt
   ```

3. **Starta tjänsten** med `docker compose up -d`:

   ```yaml
   services:
     tandem-push:
       image: ghcr.io/demacish/tandem-push:0.6.0
       restart: unless-stopped
       environment:
         TANDOOR_URL: https://recept.exempel.se
         TANDOOR_TOKEN_FILE: /run/secrets/tandoor_token
         POLL_SECONDS: "45"
         TRUSTED_PROXIES: 172.16.0.0/12   # se steg 5
       secrets: [tandoor_token]
       volumes: [tandem-push-data:/data]
       ports: ["127.0.0.1:8080:8080"]
   secrets:
     tandoor_token:
       file: ./tandoor_token.txt
   volumes:
     tandem-push-data:
   ```

4. **Kontrollera att den svarar.** `docker compose logs tandem-push` ska visa `serving one
   household` och `listening`, och `curl http://127.0.0.1:8080/healthz` ska svara
   `{"healthy":true}`.
5. **Lägg HTTPS framför.** Appen godtar bara `https://`, och telefonen måste nå adressen där den
   befinner sig, med ett certifikat den litar på (t.ex. Let's Encrypt; ett självsignerat duger
   inte). Med Caddy räcker:

   ```text
   push.exempel.se {
       reverse_proxy 127.0.0.1:8080
   }
   ```

   Andra proxyer (nginx, Traefik …) går lika bra, men de ska skicka `X-Forwarded-For`. Sätt
   `TRUSTED_PROXIES` till proxyns adress så att gränsen i API:t gäller varje telefon för sig. Når
   proxyn containern genom en publicerad port ser tjänsten Dockers brygga som avsändare, så där
   anger du Dockers nät (`172.16.0.0/12` i exemplet). Kontrollera sedan utifrån:
   `curl https://push.exempel.se/v1/info` ska svara med `"service":"tandem-push"`.
6. **Slå på i appen** (se nedan) med adressen `https://push.exempel.se`.

| Variabel | Standard | Betydelse |
|---|---|---|
| `TANDOOR_URL` | – (krävs) | Tandoor-serverns slutliga adress, sedd från containern: tjänsten följer inga omdirigeringar, så `http://` till en server som skickar vidare till `https://` fungerar inte |
| `TANDOOR_TOKEN` / `TANDOOR_TOKEN_FILE` | – (krävs) | En API-token för en användare i hushållet (läsrätt räcker). Skapa den i Tandoor under Inställningar → API |
| `POLL_SECONDS` | 45 | Hur ofta listan kontrolleras (30–600) |
| `LISTEN_ADDR` | `:8080` | Var tjänstens API lyssnar |
| `DATA_DIR` | `/data` | Där `registrations.json` sparas |
| `TRUSTED_PROXIES` | – (av) | Proxyer vars `X-Forwarded-For` tjänsten litar på: IP-adresser eller CIDR-nät, kommaseparerade. Utan den delar alla klienter bakom en proxy på en gräns |
| `ALLOW_HTTP_ENDPOINTS` | `false` | Tillåt `http://`-adresser och adresser på den egna maskinen för väckningar (bara för test eller ett lokalt ntfy) |
| `MAX_ENDPOINTS` | 20 | Högsta antal telefoner |
| `REGISTRATION_TTL_DAYS` | 60 | Telefoner som inte hörts av på så länge glöms |
| `TEST_PUSH` | `true` | Tillåt "Skicka testnotis" från appen |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

En tjänst = ett hushåll (det som tokenens användare tillhör). Har du flera hushåll kör du en
container per hushåll.

### I appen

Mer → Synk och lagring → **Snabb push (egen tjänst, experimentell)**: ange tjänstens adress, välj
distributör (t.ex. ntfy, installera den först), tryck **Slå på**. "Skicka testnotis" visar en
notis om allt fungerar. Gäster och demoläget kan inte använda funktionen.

### När det inte fungerar

| Det här ser du | Det betyder oftast | Gör så här |
|---|---|---|
| Loggen: `TANDOOR_TOKEN_FILE cannot be read`, containern stannar | Tjänsten får inte läsa filen | Steg 2 |
| Loggen: `Tandoor rejected TANDOOR_TOKEN` | Fel eller återkallad token. API:t öppnar inte förrän den godtas, så appen säger "går inte att nå" | Rätta token och starta om containern (annars prövar den bara var 15:e minut) |
| Loggen: `cannot reach Tandoor yet` | `TANDOOR_URL` går inte att nå från containern (`localhost` är containern själv) | Använd en adress som fungerar inifrån, t.ex. tjänstens namn i Docker-nätet |
| Loggen: `tandoor answered HTTP 301` eller `302` | `TANDOOR_URL` skickar vidare | Ange den slutliga adressen |
| Appen: "Push-tjänsten går inte att nå" | Telefonen når inte adressen, certifikatet godtas inte eller tjänsten väntar på Tandoor | Pröva `/v1/info` från telefonens webbläsare och läs tjänstens logg |
| Appen: "Adressen är ingen push-tjänst för Tandem" | Adressen går till något annat | `/v1/info` ska svara `tandem-push`; kontrollera proxyn |
| Appen: "Push-tjänsten svarade inte som väntat" | Proxyn når inte containern, eller tjänsten når inte Tandoor just nu | Läs tjänstens logg (`checking a phone's account failed`) |
| Appen: "Tjänsten kunde inte bekräfta ditt Tandoor-konto" | Telefonen är inloggad på en annan Tandoor än `TANDOOR_URL`, eller dess token gäller inte längre | Samma Tandoor på båda ställen; logga in igen |
| Appen: "Tjänsten hör till ett annat hushåll" | `TANDOOR_TOKEN` hör till en användare i ett annat hushåll eller utrymme | Ny token i rätt hushåll, eller en container till |
| Appen: "Tjänsten har redan så många telefoner som den tillåter" | `MAX_ENDPOINTS` är nått | Höj värdet, eller vänta tills gamla telefoner glöms |
| Appen: "Tjänsten ber dig vänta" | Gränsen i API:t, som alla delar utan `TRUSTED_PROXIES` | Försök igen om en minut, och sätt `TRUSTED_PROXIES` |
| Appen: "UnifiedPush-appen kunde inte registrera Tandem" | Också när tjänsten nekar väckningsadressen: den är `http://` eller på tjänstens egen maskin | Ett ntfy med HTTPS, eller `ALLOW_HTTP_ENDPOINTS=true` för ett lokalt |
| Testnotisen kommer aldrig, loggen: `push endpoint answered HTTP 30x` | Distributörens adress skickar vidare | Väckningar följer inga omdirigeringar: rätta ntfy-serverns adress |

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
  en avvisad token 15 min. Testnotiser högst var 10:e sekund. En begäran som bevisar en registrering
  (adress och id) har en egen gräns, så ingen annan kan förbruka den. Övriga anrop som når Tandoor
  har en gräns per klient-IP; bakom en proxy är det proxyns IP, alltså en gräns för alla, tills
  `TRUSTED_PROXIES` är satt. Tjänsten tror bara på `X-Forwarded-For` från de adresserna.
- **Väckningsadresser:** bara `https://` och aldrig den egna maskinen (loopback, link-local, också
  efter namnuppslag), om inte `ALLOW_HTTP_ENDPOINTS` är på. Omdirigeringar följs aldrig, varken för
  väckningar eller mot Tandoor. Privata LAN-adresser tillåts, eftersom ett hushålls ntfy kan finnas
  där; den som har en medlems token kan alltså få tjänsten att göra HTTPS-anrop i det egna nätet.
- **`/healthz`** säger bara `{"healthy":…}`: den kräver ingen inloggning, och antalet telefoner eller
  tiden för senaste väckning skulle berätta när hushållet handlar.
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

### Running it

The image is published as `ghcr.io/demacish/tandem-push` for linux/amd64 and linux/arm64 when a
version is tagged (the first with version 0.6.0; until then it cannot be pulled). The tag is the
version without `v`, for example `0.6.0`, or `latest`. One container serves one household (the token
user's).

1. **Create a token** in Tandoor, Settings → API, for a user of the household (read scope is enough),
   and save it as `tandoor_token.txt` next to the Compose file.
2. **Let the service read it.** It runs as user 65532 and a Compose secret keeps the host's file
   permissions, otherwise it stops with `TANDOOR_TOKEN_FILE cannot be read`:
   `sudo chown 65532:65532 tandoor_token.txt && sudo chmod 400 tandoor_token.txt`.
3. **Start it** with `docker compose up -d`, using the Compose file above (with your own
   `TANDOOR_URL`).
4. **Check it.** `docker compose logs tandem-push` shows `serving one household` and `listening`, and
   `curl http://127.0.0.1:8080/healthz` answers `{"healthy":true}`.
5. **Put HTTPS in front.** The app only accepts `https://`, and the phone must reach the address from
   wherever it is, with a certificate it trusts (e.g. Let's Encrypt; a self-signed one will not do).
   With Caddy, `push.example.com { reverse_proxy 127.0.0.1:8080 }` is enough. Other proxies work too
   as long as they send `X-Forwarded-For`. Set `TRUSTED_PROXIES` to the proxy's address so that the
   API's limit applies to each phone separately; a proxy reaching the container through a published
   port shows up as Docker's bridge, hence Docker's network in the example. Then check from outside:
   `curl https://push.example.com/v1/info` answers with `"service":"tandem-push"`.
6. **Turn it on in the app** with the address `https://push.example.com`.

| Variable | Default | Meaning |
|---|---|---|
| `TANDOOR_URL` | – (required) | The Tandoor server's final address as seen from the container: the service follows no redirects, so `http://` to a server that redirects to `https://` does not work |
| `TANDOOR_TOKEN` / `TANDOOR_TOKEN_FILE` | – (required) | An API token of a user in the household (read scope is enough) |
| `POLL_SECONDS` | 45 | How often the list is checked (30–600) |
| `LISTEN_ADDR` | `:8080` | Where the service's API listens |
| `DATA_DIR` | `/data` | Where `registrations.json` is kept |
| `TRUSTED_PROXIES` | – (off) | Proxies whose `X-Forwarded-For` is believed: IP addresses or CIDR ranges, comma-separated. Without it all clients behind a proxy share one limit |
| `ALLOW_HTTP_ENDPOINTS` | `false` | Allow `http://` push addresses and addresses on this machine (only for tests or a local ntfy) |
| `MAX_ENDPOINTS` | 20 | Maximum number of phones |
| `REGISTRATION_TTL_DAYS` | 60 | Phones not heard from for this long are forgotten |
| `TEST_PUSH` | `true` | Allow "Send test notification" from the app |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

### When it does not work

| What you see | What it usually means | What to do |
|---|---|---|
| Log: `TANDOOR_TOKEN_FILE cannot be read`, the container stops | The service may not read the file | Step 2 |
| Log: `Tandoor rejected TANDOOR_TOKEN` | A wrong or revoked token. The API stays closed until it is accepted, so the app says "cannot be reached" | Fix the token and restart the container (it otherwise only retries every 15 minutes) |
| Log: `cannot reach Tandoor yet` | `TANDOOR_URL` cannot be reached from the container (`localhost` is the container itself) | Use an address that works from inside, e.g. the service's name on the Docker network |
| Log: `tandoor answered HTTP 301` or `302` | `TANDOOR_URL` redirects | Give the final address |
| App: "The push service cannot be reached" | The phone cannot reach the address, the certificate is refused, or the service is waiting for Tandoor | Try `/v1/info` in the phone's browser and read the service's log |
| App: "That address is not a Tandem push service" | The address leads somewhere else | `/v1/info` must answer `tandem-push`; check the proxy |
| App: "The push service did not answer as expected" | The proxy cannot reach the container, or the service cannot reach Tandoor right now | Read the service's log (`checking a phone's account failed`) |
| App: "The service could not confirm your Tandoor account" | The phone is logged in to another Tandoor than `TANDOOR_URL`, or its token is no longer valid | Use the same Tandoor in both places; log in again |
| App: "The service belongs to another household" | `TANDOOR_TOKEN` belongs to a user of another household or space | A new token in the right household, or one more container |
| App: "The service already has as many phones as it allows" | `MAX_ENDPOINTS` is reached | Raise it, or wait until old phones are forgotten |
| App: "The service asks you to wait" | The API's limit, shared by everyone without `TRUSTED_PROXIES` | Try again in a minute, and set `TRUSTED_PROXIES` |
| App: "The UnifiedPush app could not register Tandem" | Also when the service refuses the push address: it is `http://` or on the service's own machine | An ntfy with HTTPS, or `ALLOW_HTTP_ENDPOINTS=true` for a local one |
| The test notification never comes, log: `push endpoint answered HTTP 30x` | The distributor's address redirects | Wake-ups follow no redirects: fix the ntfy server's address |

### In the app

More → Sync and storage → **Fast push (own service, experimental)**: enter the service URL, pick a
distributor, tap **Turn on**, then "Send test notification".

### Security and data

 a phone registers with its own Tandoor token, which the service uses once to
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

A request that proves a registration (its endpoint and id) has a limit of its own, so nobody else can
use it up; test notifications go out at most every 10 seconds. Every other call that reaches Tandoor
is limited per client IP, which behind a proxy is the proxy's, so one limit for everyone until
`TRUSTED_PROXIES` is set. `X-Forwarded-For` is only believed from those addresses. Push addresses
must be `https://` and never this machine (loopback, link-local, also after name resolution) unless
`ALLOW_HTTP_ENDPOINTS` is on. Redirects are never followed, neither for wake-ups nor towards Tandoor.
Private LAN addresses are allowed because a household's ntfy may live there, so someone holding a
member's token can make the service send HTTPS requests inside its own network. `/healthz` says only
`{"healthy":…}`: it needs no login, and the number of phones or the time of the last wake-up would
tell an outsider when the household shops.

### API (version 1)

| Request | Body | Answer |
|---|---|---|
| `GET /healthz` | – | `200 {healthy}` |
| `GET /v1/info` | – | `200 {service: "tandem-push", version, api: 1, test, encryption, poll_seconds}` |
| `POST /v1/register` + `Authorization: Bearer <Tandoor token>` | `{endpoint, p256dh?, auth?}` | `200 {id, poll_seconds, test}`; `400 invalid_endpoint/invalid_keys`, `401 unauthorized`, `403 guest/other_household`, `409 too_many`, `429 rate_limited`, `502 tandoor_unavailable` |
| `POST /v1/register` (renewal, no token) | `{endpoint, id, p256dh?, auth?}` | as above; `401 unauthorized` when the id does not match: the app then registers with the token |
| `POST /v1/unregister` | `{endpoint, id}` (or the token header instead of `id`) | `204` |
| `POST /v1/test` | `{endpoint, id}` | `204`; `404 not_registered/test_disabled`, `429`, `502 push_failed` |

Wake-up payload: `{"tandem":"sync"|"test","id":"<registration id>"}` (`Content-Encoding:
aes128gcm` when encrypted), headers `TTL: 300`, `Urgency: high`.
