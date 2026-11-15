package casa.moller.tandem.assistant

import casa.moller.tandem.core.data.assistant.AssistantActions

/**
 * Fas 5 (funktioner lane): where the App Functions' service finds [AssistantActions]. The service
 * is created by the system, not by Hilt; the application provides it (TandemApplication from the
 * Hilt graph, a test application with a fake in the Robolectric tests), so the functions can be
 * run end to end through `AppFunctionManager` in tests without a Hilt test component.
 */
interface AssistantHost {
    val assistantActions: AssistantActions
}
