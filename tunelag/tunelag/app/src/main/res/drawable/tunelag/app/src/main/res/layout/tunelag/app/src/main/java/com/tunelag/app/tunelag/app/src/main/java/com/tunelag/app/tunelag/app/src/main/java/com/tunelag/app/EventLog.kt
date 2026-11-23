package com.tunelag.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro en memoria, con hora, para diagnosticar en partida. */
object EventLog {

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayList<String>()
    private var sink: ((String) -> Unit)? = null

    fun attachSink(s: ((String) -> Unit)?) {
        sink = s
    }

    fun record(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        synchronized(lines) {
            lines.add(line)
            if (lines.size > 200) lines.removeAt(0)
        }
        sink?.invoke(line)
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
}
