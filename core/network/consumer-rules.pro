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
