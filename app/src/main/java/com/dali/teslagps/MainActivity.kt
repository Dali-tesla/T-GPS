package com.dali.teslagps

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.dali.teslagps.diag.DiagLog
import com.dali.teslagps.ui.AnchorScreen
import com.dali.teslagps.ui.AnchorViewModel
import com.dali.teslagps.ui.theme.TeslaGpsDarkColors

class MainActivity : ComponentActivity() {

    private val vm: AnchorViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        // 비정상 종료 원인을 파일에 남긴다 (다음 실행 때 진단 로그에서 확인)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            DiagLog.e("CRASH", "처리되지 않은 예외 thread=${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        enableEdgeToEdge()
        // 상태바/내비게이션바 아이콘을 밝은색으로 — 어두운 배경과 대비를 맞춘다.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        requestPermissions()

        setContent {
            MaterialTheme(colorScheme = TeslaGpsDarkColors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Scaffold(
                        Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                    ) { inner ->
                        Box(Modifier.padding(inner)) {
                            AnchorScreen(vm)
                        }
                    }
                }
            }
        }
    }

    /** 위치, 블루투스, 알림 권한을 요청한다. */
    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.distinct().toTypedArray())
    }
}
