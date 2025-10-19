package com.example.bond

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.bond.data.FirestoreRepository
import com.example.bond.screens.*
import com.example.bond.ui.theme.BondTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BLEManager
    private lateinit var timeTracker: TimeTracker // 🔹 ADDED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BLEManager(this)
        timeTracker = TimeTracker() // 🔹 ADDED
        
        // Initialize BondApi - you'll need to set your actual API ID or URL
        // BondApi.setApiId("your-api-id-here") // Replace with your actual API ID
        // OR
        BondApi.setFullUrl("https://3u8wgak0yk.execute-api.us-east-1.amazonaws.com/prod/similarity")

        setContent {
            BondTheme {
                com.example.bond.ui.effects.GlobalTouchRippleOverlay() {
                    BondApp(bleManager, timeTracker) // 🔹 MODIFIED: Pass timeTracker
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopAdvertising()
        bleManager.stopBLEScan()
    }

    fun stopBLE() {
        bleManager.stopAdvertising()
        bleManager.stopBLEScan()
        timeTracker.clearCache() // 🔹 ADDED: Clear cache on logout
        Log.d("MainActivity", "BLE fully stopped on logout.")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BondApp(bleManager: BLEManager, timeTracker: TimeTracker) { // 🔹 MODIFIED: Added timeTracker param
    val pages = listOf("Looking", "Requests", "Bonded", "Profile")
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }
    val coroutineScope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    // Request permissions before starting BLE
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.all { it.value }
        if (granted) {
            startGlobalBLE(bleManager, timeTracker, auth, db) // 🔹 MODIFIED: Pass timeTracker
        } else {
            Log.w("MainActivity", "BLE permissions denied")
        }
    }

    LaunchedEffect(Unit) {
        if (checkPermissions(context)) {
            startGlobalBLE(bleManager, timeTracker, auth, db) // 🔹 MODIFIED: Pass timeTracker
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bond") }) },
        bottomBar = {
            NavigationBar {
                pages.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        icon = {},
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            when (page) {
                0 -> LookingScreen(bleManager, timeTracker) // 🔹 MODIFIED: Pass timeTracker
                1 -> RequestsScreen()
                2 -> BondedScreen()
                3 -> ProfileScreen()
            }
        }
    }
}

// 🔹 MODIFIED: Added timeTracker parameter
private fun startGlobalBLE(
    bleManager: BLEManager,
    timeTracker: TimeTracker,
    auth: FirebaseAuth,
    db: FirebaseFirestore
) {
    val user = auth.currentUser ?: return
    db.collection("users").document(user.uid)
        .get()
        .addOnSuccessListener { doc ->
            val username = doc.getString("username") ?: "Unknown"
            try {
                Log.d("MainActivity", "Starting BLE globally as $username")

                // 🔹 NOTE: LookingScreen will set up the callback that includes TimeTracker
                // We don't set it here to avoid it being overwritten

                bleManager.startAdvertising(username)
                bleManager.startBLEScan()
            } catch (se: SecurityException) {
                Log.e("MainActivity", "BLE permission error: ${se.message}")
            }
        }
        .addOnFailureListener { e ->
            Log.e("MainActivity", "Failed to fetch username: ${e.message}")
        }
}

private fun checkPermissions(context: android.content.Context): Boolean {
    val perms = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    return perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}