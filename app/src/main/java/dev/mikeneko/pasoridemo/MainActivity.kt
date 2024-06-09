package dev.mikeneko.pasoridemo

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import dev.mikeneko.pasori.PasoriReader
import dev.mikeneko.pasoridemo.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(), CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    private lateinit var binding: ActivityMainBinding
    private val textView: TextView
        get() = binding.textView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        PasoriReader.debug = BuildConfig.DEBUG

        binding.button.setOnClickListener {
            startReader()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReader()
    }

    private fun startReader() {
        textView.text = "読み取り中..."
        launch {
            val result = PasoriReader.asyncReadIDs(this@MainActivity)
            withContext(Dispatchers.Main) {
                when (result) {
                    is PasoriReader.Result.Success -> {
                        textView.text = result.id
                    }
                    is PasoriReader.Result.Failure -> {
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

    private fun stopReader() {
        coroutineContext.cancelChildren()
    }
}
