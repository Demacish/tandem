package casa.moller.tandem.core.data.files

/**
 * Fas S komplettering: app-private cache directories that more than one module knows by name, so
 * "Rensa appdata" and the logout clean-up remove exactly what the screens write.
 */
object CacheDirectories {
    /**
     * `cache/drink-camera/`: the camera app's full-size originals for "Ny dryck" (feature:recipes
     * `DrinkCameraFiles`, often with EXIF location). Emptied by "Rensa appdata" (core:data) and when
     * no account is left (core:sync `AccountMirror`), besides the screen's own clean-up.
     */
    const val DRINK_CAMERA: String = "drink-camera"
}
