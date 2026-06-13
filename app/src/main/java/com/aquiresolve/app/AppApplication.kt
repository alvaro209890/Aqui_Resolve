package com.aquiresolve.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseConfig.initialize(this)
            NotificationManager.createNotificationChannels(this)
            ProviderNewOrderAlertManager.initialize(this)
            // Carrega o catálogo de serviços (nichos) do painel admin para o matching e os spinners.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    CatalogRepository.load()
                } catch (e: Exception) {
                    Log.w("AppApplication", "Falha ao pré-carregar catálogo: ${e.message}")
                }
            }
            Log.d("AppApplication", "Firebase initialized in Application.onCreate")
        } catch (e: Exception) {
            Log.e("AppApplication", "Error initializing Firebase: ${e.message}", e)
            throw IllegalStateException(
                "Critical startup failure. Verify app/google-services.json for package com.aquiresolve.app " +
                    "and download the correct file from Firebase Console.",
                e
            )
        }
    }
}
