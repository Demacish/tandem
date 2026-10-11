#!/usr/bin/env python3
"""Generates Tandem's demo content (plan 6.2 "Demoläge") from demo_source.py.

Writes Tandoor 2.6-shaped JSON to core/data/src/main/assets/demo/ (see README.md there):

  index.json                    manifest: reference day, recipe ids, pinned recipes, images
  cook-log.json, view-log.json  CookLog / ViewLog elements (language-neutral)
  <lang>/recipe-<id>.json       GET /api/recipe/{id}/ bodies
  <lang>/recipe-overviews.json  RecipeOverview elements of GET /api/recipe/ (sorted by name)
  <lang>/recipe-flat.json       GET /api/recipe/flat/
  <lang>/keywords.json, meal-types.json, meal-plan.json, recipe-books.json,
  <lang>/recipe-book-entries.json, user-preference.json

All dates are written relative to the reference day 2000-01-15 (like the recorded fixtures);
DemoContent shifts them so that the reference day becomes today. Instructions are rendered to
`instructions_markdown` the way Tandoor 2.6.13 does (scalable-number / plural-name tags).

Usage: python3 scripts/demo-content/generate_demo_content.py   (from the repository root)
"""
import datetime
import html
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import demo_source as src  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "core", "data", "src", "main", "assets", "demo")
REFERENCE_DAY = datetime.date(2000, 1, 15)
LANGS = ("sv", "en")
IMAGE_PREFIX = "file:///android_asset/demo/images/"
CONTENT_VERSION = 1


def pick(pair, lang):
    if pair is None:
        return None
    return pair[0] if lang == "sv" else pair[1]


def stamp(days_before, hour=10, minute=0):
    """A Tandoor date-time `days_before` the reference day, in UTC."""
    day = REFERENCE_DAY - datetime.timedelta(days=days_before)
    return f"{day.isoformat()}T{hour:02d}:{minute:02d}:00Z"


def py_float(value):
    """Python's str(float) like Tandoor's template output (250.0, 0.5, 0.6666666666666666)."""
    return str(float(value))


# --- Reference tables with stable ids -------------------------------------------------------------

UNIT_IDS = {key: i + 1 for i, key in enumerate(src.UNITS)}
FOOD_IDS = {key: i + 1 for i, key in enumerate(src.FOODS)}
KEYWORD_IDS = {key: i + 1 for i, key in enumerate(src.KEYWORDS)}


def unit_json(key, lang):
    sv, sv_pl, en, en_pl, base = src.UNITS[key]
    name, plural = (sv, sv_pl) if lang == "sv" else (en, en_pl)
    return {"id": UNIT_IDS[key], "name": name, "plural_name": plural, "description": None, "base_unit": base,
            "open_data_slug": None}


def food_json(key, lang):
    sv, sv_pl, en, en_pl = src.FOODS[key]
    name, plural = (sv, sv_pl) if lang == "sv" else (en, en_pl)
    return {
        "id": FOOD_IDS[key], "name": name, "plural_name": plural, "description": "", "recipe": None, "url": "",
        "properties": [], "properties_food_amount": 100.0, "properties_food_unit": None, "fdc_id": None,
        "food_onhand": False, "supermarket_category": None, "parent": None, "numchild": 0, "inherit_fields": [],
        "full_name": name, "ignore_shopping": False, "substitute": [], "substitute_siblings": False,
        "substitute_children": False, "substitute_onhand": False, "child_inherit_fields": [], "open_data_slug": None,
        "shopping_lists": [],
    }


def keyword_name(key, lang):
    return pick(src.KEYWORDS[key][:2], lang)


def keyword_json(key, lang):
    parent = src.KEYWORDS[key][2]
    name = keyword_name(key, lang)
    full = f"{keyword_name(parent, lang)} > {name}" if parent else name
    children = sum(1 for k in src.KEYWORDS.values() if k[2] == key)
    return {"id": KEYWORD_IDS[key], "name": name, "label": name, "description": "",
            "parent": KEYWORD_IDS[parent] if parent else None, "numchild": children,
            "created_at": stamp(900), "updated_at": stamp(900), "full_name": full}


def meal_type_json(meal_type_id, lang):
    for mt_id, sv, en, order, time, color in src.MEAL_TYPES:
        if mt_id == meal_type_id:
            return {"id": mt_id, "name": sv if lang == "sv" else en, "order": order, "time": time, "color": color,
                    "created_by": src.DEMO_USER["id"]}
    raise KeyError(meal_type_id)


# --- Conversions (FACTS "Fas 1 d": the ingredient itself first, then reachable units) -----------

CONVERSIONS = {"g": [("kg", 0.001)], "kg": [("g", 1000.0)], "dl": [("l", 0.1)], "l": [("dl", 10.0)],
               "msk": [("tsk", 3.0)], "tsk": [("msk", 1.0 / 3.0)]}


def conversions(ingredient, lang):
    unit = ingredient["unit"]
    if unit is None or ingredient["no_amount"]:
        return []
    food = food_json(ingredient["food"], lang)["name"]
    amount = float(ingredient["amount"])
    result = [{"food": food, "unit": unit_json(unit, lang)["name"], "amount": amount}]
    for other, factor in CONVERSIONS.get(unit, []):
        result.append({"food": food, "unit": unit_json(other, lang)["name"], "amount": round(amount * factor, 4)})
    return result


# --- Template + Markdown rendering as Tandoor 2.6.13 does it ------------------------------------

TEMPLATE = re.compile(r"\{\{(.*?)\}\}")
SCALE = re.compile(r"^\s*scale\(\s*([0-9.]+)\s*\)\s*$")
INGREDIENT = re.compile(r"^\s*ingredients\[(\d+)\](?:\.(amount|unit|food|note))?\s*$")


def scalable(number):
    return f'<scalable-number v-bind:number="{py_float(number)}" v-bind:factor="ingredient_factor"></scalable-number>'


def ingredient_parts(ingredient, lang):
    amount = "" if ingredient["no_amount"] else scalable(ingredient["amount"])
    unit = ""
    if ingredient["unit"]:
        u = unit_json(ingredient["unit"], lang)
        use_plural = u["plural_name"] and float(ingredient["amount"]) != 1.0 and not ingredient["no_amount"]
        unit = u["plural_name"] if use_plural else u["name"]
    f = food_json(ingredient["food"], lang)
    if f["plural_name"] and not ingredient["no_amount"]:
        food = (f'<plural-name singular="{f["name"]}" plural="{f["plural_name"]}" '
                f'v-bind:amount="{py_float(ingredient["amount"])}" v-bind:factor="ingredient_factor" '
                f':no-amount="false"></plural-name>')
    else:
        food = f["name"]
    note = pick(ingredient["note"], lang) or ""
    return amount, unit, food, note


def render_template(text, rows, lang):
    def replace(match):
        code = match.group(1)
        scale = SCALE.match(code)
        if scale:
            return scalable(float(scale.group(1)))
        ref = INGREDIENT.match(code)
        if not ref:
            raise ValueError("unsupported template " + code)
        row = rows[int(ref.group(1))]
        if "header" in row:
            raise ValueError("template points at a header: " + text)
        amount, unit, food, note = ingredient_parts(row, lang)
        part = ref.group(2)
        if part == "amount":
            return amount
        if part == "unit":
            return unit
        if part == "food":
            return food
        if part == "note":
            return note
        whole = amount + (f" {unit}" if unit else "")
        return f"{whole} {food}"

    return TEMPLATE.sub(replace, text)


def inline_markdown(text):
    # Tags produced by the template step stay as they are; everything else is escaped.
    parts = re.split(r"(<[^>]+>)", text)
    out = []
    for part in parts:
        if part.startswith("<"):
            out.append(part)
        else:
            escaped = html.escape(part, quote=False)
            escaped = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", escaped)
            escaped = re.sub(r"\*(.+?)\*", r"<em>\1</em>", escaped)
            out.append(escaped)
    return "".join(out)


def render_markdown(text):
    paragraphs = [p for p in text.split("\n\n") if p.strip()]
    return "\n".join("<p>" + inline_markdown(p).replace("\n", "<br>\n") + "</p>" for p in paragraphs)


# --- Recipes ---------------------------------------------------------------------------------------

def recipe_rating(recipe_id):
    ratings = [r for _, r in src.COOK_LOG.get(recipe_id, []) if r is not None]
    return round(sum(ratings) / len(ratings), 2) if ratings else None


def image_url(recipe):
    return IMAGE_PREFIX + recipe["image"] if recipe.get("image") else None


def recipe_detail(recipe, lang, ids):
    name = pick(recipe["name"], lang)
    steps = []
    for order, step in enumerate(recipe["steps"]):
        rows = step["ingredients"]
        ingredients = []
        for row_order, row in enumerate(rows):
            ingredient_id = ids["ingredient"]
            ids["ingredient"] += 1
            used = [{"id": recipe["id"], "name": name}]
            if "header" in row:
                ingredients.append({"id": ingredient_id, "food": None, "unit": None, "amount": 0.0, "conversions": [],
                                    "note": pick(row["header"], lang), "order": row_order, "is_header": True,
                                    "no_amount": False, "original_text": None, "used_in_recipes": used,
                                    "checked": False})
                continue
            ingredients.append({
                "id": ingredient_id,
                "food": food_json(row["food"], lang),
                "unit": unit_json(row["unit"], lang) if row["unit"] else None,
                "amount": 0.0 if row["no_amount"] else float(row["amount"]),
                "conversions": conversions(row, lang),
                "note": pick(row["note"], lang) or "",
                "order": row_order,
                "is_header": False,
                "no_amount": row["no_amount"],
                "original_text": None,
                "used_in_recipes": used,
                "checked": False,
            })
        instruction = pick(step["text"], lang)
        steps.append({
            "id": ids["step"],
            "name": pick(step["name"], lang) or "",
            "instruction": instruction,
            "ingredients": ingredients,
            "instructions_markdown": render_markdown(render_template(instruction, rows, lang)),
            "time": step["time"],
            "order": order,
            "show_as_header": step["header"],
            "file": None,
            "step_recipe": None,
            "step_recipe_data": None,
            "show_ingredients_table": True,
        })
        ids["step"] += 1

    nutrition = None
    if "nutrition" in recipe:
        n = recipe["nutrition"]
        nutrition = {"id": recipe["id"], "carbohydrates": float(n["carbohydrates"]), "fats": float(n["fats"]),
                     "proteins": float(n["proteins"]), "calories": float(n["calories"]), "source": pick(n["source"], lang)}
    properties = []
    for type_id, amount in recipe.get("properties", {}).items():
        pt = next(p for p in src.PROPERTY_TYPES if p[0] == type_id)
        properties.append({"id": recipe["id"] * 10 + type_id, "property_amount": float(amount),
                           "property_type": {"id": pt[0], "name": pt[1] if lang == "sv" else pt[2], "unit": pt[3],
                                             "description": None, "order": pt[4], "open_data_slug": None,
                                             "fdc_id": None}})
    return {
        "id": recipe["id"],
        "name": name,
        "description": pick(recipe["description"], lang),
        "image": image_url(recipe),
        "keywords": [keyword_json(k, lang) for k in recipe["keywords"]],
        "steps": steps,
        "working_time": recipe["working"],
        "waiting_time": recipe["waiting"],
        "created_by": src.DEMO_USER,
        "created_at": stamp(recipe["created"], 9, 15),
        "updated_at": stamp(recipe["updated"], 9, 30),
        "source_url": None,
        "internal": True,
        "show_ingredient_overview": True,
        "nutrition": nutrition,
        "properties": properties,
        "food_properties": {},
        "servings": recipe["servings"],
        "file_path": "",
        "servings_text": pick(recipe["servings_text"], lang),
        "diameter": recipe.get("diameter", 0),
        "diameter_text": pick(recipe.get("diameter_text"), lang) or "",
        "rating": recipe_rating(recipe["id"]),
        # Always null in Tandoor 2.6.13 (FACTS "Fas 1 c"); "last cooked" comes from cook-log.json.
        "last_cooked": None,
        "private": False,
        "shared": [],
    }


def overview(detail, recipe):
    return {
        "id": detail["id"],
        "name": detail["name"],
        "description": detail["description"],
        "image": detail["image"],
        "keywords": [{"id": k["id"], "label": k["label"]} for k in detail["keywords"]],
        "working_time": detail["working_time"],
        "waiting_time": detail["waiting_time"],
        "created_by": detail["created_by"],
        "created_at": detail["created_at"],
        "updated_at": detail["updated_at"],
        "internal": True,
        "private": False,
        "servings": detail["servings"],
        "servings_text": detail["servings_text"],
        "rating": detail["rating"],
        "last_cooked": None,
        # Tandoor: created less than 7 days ago. Stays true after the date shift.
        "new": recipe["created"] < 7,
    }


SWEDISH_ORDER = {"å": "z\u0001", "ä": "z\u0002", "ö": "z\u0003"}


def name_key(name, lang):
    lowered = name.lower()
    if lang == "sv":
        lowered = "".join(SWEDISH_ORDER.get(c, c) for c in lowered)
    return lowered


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        # Compact: the readable source is demo_source.py; this keeps the APK and the budget small.
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


def main():
    by_id = {r["id"]: r for r in src.RECIPES}
    assert len(by_id) == len(src.RECIPES), "duplicate recipe ids"

    for lang in LANGS:
        ids = {"step": 1, "ingredient": 1}
        details = {}
        for recipe in src.RECIPES:
            details[recipe["id"]] = recipe_detail(recipe, lang, ids)
            write_json(os.path.join(OUT, lang, f"recipe-{recipe['id']}.json"), details[recipe["id"]])
        overviews = sorted((overview(details[r["id"]], r) for r in src.RECIPES), key=lambda o: name_key(o["name"], lang))
        write_json(os.path.join(OUT, lang, "recipe-overviews.json"), overviews)
        write_json(os.path.join(OUT, lang, "recipe-flat.json"),
                   [{"id": o["id"], "name": o["name"], "image": o["image"]} for o in overviews])
        keywords = sorted((keyword_json(k, lang) for k in src.KEYWORDS), key=lambda k: name_key(k["name"], lang))
        write_json(os.path.join(OUT, lang, "keywords.json"), keywords)
        write_json(os.path.join(OUT, lang, "meal-types.json"), [meal_type_json(m[0], lang) for m in src.MEAL_TYPES])

        overview_by_id = {o["id"]: o for o in overviews}
        plan = []
        for index, entry in enumerate(src.MEAL_PLAN):
            day, meal_type_id, recipe_id, servings, title, note = entry[:6]
            last_day = entry[6] if len(entry) > 6 else day
            meal_type = meal_type_json(meal_type_id, lang)
            note_text = pick(note, lang) or ""
            item = {
                "id": index + 1,
                "title": pick(title, lang) or "",
                "recipe": overview_by_id[recipe_id] if recipe_id else None,
                "servings": float(servings),
                "note": note_text,
                # Tandoor renders the note without a <p> wrapper (fixture meal-plan-list).
                "note_markdown": inline_markdown(note_text),
                "from_date": stamp(-day, *map(int, meal_type["time"].split(":")[:2])),
                "to_date": stamp(-last_day, *map(int, meal_type["time"].split(":")[:2])),
                "meal_type": meal_type,
                "created_by": src.DEMO_USER["id"],
                "meal_type_name": meal_type["name"],
                "shopping": False,
            }
            if recipe_id:
                # recipe_name is missing entirely when the entry has no recipe (FACTS "Fas 1 g").
                item["recipe_name"] = overview_by_id[recipe_id]["name"]
            plan.append(item)
        write_json(os.path.join(OUT, lang, "meal-plan.json"), plan)

        books = []
        entries = []
        for book_id, name, description, recipe_ids in src.BOOKS:
            book = {"id": book_id, "name": pick(name, lang), "description": pick(description, lang), "shared": [],
                    "created_by": src.DEMO_USER, "filter": None, "order": book_id - 1}
            books.append(book)
            for recipe_id in recipe_ids:
                entries.append({"id": len(entries) + 1, "book": book_id, "book_content": book, "recipe": recipe_id,
                                "recipe_content": overview_by_id[recipe_id]})
        write_json(os.path.join(OUT, lang, "recipe-books.json"), books)
        write_json(os.path.join(OUT, lang, "recipe-book-entries.json"), entries)

        dinner = meal_type_json(3, lang)
        preference = {
            "user": src.DEMO_USER, "image": None, "theme": "TANDOOR", "nav_bg_color": "#ddbf86",
            "nav_text_color": "DARK", "nav_show_logo": True, "default_unit": "g", "default_page": "SEARCH",
            "use_fractions": False, "use_kj": False, "nav_sticky": True, "ingredient_decimals": 2, "comments": True,
            "shopping_auto_sync": 5, "mealplan_autoadd_shopping": False, "food_inherit_default": [],
            "default_delay": 4.0, "mealplan_autoinclude_related": True, "mealplan_autoexclude_onhand": True,
            "shopping_recent_days": 7, "csv_delim": ",", "csv_prefix": "", "shopping_update_food_lists": True,
            "default_meal_type": dinner, "filter_to_supermarket": False, "shopping_add_onhand": False,
            "left_handed": False, "show_step_ingredients": True, "food_children_exist": False,
        }
        preference.update(src.USER_PREFERENCE_OVERRIDES)
        write_json(os.path.join(OUT, lang, "user-preference.json"), preference)

    cook_log = []
    for recipe_id, entries in sorted(src.COOK_LOG.items()):
        for days_before, rating in entries:
            hour = 17 + (len(cook_log) % 3)
            cook_log.append({"id": len(cook_log) + 1, "recipe": recipe_id, "servings": by_id[recipe_id]["servings"],
                             "rating": rating, "comment": None, "created_by": src.DEMO_USER,
                             "created_at": stamp(days_before, hour, 30), "updated_at": stamp(days_before, hour, 30)})
    write_json(os.path.join(OUT, "cook-log.json"), cook_log)
    write_json(os.path.join(OUT, "view-log.json"),
               [{"id": i + 1, "recipe": recipe_id, "created_by": src.DEMO_USER["id"], "created_at": stamp(days, hour)}
                for i, (recipe_id, days, hour) in enumerate(src.VIEW_LOG)])
    write_json(os.path.join(OUT, "index.json"), {
        "version": CONTENT_VERSION,
        "reference_date": REFERENCE_DAY.isoformat(),
        "languages": list(LANGS),
        "recipes": [r["id"] for r in src.RECIPES],
        "pinned_recipes": src.PINNED,
        "images": sorted({r["image"] for r in src.RECIPES if r.get("image")}),
    })
    print(f"Wrote demo content for {len(src.RECIPES)} recipes to {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
