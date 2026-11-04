package com.sharfo.myplans

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sharfo.myplans.core.navigation.AppNavHost
import com.sharfo.myplans.core.theme.MyPlansTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyPlansTheme {
                AppNavHost()
            }
        }
    }
}
