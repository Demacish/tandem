package casa.moller.tandem.core.network.api

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the R8 keep rule for Retrofit services (consumer-rules.pro). Retrofit creates services
 * with java.lang.reflect.Proxy, so R8 never sees an implementation; an interface the rule does
 * not cover is treated as uninstantiable once none of its methods is called, and
 * `retrofit.create(X) as X` becomes `throw ClassCastException()` (v0.0.1's login crash).
 */
class TandoorApiServicesTest {
    private val servicePackage = "casa.moller.tandem.core.network.api"

    private val serviceTypes: List<Class<*>> = TandoorApi::class.java.declaredConstructors
        .flatMap { it.parameterTypes.toList() }
        .filter { it.isInterface }
        .distinct()

    @Test
    fun tandoorApiHasServices() {
        assertTrue("TandoorApi has no service parameters", serviceTypes.isNotEmpty())
    }

    @Test
    fun everyServiceOfTandoorApiIsCoveredByTheKeepRule() {
        for (type in serviceTypes) {
            assertTrue(
                "${type.name} is outside $servicePackage(.**) and not kept by consumer-rules.pro",
                type.name.startsWith("$servicePackage."),
            )
        }
    }

    @Test
    fun everyServiceOfTandoorApiIsARetrofitInterface() {
        for (type in serviceTypes) {
            val retrofitMethods = type.declaredMethods.filter { method ->
                method.annotations.any { it.annotationClass.java.name.startsWith("retrofit2.http.") }
            }
            assertTrue("${type.name} has no Retrofit methods", retrofitMethods.isNotEmpty())
        }
    }

    @Test
    fun keepRuleCoversThePackageAndItsSubpackages() {
        // Gradle runs unit tests in the module directory.
        val rules = File("consumer-rules.pro").readLines().map { it.trim() }.filterNot { it.startsWith("#") }
        assertTrue(rules.joinToString("\n"), "-keep,allowobfuscation interface $servicePackage.**" in rules)
        // A name filter like *Service would silently drop the next RecipeApi.
        assertFalse(rules.joinToString("\n"), rules.any { it.startsWith("-keep,allowobfuscation interface $servicePackage.*Service") })
    }
}
