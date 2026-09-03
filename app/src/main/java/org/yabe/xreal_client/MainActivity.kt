package org.yabe.xreal_client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import org.yabe.xreal_client.ui.theme.YabeXREALClientTheme

class LogViewModel : ViewModel() {
    val logs = mutableStateListOf<String>()
    fun append(message: String) {
        logs += message
    }
}

class MainActivity : ComponentActivity(), CompositorService.LogListener {
    private val logViewModel: LogViewModel by viewModels()

    override fun appendLog(message: String) {
        runOnUiThread {
            logViewModel.append(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompositorService.listener = this
        appendLog("MainActivity started")
        setContent {
            YabeXREALClientTheme {
                loggerScreen(logViewModel)
            }
        }
    }

    override fun onDestroy() {
        CompositorService.listener = null
        super.onDestroy()
    }
}

@Composable
private fun loggerScreen(viewModel: LogViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(viewModel.logs) { log ->
                Text(
                    text = log,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
