package com.waterdistrict.meterreader.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.waterdistrict.meterreader.BuildConfig
import com.waterdistrict.meterreader.R
import com.waterdistrict.meterreader.ui.components.AppTopBar
import com.waterdistrict.meterreader.ui.components.KeyValueRow
import com.waterdistrict.meterreader.ui.components.SectionCard

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

/** Who to reach when the app itself is at fault. */
private data class Developer(val name: String, val email: String, val phone: String)

private val DEVELOPERS = listOf(
    Developer("Melvin", "piolo.melvin17@gmail.com", "0906 780 7028"),
    Developer("Aljohn Arranguez", "arranguez.aljohn0130@gmail.com", "+63 953 538 3369"),
)

/** The company that built the system, and owns it. */
private const val COMPANY_NAME = "Sysware"
private const val COMPANY_FULL_NAME = "Sysware Computer Sales & Services"

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

@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    Scaffold(
        topBar = { AppTopBar(title = "About", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("MEEDO Field", style = MaterialTheme.typography.titleLarge)
                    Muted(SYSTEM_NAME)
                    Muted(SYSTEM_ADDRESS)
                }
            }
            Muted(
                "Meter readings taken house to house, billed on the spot, and uploaded to the " +
                    "office when there is a signal."
            )

            SectionCard(title = "Developers", icon = Icons.Default.Code) {
                DEVELOPERS.forEachIndexed { index, developer ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    Text(developer.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    ContactRow(Icons.Default.Email, developer.email)
                    ContactRow(Icons.Default.Phone, developer.phone)
                }
                Spacer(Modifier.height(8.dp))
                Muted(
                    "For a household's bill or balance, ask the office — they can see the " +
                        "account. Call a developer when the app itself is misbehaving."
                )
            }

            SectionCard(title = "Ownership and copyright", icon = Icons.Default.Business) {
                Text(
                    "© $COPYRIGHT_YEAR $COMPANY_FULL_NAME. All rights reserved.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Muted(
                    "The app and its source code belong to $COMPANY_NAME, which built the " +
                        "system for $SYSTEM_NAME. Every record it holds — the households on " +
                        "your route, their readings and their balances — belongs to the water " +
                        "office."
                )
            }

            SectionCard(title = "Terms of use", icon = Icons.Default.Gavel) {
                Bullet("This app is for readers the office has issued an account to. Do not sign in for anyone else, and do not let anyone else use your sign-in.")
                Bullet("Every reading you save carries your name and the time you took it, and the office can see both. A reading is a record, not a note.")
                Bullet("Read the meter in front of you and enter what it says. If a number looks wrong, save what is on the dial and tell the office — do not adjust it to look right.")
                Bullet("The phone is office equipment. Keep it locked, and hand it back when you leave the route.")
            }

            SectionCard(title = "Data privacy", icon = Icons.Default.Policy) {
                Text(
                    "Republic Act No. 10173 — the Data Privacy Act of 2012.",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                Muted(
                    "This phone carries personal information about residents: names, " +
                        "addresses, what they used and what they owe. The law makes the office " +
                        "answerable for it, and you answerable for what you do with it."
                )
                Spacer(Modifier.height(6.dp))
                Bullet("Open only the household you are standing at. The app asks for a meter number rather than showing a list for exactly this reason.")
                Bullet("Do not photograph the screen, copy records, or send anyone's details through a personal message.")
                Bullet("Hand a receipt to the household it belongs to, nobody else.")
                Bullet("If this phone is lost or stolen, tell the office the same day. A breach that could harm people must be reported to the National Privacy Commission within 72 hours of the office learning of it.")
                Spacer(Modifier.height(6.dp))
                Muted(
                    "A working summary for readers, not legal advice. The office's data " +
                        "protection officer has the final word."
                )
            }

            SectionCard(title = "Open-source components", icon = Icons.Default.Description) {
                OPEN_SOURCE.forEach { (name, version, licence) ->
                    KeyValueRow("$name $version", licence)
                }
                Spacer(Modifier.height(6.dp))
                Muted("Copyright in each stays with its own authors, and each is used under the licence named.")
            }

            SectionCard(title = "This build", icon = Icons.Default.Tag) {
                KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                KeyValueRow("Built", BuildConfig.BUILD_DATE)
                KeyValueRow("App ID", BuildConfig.APPLICATION_ID)
                Spacer(Modifier.height(6.dp))
                Muted(
                    "Quote the version when you report a problem — it is the quickest way to " +
                        "know whether the phone has the fix."
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ContactRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
