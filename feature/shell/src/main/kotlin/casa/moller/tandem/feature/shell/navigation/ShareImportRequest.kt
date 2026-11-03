package casa.moller.tandem.feature.shell.navigation

/**
 * Fas 3 (import lane): something another app shared to Tandem (`ACTION_SEND`, plan §8.9
 * "Importera recept"): the app turns the intent into this and hands it to `TandemShell`, which
 * opens "Importera recept" with it ([ShellNavigationState.openImport]).
 *
 * @property text the shared text (a link, or a recipe as text), null for a file.
 * @property fileUri the shared photo or PDF as a `content://` uri, null for a text.
 * @property mimeType the file's type as the sender declared it, when known.
 */
data class ShareImportRequest(val text: String? = null, val fileUri: String? = null, val mimeType: String? = null) {
    init {
        require(text != null || fileUri != null) { "a shared text or file is needed" }
    }
}
