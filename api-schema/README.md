# api-schema

`tandoor-2.6.13.yaml` är OpenAPI-schemat för **Tandoor Recipes 2.6.13**, den version som
Tandem testas mot.

## Ursprung och licens

- Schemat är **genererat av Tandoor** (drf-spectacular, `/openapi/`). Tandoor Recipes är
  licensierat under **AGPL-3.0** (https://github.com/TandoorRecipes/recipes).
- Filen ligger här **bara som referens**: som gränssnittsspecifikation för de handskrivna
  DTO:erna och för kontraktstesterna och schemadiffen (`oasdiff`). Ingen Tandoor-kod kopieras
  in i appen, och ingen kod genereras från schemat till appen.
- Tandem är en inofficiell klient och är inte associerad med Tandoor-projektet.

## Filer

| Fil | Innehåll |
|---|---|
| `tandoor-2.6.13.yaml` | Schemat för baslinjeversionen (byts efter en grön körning mot en ny version). |
| `used-endpoints.txt` | De endpoints appen faktiskt använder. Allow-list för schemadiffen i `tandoor-watch.yml`; uppdateras i samma PR som ett nytt anrop läggs till. |
