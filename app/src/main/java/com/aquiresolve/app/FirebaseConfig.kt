package com.aquiresolve.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage

object FirebaseConfig {

    private const val TAG = "FirebaseConfig"
    private const val EXPECTED_PACKAGE = "com.aquiresolve.app"
    private const val EXPECTED_FILE_PATH = "app/google-services.json"

    private var firebaseApp: FirebaseApp? = null
    private var firebaseAuth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null
    private var storage: FirebaseStorage? = null
    private var analytics: FirebaseAnalytics? = null
    private var messaging: FirebaseMessaging? = null

    fun initialize(context: Context) {
        try {
            Log.d(TAG, "Initializing Firebase...")

            val apps = FirebaseApp.getApps(context)
            firebaseApp = if (apps.isNotEmpty()) {
                apps.firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: apps.first()
            } else {
                FirebaseApp.initializeApp(context)
            }

            if (firebaseApp == null) {
                throw IllegalStateException(
                    buildInvalidFirebaseConfigMessage(
                        "FirebaseApp.initializeApp returned null for package='${context.packageName}'."
                    )
                )
            }

            val hasDefaultApp = FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (!hasDefaultApp) {
                throw IllegalStateException(
                    buildInvalidFirebaseConfigMessage(
                        "Default FirebaseApp not found after initialization for package='${context.packageName}'."
                    )
                )
            }

            if (firebaseAuth == null) {
                firebaseAuth = FirebaseAuth.getInstance()
                Log.d(TAG, "FirebaseAuth initialized successfully")
            }

            if (firestore == null) {
                firestore = FirebaseFirestore.getInstance()
                Log.d(TAG, "Firestore initialized successfully")
            }

            if (storage == null) {
                storage = FirebaseStorage.getInstance()
                Log.d(TAG, "FirebaseStorage initialized successfully")
            }

            if (analytics == null) {
                analytics = FirebaseAnalytics.getInstance(context)
                Log.d(TAG, "FirebaseAnalytics initialized successfully")
            }

            if (messaging == null) {
                messaging = FirebaseMessaging.getInstance()
                Log.d(TAG, "FirebaseMessaging initialized successfully")
            }

            Log.d(TAG, "Firebase initialization completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase: ${e.message}", e)
            throw e
        }
    }

    fun getAuth(): FirebaseAuth {
        return firebaseAuth ?: throw IllegalStateException("Firebase not initialized. Call initialize() first.")
    }

    fun getFirestore(): FirebaseFirestore {
        return firestore ?: throw IllegalStateException("Firebase not initialized. Call initialize() first.")
    }

    fun getStorage(): FirebaseStorage {
        return storage ?: throw IllegalStateException("Firebase not initialized. Call initialize() first.")
    }

    fun getAnalytics(): FirebaseAnalytics? {
        return analytics
    }

    fun getMessaging(): FirebaseMessaging? {
        return messaging
    }

    fun isInitialized(): Boolean {
        return firebaseApp != null && firebaseAuth != null && firestore != null
    }

    private fun buildInvalidFirebaseConfigMessage(reason: String): String {
        return "$reason Verify '$EXPECTED_FILE_PATH' with Android client package '$EXPECTED_PACKAGE'. " +
            "Download the correct google-services.json from Firebase Console."
    }
}
