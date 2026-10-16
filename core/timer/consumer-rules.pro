# R8 rules every app using :core:timer needs (consumerProguardFiles in build.gradle.kts).

# The receivers, the ringing service and the alarm activity are referenced from the merged
# manifest, which AAPT2 already keeps. Alarms and notification actions are PendingIntents that the
# system delivers later, possibly to the NEXT build after an update: keep the component names
# stable as well (the persisted timers file uses @SerialName, so it does not depend on names).
-keepnames class casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver
-keepnames class casa.moller.tandem.core.timer.alarm.TimerSystemEventReceiver
-keepnames class casa.moller.tandem.core.timer.ring.TimerRingingService
-keepnames class casa.moller.tandem.core.timer.ring.TimerAlarmActivity
