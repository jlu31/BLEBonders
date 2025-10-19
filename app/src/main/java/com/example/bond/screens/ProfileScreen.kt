// ProfileScreen.kt
package com.example.bond.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.bond.Login
import com.example.bond.MainActivity
import com.example.bond.network.UploadHelper
import com.example.bond.data.FirestoreRepository
import com.example.bond.data.BondedUserWithTime
import com.google.firebase.auth.FirebaseAuth
import java.io.File

@Composable
fun ProfileScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    val user = auth.currentUser
    val username = user?.email?.substringBefore("@") ?: "unknown"

    val context = LocalContext.current
    val activity = context as? MainActivity

    var isRecording by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var recorder: MediaRecorder? by remember { mutableStateOf(null) }
    var filePath by remember { mutableStateOf("") }
    var bondedUsers by remember { mutableStateOf<List<BondedUserWithTime>>(emptyList()) }

    // --- Animated background (matches other screens) ---
    val bgAnim = rememberInfiniteTransition(label = "bg")
    val animatedOffset by bgAnim.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )
    val backgroundBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0F0F1E),
            Color(0xFF1A1A2E),
            Color(0xFF16213E)
        ),
        start = Offset(animatedOffset, animatedOffset),
        end = Offset(animatedOffset + 500f, animatedOffset + 500f)
    )
    val primaryGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))
    )
    val dangerGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    )
    val titleScale by bgAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "titleScale"
    )

    // --- Mic permission ---
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(context, "Microphone permission required", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        
        // Fetch bonded users with time data
        FirestoreRepository.getBondedUsersWithTime { users ->
            bondedUsers = users.take(3) // Take top 3 users
        }
    }

    fun startRecording() {
        try {
            // Record to app-scoped Music dir: <username>.mp3 (same as your old backend)
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "$username.mp3"
            )
            if (file.exists()) {
                try {
                    if (file.delete()) {
                        Toast.makeText(context, "Previous recording deleted", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {}
            }

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // MP4 container
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            filePath = file.absolutePath
            isRecording = true
            Toast.makeText(context, "Recording…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecording() {
        try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isRecording = false

            val file = File(filePath)
            Toast.makeText(context, "Saved at $filePath", Toast.LENGTH_SHORT).show()

            // Upload in background using your UploadHelper
            isUploading = true
            Thread {
                try {
                    val uploadData = UploadHelper.getUploadUrl(file.name)
                    UploadHelper.uploadToS3(file, uploadData.uploadURL)
                    (context as? android.app.Activity)?.runOnUiThread {
                        isUploading = false
                        Toast.makeText(context, "Uploaded to S3! User: $username", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        isUploading = false
                        Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(context, "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Profile",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.scale(titleScale)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    isRecording -> "Recording voice sample…"
                    isUploading -> "Uploading sample…"
                    else -> "Customize your identity & stats"
                },
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))

            // --- Card container ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // --- Round profile avatar (same style as LookingScreen) ---
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = username.firstOrNull()?.uppercase() ?: "?",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "@$username",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    // --- Voice recording CTA ---
                    val pressScale by animateFloatAsState(
                        targetValue = if (isRecording) 1.02f else 1f,
                        animationSpec = spring(),
                        label = "pressScale"
                    )
                    Button(
                        onClick = {
                            val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            if (perm == PackageManager.PERMISSION_GRANTED && !isUploading) {
                                if (isRecording) stopRecording() else startRecording()
                            } else if (perm != PackageManager.PERMISSION_GRANTED) {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !isUploading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .scale(pressScale),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(primaryGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isUploading) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Uploading…", color = Color.White, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(
                                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = if (isRecording) "Stop Recording" else "Start Recording",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            isRecording -> "Recording…"
                            isUploading -> "Uploading to S3…"
                            filePath.isNotEmpty() -> "Saved to: $filePath"
                            else -> "Ready"
                        },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp
                    )

                    Spacer(Modifier.height(16.dp))

                    // --- Your Stats Section ---
                    Text(
                        text = "Your Stats",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    if (bondedUsers.isEmpty()) {
                        Text(
                            text = "No bonds yet - start connecting with others!",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Start)
                        )
                    } else {
                        // Display top bonded users
                        bondedUsers.forEachIndexed { index, bondedUser ->
                            BondedUserStatsItem(
                                user = bondedUser,
                                rank = index + 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (index < bondedUsers.size - 1) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- Logout ---
            Button(
                onClick = {
                    try {
                        activity?.stopBLE()
                        auth.signOut()
                        val intent = Intent(context, Login::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Logout failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(dangerGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Logout, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(10.dp))
                        Text("Log Out", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun BondedUserStatsItem(
    user: BondedUserWithTime,
    rank: Int,
    modifier: Modifier = Modifier
) {
    val hours = user.totalTimeMinutes / 60
    val minutes = user.totalTimeMinutes % 60
    
    val timeText = when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "0m"
    }
    
    val rankColor = when (rank) {
        1 -> Color(0xFFFFD700) // Gold
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFCD7F32) // Bronze
        else -> Color.White.copy(alpha = 0.7f)
    }
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Rank and username
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "#$rank",
                    color = rankColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "@${user.username}",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
            
            // Time spent
            Text(
                text = timeText,
                color = Color(0xFF10B981),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
