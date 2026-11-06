# Fas 4 (review): Glance creates an ActionCallback by reflection from the class name in the click's
# PendingIntent (RunCallbackAction: Class.forName(name).getDeclaredConstructor().newInstance()).
# Glance's own rule keeps the class only, and in R8 full mode that does not keep the constructor:
# a tap on a row in the shopping widget failed with NoSuchMethodException in every minified build
# and nothing was checked off. smoke.yml's mapping check fails if the constructor goes again.
-keepclassmembers class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
