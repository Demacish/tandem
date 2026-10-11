# R8 rules every app using :core:sync needs (consumerProguardFiles in build.gradle.kts).

# WorkManager stores the worker's class name in its database (WorkSpec.workerClassName) and
# HiltWorkerFactory finds the factory by the name written into the generated @StringKey. If R8
# renamed a worker, a request enqueued by one build could never be started by the next one (and
# the Hilt key would not match the renamed class), so keep the names of our workers.
-keepnames class casa.moller.tandem.core.sync.work.** extends androidx.work.ListenableWorker
