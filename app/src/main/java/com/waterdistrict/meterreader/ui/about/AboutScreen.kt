package com.waterdistrict.meterreader.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterdistrict.meterreader.BuildConfig
import com.waterdistrict.meterreader.ui.theme.BrandTeal300
import com.waterdistrict.meterreader.ui.theme.SurfaceCard
import com.waterdistrict.meterreader.ui.theme.TextPrimaryDark
import com.waterdistrict.meterreader.ui.theme.TextSecondaryDark

/**
 * Who made the app, who owns it, what a reader is agreeing to by using it,
 * and which build is on the phone.
 *
 * The same ground the console's About page covers, written for the person
 * carrying the phone: the rules that matter here are about the device and the
 * household in front of them, not about a counter.
 *
 * Every word is compiled in. A reader standing at a meter with no signal can
 * still read the terms they are working under and find who to call.
 */

private const val DEVELOPER_NAME = "Aljohn Arranguez"
private const val DEVELOPER_EMAIL = "arranguez.aljohn0130@gmail.com"
private const val DEVELOPER_PHONE = "+63 953 538 3369"

private const val SYSTEM_NAME = "South Wao Water System (MEEDO)"
private const val SYSTEM_ADDRESS = "Wao, Lanao del Sur"
private const val COPYRIGHT_YEAR = "2026"

/** Third-party components this app ships, and the licence each is used under. */
private val OPEN_SOURCE = listOf(
    Triple("Jetpack Compose & AndroidX", "2024.06", "Apache-2.0"),
    Triple("Kotlin & Coroutines", "2.0.0", "Apache-2.0"),
    Triple("Room", "2.6.1", "Apache-2.0"),
    Triple("WorkManager", "2.9.0", "Apache-2.0"),
    Triple("Hilt / Dagger", "2.51.1", "Apache-2.0"),
    Triple("Accompanist", "0.34.0", "Apache-2.0"),
    Triple("ZXing", "3.5.3", "Apache-2.0"),
    Triple("ZXing Android Embedded", "4.3.0", "Apache-2.0"),
    Triple("Google client libraries", "33.1.2", "Apache-2.0"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
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
            Column {
                Text(
                    "MEEDO Field",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Text(SYSTEM_NAME, fontSize = 13.sp, color = TextSecondaryDark)
                Text(SYSTEM_ADDRESS, fontSize = 12.sp, color = TextSecondaryDark)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Meter readings taken house to house, billed on the spot, and uploaded " +
                        "to the office when there is a signal.",
                    fontSize = 13.sp,
                    color = TextSecondaryDark
                )
            }

            AboutCard(title = "Developer") {
                Text(DEVELOPER_NAME, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                Spacer(Modifier.height(6.dp))
                Text(DEVELOPER_EMAIL, fontSize = 13.sp, color = BrandTeal300)
                Text(DEVELOPER_PHONE, fontSize = 13.sp, color = BrandTeal300)
                Spacer(Modifier.height(8.dp))
                Text(
                    "For a household's bill or balance, ask the office — they can see the " +
                        "account. Call the developer when the app itself is misbehaving.",
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }

            AboutCard(title = "Ownership and copyright") {
                Text(
                    "© $COPYRIGHT_YEAR $SYSTEM_NAME. All rights reserved.",
                    fontSize = 13.sp,
                    color = TextPrimaryDark
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The app and its source code belong to the water system. So does every " +
                        "record it holds — the households on your route, their readings and " +
                        "their balances.",
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }

            AboutCard(title = "Terms of use") {
                Bullet("This app is for readers the office has issued an account to. Do not sign in for anyone else, and do not let anyone else use your sign-in.")
                Bullet("Every reading you save carries your name and the time you took it, and the office can see both. A reading is a record, not a note.")
                Bullet("Read the meter in front of you and enter what it says. If a number looks wrong, save what is on the dial and tell the office — do not adjust it to look right.")
                Bullet("The phone is office equipment. Keep it locked, and hand it back when you leave the route.")
            }

            AboutCard(title = "Data privacy") {
                Text(
                    "Republic Act No. 10173 — the Data Privacy Act of 2012.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This phone carries personal information about residents: names, " +
                        "addresses, what they used and what they owe. The law makes the office " +
                        "answerable for it, and you answerable for what you do with it.",
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
                Spacer(Modifier.height(8.dp))
                Bullet("Open only the household you are standing at. The app asks for a meter number rather than showing a list for exactly this reason.")
                Bullet("Do not photograph the screen, copy records, or send anyone's details through a personal message.")
                Bullet("Hand a receipt to the household it belongs to, nobody else.")
                Bullet("If this phone is lost or stolen, tell the office the same day. A breach that could harm people must be reported to the National Privacy Commission within 72 hours of the office learning of it.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "A working summary for readers, not legal advice. The office's data " +
                        "protection officer has the final word.",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }

            AboutCard(title = "Open-source components") {
                OPEN_SOURCE.forEach { (name, version, licence) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 13.sp, color = TextPrimaryDark)
                            Text(version, fontSize = 11.sp, color = TextSecondaryDark)
                        }
                        Text(licence, fontSize = 11.sp, color = TextSecondaryDark)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Copyright in each stays with its own authors, and each is used under the " +
                        "licence named.",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }

            AboutCard(title = "This build") {
                InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("Built", BuildConfig.BUILD_DATE)
                InfoRow("App ID", BuildConfig.APPLICATION_ID)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Quote the version when you report a problem — it is the quickest way to " +
                        "know whether the phone has the fix.",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("•", fontSize = 13.sp, color = TextSecondaryDark)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = TextSecondaryDark)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(label, fontSize = 12.sp, color = TextSecondaryDark, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 12.sp, color = TextPrimaryDark)
    }
}
