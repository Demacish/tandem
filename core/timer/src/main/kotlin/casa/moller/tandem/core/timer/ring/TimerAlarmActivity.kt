package casa.moller.tandem.core.timer.ring

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.timer.R
import casa.moller.tandem.core.timer.di.TimerEntryPoint
import casa.moller.tandem.core.timer.engine.DefaultTimerController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The full-screen alarm on a locked screen (sideload build, plan 8.1 "Larmet går även med låst
 * skärm"): started by the alarm notification's full-screen intent. Shows every ringing timer with
 * big "+1 min" and "Stop" buttons (64 dp, plan 6.6) without unlocking, and closes itself when
 * nothing rings any more. Deliberately minimal (plain views, no Compose): it must start fast from
 * a cold process with the screen off.
 */
internal class TimerAlarmActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.timer_alarm_activity)
        val rows = findViewById<LinearLayout>(R.id.timer_alarm_rows)
        val engine = TimerEntryPoint.from(this).engine()
        scope.launch {
            engine.loadedTimers()
            engine.timers.map { list -> list.filter { it.state == TimerState.RINGING } }
                .distinctUntilChanged()
                .collect { ringing ->
                    if (ringing.isEmpty()) {
                        finish()
                    } else {
                        render(rows, ringing, engine)
                    }
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(rows: LinearLayout, ringing: List<CookTimer>, engine: DefaultTimerController) {
        rows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (timer in ringing) {
            val row = inflater.inflate(R.layout.timer_alarm_row, rows, false)
            row.findViewById<TextView>(R.id.timer_alarm_label).text = timer.label
            row.findViewById<Button>(R.id.timer_alarm_plus_one).apply {
                contentDescription = getString(R.string.timer_action_plus_one_minute_for, timer.label)
                setOnClickListener { scope.launch { engine.addOneMinute(timer.id) } }
            }
            row.findViewById<Button>(R.id.timer_alarm_stop).apply {
                contentDescription = getString(R.string.timer_action_stop_for, timer.label)
                setOnClickListener { scope.launch { engine.stopRinging(timer.id) } }
            }
            rows.addView(row)
        }
    }
}
