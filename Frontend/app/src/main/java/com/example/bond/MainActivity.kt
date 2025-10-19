package com.example.bond

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.bond.data.FirestoreRepository
import com.example.bond.screens.*
import com.example.bond.ui.theme.BondTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BLEManager
    private lateinit var timeTracker: TimeTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BLEManager(this)
        timeTracker = TimeTracker()

        BondApi.setFullUrl("https://3u8wgak0yk.execute-api.us-east-1.amazonaws.com/prod/similarity")

        setContent {
            BondTheme {
                com.example.bond.ui.effects.GlobalTouchRippleOverlay() {
                    BondApp(bleManager, timeTracker)
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
        timeTracker.clearCache()
        Log.d("MainActivity", "BLE fully stopped on logout.")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BondApp(bleManager: BLEManager, timeTracker: TimeTracker) {
    val pagerState = rememberPagerState(initialPage = 0) { 4 }

    val context = androidx.compose.ui.platform.LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    // Request permissions before starting BLE
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.all { it.value }
        if (granted) {
            startGlobalBLE(bleManager, timeTracker, auth, db)
        } else {
            Log.w("MainActivity", "BLE permissions denied")
        }
    }

    LaunchedEffect(Unit) {
        if (checkPermissions(context)) {
            startGlobalBLE(bleManager, timeTracker, auth, db)
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> LookingScreen(bleManager, timeTracker)
                1 -> RequestsScreen()
                2 -> BondedScreen()
                3 -> ProfileScreen()
            }
        }

        // Minimal page indicator at bottom
        PageIndicator(
            pageCount = 4,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@Composable
fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isSelected = currentPage == index

            // Animate width and color
            val width by animateDpAsState(
                targetValue = if (isSelected) 32.dp else 8.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "width"
            )

            val color by animateColorAsState(
                targetValue = if (isSelected) {
                    Color(0xFFEC4899) // Pink for selected
                } else {
                    Color.White.copy(alpha = 0.3f)
                },
                animationSpec = tween(300),
                label = "color"
            )

            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

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