# Tandems demoinnehåll

Inbyggd exempeldata för demoläget (plan 6.2 "Demoläge"). Läses av
`casa.moller.tandem.core.data.demo.DemoContent`; se docs/BYGGE.md "Demoinnehåll".

**Redigera inte filerna här för hand.** Källan är `scripts/demo-content/demo_source.py`;
`generate_demo_content.py` skriver JSON-filerna och `build_images.py` bilderna.

## Filer

| Fil | Innehåll (Tandoor 2.6.13-form) |
|---|---|
| `index.json` | Version, referensdag, recept-id:n, fästa recept, bildlista |
| `cook-log.json`, `view-log.json` | Kokhistorik och nyligen visade (språkoberoende) |
| `<sv\|en>/recipe-<id>.json` | `GET /api/recipe/{id}/` |
| `<sv\|en>/recipe-overviews.json` | Elementen i `GET /api/recipe/` (sorterade på namn) |
| `<sv\|en>/recipe-flat.json` | `GET /api/recipe/flat/` |
| `<sv\|en>/keywords.json`, `meal-types.json`, `meal-plan.json` | Nyckelord, måltidstyper, matsedel |
| `<sv\|en>/recipe-books.json`, `recipe-book-entries.json` | Receptböckerna "Favoriter" och "Vill laga" |
| `<sv\|en>/user-preference.json` | Användarinställningar (bråk på) |
| `images/*.webp` | 640 × 480, kvalitet 80, utan metadata |

Alla datum är skrivna som om idag vore 2000-01-15; appen flyttar dem till dagens datum.

## Ursprung och licens

- **Recepten** (namn, beskrivningar, ingredienslistor och instruktioner) är skrivna för Tandem
  och inte kopierade från någon kokbok eller webbplats. De omfattas av projektets licens
  (Apache-2.0).
- **Foton:** `lax.webp`, `tomatsoppa.webp`, `citronkyckling.webp`, `linsdahl.webp` och
  `gronsaksgryta.webp` är AI-genererade bilder som hör till projektets designskisser
  (`docs/design/stitch-export/*/screen.png`), beskurna till 4:3, nedskalade och omkodade till
  WebP utan metadata. Inga riktiga personer, platser eller varumärken.
- **Illustrationer:** `pannkakor`, `kardemummabullar`, `havregrynsgrot`, `kottbullar`,
  `kottfarssas`, `kladdkaka`, `fredagstacos`, `wokade-nudlar` och `halloumisallad` är ritade av
  `build_images.py` i appens färgpalett. Sju av dem har en ikon ur Material Symbols Rounded
  (Google, Apache-2.0; samma fastlåsta version som appens ikoner, se
  `core/designsystem/src/main/assets/licenses/NOTICE.txt`): `skillet`, `bakery_dining`,
  `rice_bowl`, `dinner_dining`, `cake`, `ramen_dining` och `nutrition`. Köttbullarna och tacon
  är ritade med enkla former.
