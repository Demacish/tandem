package casa.moller.tandem.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreModelTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core:model", CoreModel.MODULE)
    }
}
