package casa.moller.tandem.core.data.assistant

/**
 * Fas 5 (funktioner lane): whether the app lock (Mer → Lås appen, plan §5.5) lets the assistant
 * read or write right now. A request comes without the app in front, so the answer is asked for at
 * the moment of the request (a phone asleep in the background does not run the lock's timer on
 * time). Bound in core:sync to the app lock.
 */
fun interface AssistantLockCheck {
    /**
     * True when the lock is off, or the user unlocked the app and it has not been in the
     * background longer than the lock's timeout since.
     */
    suspend fun contentAllowed(): Boolean
}
