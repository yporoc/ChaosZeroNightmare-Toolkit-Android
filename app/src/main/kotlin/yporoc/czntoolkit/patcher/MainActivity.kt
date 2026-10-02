package yporoc.czntoolkit.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import yporoc.czntoolkit.patcher.ui.CznTheme
import yporoc.czntoolkit.patcher.ui.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm = ViewModelProvider(this)[PatcherViewModel::class.java]
        setContent {
            CznTheme {
                HomeScreen(vm)
            }
        }
    }
}
