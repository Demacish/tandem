#!/usr/bin/env python3
"""Generates the demo shopping list (Phase 2, plan 6.2 "Demoläge") next to the other demo content.

Writes Tandoor 2.6-shaped JSON (the shapes of api-schema/fixtures/2.6.13/fas2/) to
core/data/src/main/assets/demo/<lang>/:

  shopping-lists.json           ShoppingList elements of GET /api/shopping-list/
  supermarket-categories.json   SupermarketCategory elements of GET /api/supermarket-category/
  supermarkets.json             Supermarket elements of GET /api/supermarket/ (with the aisle order)
  shopping-list-entries.json    ShoppingListEntry elements of GET /api/shopping-list-entry/

The entries use the foods and units of demo_source.py (same ids as the recipes) plus a few
household items, and the ingredients of tomorrow's dinner from the generated recipe JSON, so run
generate_demo_content.py first. Dates are relative to the reference day like everything else.
Kept apart from generate_demo_content.py so the recipe content and its files stay untouched.

Usage: python3 scripts/demo-content/generate_demo_shopping.py   (from the repository root)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import demo_source as src  # noqa: E402
import generate_demo_content as gen  # noqa: E402

LANGS = gen.LANGS

LISTS = [
    (1, ("Veckohandling", "Weekly shop"), "#2E7D32"),
    (2, ("Helgen", "Weekend"), "#1565C0"),
]

CATEGORIES = [
    (1, ("Frukt & grönt", "Fruit & veg")),
    (2, ("Bröd", "Bakery")),
    (3, ("Mejeri", "Dairy")),
    (4, ("Kött & fisk", "Meat & fish")),
    (5, ("Skafferi", "Pantry")),
    (6, ("Frys", "Frozen")),
    (7, ("Hushåll", "Household")),
]

# Two stores with their own walking order (category ids); the small one has no meat counter.
STORES = [
    (1, ("Matbutiken", "The supermarket"), [1, 2, 4, 3, 5, 6, 7], [1]),
    (2, ("Närbutiken", "The corner shop"), [3, 2, 1, 5, 7, 6], [2]),
]

# Foods that are no recipe ingredient: key -> (id, sv, sv plural, en, en plural).
EXTRA_FOODS = {
    "banan": (901, "banan", "bananer", "banana", "bananas"),
    "kaffe": (902, "kaffe", None, "coffee", None),
    "tandkräm": (903, "tandkräm", None, "toothpaste", None),
    "glass": (904, "vaniljglass", None, "vanilla ice cream", None),
    "diskmedel": (905, "diskmedel", None, "washing-up liquid", None),
}

# Aisle of each food used below.
FOOD_CATEGORY = {
    "mjölk": 3, "ägg": 3, "smör": 3, "banan": 1, "kaffe": 5, "tandkräm": 7, "surdegsbröd": 2, "havregryn": 5,
    "glass": 6, "diskmedel": 7, "äpple": 1, "laxfilé": 4,
    "gul lök": 1, "vitlöksklyfta": 1, "röd lins": 5, "kokosmjölk": 5, "lime": 1, "basmatiris": 5, "koriander": 1,
}

PARTNER = {"id": 2, "username": "alex", "first_name": "Alex", "last_name": "", "display_name": "Alex",
           "is_staff": False, "is_superuser": False, "is_active": True}

# Manual entries: (food key, amount, unit key or None, added by, checked days ago or None,
#                  delayed until (days from the reference day) or None, list ids, created days ago)
MANUAL = [
    ("mjölk", 2, "l", src.DEMO_USER, None, None, [1], 1),
    ("ägg", 12, None, PARTNER, None, None, [1], 1),
    ("banan", 6, None, PARTNER, None, None, [1], 0),
    ("kaffe", 1, "påse", src.DEMO_USER, None, None, [1], 2),
    ("tandkräm", 1, None, PARTNER, None, None, [], 0),
    ("laxfilé", 600, "g", src.DEMO_USER, None, None, [1], 0),
    ("glass", 1, "förp", PARTNER, None, None, [2], 0),
    ("havregryn", 1, "påse", src.DEMO_USER, None, -1, [1], 3),
    ("diskmedel", 1, None, src.DEMO_USER, None, 2, [], 4),
    ("smör", 1, "förp", src.DEMO_USER, 1, None, [1], 2),
    ("surdegsbröd", 1, None, PARTNER, 0, None, [1], 1),
    ("äpple", 4, None, src.DEMO_USER, 2, None, [1], 3),
]

# Tomorrow's dinner (MEAL_PLAN index 5 → meal plan id 6) is on the list: these ingredients.
MEAL_INDEX = 5
MEAL_FOODS = ["gul lök", "vitlöksklyfta", "röd lins", "kokosmjölk", "lime", "basmatiris", "koriander"]


def pick(pair, lang):
    return gen.pick(pair, lang)


def category_json(category_id, lang):
    for cid, name in CATEGORIES:
        if cid == category_id:
            return {"id": cid, "name": pick(name, lang), "description": "", "open_data_slug": None}
    raise KeyError(category_id)


def list_json(list_id, lang):
    for lid, name, color in LISTS:
        if lid == list_id:
            return {"id": lid, "name": pick(name, lang), "description": "", "color": color}
    raise KeyError(list_id)


def food_json(key, lang):
    if key in EXTRA_FOODS:
        food_id, sv, sv_pl, en, en_pl = EXTRA_FOODS[key]
    else:
        food_id = gen.FOOD_IDS[key]
        sv, sv_pl, en, en_pl = src.FOODS[key]
    name, plural = (sv, sv_pl) if lang == "sv" else (en, en_pl)
    category = FOOD_CATEGORY.get(key)
    return {"id": food_id, "name": name, "plural_name": plural,
            "supermarket_category": category_json(category, lang) if category else None, "shopping_lists": []}


def unit_json(key, lang):
    if key == "l" or key in src.UNITS:
        return gen.unit_json(key, lang)
    raise KeyError(key)


def entry_json(entry_id, food, amount, unit, user, checked_days, delayed_days, lists, created_days, lang,
               list_recipe=None, ingredient=None):
    created = gen.stamp(created_days, 9 + entry_id % 8, 5 * (entry_id % 12))
    return {
        "id": entry_id,
        "list_recipe": list_recipe["id"] if list_recipe else None,
        "shopping_lists": [list_json(i, lang) for i in lists],
        "food": food,
        "unit": unit,
        "amount": float(amount),
        "order": 0,
        "checked": checked_days is not None,
        "ingredient": ingredient,
        "list_recipe_data": list_recipe,
        "created_by": user,
        "created_at": created,
        "updated_at": gen.stamp(checked_days, 17, 10) if checked_days is not None else created,
        "completed_at": gen.stamp(checked_days, 17, 10) if checked_days is not None else None,
        "delay_until": gen.stamp(delayed_days, 8) if delayed_days is not None else None,
    }


def main():
    out = gen.OUT
    for lang in LANGS:
        gen.write_json(os.path.join(out, lang, "shopping-lists.json"), [list_json(lid, lang) for lid, _, _ in LISTS])
        gen.write_json(os.path.join(out, lang, "supermarket-categories.json"), [category_json(cid, lang) for cid, _ in CATEGORIES])
        stores = []
        relation_id = 1
        for store_id, name, order, lists in STORES:
            relations = []
            for position, category_id in enumerate(order):
                relations.append({"id": relation_id, "category": category_json(category_id, lang), "supermarket": store_id,
                                  "order": position})
                relation_id += 1
            stores.append({"id": store_id, "name": pick(name, lang), "description": "",
                           "shopping_lists": [list_json(i, lang) for i in lists], "category_to_supermarket": relations,
                           "open_data_slug": None})
        gen.write_json(os.path.join(out, lang, "supermarkets.json"), stores)

        entries = []
        for food, amount, unit, user, checked, delayed, lists, created in MANUAL:
            entries.append(entry_json(len(entries) + 1, food_json(food, lang), amount,
                                      unit_json(unit, lang) if unit else None, user, checked, delayed, lists, created, lang))

        meal_day, _, recipe_id, servings = src.MEAL_PLAN[MEAL_INDEX][:4]
        with open(os.path.join(out, lang, f"recipe-{recipe_id}.json"), encoding="utf-8") as f:
            recipe = json.load(f)
        with open(os.path.join(out, lang, "recipe-overviews.json"), encoding="utf-8") as f:
            overview = next(o for o in json.load(f) if o["id"] == recipe_id)
        list_recipe = {"id": 1, "name": "", "recipe": recipe_id, "recipe_data": overview, "meal_plan_data": None,
                       "mealplan": MEAL_INDEX + 1, "servings": float(servings), "created_by": src.DEMO_USER}
        ingredients = [i for step in recipe["steps"] for i in step["ingredients"] if not i["is_header"] and i["food"]]
        food_ids = {gen.FOOD_IDS[key]: key for key in MEAL_FOODS}
        for ingredient in ingredients:
            key = food_ids.get(ingredient["food"]["id"])
            if key is None:
                continue
            unit = ingredient["unit"]
            entries.append(entry_json(len(entries) + 1, food_json(key, lang), ingredient["amount"] * servings / recipe["servings"],
                                      unit, src.DEMO_USER, None, None, [1], 0, lang, list_recipe, ingredient["id"]))
        gen.write_json(os.path.join(out, lang, "shopping-list-entries.json"), entries)
    print(f"Wrote the demo shopping list ({len(MANUAL)} manual entries and tomorrow's dinner) to {os.path.relpath(out, gen.ROOT)}")


if __name__ == "__main__":
    main()
