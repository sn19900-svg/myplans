package com.myprojects.cad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import com.myprojects.cad.presentation.editor.CadEditorScreen
import com.myprojects.cad.presentation.editor.CadEditorViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Same instance that hiltViewModel() returns inside the screen (activity-scoped store).
    private val editorViewModel: CadEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { CadEditorScreen() }
            }
        }
    }

    override fun onStop() {
        editorViewModel.saveIfDirty() // autosave whenever the app leaves the screen
        super.onStop()
    }
}
