package wtf.buddyworks.multitasker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import wtf.buddyworks.multitasker.ui.MainScreen
import wtf.buddyworks.multitasker.ui.MultiTaskerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MultiTaskerTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCapabilities()
        viewModel.reloadAppsIfChanged()
        ShortcutFactory.migratePinned(this)
    }
}
