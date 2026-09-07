package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.example.presentation.MainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }

    /**
     * O app voltou para o primeiro plano (T16.6).
     *
     * Este é o gatilho conservador de sincronização: o coordenador decide se vale rodar — só roda
     * se houver alteração pendente ou se a última sincronização já estiver velha. Abrir o Spark
     * dez vezes em cinco minutos não produz dez ciclos.
     *
     * Ele **não bloqueia a primeira renderização**: a chamada apenas lança uma corrotina, e a UI
     * continua lendo o Room. Sem rede, sem conta ou sem vínculo, nada acontece e nada muda.
     */
    override fun onStart() {
        super.onStart()
        (application as? MainApplication)?.syncCoordinator?.onAppForeground()
    }
}
