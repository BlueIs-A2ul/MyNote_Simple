package com.mynote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mynote.app.ui.navigation.AppNavHost
import com.mynote.app.ui.theme.MyNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyNoteTheme {
                AppNavHost((application as MyNoteApp).container)
            }
        }
    }
}
