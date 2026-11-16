# Observerade fakta: Tandoor 2.7.3 (skillnader mot 2.7.1/2.7.2)

> **Källa:** alla kontraktsklasser i [`contract.yml`](../../../.github/workflows/contract.yml),
> körda för hand (`workflow_dispatch`, Contract 43802306408, banan komplettering-09) mot en tillfällig
> `vabene1111/recipes:2.7.3` + Postgres 16 i GitHub Actions, aldrig produktion. Inspelad
> 2026-11-16, samma serverinställningar som 2.6.13 och 2.7.1. 2.7.3 är en **säkerhetsrelease**
> (2026-11-15) och var `latest` när den spelades in.
> **[O]** = observerat, **[K]** = läst i Tandoors källkod (taggarna 2.7.2 och 2.7.3), **[S]** =
> slutsats för appen. Allt som inte står här är som i [`../2.7.1/FACTS.md`](../2.7.1/FACTS.md);
> varje skillnad i `observed-facts.json` mot 2.6.13 står också, granskad, i
> [`contract-tests/watch/reviewed-changes.json`](../../../contract-tests/watch/reviewed-changes.json)
> (raden "2.7"). `TandoorVersionTable.TESTED` har **inte** 2.7.3: appen kör den i säkert läge tills
> ägaren godkänner versionen.

## Filerna

- Samma upplägg som 2.7.1. `fas1/scaling-golden.json` saknas: webbens skalningstabell var
  **identisk** med `../2.6.13/fas1/scaling-golden.json`. [O]
- I `fas2/` har listreceptet `shopping-list-recipe-create` id 3 (2 i 2.7.1): listreceptet före det
  gjordes på appens sätt, se nedan.

## Fel i Tandoor: `PUT /api/recipe/{id}/shopping/` svarar 500

- `PUT /api/recipe/{id}/shopping/` med `{"ingredients": [74, 73], "servings": 8}` → **500**, Djangos
  HTML-sida "Server Error (500)" (inte vyns egen JSON `{"msg": "An error occurred"}`). [O]
  (`fas2/recipe-shopping-put`)
- Orsak [K]: commit a4f91e5 ("change import log permission and fix shopping sub endpoint ingredient
  filter") gör `ingredients` till `Ingredient.objects.filter(id__in=…).filter(…).values_list('id',
  flat=True)`, en QuerySet av heltal. `RecipeShoppingEditor._add_ingredients` gör om bara en
  **lista** till Ingredient-objekt (`isinstance(ingredients, list)`) och läser sedan `x.food` på
  varje element: `AttributeError` på ett heltal. Samma sak med `list_recipe` (edit). Det är ett
  **fel**, ingen ny semantik: vyns avsikt (bara ingredienser i recept användaren får se) är en
  behörighetskontroll, och svaret är ett ohanterat undantag. Oförändrat på `develop` 2026-11-16.
- Ett tomt `ingredients` (måltid) och `servings: 0` svarade 500 redan i 2.6.13/2.7.1 (FACTS "Fas 2"). [O]
- [S] Appen anropar aldrig den här vägen (`api-schema/used-endpoints.txt`): "Lägg på listan" gör
  listreceptet med `POST /api/shopping-list-recipe/` och en post per ingrediens med `list_recipe`
  och `ingredient` (`ShoppingPayloads`, `ShoppingWritesContractTest`). `Phase2ContractTest` gör
  då listreceptet på appens sätt (`listRecipe.viaAppRoute`), så att `PATCH` av portionerna och
  `DELETE`, som appen använder, prövas ändå: samma värden som i 2.7.1. [O]

## Gäster får inte skapa livsmedel, måltidstyper eller listor

- `FoodSerializer.create` (också inbäddad i en inköpspost), `MealTypeSerializer.create` och
  `ShoppingListSerializer.create` kräver gruppen `user` för ett **nytt** objekt; ett befintligt (samma
  namn, oavsett skiftläge) returneras som förut. Ett namnbyte kräver `user`, och serialiseraren
  lämnar en gästs övriga ändringar av ett livsmedel utan verkan (objektet oförändrat). [K]
- En gästs `POST /api/shopping-list-entry/` med `{"food": {"name": "godis"}}` → **400**
  `{"food": ["You do not have the required permissions to create food."]}` (2.7.1: 201). [O]
  (`fas2/shopping-list-entry-create-as-guest`). Formen: en **lista med meddelanden** på fältet, inte
  ett objekt som vid ett fältfel inne i livsmedlet (`{"food": {"name": [...]}}`). [O]
- Gästens `bulk` ger fortfarande 403, med texten "You do not have permission to perform this
  action." (`bulk` och `bulk_create_entries` kräver nu också tokenens `read write`). [O/K]
- [S] Appen skriver aldrig som gäst (#42). Står en rad i utkorgen när rollen ändrats till gäst
  känner `NestedCreateRefusal` (core:sync) igen formen (aldrig texten) och behandlar raden som en
  nekad skrivning: FAILED `forbidden`, rollen läses om, utkorgen stannar, kontot förblir inloggat.

## Skrivskyddade objekt och nya `_id`-fält

- `recipe-book`: `filter` är skrivskyddad; skrivningar anger filtret med **`filter_id`** (nytt fält
  i svaret, `null` för en vanlig bok). Ett `filter`-objekt med `id` (eller ett heltal) utan
  `filter_id` översätts fortfarande, och filtret måste vara användarens eget eller delat med hen,
  annars 400 "Permission Denied". [O/K]
- `user-preference`: `default_meal_type` skrivskyddad, nytt `default_meal_type_id`. `space`:
  `ai_default_provider` skrivskyddad, nytt `ai_default_provider_id`. `inventory-location`:
  `household` skrivskyddad, nytt `household_id` (heltal). [O/K]
- `POST /api/inventory-location/` **utan** hushåll → **500** (2.7.1: 400 `household` krävs):
  serialiseraren kräver inte längre hushållet och databasen nekar raden. [O]
- [S] Appen skickar receptboken "Vill laga" med filterobjektet **och** `filter_id` (äldre versioner
  bortser från det okända fältet, `BookWritesContractTest`) och läser `filter_id` när objektet
  saknas (`BookMappers`). En lagerplats skickas alltid med sitt hushåll som `{id, name}` (översätts
  till `household_id`); utan hushåll stoppar appen raden själv. Måltidstyp som standard och
  AI-leverantör skriver appen inte.

## Övrigt

- `MealPlanSerializer` nekar (400 "Permission Denied") ett recept användaren inte får se;
  `StepSerializer` nekar ett underrecept användaren inte får se (404) och visar inte dess data. [K]
  Appen planerar bara recept den har läst.
- Alla andra fakta i Fas 0, 1, 2, S, 3, 4 och 5 är som i 2.7.1. [O]
