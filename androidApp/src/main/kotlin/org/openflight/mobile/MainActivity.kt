package org.openflight.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import org.openflight.mobile.ui.OpenFlightApp

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: OpenFlightViewModel
    private var permissionRequestInFlight = false

    private val networkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequestInFlight = false
        if (granted) {
            viewModel.startFeed()
        } else {
            viewModel.requireNetworkPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[OpenFlightViewModel::class.java]
        setContent {
            OpenFlightApp(
                state = viewModel.store.state,
                onRequestNetworkPermission = ::requestNetworkPermission,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        startFeedWhenPermitted(requestAutomatically = true)
    }

    override fun onStop() {
        viewModel.stopFeed()
        super.onStop()
    }

    private fun startFeedWhenPermitted(requestAutomatically: Boolean) {
        if (Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startFeed()
            return
        }

        viewModel.requireNetworkPermission()
        if (requestAutomatically) {
            requestNetworkPermission()
        }
    }

    private fun requestNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37) {
            viewModel.startFeed()
            return
        }
        if (!permissionRequestInFlight) {
            permissionRequestInFlight = true
            networkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
