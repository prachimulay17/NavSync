package com.example.navsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.navsync.ui.NavigationScreen
import com.example.navsync.ui.theme.NavSyncTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            NavSyncTheme {
                NavigationScreen()
            }
        }
    }
}