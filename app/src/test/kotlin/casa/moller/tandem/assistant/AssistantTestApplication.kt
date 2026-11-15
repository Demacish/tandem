package casa.moller.tandem.assistant

import android.app.Application
import casa.moller.tandem.core.testing.data.FakeAssistantActions

/** Fas 5 (funktioner lane): the application of the App Function tests, with a fake [AssistantHost.assistantActions]. */
class AssistantTestApplication :
    Application(),
    AssistantHost {
    val fake = FakeAssistantActions()

    override val assistantActions: FakeAssistantActions
        get() = fake
}
