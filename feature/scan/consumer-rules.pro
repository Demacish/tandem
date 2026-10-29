# Fas S (grund lane): ML Kit finds its component registrars (CommonComponentRegistrar,
# BarcodeRegistrar, TextRegistrar, VisionCommonRegistrar) through manifest meta-data of
# MlKitComponentDiscoveryService and creates them with the no-argument constructor. R8 full mode
# removed that constructor ("Could not instantiate ... NoSuchMethodException <init> []" at every app
# start in the minified smoke test), so keep it. Nothing else of ML Kit is kept by this rule.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
