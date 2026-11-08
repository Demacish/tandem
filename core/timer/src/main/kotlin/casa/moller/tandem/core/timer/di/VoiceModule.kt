package casa.moller.tandem.core.timer.di

import casa.moller.tandem.core.data.voice.StepSpeaker
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
import casa.moller.tandem.core.timer.voice.AndroidStepSpeaker
import casa.moller.tandem.core.timer.voice.AndroidVoiceCommandRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): cook mode's on-device speech recognizer and
 * text to speech. core:timer is the module for the kitchen's device services (timers, alarms and
 * now the voice); features reach them only through core:data.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class VoiceModule {
    @Binds
    abstract fun bindVoiceCommandRecognizer(impl: AndroidVoiceCommandRecognizer): VoiceCommandRecognizer

    @Binds
    abstract fun bindStepSpeaker(impl: AndroidStepSpeaker): StepSpeaker
}
