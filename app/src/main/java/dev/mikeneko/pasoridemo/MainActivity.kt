package dev.mikeneko.pasoridemo

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import dev.mikeneko.pasori.PasoriReader
import dev.mikeneko.pasoridemo.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

// 画面に残す読み取り履歴の最大件数
private const val MAX_HISTORY_ENTRIES = 5

class MainActivity : AppCompatActivity(), CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    private lateinit var binding: ActivityMainBinding
    private val summaryView: TextView
        get() = binding.summaryView
    private val textView: TextView
        get() = binding.textView

    // 連続読み取り中かどうか（開始ボタンで true、停止ボタンまたは読み取り失敗で false）
    private var reading = false
    private var readCount = 0
    private val history = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN)

    private val diagnostics = Diagnostics()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        // 組み込み先アプリ（pitjobapp の MainActivity）と同じく画面を消灯させない
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        PasoriReader.debug = BuildConfig.DEBUG

        // 手動で連続読み取りを開始・停止する。開始後は成功ごとに次の読み取りへ進む
        binding.button.setOnClickListener {
            if (reading) {
                stopReader()
            } else {
                startReader()
            }
        }
        updateButtonLabel()
        binding.clearButton.setOnClickListener {
            diagnostics.clearStatistics()
            readCount = 0
            history.clear()
            textView.text = ""
            updateSummary()
        }

        updateSummary()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReader()
        // 読み取り成功後はセッションが開いたまま保持されるので、画面終了時に接続を解放する
        PasoriReader.close()
    }

    private fun startReader() {
        reading = true
        updateButtonLabel()
        launch {
            val result = PasoriReader.asyncReadIDs(this@MainActivity)
            withContext(Dispatchers.Main) {
                when (result) {
                    is PasoriReader.Result.Success -> {
                        diagnostics.onReadSuccess()
                        appendHistory(result.id)
                        updateSummary()
                        // 組み込み先アプリでは JavaScript 側が読み取り完了ごとに start() を呼び直している。
                        // その動きに合わせて、成功したら間を置かずに次の読み取りを開始する。
                        startReader()
                    }
                    is PasoriReader.Result.Failure -> {
                        reading = false
                        updateButtonLabel()
                        with(AlertDialog.Builder(this@MainActivity)) {
                            setTitle(result.error.message)
                            setMessage(result.error.detail)
                            setPositiveButton("再試行") { _, _ -> startReader() }
                            show()
                        }
                    }
                }
            }
        }
    }

    private fun appendHistory(id: String) {
        readCount += 1
        val entry = "${readCount}回目 ${timeFormat.format(Date())} ${id}"
        history.addFirst(entry)
        while (history.size > MAX_HISTORY_ENTRIES) {
            history.removeLast()
        }
        textView.text = history.joinToString("\n")
    }

    private fun updateSummary() {
        summaryView.text = diagnostics.summary()
    }

    private fun stopReader() {
        coroutineContext.cancelChildren()
        reading = false
        updateButtonLabel()
    }

    private fun updateButtonLabel() {
        binding.button.text = if (reading) "停止" else "開始"
    }
}
