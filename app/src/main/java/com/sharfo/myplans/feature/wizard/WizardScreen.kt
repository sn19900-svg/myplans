package com.sharfo.myplans.feature.wizard

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sharfo.myplans.domain.model.BuildingCode
import com.sharfo.myplans.domain.model.SlabType
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WizardScreen(
    onBack: () -> Unit,
    onPlanGenerated: (Long) -> Unit,
    viewModel: WizardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.updateSitePhoto(tempCameraUri.toString())
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val photoDir = File(context.cacheDir, "photos").apply { mkdirs() }
            val photoFile = File(photoDir, "site_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateSitePhoto(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إنشاء مخطط معماري وإنشائي") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. المعطيات المعمارية
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "1. المعطيات المعمارية والأرض",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = state.projectName,
                        onValueChange = viewModel::updateProjectName,
                        label = { Text("اسم المشروع") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.landAreaText,
                            onValueChange = viewModel::updateLandArea,
                            label = { Text("مساحة الأرض (م²)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.frontageWidthText,
                            onValueChange = viewModel::updateFrontageWidth,
                            label = { Text("عرض الواجهة (م)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    CounterRow("عدد الطوابق", state.floorsCount, viewModel::updateFloors)
                    CounterRow("عدد غرف النوم", state.roomsCount, viewModel::updateRooms)
                    CounterRow("عدد الحمامات", state.bathroomsCount, viewModel::updateBathrooms)

                    Text("المنافع الإضافية:", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.hasReception, onCheckedChange = viewModel::toggleReception)
                        Text("صالون استقبال")
                        Spacer(Modifier.weight(1f))
                        Checkbox(checked = state.hasLivingRoom, onCheckedChange = viewModel::toggleLiving)
                        Text("غرفة معيشة")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.hasKitchen, onCheckedChange = viewModel::toggleKitchen)
                        Text("مطبخ رئيسي")
                        Spacer(Modifier.weight(1f))
                        Checkbox(checked = state.hasStaircase, onCheckedChange = viewModel::toggleStaircase)
                        Text("بيت درج")
                    }

                    Text("صورة موقع الأرض أو الكروكي (اختياري):", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Text(" الكاميرا")
                        }
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            Text(" المعرض")
                        }
                    }
                    if (state.sitePhotoUri != null) {
                        AsyncImage(
                            model = state.sitePhotoUri,
                            contentDescription = "صورة الموقع",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                    }
                }
            }

            // 2. المعطيات الإنشائية
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "2. الكود الهندسي والنظام الإنشائي",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text("اختر الكود المعتمد:", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BuildingCode.entries.forEach { code ->
                            FilterChip(
                                selected = state.buildingCode == code,
                                onClick = { viewModel.updateBuildingCode(code) },
                                label = { Text(code.titleAr) }
                            )
                        }
                    }

                    Text("نوع البلاطة الإنشائية:", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SlabType.entries.forEach { slab ->
                            FilterChip(
                                selected = state.slabType == slab,
                                onClick = { viewModel.updateSlabType(slab) },
                                label = { Text(slab.titleAr) }
                            )
                        }
                    }

                    Text("القطر المفضل لتسليح البلاطات (مم):", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 12, 14).forEach { dia ->
                            FilterChip(
                                selected = state.slabBarDiaMm == dia,
                                onClick = { viewModel.updateSlabBarDia(dia) },
                                label = { Text("Ø$dia") }
                            )
                        }
                    }

                    Text("القطر المفضل لتسليح الجسور (مم):", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(14, 16, 18, 20).forEach { dia ->
                            FilterChip(
                                selected = state.beamBarDiaMm == dia,
                                onClick = { viewModel.updateBeamBarDia(dia) },
                                label = { Text("Ø$dia") }
                            )
                        }
                    }

                    Text("القطر المفضل لتسليح الأعمدة (مم):", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(16, 18, 20, 22, 25).forEach { dia ->
                            FilterChip(
                                selected = state.columnBarDiaMm == dia,
                                onClick = { viewModel.updateColumnBarDia(dia) },
                                label = { Text("Ø$dia") }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.generateAndSave(onPlanGenerated) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("توليد المخطط المعماري والإنشائي", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CounterRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = { onValueChange(value - 1) }) { Text("-") }
            Text(value.toString(), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onValueChange(value + 1) }) { Text("+") }
        }
    }
}
