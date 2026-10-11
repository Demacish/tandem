"""Source of Tandem's built-in demo content (plan 6.2 "Demoläge").

Everything here is original text written for Tandem (no recipe is copied from a book or site).
Each text is a pair (Swedish, English); both languages share every id, amount, unit, time and
ingredient order, so the two variants have an identical structure (DemoContentTest checks it).

generate_demo_content.py turns this file into the Tandoor-shaped JSON in
core/data/src/main/assets/demo/. Edit this file, run the generator, commit both.

Dates are written as "days before the reference day"; the app shifts them so that the
reference day becomes today (DemoContent.REFERENCE_DATE).
"""

# --- Units: key -> (sv name, sv plural, en name, en plural, base_unit) -----------------------
UNITS = {
    "g": ("g", None, "g", None, "g"),
    "kg": ("kg", None, "kg", None, "kg"),
    "dl": ("dl", None, "dl", None, None),
    "l": ("l", None, "l", None, "l"),
    "msk": ("msk", None, "tbsp", None, "tbsp"),
    "tsk": ("tsk", None, "tsp", None, "tsp"),
    "burk": ("burk", "burkar", "can", "cans", None),
    "nypa": ("nypa", "nypor", "pinch", "pinches", None),
    "påse": ("påse", "påsar", "packet", "packets", None),
    "kruka": ("kruka", "krukor", "pot", "pots", None),
    "knippe": ("knippe", "knippen", "bunch", "bunches", None),
    "stjälk": ("stjälk", "stjälkar", "stalk", "stalks", None),
    "förp": ("förpackning", "förpackningar", "pack", "packs", None),
    "huvud": ("huvud", "huvuden", "head", "heads", None),
}

# --- Foods: key -> (sv name, sv plural, en name, en plural) -----------------------------------
FOODS = {
    "potatis": ("potatis", "potatisar", "potato", "potatoes"),
    "salt": ("salt", None, "salt", None),
    "svartpeppar": ("svartpeppar", None, "black pepper", None),
    "vitpeppar": ("vitpeppar", None, "white pepper", None),
    "laxfilé": ("laxfilé", "laxfiléer", "salmon fillet", "salmon fillets"),
    "citron": ("citron", "citroner", "lemon", "lemons"),
    "olivolja": ("olivolja", None, "olive oil", None),
    "rapsolja": ("rapsolja", None, "rapeseed oil", None),
    "smör": ("smör", None, "butter", None),
    "dill": ("dill", None, "dill", None),
    "gul lök": ("gul lök", "gula lökar", "onion", "onions"),
    "rödlök": ("rödlök", "rödlökar", "red onion", "red onions"),
    "vitlöksklyfta": ("vitlöksklyfta", "vitlöksklyftor", "garlic clove", "garlic cloves"),
    "krossade tomater": ("krossade tomater", None, "chopped tomatoes", None),
    "vatten": ("vatten", None, "water", None),
    "buljongtärning": ("grönsaksbuljongtärning", "grönsaksbuljongtärningar", "vegetable stock cube", "vegetable stock cubes"),
    "socker": ("strösocker", None, "caster sugar", None),
    "matlagningsgrädde": ("matlagningsgrädde", None, "single cream", None),
    "vispgrädde": ("vispgrädde", None, "whipping cream", None),
    "gräddfil": ("gräddfil", None, "sour cream", None),
    "basilika": ("färsk basilika", None, "fresh basil", None),
    "morot": ("morot", "morötter", "carrot", "carrots"),
    "palsternacka": ("palsternacka", "palsternackor", "parsnip", "parsnips"),
    "kyckling": ("hel kyckling", "hela kycklingar", "whole chicken", "whole chickens"),
    "timjan": ("färsk timjan", None, "fresh thyme", None),
    "torkad timjan": ("torkad timjan", None, "dried thyme", None),
    "ingefära": ("riven ingefära", None, "grated ginger", None),
    "curry": ("currypulver", None, "curry powder", None),
    "gurkmeja": ("gurkmeja", None, "ground turmeric", None),
    "spiskummin": ("malen spiskummin", None, "ground cumin", None),
    "röd lins": ("röd lins", "röda linser", "red lentil", "red lentils"),
    "kokosmjölk": ("kokosmjölk", None, "coconut milk", None),
    "lime": ("lime", "limefrukter", "lime", "limes"),
    "koriander": ("färsk koriander", None, "fresh coriander", None),
    "basmatiris": ("basmatiris", None, "basmati rice", None),
    "selleri": ("blekselleri", None, "celery", None),
    "squash": ("squash", "squashar", "courgette", "courgettes"),
    "vita bönor": ("vita bönor", None, "white beans", None),
    "lagerblad": ("lagerblad", "lagerblad", "bay leaf", "bay leaves"),
    "persilja": ("bladpersilja", None, "flat-leaf parsley", None),
    "surdegsbröd": ("surdegsbröd", None, "sourdough bread", None),
    "jäst": ("färsk jäst", None, "fresh yeast", None),
    "mjölk": ("mjölk", None, "milk", None),
    "kardemumma": ("stött kardemumma", None, "ground cardamom", None),
    "vetemjöl": ("vetemjöl", None, "plain flour", None),
    "ägg": ("ägg", None, "egg", "eggs"),
    "pärlsocker": ("pärlsocker", None, "pearl sugar", None),
    "jordgubbssylt": ("jordgubbssylt", None, "strawberry jam", None),
    "ströbröd": ("ströbröd", None, "breadcrumbs", None),
    "blandfärs": ("blandfärs", None, "beef and pork mince", None),
    "kalvfond": ("kalvfond", None, "veal stock", None),
    "soja": ("japansk soja", None, "soy sauce", None),
    "lingonsylt": ("lingonsylt", None, "lingonberry jam", None),
    "nötfärs": ("nötfärs", None, "beef mince", None),
    "tomatpuré": ("tomatpuré", None, "tomato purée", None),
    "oregano": ("torkad oregano", None, "dried oregano", None),
    "spaghetti": ("spaghetti", None, "spaghetti", None),
    "parmesan": ("parmesan", None, "parmesan", None),
    "tacokrydda": ("tacokrydda", None, "taco seasoning", None),
    "tomat": ("tomat", "tomater", "tomato", "tomatoes"),
    "gurka": ("gurka", "gurkor", "cucumber", "cucumbers"),
    "avokado": ("avokado", "avokador", "avocado", "avocados"),
    "isbergssallad": ("isbergssallad", None, "iceberg lettuce", None),
    "majs": ("majs", None, "sweetcorn", None),
    "riven ost": ("riven ost", None, "grated cheese", None),
    "tortilla": ("tortilla", "tortillor", "tortilla", "tortillas"),
    "tacosås": ("tacosås", None, "taco sauce", None),
    "kakao": ("kakao", None, "cocoa powder", None),
    "vaniljsocker": ("vaniljsocker", None, "vanilla sugar", None),
    "hallon": ("hallon", "hallon", "raspberry", "raspberries"),
    "jordnötssmör": ("jordnötssmör", None, "peanut butter", None),
    "sesamolja": ("rostad sesamolja", None, "toasted sesame oil", None),
    "limesaft": ("limesaft", None, "lime juice", None),
    "sambal": ("sambal oelek", None, "sambal oelek", None),
    "äggnudlar": ("äggnudlar", None, "egg noodles", None),
    "broccoli": ("broccoli", None, "broccoli", None),
    "röd paprika": ("röd paprika", "röda paprikor", "red pepper", "red peppers"),
    "salladslök": ("salladslök", "salladslökar", "spring onion", "spring onions"),
    "jordnötter": ("salta jordnötter", None, "salted peanuts", None),
    "havregryn": ("havregryn", None, "rolled oats", None),
    "äpple": ("äpple", "äpplen", "apple", "apples"),
    "kanel": ("kanel", None, "ground cinnamon", None),
    "honung": ("flytande honung", None, "runny honey", None),
    "bulgur": ("bulgur", None, "bulgur wheat", None),
    "halloumi": ("halloumi", None, "halloumi", None),
    "körsbärstomat": ("körsbärstomat", "körsbärstomater", "cherry tomato", "cherry tomatoes"),
    "ruccola": ("ruccola", None, "rocket", None),
    "mynta": ("färsk mynta", None, "fresh mint", None),
}

# --- Keywords: key -> (sv, en, parent key) -------------------------------------------------------
KEYWORDS = {
    "vardag": ("Vardag", "Weeknight", None),
    "snabbt": ("Snabbt", "Quick", None),
    "vegetariskt": ("Vegetariskt", "Vegetarian", None),
    "bakning": ("Bakning", "Baking", None),
    "fika": ("Fika", "Fika", None),
    "helg": ("Helg", "Weekend", None),
    "frukost": ("Frukost", "Breakfast", None),
    "klassiker": ("Klassiker", "Classic", None),
    "huvudratt": ("Huvudrätt", "Main course", None),
    "fisk": ("Fisk", "Fish", "huvudratt"),
    "kyckling": ("Kyckling", "Chicken", "huvudratt"),
    "pasta": ("Pasta", "Pasta", "huvudratt"),
    "soppa": ("Soppa", "Soup", "huvudratt"),
    "gryta": ("Gryta", "Stew", "huvudratt"),
    "sallad": ("Sallad", "Salad", "huvudratt"),
    "varldens": ("Världens kök", "World food", None),
    "indiskt": ("Indiskt", "Indian", "varldens"),
    "italienskt": ("Italienskt", "Italian", "varldens"),
    "mexikanskt": ("Mexikanskt", "Mexican", "varldens"),
    "asiatiskt": ("Asiatiskt", "Asian", "varldens"),
}

# --- Meal types: (id, sv, en, order, time, colour) ----------------------------------------------
MEAL_TYPES = [
    (1, "Frukost", "Breakfast", 0, "07:30:00", "#D97706"),
    (2, "Lunch", "Lunch", 1, "12:00:00", "#4D7C0F"),
    (3, "Middag", "Dinner", 2, "18:00:00", "#C2410C"),
    (4, "Fika", "Fika", 3, "15:00:00", "#8C5A3C"),
]

# Nutrition property types (Tandoor 2.x "properties"): (id, sv, en, unit, order).
PROPERTY_TYPES = [
    (1, "Energi", "Energy", "kcal", 0),
    (2, "Protein", "Protein", "g", 1),
    (3, "Fett", "Fat", "g", 2),
    (4, "Kolhydrater", "Carbohydrates", "g", 3),
]


def H(sv, en):
    """A header row in a step's ingredient list."""
    return {"header": (sv, en)}


def I(amount, unit, food, note=None, no_amount=False):  # noqa: E743 - short on purpose
    """An ingredient: amount (in the recipe's servings), unit key or None, food key, note pair."""
    return {"amount": amount, "unit": unit, "food": food, "note": note, "no_amount": no_amount}


def S(name, time, text, ingredients=(), header=True):
    """A step: name pair (or None), minutes, instruction pair, ingredients."""
    return {"name": name, "time": time, "text": text, "ingredients": list(ingredients), "header": header and name is not None}


TO_SERVE = ("Till servering", "To serve")

RECIPES = [
    {
        "id": 1, "slug": "lax", "image": "lax.webp",
        "name": ("Ugnsbakad lax med dillpotatis", "Baked salmon with dill potatoes"),
        "description": ("Saftig lax med citron, het dillsmörad potatis och nästan ingen disk.",
                        "Juicy salmon with lemon, hot potatoes in dill butter and hardly any washing-up."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 20, "waiting": 25,
        "keywords": ["fisk", "vardag"], "created": 160, "updated": 40,
        "properties": {1: 540, 2: 33, 3: 27, 4: 38},
        "steps": [
            S(("Potatis", "Potatoes"), 20,
              ("Skrubba {{ ingredients[0] }}. Koka potatisen i saltat vatten i 20 minuter tills den är mjuk.",
               "Scrub {{ ingredients[0] }}. Boil the potatoes in salted water for 20 minutes until tender."),
              [I(1, "kg", "potatis", ("gärna fast", "waxy if you can")), I(1, "tsk", "salt")]),
            S(("Laxen", "The salmon"), 18,
              ("Sätt ugnen på 200 grader. Lägg laxen i en ugnsform, ringla över oljan och pressa över hälften av citronen. "
               "Krydda med salt och peppar och baka mitt i ugnen i 15–18 minuter.",
               "Heat the oven to 200 °C. Put the salmon in a baking dish, drizzle with the oil and squeeze over half of the lemon. "
               "Season with salt and pepper and bake in the middle of the oven for 15–18 minutes."),
              [I(600, "g", "laxfilé", ("i fyra bitar", "in four pieces")), I(1, None, "citron"), I(1, "msk", "olivolja"),
               I(0, None, "salt", no_amount=True), I(0, None, "svartpeppar", no_amount=True)]),
            S(("Dillsmör", "Dill butter"), 2,
              ("Smält {{ ingredients[1] }} på svag värme i 2 minuter och rör ner dillen. Vänd den varma potatisen i dillsmöret "
               "och servera med laxen och resten av citronen i klyftor.",
               "Melt {{ ingredients[1] }} over low heat for 2 minutes and stir in the dill. Toss the hot potatoes in the dill butter "
               "and serve with the salmon and the rest of the lemon in wedges."),
              [H(*TO_SERVE), I(50, "g", "smör"), I(1, "knippe", "dill", ("finhackad", "finely chopped"))]),
        ],
    },
    {
        "id": 2, "slug": "tomatsoppa", "image": "tomatsoppa.webp",
        "name": ("Krämig tomatsoppa", "Creamy tomato soup"),
        "description": ("Len soppa på krossade tomater, toppad med gräddfil och basilika. Klar på en halvtimme.",
                        "A smooth soup of chopped tomatoes, topped with sour cream and basil. Ready in half an hour."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 15, "waiting": 20,
        "keywords": ["soppa", "vegetariskt", "snabbt"], "created": 300, "updated": 300,
        "steps": [
            S(("Fräs", "Sauté"), 5,
              ("Hacka löken och vitlöken. Fräs dem i oljan på medelvärme i 5 minuter utan att de tar färg.",
               "Chop the onion and garlic. Sauté them in the oil over medium heat for 5 minutes without letting them brown."),
              [I(1, None, "gul lök"), I(2, None, "vitlöksklyfta"), I(2, "msk", "olivolja")]),
            S(("Koka", "Simmer"), 20,
              ("Tillsätt {{ ingredients[0] }}, {{ ingredients[1] }}, buljongtärningen och sockret. "
               "Låt soppan sjuda under lock i 20 minuter.",
               "Add {{ ingredients[0] }}, {{ ingredients[1] }}, the stock cube and the sugar. "
               "Simmer the soup under a lid for 20 minutes."),
              [I(2, "burk", "krossade tomater", ("à 400 g", "400 g each")), I(5, "dl", "vatten"), I(1, None, "buljongtärning"),
               I(1, "tsk", "socker")]),
            S(("Mixa", "Blend"), 0,
              ("Mixa soppan slät med en stavmixer. Rör ner grädden, värm upp och smaka av med salt och peppar.\n\n"
               "Servera med en klick gräddfil och färsk basilika.",
               "Blend the soup until smooth with a stick blender. Stir in the cream, heat through and season with salt and pepper.\n\n"
               "Serve with a spoonful of sour cream and fresh basil."),
              [I(1, "dl", "matlagningsgrädde"), I(0, None, "salt", no_amount=True), I(0, None, "svartpeppar", no_amount=True),
               H(*TO_SERVE), I(1, "dl", "gräddfil"), I(1, "kruka", "basilika")]),
        ],
    },
    {
        "id": 3, "slug": "citronkyckling", "image": "citronkyckling.webp",
        "name": ("Citron- och örtkyckling med rotfrukter", "Lemon and herb roast chicken with root vegetables"),
        "description": ("Helstekt kyckling på en bädd av rotfrukter. Ugnen gör jobbet medan du dukar.",
                        "A whole roast chicken on a bed of root vegetables. The oven does the work while you lay the table."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 25, "waiting": 85,
        "keywords": ["kyckling", "helg"], "created": 400, "updated": 120,
        "steps": [
            S(("Rotfrukter", "Root vegetables"), 0,
              ("Sätt ugnen på 200 grader. Skär rotfrukterna i bitar och lägg dem i en stor ugnsform med rödlöken i klyftor. "
               "Ringla över oljan.",
               "Heat the oven to 200 °C. Cut the vegetables into chunks and put them in a large roasting dish with the red onion "
               "in wedges. Drizzle with the oil."),
              [I(600, "g", "potatis", ("små", "small")), I(3, None, "morot"), I(2, None, "palsternacka"), I(1, None, "rödlök"),
               I(2, "msk", "olivolja")]),
            S(("Kycklingen", "The chicken"), 75,
              ("Fyll kycklingen med citronen i halvor, vitlöken och hälften av timjanen. Gnid in skinnet med smör, salt och resten "
               "av timjanen och lägg kycklingen på rotfrukterna.\n\n"
               "Stek i ugnen i 1 tim och 15 minuter, tills köttsaften är klar.",
               "Fill the chicken with the halved lemon, the garlic and half of the thyme. Rub the skin with butter, salt and the rest "
               "of the thyme and place the chicken on the vegetables.\n\n"
               "Roast for 1 hour and 15 minutes, until the juices run clear."),
              [I(1, None, "kyckling", ("ca 1,3 kg", "about 1.3 kg")), I(1, None, "citron"), I(4, None, "vitlöksklyfta"),
               I(1, "knippe", "timjan"), I(25, "g", "smör", ("rumsvarmt", "softened")), I(1, "tsk", "salt")]),
            S(("Vila", "Rest"), 10,
              ("Låt kycklingen vila under folie i 10 minuter innan du skär upp den.",
               "Let the chicken rest under foil for 10 minutes before carving."),
              []),
        ],
    },
    {
        "id": 4, "slug": "linsdahl", "image": "linsdahl.webp",
        "name": ("Gul linsdahl med kokosmjölk", "Yellow lentil dahl with coconut milk"),
        "description": ("Mild och värmande linsgryta med ingefära, gurkmeja och lime.",
                        "A mild, warming lentil stew with ginger, turmeric and lime."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 15, "waiting": 25,
        "keywords": ["vegetariskt", "indiskt", "vardag", "gryta"], "created": 220, "updated": 220,
        "properties": {1: 480, 2: 18, 3: 21, 4: 52},
        "steps": [
            S(("Kryddbas", "Spice base"), 5,
              ("Hacka löken och vitlöken. Fräs dem med ingefäran i oljan i 3–4 minuter, tillsätt kryddorna och fräs "
               "ytterligare 1 minut.",
               "Chop the onion and garlic. Fry them with the ginger in the oil for 3–4 minutes, add the spices and fry for "
               "another 1 minute."),
              [I(1, None, "gul lök"), I(3, None, "vitlöksklyfta"), I(1, "msk", "ingefära"), I(2, "msk", "rapsolja"),
               I(2, "tsk", "curry"), I(1, "tsk", "gurkmeja"), I(1, "tsk", "spiskummin")]),
            S(("Koka linserna", "Cook the lentils"), 25,
              ("Skölj {{ ingredients[0] }} och lägg dem i grytan med kokosmjölken, vattnet och buljongtärningen. "
               "Låt sjuda under omrörning i ca 20–25 min tills linserna är mjuka.",
               "Rinse {{ ingredients[0] }} and add them to the pot with the coconut milk, water and stock cube. "
               "Simmer, stirring now and then, for about 20–25 minutes until the lentils are soft."),
              [I(3, "dl", "röd lins"), I(1, "burk", "kokosmjölk"), I(6, "dl", "vatten"), I(1, None, "buljongtärning")]),
            S(("Servering", "Serving"), 0,
              ("Smaka av med limesaft och salt. Servera dahlen med ris och toppa med koriander.",
               "Season with lime juice and salt. Serve the dahl with rice and top with coriander."),
              [I(1, None, "lime"), I(0, None, "salt", no_amount=True), H(*TO_SERVE),
               I(3, "dl", "basmatiris", ("kokt enligt förpackningen", "cooked as the packet says")), I(1, "kruka", "koriander")]),
        ],
    },
    {
        "id": 5, "slug": "gronsaksgryta", "image": "gronsaksgryta.webp",
        "name": ("Grönsaksgryta med vita bönor", "Vegetable and white bean stew"),
        "description": ("Mustig gryta med tomat, timjan och bönor. Ännu godare dagen efter.",
                        "A hearty stew with tomato, thyme and beans. Even better the next day."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 20, "waiting": 25,
        "keywords": ["vegetariskt", "gryta"], "created": 1, "updated": 1,
        "steps": [
            S(("Fräs grönsakerna", "Sauté the vegetables"), 8,
              ("Skär grönsakerna i bitar och fräs dem i oljan i en tjock gryta i 8 minuter.",
               "Cut the vegetables into pieces and sauté them in the oil in a heavy pot for 8 minutes."),
              [I(1, None, "gul lök"), I(2, None, "morot"), I(2, "stjälk", "selleri"), I(1, None, "squash"),
               I(2, "msk", "olivolja")]),
            S(("Låt puttra", "Simmer"), 25,
              ("Tillsätt tomaterna, bönorna, vattnet, buljongtärningen, timjanen och lagerbladet. "
               "Låt grytan puttra under lock i 25 minuter. Smaka av med salt och peppar.",
               "Add the tomatoes, beans, water, stock cube, thyme and bay leaf. "
               "Let the stew simmer under a lid for 25 minutes. Season with salt and pepper."),
              [I(1, "burk", "krossade tomater"), I(2, "burk", "vita bönor", ("sköljda", "rinsed")), I(4, "dl", "vatten"),
               I(1, None, "buljongtärning"), I(1, "tsk", "torkad timjan"), I(1, None, "lagerblad"),
               I(0, None, "salt", no_amount=True), I(0, None, "svartpeppar", no_amount=True)]),
            S(None, 0,
              ("Strö över hackad persilja och servera med gott bröd.",
               "Scatter over chopped parsley and serve with good bread."),
              [H(*TO_SERVE), I(1, "knippe", "persilja"), I(0, None, "surdegsbröd", no_amount=True)]),
        ],
    },
    {
        "id": 6, "slug": "kardemummabullar", "image": "kardemummabullar.webp",
        "name": ("Kardemummabullar", "Cardamom buns"),
        "description": ("Knutar med smör, socker och mycket kardemumma. Doftar fika i hela huset.",
                        "Knotted buns with butter, sugar and plenty of cardamom. The whole house smells of fika."),
        "servings": 24, "servings_text": ("bullar", "buns"), "working": 45, "waiting": 85,
        "keywords": ["bakning", "fika", "helg"], "created": 500, "updated": 500,
        "steps": [
            S(("Degen", "The dough"), 10,
              ("Smält smöret och häll i mjölken. Värm till 37 grader. Smula {{ ingredients[1] }} i en bunke och rör ut den med "
               "lite av degvätskan. Tillsätt resten av vätskan, sockret, kardemumman, saltet och det mesta av mjölet.\n\n"
               "Knåda degen smidig i 10 minuter.",
               "Melt the butter and add the milk. Warm to 37 °C. Crumble {{ ingredients[1] }} into a bowl and stir in a little of "
               "the liquid. Add the rest of the liquid, the sugar, cardamom, salt and most of the flour.\n\n"
               "Knead the dough until smooth, for 10 minutes."),
              [H("Deg", "Dough"), I(50, "g", "jäst"), I(5, "dl", "mjölk"), I(150, "g", "smör"), I(1, "dl", "socker"),
               I(1, "msk", "kardemumma"), I(0.5, "tsk", "salt"), I(13, "dl", "vetemjöl", ("ca", "about"))]),
            S(("Jäsning", "First rise"), 45,
              ("Låt degen jäsa under bakduk i 45 minuter.",
               "Let the dough rise under a tea towel for 45 minutes."),
              []),
            S(("Fyllning och formning", "Filling and shaping"), 30,
              ("Rör ihop {{ ingredients[1] }}, sockret och kardemumman till en fyllning. Kavla ut degen, bred på fyllningen, "
               "vik och skär i strimlor. Forma ca {{ scale(24) }} knutar och lägg dem på plåtar med bakplåtspapper. "
               "Låt dem jäsa i 30 minuter.",
               "Mix {{ ingredients[1] }}, the sugar and the cardamom into a filling. Roll out the dough, spread the filling, "
               "fold and cut into strips. Shape about {{ scale(24) }} knots and place them on lined baking trays. "
               "Let them rise for 30 minutes."),
              [H("Fyllning", "Filling"), I(100, "g", "smör", ("rumsvarmt", "softened")), I(0.75, "dl", "socker"),
               I(1, "msk", "kardemumma")]),
            S(("Gräddning", "Baking"), 10,
              ("Sätt ugnen på 225 grader. Pensla bullarna med uppvispat ägg, strö över pärlsocker och grädda mitt i ugnen "
               "i 8–10 minuter.",
               "Heat the oven to 225 °C. Brush the buns with beaten egg, sprinkle with pearl sugar and bake in the middle of the "
               "oven for 8–10 minutes."),
              [H("Pensling", "Glaze"), I(1, None, "ägg"), I(0.5, "dl", "pärlsocker")]),
        ],
    },
    {
        "id": 7, "slug": "pannkakor", "image": "pannkakor.webp",
        "name": ("Pannkakor", "Swedish pancakes"),
        "description": ("Tunna pannkakor med sylt och vispad grädde. Torsdagsklassikern alla tycker om.",
                        "Thin pancakes with jam and whipped cream. The Thursday classic everyone likes."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 30, "waiting": 30,
        "keywords": ["vardag", "vegetariskt", "klassiker"], "created": 700, "updated": 210,
        "steps": [
            S(("Smeten", "The batter"), 30,
              ("Vispa ihop mjölet, saltet och hälften av mjölken till en slät smet. Vispa i resten av mjölken och "
               "{{ ingredients[3] }}. Låt smeten vila i 30 minuter.",
               "Whisk the flour, salt and half of the milk into a smooth batter. Whisk in the rest of the milk and "
               "{{ ingredients[3] }}. Let the batter rest for 30 minutes."),
              [I(2.5, "dl", "vetemjöl"), I(0.5, "tsk", "salt"), I(6, "dl", "mjölk"), I(3, None, "ägg")]),
            S(("Stek", "Fry"), 25,
              ("Smält lite smör i en stekpanna på medelhög värme. Stek ca {{ scale(12) }} tunna pannkakor, ungefär 1 minut "
               "per sida.",
               "Melt a little butter in a frying pan over medium-high heat. Fry about {{ scale(12) }} thin pancakes, about "
               "1 minute per side."),
              [I(3, "msk", "smör", ("till stekning", "for frying"))]),
            S(None, 0,
              ("Servera pannkakorna med sylt och vispad grädde.",
               "Serve the pancakes with jam and whipped cream."),
              [H(*TO_SERVE), I(3, "dl", "vispgrädde", ("vispad", "whipped")), I(2, "dl", "jordgubbssylt")]),
        ],
    },
    {
        "id": 8, "slug": "kottbullar", "image": "kottbullar.webp",
        "name": ("Köttbullar med gräddsås", "Meatballs with cream sauce"),
        "description": ("Hemrullade köttbullar, len gräddsås, potatis och lingonsylt.",
                        "Home-rolled meatballs, a smooth cream sauce, potatoes and lingonberry jam."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 45, "waiting": 0,
        "keywords": ["vardag", "klassiker"], "created": 600, "updated": 600,
        "steps": [
            S(("Köttbullar", "Meatballs"), 10,
              ("Blanda {{ ingredients[1] }} med {{ ingredients[2] }} och låt svälla i 10 minuter. Rör ner löken, färsen, ägget "
               "och kryddorna. Rulla ca {{ scale(30) }} köttbullar med fuktiga händer.",
               "Mix {{ ingredients[1] }} with {{ ingredients[2] }} and leave to swell for 10 minutes. Stir in the onion, mince, "
               "egg and seasoning. Roll about {{ scale(30) }} meatballs with damp hands."),
              [H("Färs", "Mince"), I(0.5, "dl", "ströbröd"), I(1, "dl", "mjölk"), I(1, None, "gul lök", ("finriven", "finely grated")),
               I(500, "g", "blandfärs"), I(1, None, "ägg"), I(1, "tsk", "salt"), I(0.5, "tsk", "vitpeppar")]),
            S(("Stek", "Fry"), 15,
              ("Bryn köttbullarna i smör i omgångar, ca 6–8 minuter per omgång, tills de är genomstekta.",
               "Brown the meatballs in butter in batches, about 6–8 minutes per batch, until cooked through."),
              [I(2, "msk", "smör")]),
            S(("Gräddsås", "Cream sauce"), 3,
              ("Vispa ner mjölet i stekfettet, späd med grädden, vattnet och fonden och låt såsen koka i 3 minuter. "
               "Smaka av med soja.",
               "Whisk the flour into the pan juices, add the cream, water and stock and let the sauce boil for 3 minutes. "
               "Season with soy sauce."),
              [I(1, "msk", "vetemjöl"), I(3, "dl", "matlagningsgrädde"), I(1, "dl", "vatten"), I(1, "msk", "kalvfond"),
               I(1, "tsk", "soja")]),
            S(None, 0,
              ("Servera med potatis och lingonsylt.",
               "Serve with potatoes and lingonberry jam."),
              [H(*TO_SERVE), I(1, "kg", "potatis", ("kokt eller som mos", "boiled or mashed")), I(1, "dl", "lingonsylt")]),
        ],
    },
    {
        "id": 9, "slug": "kottfarssas", "image": "kottfarssas.webp",
        "name": ("Spaghetti med köttfärssås", "Spaghetti with meat sauce"),
        "description": ("Långkokt köttfärssås med tomat och oregano. Gör gärna dubbel sats och frys in.",
                        "A slow-simmered meat sauce with tomato and oregano. Make a double batch and freeze half."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 20, "waiting": 45,
        "keywords": ["pasta", "vardag", "italienskt"], "created": 650, "updated": 90,
        "steps": [
            S(("Såsen", "The sauce"), 50,
              ("Hacka lök och vitlök och fräs dem med moroten i oljan i 5 minuter. Tillsätt färsen och bryn den. Rör ner "
               "tomatpurén, tomaterna, vattnet och kryddorna.\n\n"
               "Låt såsen puttra på svag värme i 45 minuter.",
               "Chop the onion and garlic and sauté them with the carrot in the oil for 5 minutes. Add the mince and brown it. "
               "Stir in the tomato purée, tomatoes, water and seasoning.\n\n"
               "Let the sauce simmer over low heat for 45 minutes."),
              [I(1, None, "gul lök"), I(2, None, "vitlöksklyfta"), I(1, None, "morot", ("finriven", "finely grated")),
               I(2, "msk", "olivolja"), I(500, "g", "nötfärs"), I(2, "msk", "tomatpuré"), I(1, "burk", "krossade tomater"),
               I(1, "dl", "vatten"), I(1, "tsk", "oregano"), I(1, "tsk", "salt")]),
            S(("Pastan", "The pasta"), 11,
              ("Koka spaghettin i rikligt med saltat vatten i 9–11 minuter, enligt anvisningen på förpackningen.",
               "Cook the spaghetti in plenty of salted water for 9–11 minutes, as the packet says."),
              [I(400, "g", "spaghetti"), I(0, None, "salt", no_amount=True)]),
            S(None, 0,
              ("Servera såsen över pastan och toppa med riven parmesan.",
               "Spoon the sauce over the pasta and top with grated parmesan."),
              [H(*TO_SERVE), I(50, "g", "parmesan", ("riven", "grated"))]),
        ],
    },
    {
        "id": 10, "slug": "fredagstacos", "image": "fredagstacos.webp",
        "name": ("Fredagstacos", "Friday tacos"),
        "description": ("Kryddig färs, krispiga grönsaker och alla tillbehör på bordet. Alla bygger sina egna.",
                        "Spiced mince, crisp vegetables and every topping on the table. Everyone builds their own."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 30, "waiting": 0,
        "keywords": ["vardag", "mexikanskt", "snabbt"], "created": 550, "updated": 550,
        "steps": [
            S(("Färsen", "The filling"), 5,
              ("Bryn färsen i en het stekpanna. Tillsätt {{ ingredients[1] }} och vattnet och låt koka ihop i 5 minuter.",
               "Brown the mince in a hot frying pan. Add {{ ingredients[1] }} and the water and let it simmer for 5 minutes."),
              [I(500, "g", "nötfärs"), I(1, "påse", "tacokrydda"), I(1.5, "dl", "vatten")]),
            S(("Tillbehör", "Toppings"), 0,
              ("Skär grönsakerna i små bitar och lägg upp tillbehören i skålar.",
               "Chop the vegetables into small pieces and put the toppings in bowls."),
              [I(2, None, "tomat"), I(0.5, None, "gurka"), I(1, None, "avokado"), I(0.5, "huvud", "isbergssallad"),
               I(1, "burk", "majs"), I(2, "dl", "gräddfil"), I(150, "g", "riven ost")]),
            S(("Servering", "Serving"), 5,
              ("Värm tortillorna i ugnen på 150 grader i 5 minuter och servera med färsen och tillbehören.",
               "Warm the tortillas in the oven at 150 °C for 5 minutes and serve with the filling and toppings."),
              [I(8, None, "tortilla"), I(1, "burk", "tacosås")]),
        ],
    },
    {
        "id": 11, "slug": "kladdkaka", "image": "kladdkaka.webp",
        "name": ("Kladdkaka", "Sticky chocolate cake"),
        "description": ("Seg chokladkaka som är klar på en halvtimme. Servera med grädde och hallon.",
                        "A gooey chocolate cake ready in half an hour. Serve with cream and raspberries."),
        "servings": 8, "servings_text": ("bitar", "slices"), "working": 10, "waiting": 18,
        "diameter": 24, "diameter_text": ("cm", "cm"),
        "keywords": ["bakning", "fika", "snabbt"], "created": 450, "updated": 450,
        "steps": [
            S(("Smeten", "The batter"), 0,
              ("Sätt ugnen på 175 grader. Smält smöret och rör ner sockret, äggen, mjölet, kakaon, vaniljsockret och saltet "
               "till en jämn smet.",
               "Heat the oven to 175 °C. Melt the butter and stir in the sugar, eggs, flour, cocoa, vanilla sugar and salt "
               "until smooth."),
              [I(100, "g", "smör"), I(2, None, "ägg"), I(2.5, "dl", "socker"), I(1.5, "dl", "vetemjöl"), I(3, "msk", "kakao"),
               I(1, "tsk", "vaniljsocker"), I(1, "nypa", "salt")]),
            S(("Grädda", "Bake"), 18,
              ("Smörj och bröa en form med löstagbar kant, ca 24 cm i diameter. Häll i smeten och grädda mitt i ugnen "
               "i 15–18 minuter. Kakan ska vara kladdig i mitten.",
               "Grease a springform tin, about 24 cm across, and dust it with breadcrumbs. Pour in the batter and bake in the "
               "middle of the oven for 15–18 minutes. The cake should still be sticky in the middle."),
              [I(1, "msk", "smör", ("till formen", "for the tin")), I(1, "msk", "ströbröd")]),
            S(None, 60,
              ("Låt kakan svalna i minst 1 timme. Servera med vispad grädde och hallon.",
               "Let the cake cool for at least 1 hour. Serve with whipped cream and raspberries."),
              [H(*TO_SERVE), I(2, "dl", "vispgrädde", ("vispad", "whipped")), I(150, "g", "hallon")]),
        ],
    },
    {
        "id": 12, "slug": "wokade-nudlar", "image": "wokade-nudlar.webp",
        "name": ("Wokade nudlar med jordnötssås", "Stir-fried noodles with peanut sauce"),
        "description": ("Krispiga grönsaker, nudlar och en snabb jordnötssås. Middag på en kvart.",
                        "Crunchy vegetables, noodles and a quick peanut sauce. Dinner in fifteen minutes."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 25, "waiting": 0,
        "keywords": ["vegetariskt", "snabbt", "asiatiskt"], "created": 4, "updated": 4,
        "steps": [
            S(("Jordnötssås", "Peanut sauce"), 0,
              ("Vispa ihop alla ingredienser till en slät sås.",
               "Whisk everything together into a smooth sauce."),
              [I(3, "msk", "jordnötssmör"), I(3, "msk", "soja"), I(1, "msk", "sesamolja"), I(1, "msk", "limesaft"),
               I(1, "tsk", "sambal"), I(0.5, "dl", "vatten")]),
            S(("Nudlar", "Noodles"), 4,
              ("Koka nudlarna i 4 minuter, häll av och skölj dem i kallt vatten.",
               "Boil the noodles for 4 minutes, drain and rinse them in cold water."),
              [I(250, "g", "äggnudlar")]),
            S(("Woka", "Stir-fry"), 5,
              ("Hetta upp oljan i en wok. Woka broccolin, paprikan och morötterna i 3 minuter. Tillsätt nudlarna och såsen och "
               "woka ytterligare 2 minuter.\n\n"
               "Toppa med salladslök och jordnötter.",
               "Heat the oil in a wok. Stir-fry the broccoli, pepper and carrots for 3 minutes. Add the noodles and sauce and "
               "stir-fry for another 2 minutes.\n\n"
               "Top with spring onion and peanuts."),
              [I(250, "g", "broccoli"), I(1, None, "röd paprika"), I(2, None, "morot"), I(2, "msk", "rapsolja"),
               I(4, None, "salladslök"), I(0.5, "dl", "jordnötter", ("grovhackade", "roughly chopped"))]),
        ],
    },
    {
        "id": 13, "slug": "havregrynsgrot", "image": "havregrynsgrot.webp",
        "name": ("Havregrynsgröt med äpple och kanel", "Porridge with apple and cinnamon"),
        "description": ("En varm frukost som håller länge. Rivet äpple och kanel gör den lite festlig.",
                        "A warm breakfast that keeps you going. Grated apple and cinnamon make it a little special."),
        "servings": 2, "servings_text": ("portioner", "servings"), "working": 10, "waiting": 0,
        "keywords": ["frukost", "vegetariskt", "snabbt"], "created": 800, "updated": 800,
        "nutrition": {"carbohydrates": 54, "fats": 7, "proteins": 11, "calories": 330,
                      "source": ("Uppskattning per portion", "Estimate per serving")},
        "steps": [
            S(("Gröten", "The porridge"), 5,
              ("Koka upp havregrynen, vattnet och saltet under omrörning. Låt gröten sjuda på svag värme i 3–5 minuter.",
               "Bring the oats, water and salt to the boil, stirring. Let the porridge simmer over low heat for 3–5 minutes."),
              [I(2, "dl", "havregryn"), I(4, "dl", "vatten"), I(1, "nypa", "salt")]),
            S(("Toppa", "Top"), 0,
              ("Toppa gröten med rivet äpple, kanel och en skvätt honung. Servera med kall mjölk.",
               "Top the porridge with grated apple, cinnamon and a drizzle of honey. Serve with cold milk."),
              [I(1, None, "äpple", ("rivet", "grated")), I(0.5, "tsk", "kanel"), I(1, "msk", "honung"),
               I(2, "dl", "mjölk", ("till servering", "to serve"))]),
        ],
    },
    {
        "id": 14, "slug": "halloumisallad", "image": "halloumisallad.webp",
        "name": ("Halloumisallad med bulgur", "Halloumi salad with bulgur"),
        "description": ("Matig sallad med stekt halloumi, bulgur, tomater och mynta. Perfekt som lunchlåda.",
                        "A filling salad with fried halloumi, bulgur, tomatoes and mint. Great for packed lunches."),
        "servings": 4, "servings_text": ("portioner", "servings"), "working": 25, "waiting": 0,
        "keywords": ["sallad", "vegetariskt", "snabbt"], "created": 2, "updated": 2,
        "steps": [
            S(("Bulgur", "Bulgur"), 10,
              ("Koka {{ ingredients[0] }} i vattnet med buljongtärningen i 10 minuter. Låt den svalna lite.",
               "Cook {{ ingredients[0] }} in the water with the stock cube for 10 minutes. Leave to cool a little."),
              [I(3, "dl", "bulgur"), I(6, "dl", "vatten"), I(1, None, "buljongtärning")]),
            S(("Halloumi", "Halloumi"), 4,
              ("Skär halloumin i skivor och stek dem gyllene i oljan, ca 2 minuter per sida.",
               "Slice the halloumi and fry it in the oil until golden, about 2 minutes per side."),
              [I(2, "förp", "halloumi", ("à 200 g", "200 g each")), I(1, "msk", "olivolja")]),
            S(("Sallad", "Salad"), 0,
              ("Blanda bulguren med tomater, gurka, rödlök och ruccola. Vispa ihop citronsaft och olivolja till en dressing, "
               "vänd ner den och toppa med halloumi och mynta.",
               "Toss the bulgur with the tomatoes, cucumber, red onion and rocket. Whisk the lemon juice and olive oil into a "
               "dressing, fold it through and top with the halloumi and mint."),
              [I(250, "g", "körsbärstomat"), I(1, None, "gurka"), I(0.5, None, "rödlök"), I(1, "påse", "ruccola"),
               I(1, None, "citron", ("saften", "juice only")), I(3, "msk", "olivolja"), I(1, "kruka", "mynta")]),
        ],
    },
]

# --- Cook log: recipe id -> [(days before the reference day, rating or None)] -------------------
# Drives "Ofta lagade" (times cooked), "Inte lagat på länge" (last cooked) and the ratings.
COOK_LOG = {
    1: [(10, 5), (45, 4)],
    2: [(3, 4), (17, 4), (31, 5), (60, None), (90, None), (120, 4)],
    3: [(75, 5), (160, 5), (260, None)],
    4: [(50, 4), (110, 5), (200, None)],
    6: [(120, 5), (300, None)],
    7: [(5, 5), (12, None), (19, 5), (26, None), (40, None), (54, 4), (68, None), (82, None), (96, 5)],
    8: [(9, 4), (30, 5), (58, None), (85, None), (140, 4)],
    9: [(1, 4), (8, 4), (22, None), (36, 5), (50, None), (64, None), (99, 4)],
    10: [(6, 5), (13, None), (27, 4), (41, None), (69, None), (97, 5)],
    11: [(20, 5), (70, None), (150, None), (230, 4)],
    13: [(2, None), (4, None), (7, 4), (11, None), (15, None), (22, None), (29, 5), (36, None)],
}

# Recently viewed: (recipe id, days before the reference day, hour UTC).
VIEW_LOG = [(9, 0, 9), (12, 1, 17), (1, 1, 11), (7, 3, 16), (4, 5, 12)]

# Recipes the demo user has pinned on "Idag" (app-local in Tandem, not a Tandoor field).
PINNED = [7, 2]

# --- Meal plan: (day offset from today, meal type id, recipe id or None, servings, title, note) --
MEAL_PLAN = [
    (-1, 3, 9, 4, None, None),
    (0, 1, 13, 2, None, None),
    (0, 2, 2, 2, None, ("Ta med resten till jobbet i morgon.", "Take the leftovers to work tomorrow.")),
    (0, 3, 1, 4, None, None),
    (1, 2, 14, 2, None, None),
    (1, 3, 4, 4, None, None),
    (2, 3, 8, 4, None, None),
    (3, 3, None, 2, ("Rester", "Leftovers"), ("Ät upp det som finns i **kylen**.", "Finish what is in the **fridge**.")),
    (4, 3, 10, 4, None, None),
    (4, 4, 11, 8, None, None),
    (5, 1, 7, 4, None, ("Med blåbär om det finns", "With blueberries if there are any")),
    (5, 3, 3, 4, None, None),
    (5, 4, 6, 24, None, None, 6),  # Two-day entry: buns for Saturday and Sunday fika.
    (6, 3, 12, 4, None, None),
    (7, 3, 5, 4, None, None),
]

# --- Recipe books (plan 6.2: "två böcker (Favoriter, Vill laga)") ------------------------------
BOOKS = [
    (1, ("Favoriter", "Favourites"), ("Det vi lagar om och om igen.", "The ones we cook again and again."), [7, 8, 11, 2, 9]),
    (2, ("Vill laga", "Want to cook"), ("Nya recept att testa snart.", "New recipes to try soon."), [5, 12, 14, 3]),
]

DEMO_USER = {"id": 1, "username": "demo", "first_name": "", "last_name": "", "display_name": "Demo",
             "is_staff": False, "is_superuser": False, "is_active": True}

USER_PREFERENCE_OVERRIDES = {
    "use_fractions": True,
    "ingredient_decimals": 2,
    "default_unit": "g",
    "use_kj": False,
    "show_step_ingredients": True,
    "comments": True,
}
