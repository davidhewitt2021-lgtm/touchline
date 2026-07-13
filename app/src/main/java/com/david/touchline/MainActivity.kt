package com.david.touchline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.david.touchline.ui.GameViewModel
import com.david.touchline.ui.TouchlineApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: GameViewModel = viewModel()
            TouchlineApp(vm)
        }
    }
}
