package com.prismai.llmhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var service by mutableStateOf<InferenceService?>(null)
    private var uiMessage by mutableStateOf<String?>(null)
    private var isBound = false
    private var keepServiceBoundForPicker = false
    private var uiMessageJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val boundService = (binder as InferenceService.LocalBinder).getService()
            service = boundService
            uiMessage = boundService.uiMessage.value
            Log.d(TAG, "service connected uiMessage=$uiMessage")
            uiMessageJob?.cancel()
            uiMessageJob = lifecycleScope.launch {
                boundService.uiMessage.collect { message ->
                    Log.d(TAG, "uiMessage collected=$message")
                    uiMessage = message
                }
            }
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            uiMessageJob?.cancel()
            uiMessageJob = null
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChatScreen(
                service = service,
                uiMessage = uiMessage,
                onClearUiMessage = { message ->
                    service?.clearUiMessage(message)
                    if (uiMessage == message) {
                        uiMessage = null
                    }
                },
                onImportPickerStarted = {
                    keepServiceBoundForPicker = true
                },
                onImportPickerFinished = {
                    keepServiceBoundForPicker = false
                },
                onSwitchModel = { modelId ->
                    lifecycleScope.launch(Dispatchers.Default) {
                        service?.switchModel(modelId)
                    }
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, InferenceService::class.java)
        startService(intent)
        if (!isBound) {
            bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    override fun onStop() {
        if (isBound && !keepServiceBoundForPicker) {
            uiMessageJob?.cancel()
            uiMessageJob = null
            unbindService(serviceConnection)
            isBound = false
            service = null
        }
        super.onStop()
    }
}
