package io.github.wlroots.budsapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.wlroots.budsapp.R
import io.github.wlroots.budsapp.bt.BudsRepository
import io.github.wlroots.budsapp.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BudsRepository.logs.collect { logs ->
                    binding.logText.text = if (logs.isEmpty()) {
                        getString(R.string.diagnostics_empty)
                    } else {
                        logs.joinToString("\n")
                    }
                    binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_diagnostics, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_copy -> {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.diagnostics_title), binding.logText.text),
            )
            Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
            true
        }

        R.id.action_clear -> {
            BudsRepository.clearLogs()
            true
        }

        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
