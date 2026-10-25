// Scaling/formatting golden table (plan §10 Fas 1: "Skalning ger samma mängder som Tandoors
// webb för 10 testrecept").
//
// Runs in contract.yml only. It loads the display functions of Tandoor's own web frontend
// (TandoorRecipes/recipes, vue3/src, checked out at the image tag under test into the runner's
// build directory, never committed here) and executes them on the recipe details that
// Phase1ContractTest recorded. Nothing from Tandoor is copied into this repository: the
// functions are read from the checkout at run time, their TypeScript signatures are stripped,
// and they run in a vm sandbox.
//
//   node golden.mjs <tandoor-src> <fas1-recordings-dir> <out-file> [--compare <committed-golden>]
//
// The rules the web applies on top of those functions live in Vue templates
// (IngredientsTable.vue, RecipeView.vue, ScalableNumber.vue); this script re-states them and
// first checks that the template lines it re-states are still present verbatim, so a changed
// frontend fails here instead of silently producing a different table.
// The algorithm is documented in api-schema/fixtures/2.6.13/FACTS.md (Fas 1, "Skalning").

import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const INGREDIENT_DECIMALS = 2;
const FACTORS = [0.5, 1, 2, 3];

const args = process.argv.slice(2);
const compareIndex = args.indexOf("--compare");
const compareFile = compareIndex >= 0 ? args[compareIndex + 1] : null;
const [srcDir, recordingsDir, outFile] = args.filter((_, i) => compareIndex < 0 || (i !== compareIndex && i !== compareIndex + 1));
if (!srcDir || !recordingsDir || !outFile) {
    console.error("usage: node golden.mjs <tandoor-src> <fas1-recordings-dir> <out-file> [--compare <golden>]");
    process.exit(2);
}

const read = (relative) => fs.readFileSync(path.join(srcDir, relative), "utf8");

// --- 1. Guard: the template rules re-stated below must still be in the frontend -------------

const REQUIRED = {
    "vue3/src/components/display/IngredientsTable.vue": [
        `<template v-if="i.isHeader">`,
        `<td colspan="5" class="font-weight-bold">{{ i.note }}</td>`,
        `v-html="calculateFoodAmount(i.amount, props.ingredientFactor, useUserPreferenceStore().userSettings.useFractions)"`,
        `v-if="!i.noAmount && i.amount != 0">`,
        `<template v-if="i.unit && !i.noAmount && i.amount != 0"> {{ ingredientToUnitString(i, ingredientFactor) }}</template>`,
        `<span v-else>{{ ingredientToFoodString(i, ingredientFactor) }}</span>`,
    ],
    "vue3/src/components/display/RecipeView.vue": [
        `return servings.value / ((recipe.value.servings != undefined) ? Math.max(recipe.value.servings, 1) : 1)`,
    ],
    "vue3/src/components/display/ScalableNumber.vue": [
        `return calculateFoodAmount(x, props.factor, useUserPreferenceStore().userSettings.useFractions)`,
    ],
    "vue3/src/components/display/PluralName.vue": [
        `const scaledForm = computed(() => resolve(props.amount * props.factor))`,
    ],
};
for (const [file, needles] of Object.entries(REQUIRED)) {
    const text = read(file);
    for (const needle of needles) {
        if (!text.includes(needle)) {
            console.error(`::error::${file} no longer contains: ${needle}\nThe web display rules changed; review FACTS.md (Fas 1, Skalning) and this script.`);
            process.exit(1);
        }
    }
}

// --- 2. Load Tandoor's functions --------------------------------------------------------------

/** Returns `function name(...) {...}` from TypeScript/Vue source, with parameter and return types removed. */
function extractFunction(source, name) {
    const match = new RegExp(`(?:export\\s+)?function\\s+${name}\\s*\\(`).exec(source);
    if (!match) throw new Error(`function ${name} not found`);
    let i = match.index + match[0].length; // just after "("
    let depth = 1;
    const paramsStart = i;
    while (depth > 0) {
        const c = source[i++];
        if (c === "(") depth++;
        else if (c === ")") depth--;
        if (i > source.length) throw new Error(`unbalanced parameters in ${name}`);
    }
    const params = source.slice(paramsStart, i - 1);
    const bodyStart = source.indexOf("{", i);
    let j = bodyStart + 1;
    depth = 1;
    while (depth > 0) {
        const c = source[j++];
        if (c === "{") depth++;
        else if (c === "}") depth--;
        if (j > source.length) throw new Error(`unbalanced body in ${name}`);
    }
    const body = source.slice(bodyStart, j);
    return `function ${name}(${stripParamTypes(params)}) ${body}`;
}

function stripParamTypes(params) {
    const parts = [];
    let depth = 0;
    let current = "";
    for (const c of params) {
        if ("([{<".includes(c)) depth++;
        if (")]}>".includes(c)) depth--;
        if (c === "," && depth === 0) {
            parts.push(current);
            current = "";
        } else current += c;
    }
    if (current.trim()) parts.push(current);
    return parts
        .map((p) => {
            const m = /^\s*([A-Za-z_$][\w$]*)\s*\??\s*(?::\s*([^=]+?))?\s*(?:=\s*(.+?))?\s*$/s.exec(p);
            if (!m) throw new Error(`cannot parse parameter: ${p}`);
            return m[3] !== undefined ? `${m[1]} = ${m[3]}` : m[1];
        })
        .join(", ");
}

function scriptOf(vueSource) {
    const m = /<script[^>]*>([\s\S]*?)<\/script>/.exec(vueSource);
    if (!m) throw new Error("no <script> in .vue file");
    return m[1];
}

const numberUtils = read("vue3/src/utils/number_utils.ts");
const modelUtils = read("vue3/src/utils/model_utils.ts");
const pluralName = scriptOf(read("vue3/src/components/display/PluralName.vue"));

const code = [
    extractFunction(numberUtils, "roundDecimals"),
    extractFunction(numberUtils, "calculateFoodAmount"),
    extractFunction(numberUtils, "frac"),
    extractFunction(modelUtils, "isSingularAmount"),
    extractFunction(modelUtils, "pluralString"),
    extractFunction(modelUtils, "ingredientToUnitString"),
    extractFunction(modelUtils, "ingredientToFoodString"),
    extractFunction(pluralName, "resolve"),
].join("\n\n");

const sandbox = {
    // The only store value these functions read (number_utils.roundDecimals).
    useUserPreferenceStore: () => ({ userSettings: { ingredientDecimals: INGREDIENT_DECIMALS } }),
    props: null, // set per <plural-name> before calling resolve()
};
vm.createContext(sandbox);
vm.runInContext(code, sandbox, { filename: "tandoor-frontend-functions.js" });
const T = (name) => vm.runInContext(name, sandbox);
const calculateFoodAmount = T("calculateFoodAmount");
const ingredientToUnitString = T("ingredientToUnitString");
const ingredientToFoodString = T("ingredientToFoodString");
const resolvePluralName = (props) => {
    sandbox.props = props;
    return vm.runInContext("resolve(props.amount * props.factor)", sandbox);
};

// --- 3. Re-stated template rules --------------------------------------------------------------

/** The visible text of an amount's HTML: tags removed, &frasl; as U+2044, whitespace collapsed. */
function htmlToText(html) {
    return String(html)
        .replace(/&frasl;/g, "⁄")
        .replace(/<[^>]+>/g, "")
        .replace(/\s+/g, " ")
        .trim();
}

/** Ingredient JSON (snake_case, as the API returns it) to the generated client's camelCase. */
function toClient(i) {
    const named = (o) => (o ? { name: o.name, pluralName: o.plural_name } : o);
    return { amount: i.amount, unit: named(i.unit), food: named(i.food), noAmount: i.no_amount, isHeader: i.is_header, note: i.note };
}

function ingredientRow(apiIngredient, factor, useFractions) {
    const i = toClient(apiIngredient);
    if (i.isHeader) return { header: i.note ?? "" };
    const showAmount = !i.noAmount && i.amount != 0;
    const amountHtml = showAmount ? String(calculateFoodAmount(i.amount, factor, useFractions)) : "";
    const row = { amount: htmlToText(amountHtml) };
    if (useFractions) row.amountHtml = amountHtml;
    row.unit = i.unit && !i.noAmount && i.amount != 0 ? htmlToText(ingredientToUnitString(i, factor)) : "";
    row.food = i.food ? htmlToText(ingredientToFoodString(i, factor)) : "";
    row.note = i.note ?? "";
    return row;
}

const TAG = /<(scalable-number|plural-name)((?:\s+[^\s=>]+=(?:"[^"]*"|'[^']*'))*)\s*>\s*<\/\1>/g;
const ATTR = /([^\s=>]+)=(?:"([^"]*)"|'([^']*)')/g;

function decodeEntities(s) {
    return s
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&#x27;/g, "'")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&amp;/g, "&");
}

function attributes(text) {
    const out = {};
    for (const m of text.matchAll(ATTR)) out[m[1]] = decodeEntities(m[2] ?? m[3] ?? "");
    return out;
}

function templateTokens(instructionsHtml, factor, useFractions) {
    const tokens = [];
    const rendered = (instructionsHtml ?? "").replace(TAG, (_, tag, attrText) => {
        const a = attributes(attrText);
        if (tag === "scalable-number") {
            const number = parseFloat(a["v-bind:number"]);
            const html = String(calculateFoodAmount(number, factor, useFractions));
            tokens.push({ tag, number, html, text: htmlToText(html) });
            return html;
        }
        const props = {
            singular: a.singular ?? "",
            plural: a.plural ?? "",
            amount: parseFloat(a["v-bind:amount"]),
            factor,
            noAmount: a[":no-amount"] === "true",
        };
        const text = resolvePluralName(props);
        tokens.push({ tag, singular: props.singular, plural: props.plural, amount: props.amount, noAmount: props.noAmount, text });
        return text;
    });
    return { tokens, rendered };
}

function view(recipe, factor, useFractions) {
    return {
        steps: recipe.steps.map((step) => {
            const { tokens, rendered } = templateTokens(step.instructions_markdown, factor, useFractions);
            return {
                ingredients: step.ingredients.map((i) => ingredientRow(i, factor, useFractions)),
                templates: tokens,
                instructionsHtml: rendered,
            };
        }),
    };
}

function servingsCases(base) {
    const servings = [...FACTORS.map((f) => base * f), 1, base - 1, base + 1].filter((s) => s >= 1);
    return [...new Set(servings)].sort((a, b) => a - b);
}

// --- 4. Build the table -----------------------------------------------------------------------

const recipes = [];
for (const file of fs.readdirSync(recordingsDir).sort()) {
    if (!file.endsWith(".json")) continue;
    const recording = JSON.parse(fs.readFileSync(path.join(recordingsDir, file), "utf8"));
    const request = recording.request ?? {};
    const response = recording.response ?? {};
    if (request.method !== "GET" || !/^\/api\/recipe\/\d+\/$/.test(request.path ?? "") || response.status !== 200) continue;
    const recipe = response.body;
    // Nothing to scale (e.g. the step-file recipe of Phase1ContractTest.stepWithAFile): not part of the table.
    if (!(recipe.steps ?? []).some((step) => (step.ingredients ?? []).length > 0)) continue;
    const base = recipe.servings;
    const cases = servingsCases(base).map((servings) => {
        const factor = servings / (base != undefined ? Math.max(base, 1) : 1);
        return { servings, factor, fractionsOff: view(recipe, factor, false), fractionsOn: view(recipe, factor, true) };
    });
    recipes.push({ fixture: file, id: recipe.id, name: recipe.name, servings: base, cases });
}
recipes.sort((a, b) => a.id - b.id);
if (recipes.length < 10) {
    console.error(`::error::expected at least 10 recorded recipe details, found ${recipes.length}`);
    process.exit(1);
}

const golden = {
    meta: {
        description:
            "What Tandoor's web frontend shows for every seeded recipe at several servings: ingredient rows (amount, unit, food, note; " +
            "headers) and the scaled template values in the step instructions, with use_fractions off and on and ingredient_decimals " +
            `${INGREDIENT_DECIMALS}. Generated by contract-tests/web-scaling/golden.mjs, which executes the frontend's own functions; ` +
            "see FACTS.md (Fas 1, Skalning).",
        frontendSource: "TandoorRecipes/recipes vue3/src at the image tag under test (2.6.13 for this file)",
        ingredientDecimals: INGREDIENT_DECIMALS,
        factor: "servings / max(recipe.servings, 1)",
        amountText: "amountHtml with tags removed, &frasl; as U+2044, whitespace collapsed and trimmed",
    },
    recipes,
};
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, JSON.stringify(golden, null, 2) + "\n");
console.log(`Wrote ${outFile}: ${recipes.length} recipes, ${recipes.reduce((n, r) => n + r.cases.length, 0)} servings cases`);

// --- 5. Compare with the committed table --------------------------------------------------------

function firstDifference(a, b, where = "recipes") {
    if (typeof a !== typeof b || Array.isArray(a) !== Array.isArray(b) || (a === null) !== (b === null)) return `${where}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`;
    if (a && typeof a === "object") {
        const keys = [...new Set([...Object.keys(a), ...Object.keys(b)])];
        for (const k of keys) {
            const d = firstDifference(a[k], b[k], `${where}.${k}`);
            if (d) return d;
        }
        return null;
    }
    return a === b ? null : `${where}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`;
}

if (compareFile) {
    if (!fs.existsSync(compareFile)) {
        console.log(`::notice::${compareFile} does not exist yet; nothing to compare`);
    } else {
        const committed = JSON.parse(fs.readFileSync(compareFile, "utf8"));
        const diff = firstDifference(recipes, committed.recipes);
        if (diff) {
            console.error(`::error::The web frontend's scaling differs from ${compareFile}: ${diff}`);
            process.exit(1);
        }
        console.log(`Identical to ${compareFile}`);
    }
}
