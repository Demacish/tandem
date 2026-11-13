# Tandem for Wear OS (release, R8 full mode). The services, the receiver and the worker are kept by
# their manifest entries and WorkManager's own rules; kotlinx.serialization ships its rules for the
# @Serializable payloads (core:watchlink) and the stored state; core:network brings its consumer
# rules for Retrofit. Nothing else needs a keep rule yet.
