# R8 rules that every app using :core:network needs (consumerProguardFiles in build.gradle.kts).
# Retrofit 3 and kotlinx.serialization ship their own rules (META-INF/proguard in their jars);
# these only cover what those rules cannot know about this module.

# --- Retrofit service interfaces: keep them, unconditionally ---------------------------------
#
# TandoorApiFactory.create() gets its services from Retrofit.create(), i.e. java.lang.reflect.Proxy.
# R8 never sees a class that implements AuthService, UserService, SpaceService or ServerService.
# In full mode (AGP's default) R8 therefore treats such an interface as having NO instances: a
# value of that type can only be null, and a checkcast of a non-null value to it can only fail.
# It rewrites `retrofit.create(X::class.java) as X` into `throw ClassCastException()` and deletes
# everything after it as unreachable.
#
# Retrofit's own rule only protects an interface while at least one of its annotated methods is
# still live:
#     -if interface * { @retrofit2.http.* <methods>; }
#     -keep,allowobfuscation interface <1>
# ServerService.serverSettings() is never called (ServerProbe uses raw OkHttp), so R8 removed it,
# the -if no longer matched, and in v0.0.1 TandoorApiFactory.create() ALWAYS threw
# ClassCastException at `server = retrofit.create(ServerService::class.java)`. Every login
# crashed before a single request was sent, and R8 removed AuthService.login(),
# TokenAuthRequestDto and the login steps as dead code behind that throw.
#
# Keeping the interfaces themselves (names may still be obfuscated, members are still governed by
# Retrofit's rules) tells R8 that implementations may exist that it cannot see.
#
# The rule covers EVERY interface in core.network.api and its subpackages (`**` crosses package
# separators, `*` does not), whatever it is called: a new service such as `RecipeApi` or
# `api.recipes.RecipeService`, wired into TandoorApi before any feature calls it, is exactly the
# shape that broke v0.0.1. Retrofit service interfaces belong in this package and nowhere else
# (TandoorApiServicesTest checks TandoorApi; smoke.yml checks the mapping).
-keep,allowobfuscation interface casa.moller.tandem.core.network.api.**

# --- Fas S (integration): DTOs that are only a suspend function's return type ---------------
#
# A Retrofit `suspend fun` returns Object to R8; its response type lives only in the generic
# signature of the Continuation parameter. A DTO whose value the caller ignores (KeywordMoveDto of
# `PUT keyword/{id}/move/{parent}/`, RecipeImageDto of the drink photo's `PUT recipe/{id}/image/`)
# is therefore referenced by no code, and R8 full mode removed the class. Retrofit then failed to
# build the call BEFORE sending anything (the release smoke test's drink pairing stopped after the
# keyword POSTs and never moved the keyword or PATCHed the recipe). Keeping every @Serializable
# class of the DTO package (names may be obfuscated) also keeps its generated serializer through
# kotlinx.serialization's own rules. smoke.yml checks that each of them is in the R8 mapping.
-keep,allowobfuscation @kotlinx.serialization.Serializable class casa.moller.tandem.core.network.dto.**
-keep,allowobfuscation @kotlinx.serialization.Serializable class casa.moller.tandem.core.network.openfoodfacts.**
