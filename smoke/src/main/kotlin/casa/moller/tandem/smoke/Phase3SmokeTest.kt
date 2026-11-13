package casa.moller.tandem.smoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for what Phase 3 (0.5.0, plan §10 Fas 3) added and nothing else ran under R8
 * (Fas 4 review, fix-bygg): v0.0.1's class of failure, code that works unminified and dies in the
 * release, needs these paths on the minified app.
 *
 * - [newRecipe_reachesTheServer_andIsDeletedAgain]: the editor's whole-recipe POST through the
 *   outbox, the temporary id replaced, DELETE.
 * - [editedRecipe_stepIsPatched_andThePictureRemoved]: PATCH after the fresh read, the recipe read
 *   back, and the RECIPE_IMAGE row with an empty `image_url`.
 * - [editorOptions_flagsSubRecipeAndNutrition_goWithThePatch]: the switches, the sub-recipe sheet,
 *   the property types and what a PATCH says of them.
 * - [stepFile_chosenInTheSystemFilePicker_isUploadedBeforeThePatch]: the system file picker, the copy,
 *   `POST /api/user-file/` and the step's file in the PATCH.
 * - [recipeShared_aLinkIsOneRequest_andTheTextNeedsNone]: `share-link` through R8 to the share sheet.
 * - [quickSettingsTileAndShortcuts_openTheRightPlace]: the tile's service, the launcher shortcuts
 *   and their intents.
 * - [shoppingWidget_drawsTheList_andATapChecksTheRow]: a bound widget in a [WidgetHost]: the Glance
 *   receiver, `provideGlance`, the Hilt entry point and the action callback Glance creates by
 *   reflection.
 * - [recipePhotographed_readOnThePhone_andOpenedInTheEditor]: "Fota recept" from the gallery, ML
 *   Kit's text model, the shell's way into the import, and the draft.
 */
@RunWith(AndroidJUnit4::class)
class Phase3SmokeTest : Phase2SmokeBase() {
    @get:Rule(order = 3)
    val gallery = Gallery(instrumentation.context)

    @get:Rule(order = 4)
    val downloads = Downloads(instrumentation.context)

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- recipe writes:") +
        tandoor.recipeEdits.writes.map { "${it.method} ${it.path} ${it.body.take(REPORT_CHARS)}" }

    @Before
    fun recipesAreWritable() {
        tandoor.recipeEdits.enabled = true
    }

    // ------------------------------------------------------------------------------ new and delete

    @Test
    fun newRecipe_reachesTheServer_andIsDeletedAgain() {
        logIn()
        app.tap("Recipes")
        waitForAction("the editor") {
            app.clickNodeDescribed(NEW_RECIPE_BUTTON) && nodeShows(TAP_WAIT_MS) { it.text?.toString() == SAVE_RECIPE }
        }
        app.type(0, NEW_RECIPE)

        // One step with one line. The step's fields end the form: the line, then the instructions.
        app.tap(ADD_STEP)
        app.waitFor(By.text("Step 1"))
        app.scrollTo(By.text(ADD_STEP))
        fieldsOnScreen().let { fields -> fields[fields.size - 2].text = NEW_LINE }
        app.tapDescription("Add an ingredient to step 1")
        app.waitFor(By.text("cream"))
        app.scrollTo(By.text(ADD_STEP))
        fieldsOnScreen().last().text = NEW_INSTRUCTION
        app.capture("phase3-new-recipe")
        save()

        waitForServer("the new recipe") { if (tandoor.recipeEdits.writes("POST", "/api/recipe/").isEmpty()) "no POST yet" else null }
        val sent = tandoor.recipeEdits.writes("POST", "/api/recipe/").single().json
        assertEquals(NEW_RECIPE, sent.string("name"))
        assertEquals(true, (sent["internal"] as? JsonPrimitive)?.booleanOrNull)
        val step = sent.getValue("steps").jsonArray.single().jsonObject
        assertEquals(NEW_INSTRUCTION, step.string("instruction"))
        val line = step.getValue("ingredients").jsonArray.single().jsonObject
        assertEquals("cream", line.getValue("food").jsonObject.string("name"))
        assertEquals("dl", line.getValue("unit").jsonObject.string("name"))
        assertEquals(2.0, (line["amount"] as? JsonPrimitive)?.doubleOrNull)
        assertEquals("cold", line.string("note"))

        // The saved recipe opens, under its server id from here on.
        app.waitFor(By.text(NEW_RECIPE))
        app.scrollTo(By.text(NEW_INSTRUCTION))
        nothingWaiting()
        app.capture("phase3-new-recipe-saved")

        menu("Delete")
        app.waitFor(By.text("Delete the recipe?"))
        app.tap("Delete")
        waitForServer("the DELETE") { if (tandoor.recipeEdits.writes("DELETE", "/api/recipe/").isEmpty()) "no DELETE yet" else null }
        val created = tandoor.recipeEdits.writes("DELETE", "/api/recipe/").single().path
        assertTrue("Deleted under the server's id: $created", Regex("^/api/recipe/\\d+/$").matches(created))
        app.waitUntilGone(By.text(NEW_INSTRUCTION))
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    // ------------------------------------------------------------------------------ edit

    @Test
    fun editedRecipe_stepIsPatched_andThePictureRemoved() {
        logIn()
        app.tap("Recipes")
        app.findInList(By.text(PASTA))
        app.tap(PASTA)
        app.scrollTo(By.textStartsWith(PASTA_STEP_SHOWN))
        menu("Edit")
        app.waitFor(By.text(SAVE_RECIPE))

        app.scrollTo(By.clazz(EDIT_TEXT).textStartsWith(PASTA_STEP_RAW)).text = EDITED_INSTRUCTION
        app.waitFor(By.clazz(EDIT_TEXT).text(EDITED_INSTRUCTION))
        save()

        waitForServer("the PATCH") { if (patches().isEmpty()) "no PATCH yet" else null }
        val sent = patches().single().json
        assertEquals(PASTA, sent.string("name"))
        val steps = sent.getValue("steps").jsonArray.map { it.jsonObject }
        assertEquals("All three steps go with the recipe", PASTA_STEPS, steps.size)
        assertEquals(EDITED_INSTRUCTION, steps.first().string("instruction"))
        assertEquals("The step keeps its id", 1L, (steps.first()["id"] as? JsonPrimitive)?.longOrNull)
        assertTrue("A keyword without a name is a 400", (sent["keywords"] as JsonArray).all { (it as JsonObject).string("name") != null })
        val read = tandoor.requests.indexOfFirst { it == "PATCH $PASTA_PATH" }
        assertTrue("The fresh read before the PATCH: ${tandoor.requests}", tandoor.requests.take(read).contains("GET $PASTA_PATH"))

        // The answer is what the detail shows.
        app.scrollTo(By.text(EDITED_INSTRUCTION))
        nothingWaiting()

        menu("Edit")
        app.waitFor(By.text(SAVE_RECIPE))
        app.tap("Remove the picture")
        app.waitFor(By.text("The picture is removed when you save the recipe."))
        app.capture("phase3-picture-removed")
        save()

        val image = "/api/recipe/$PASTA_ID/image/"
        waitForServer("the picture's removal") { if (tandoor.recipeEdits.writes("PUT", image).isEmpty()) "no PUT yet" else null }
        val form = tandoor.recipeEdits.writes("PUT", image).single().body
        assertTrue("A form with an empty image_url: $form", EMPTY_IMAGE_URL.containsMatchIn(form))
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    /** Fas 4 (#22, decision B9): the controls beyond the plain form, through R8: what the PATCH says and what it leaves out. */
    @Test
    fun editorOptions_flagsSubRecipeAndNutrition_goWithThePatch() {
        tandoor.recipeEdits.propertyTypes += ENERGY_TYPE
        logIn()
        app.tap("Recipes")
        app.findInList(By.text(PASTA))
        app.tap(PASTA)
        app.scrollTo(By.textStartsWith(PASTA_STEP_SHOWN))
        menu("Edit")
        app.waitFor(By.text(SAVE_RECIPE))

        app.scrollTo(By.textStartsWith(PRIVATE_RECIPE)).click()
        app.scrollTo(By.desc("Step 1: show the name as a heading")).click()
        app.scrollTo(By.desc("Attach a file to step 1"))
        app.scrollTo(By.desc("Link a sub-recipe to step 1")).click()
        app.waitFor(By.text("Sub-recipe for step 1"))
        app.capture("phase4-link-sheet")
        app.scrollTo(By.text(SUB_RECIPE)).click()
        app.waitFor(By.text("Sub-recipe: $SUB_RECIPE"))

        scrollThroughTheForm(By.text(ENERGY)).click()
        app.waitUntilGone(By.text(ENERGY))
        fieldsOnScreen().last().text = ENERGY_VALUE
        app.waitFor(By.clazz(EDIT_TEXT).text(ENERGY_VALUE))
        app.capture("phase4-editor-options")
        save()

        waitForServer("the PATCH") { if (patches().isEmpty()) "no PATCH yet" else null }
        val sent = patches().single().json
        assertEquals(true, (sent["private"] as? JsonPrimitive)?.booleanOrNull)
        val steps = sent.getValue("steps").jsonArray.map { it.jsonObject }
        assertEquals("The recorded step has it on", false, (steps.first()["show_as_header"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(SUB_RECIPE_ID, (steps.first()["step_recipe"] as? JsonPrimitive)?.longOrNull)
        assertFalse("The name is the device's alone", "step_recipe_data" in steps.first())
        assertTrue("Untouched steps say nothing of it: $steps", steps.drop(1).none { "step_recipe" in it || "file" in it })
        val value = sent.getValue("properties").jsonArray.single().jsonObject
        assertEquals(ENERGY_VALUE.toDouble(), (value["property_amount"] as? JsonPrimitive)?.doubleOrNull)
        assertEquals(setOf("id", "name"), value.getValue("property_type").jsonObject.keys)
        nothingWaiting()
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    /** Fas 4, komplettering 3: the upload was only tested without R8 (BYGGE "Redigeraren som webben"). */
    @Test
    fun stepFile_chosenInTheSystemFilePicker_isUploadedBeforeThePatch() {
        downloads.add(STEP_FILE, "application/pdf", STEP_FILE_CONTENT.toByteArray())
        logIn()
        app.tap("Recipes")
        app.findInList(By.text(PASTA))
        app.tap(PASTA)
        app.scrollTo(By.textStartsWith(PASTA_STEP_SHOWN))
        menu("Edit")
        app.waitFor(By.text(SAVE_RECIPE))

        scrollThroughTheForm(By.desc("Attach a file to step 1")).click()
        downloads.pick(app, STEP_FILE)
        // Back from the picker the form may rest with the attach row just below the fold (seen
        // 2026-11-13 on main, run 43436583776), so the label is scrolled to, not only waited for.
        app.scrollTo(By.text("File: $STEP_FILE"))
        app.capture("phase4-step-file")
        save()

        waitForServer("the PATCH") { if (patches().isEmpty()) "no PATCH yet" else null }
        val writes = tandoor.recipeEdits.writes
        val upload = writes.single { it.path == "/api/user-file/" }
        assertTrue("The name goes with it: ${upload.body.take(REPORT_CHARS)}", STEP_FILE in upload.body)
        assertTrue("The file's own bytes: ${upload.body.take(REPORT_CHARS)}", STEP_FILE_CONTENT in upload.body)
        assertTrue("The file first: $writes", writes.indexOf(upload) < writes.indexOf(patches().single()))
        val file = patches().single().json.getValue("steps").jsonArray.first().jsonObject.getValue("file").jsonObject
        assertEquals("Only what the server needs: $file", setOf("id", "name"), file.keys)
        assertEquals(STEP_FILE, file.string("name"))
        assertTrue("The id the server gave it: $file", (file["id"] as? JsonPrimitive)?.longOrNull != null)
        nothingWaiting()
        assertTrue("A 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    // ------------------------------------------------------------------------------ share

    /** Plan §6.2, §8.6: a link goes through R8 to the share sheet in one request, the text needs none. */
    @Test
    fun recipeShared_aLinkIsOneRequest_andTheTextNeedsNone() {
        logIn()
        app.tap("Recipes")
        app.findInList(By.text(PASTA))
        app.tap(PASTA)
        app.scrollTo(By.textStartsWith(PASTA_STEP_SHOWN))
        assertTrue("Never ahead of time: ${shareRequests()}", shareRequests().isEmpty())

        menu("Share link")
        shareSheet("phase4-share-link")
        assertEquals(listOf("GET /api/share-link/2"), shareRequests())

        menu("Share as text")
        shareSheet("phase4-share-text")
        assertEquals("The text asks the server nothing", listOf("GET /api/share-link/2"), shareRequests())
    }

    private fun shareRequests(): List<String> = tandoor.requests.filter { "/api/share-link/" in it }

    /** The system's share sheet is up: it leaves a picture, and Back returns to the recipe. */
    private fun shareSheet(name: String) {
        app.waitFor(By.pkg(SHARE_SHEET))
        app.capture(name)
        app.device.pressBack()
        app.waitFor(By.desc(MORE_OPTIONS))
    }

    // ------------------------------------------------------------------------------ tile, shortcuts

    @Test
    fun quickSettingsTileAndShortcuts_openTheRightPlace() {
        logIn()
        waitForAction("the launcher shortcuts") { "id=add-item" in shortcuts() && "id=import" in shortcuts() }

        openAt("week")
        tabSelected("Week")
        openAt("import")
        app.waitFor(By.text("Import recipe"))

        // The tile, from the home screen: the system binds the service and sends the click.
        openAt("week")
        tabSelected("Week")
        app.device.pressHome()
        app.shell("cmd statusbar add-tile $TILE")
        try {
            SystemClock.sleep(TILE_SETTLE_MS)
            app.shell("cmd statusbar click-tile $TILE")
            app.waitFor(By.pkg(TandemApp.PACKAGE))
            tabSelected("Shopping")
        } finally {
            app.shell("cmd statusbar remove-tile $TILE")
            app.shell("cmd statusbar collapse")
        }

        // "Ny vara" opens the add panel over Handla.
        openAt("add-item")
        app.waitFor(By.text(ADD_TITLE))
    }

    // ------------------------------------------------------------------------------ the widget

    @Test
    fun shoppingWidget_drawsTheList_andATapChecksTheRow() {
        val entry = tandoor.shopping.seed(WIDGET_ITEM)
        logIn()
        app.tap("Shopping")
        app.waitFor(row(WIDGET_ITEM))
        app.device.pressHome()

        WidgetHost(app, instrumentation).use { widget ->
            widget.bind(WIDGET_RECEIVER)
            widget.waitForTexts("the list") { texts -> "Shopping list" in texts && texts.any { WIDGET_ITEM in it } }
            val row = widget.descriptions().first { it.startsWith("Check off") && WIDGET_ITEM in it }
            assertTrue("The row took no click: ${widget.descriptions()}", widget.click(row))
            waitForServer("the check from the widget") {
                val checked = tandoor.shopping.all().single { it.id == entry }.checked
                if (checked) null else "still open"
            }
        }
    }

    // ------------------------------------------------------------------------------ Fota recept

    @Test
    fun recipePhotographed_readOnThePhone_andOpenedInTheEditor() {
        logIn()
        gallery.add("recipe", Gallery.recipe())
        openAt("import")
        app.waitFor(By.text("Import recipe"))
        app.tap("Open the camera")
        app.waitFor(By.text("Photograph the recipe"))
        app.tap(PICK_IMAGE)
        gallery.pickNewest(app)

        app.waitFor(By.text("Use the text"), ML_KIT_TIMEOUT_MS)
        app.capture("phase3-recipe-read")
        app.tap("Use the text")
        app.waitFor(By.text("Fetch the recipe"))
        app.tap("Fetch the recipe")

        app.waitFor(By.text("Found this"))
        app.waitFor(By.text(Gallery.RECIPE_NAME))
        app.capture("phase3-recipe-found")
        app.tapUntil("Open in the editor", By.clazz(EDIT_TEXT))
        app.waitFor(By.text(Gallery.RECIPE_NAME))
        assertTrue("The text stays on the phone: ${tandoor.importPosts}", tandoor.importPosts.isEmpty())
        assertTrue("Nothing is saved yet: ${tandoor.recipeEdits.writes}", tandoor.recipeEdits.writes.isEmpty())
    }

    // ------------------------------------------------------------------------------ helpers

    private fun fieldsOnScreen(): List<UiObject2> {
        val fields = app.device.findObjects(By.clazz(EDIT_TEXT)).sortedBy { it.visibleBounds.top }
        assertTrue("No text fields on screen", fields.size >= 2)
        return fields
    }

    private fun patches() = tandoor.recipeEdits.writes("PATCH", PASTA_PATH)

    /** The recipe's three steps are longer than [TandemApp.scrollTo] goes: it gives up after a few swipes. */
    private fun scrollThroughTheForm(selector: BySelector): UiObject2 {
        val x = app.device.displayWidth / 2
        repeat(FORM_SWIPES) {
            if (app.device.hasObject(selector)) return app.scrollTo(selector)
            app.device.swipe(x, app.device.displayHeight * 2 / 3, x, app.device.displayHeight / 3, FORM_SWIPE_STEPS)
            app.device.waitForIdle()
        }
        return app.scrollTo(selector)
    }

    /** Saves through the button's accessibility action, until the editor has closed. */
    private fun save() {
        repeat(TAP_ATTEMPTS) {
            app.clickNodeWithText(SAVE_RECIPE)
            if (nodeGone(TAP_WAIT_MS) { it.text?.toString() == SAVE_RECIPE }) return
        }
        throw AssertionError("The editor did not close after \"$SAVE_RECIPE\"")
    }

    /** The overflow menu's [item] on the recipe's page, once it is enabled (the detail may still be loading). */
    private fun menu(item: String) {
        app.tapDescription(MORE_OPTIONS)
        waitForAction("the menu item $item") { app.clickNodeWithText(item) }
    }

    /** The chip "n changes waiting to sync" is gone: the outbox has nothing left. */
    private fun nothingWaiting() {
        assertTrue("Something still waits to sync", nodeGone(DRAIN_TIMEOUT_MS) { WAITING.matcher(it.text ?: "").matches() })
    }

    private fun shortcuts(): String = app.shell("dumpsys shortcut").substringAfter("Package: ${TandemApp.PACKAGE}", "")

    /** The intent a shortcut, the tile and a widget's header send (core:ui AppOpenIntents). */
    private fun openAt(destination: String) {
        app.shell(
            "am start -W -a casa.moller.tandem.action.OPEN -d tandem-open://$destination " +
                "--es casa.moller.tandem.extra.DESTINATION $destination -f $NEW_TASK_CLEAR_TOP " +
                "-n ${TandemApp.PACKAGE}/$MAIN_ACTIVITY",
        )
    }

    private fun tabSelected(label: String) {
        app.waitFor(By.selected(true).hasDescendant(By.text(label), MAX_ANCESTORS))
    }

    private fun waitForAction(what: String, action: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MS
        while (!action()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Not possible within $ACTION_TIMEOUT_MS ms: $what")
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val SAVE_RECIPE = "Save recipe"
        const val ADD_STEP = "Add step"
        const val MORE_OPTIONS = "More options"
        const val NEW_RECIPE_BUTTON = "New recipe"
        const val NEW_RECIPE = "Röktestets gräddsås"
        const val NEW_LINE = "2 dl cream, cold"
        const val NEW_INSTRUCTION = "Vispa grädden i röktestet."
        const val PASTA_PATH = "/api/recipe/2/"
        const val PASTA_STEPS = 3
        const val PASTA_STEP_SHOWN = "Skiva"
        const val PASTA_STEP_RAW = "Skiva {{"
        const val EDITED_INSTRUCTION = "Skiva svampen tunt, ändrat i röktestet."
        const val PRIVATE_RECIPE = "Private recipe"
        const val STEP_FILE = "Tandem-stegfil.pdf"
        const val STEP_FILE_CONTENT = "%PDF-1.4 stegfil i röktestet"

        /** The chooser moved out of the framework in Android 14 (`com.android.intentresolver`). */
        val SHARE_SHEET: Pattern = Pattern.compile("android|.*intentresolver.*")
        const val FORM_SWIPES = 30
        const val FORM_SWIPE_STEPS = 20
        const val SUB_RECIPE = "Äppelkaka"
        const val SUB_RECIPE_ID = 10L
        const val ENERGY = "Energi"
        const val ENERGY_VALUE = "350"
        val ENERGY_TYPE = JsonObject(
            mapOf(
                "id" to JsonPrimitive(40),
                "name" to JsonPrimitive(ENERGY),
                "unit" to JsonPrimitive("kcal"),
                "order" to JsonPrimitive(0),
            ),
        )
        val EMPTY_IMAGE_URL = Regex("name=\"image_url\"[^\\r\\n]*(\\r?\\n[^\\r\\n]+)*\\r?\\n\\r?\\n\\r?\\n?-")
        const val WIDGET_ITEM = "widgetmjölk"
        const val WIDGET_RECEIVER = "casa.moller.tandem.feature.widgets.ShoppingListWidgetReceiver"
        const val TILE_SERVICE = "casa.moller.tandem.feature.widgets.ShoppingTileService"
        const val TILE = "${TandemApp.PACKAGE}/$TILE_SERVICE"
        const val TILE_SETTLE_MS = 3_000L
        const val MAIN_ACTIVITY = "casa.moller.tandem.MainActivity"

        /** FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP | SINGLE_TOP, as AppOpenIntents sets them. */
        const val NEW_TASK_CLEAR_TOP = 0x34000000
        const val PICK_IMAGE = "Pick a photo from the gallery"
        const val ML_KIT_TIMEOUT_MS = 90_000L
        const val ACTION_TIMEOUT_MS = 45_000L
        const val REPORT_CHARS = 2_000
    }
}
