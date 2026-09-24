package com.dali.teslagps.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱 전체 진단 로그.
 * - 메모리 링버퍼(최근 600줄) + 파일(filesDir/diag.log, 200KB 초과 시 최근분만 남기고 정리)
 * - 앱이 비정상 종료되어도 파일에 남아 다음 실행 때 볼 수 있다.
 * - 개인키·토큰은 절대 기록하지 않는다. VIN 은 호출하는 쪽에서 끝 6자리만 넘긴다.
 */
object DiagLog {
    private const val MAX_LINES = 600
    private const val MAX_FILE_BYTES = 200_000L
    private const val KEEP_ON_TRIM = 300

    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private var file: File? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, "diag.log")
            file = f
            try {
                if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { buffer.addLast(it) }
            } catch (e: Exception) {
                Log.w("DiagLog", "이전 로그 읽기 실패", e)
            }
        }
        i("APP", "── 앱 시작 ──")
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun d(tag: String, msg: String) = add("D", tag, msg, null)
    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e("T-GPS/$tag", msg, t)
            "W" -> Log.w("T-GPS/$tag", msg, t)
            else -> Log.d("T-GPS/$tag", msg)
        }
        synchronized(lock) {
            val ts = timeFormat.format(Date())
            val lines = ArrayList<String>()
            lines.add("$ts $level/$tag: $msg")
            if (t != null) {
                t.stackTraceToString().lines().filter { it.isNotBlank() }.take(16).forEach { lines.add("    $it") }
            }
            for (line in lines) {
                buffer.addLast(line)
                while (buffer.size > MAX_LINES) buffer.removeFirst()
            }
            writeToFile(lines)
        }
        listeners.forEach { it() }
    }

    private fun writeToFile(lines: List<String>) {
        val f = file ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) {
                f.writeText(buffer.toList().takeLast(KEEP_ON_TRIM).joinToString("\n", postfix = "\n"))
            } else {
                FileWriter(f, true).use { w -> lines.forEach { w.write(it); w.write("\n") } }
            }
        } catch (e: Exception) {
            Log.w("DiagLog", "로그 파일 쓰기 실패", e)
        }
    }

    /** 최근 로그 텍스트 (너무 길면 뒤쪽 maxChars 만) */
    fun text(maxChars: Int = 60_000): String = synchronized(lock) {
        val all = buffer.joinToString("\n")
        if (all.length <= maxChars) all else "…(앞부분 생략)\n" + all.takeLast(maxChars)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            try { file?.writeText("") } catch (e: Exception) { /* ignore */ }
        }
        i("APP", "로그를 지웠습니다")
    }
}
