package casa.moller.tandem.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppBuildInfoTest {
    @Test
    fun userAgentFollowsPlanFormat() {
        val info = AppBuildInfo(
            versionName = "0.0.1-dev",
            versionCode = 1,
            distribution = "sideload",
            isDebug = true,
            sdkInt = 36,
        )
        assertEquals("Tandem/0.0.1-dev (Android 36)", info.userAgent)
    }
}
