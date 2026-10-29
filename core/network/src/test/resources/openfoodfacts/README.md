# Open Food Facts-svar för testerna

Två svar från Open Food Facts publika läs-API (`GET /api/v2/product/{kod}?fields=code,product_name,brands,quantity`),
hämtade 2026-10-29 med Tandems User-Agent för att låsa formen (plan §8.8):

- `product-found.json`: en offentlig produkt i Open Food Facts databas (ODbL), `737628064502` (UPC-A; svaret
  normaliserar koden till 13 siffror).
- `product-not-found.json`: en syntetisk kod som inte finns (HTTP 404, `status: 0`).

Testerna kör aldrig mot Open Food Facts; de serverar filerna från en MockWebServer.
