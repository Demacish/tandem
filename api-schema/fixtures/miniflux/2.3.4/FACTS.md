# Miniflux 2.3.4: fakta för inkorgen för recept (Fas 5, bana rss)

Inspelat av `MinifluxContractTest` (`miniflux-contract.yml`) mot en tillfällig Miniflux 2.3.4
(`miniflux/miniflux:2.3.4@sha256:72e49f51…`, PostgreSQL 16) med ett flöde som serveras inne i
jobbet (`contract-tests/miniflux/recipes.xml`, det reserverade namnet `recipes.example.com`).
Nyckeln skapas med administratörens Basic-inloggning (`POST /v1/api-keys`, som under Settings →
API Keys i webben) och står bara som `<redacted>` i inspelningarna. Tidsstämplar är normaliserade
till `2000-01-01T00:00:00…`. `observed-facts.json` har alla fakta; testet jämför dem med sin baslinje.

| Fakta | Inspelning | Appen |
|---|---|---|
| `GET /v1/me` med rätt nyckel i `X-Auth-Token` → 200 med `id` | `me` | anslutningen prövas så innan nyckeln sparas |
| fel nyckel → 401 `application/json` `{"error_message": "access unauthorized"}`; ingen nyckel → 401 | `me-wrong-key`, `me-no-key` | `Unauthorized` → `InboxProblem.AUTH`, skyddet stoppar tills en ny nyckel anges |
| en sökväg som inte finns → 404 `text/plain` "404 page not found" | `not-api` | `NOT_MINIFLUX` (ingen JSON) |
| `GET /v1/categories?counts=true` → lista med `feed_count` och `total_unread`; utan `counts` saknas fälten; Miniflux har alltid en kategori "All" | `categories-counts`, `categories` | kategorivalet visar antalet olästa |
| `GET /v1/categories/{id}/entries?status=unread&order=published_at&direction=desc&limit=50` → `{total, entries}`, nyast först, `url`, `title`, `feed.title`, `feed.category.id`, `published_at`, `content` (HTML), `enclosures` (`url`, `mime_type`) | `entries-unread`, `entries-unread-limit` | posterna i inkorgen; bilden ur en bild-enclosure, annars första `<img>` i `content`; `limit` respekteras och `total` räknar alla |
| en tom kategori → `total: 0`, `entries: []` | `entries-unread-other` | tom inkorg |
| en kategori som inte finns → **400** `{"error_message": "invalid category ID"}` (inte 404) | `entries-unknown-category` | kategorin hoppas över (borttagen i Miniflux) |
| `PUT /v1/entries` `{"entry_ids": [...], "status": "read"}` → 204 utan kropp; samma igen → 204 | `entries-mark-read`, `entries-mark-read-again` | "Markera som läst" och läst efter import; att skicka igen efter ett tappat svar är ofarligt |
| efter läst är posten borta ur olästa och `total` en mindre | `entries-unread-after-read` | nästa uppdatering tar bort den |
| `GET /v1/version` → `{"version": "2.3.4", …}` | `version` | används inte av appen (bara för inspelningen) |
