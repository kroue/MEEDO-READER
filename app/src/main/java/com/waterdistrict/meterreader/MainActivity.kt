package com.waterdistrict.meterreader

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import android.net.Uri
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.waterdistrict.meterreader.data.prefs.AppPreferences
import com.waterdistrict.meterreader.data.prefs.ThemeChoice
import com.waterdistrict.meterreader.ui.about.AboutScreen
import com.waterdistrict.meterreader.ui.auth.AuthViewModel
import com.waterdistrict.meterreader.ui.auth.LoginScreen
import com.waterdistrict.meterreader.ui.bill.DigitalBillScreen
import com.waterdistrict.meterreader.ui.consumers.MeterEntryScreen
import com.waterdistrict.meterreader.ui.consumers.MeterEntryViewModel
import com.waterdistrict.meterreader.ui.components.KeepScreenOnWhile
import com.waterdistrict.meterreader.ui.reading.ReadingEntryScreen
import com.waterdistrict.meterreader.ui.settings.SettingsScreen
import com.waterdistrict.meterreader.ui.sync.SyncScreen
import com.waterdistrict.meterreader.ui.theme.MeterReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn edge to edge on every Android version, not only on 15+ where a
        // targetSdk 35 app gets it forced: one layout to get right, with the
        // bars' insets handled by the screens themselves.
        enableEdgeToEdge()
        setContent {
            val preferences by appPreferences.state.collectAsStateWithLifecycle()
            val dark = when (preferences.theme) {
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            }

            // Status and navigation bar icons follow the app's theme rather
            // than the phone's, so they stay visible when the two differ.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                onDispose {}
            }

            MeterReaderTheme(darkTheme = dark, largeText = preferences.largeText) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(keepScreenOnWhileReading = preferences.keepScreenOn)
                }
            }
        }
    }
}

/**
 * Top-level session gate: nothing else in the app is reachable without a
 * signed-in session. Firebase Auth persists the session locally, so a
 * reader who already logged in skips straight past this on next launch.
 */
@Composable
private fun AppRoot(
    keepScreenOnWhileReading: Boolean,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val user by authViewModel.currentUser.collectAsStateWithLifecycle()

    val signedInUser = user
    if (signedInUser == null) {
        LoginScreen()
    } else {
        // The Auth email is a synthetic "username@meedo.local" address (see
        // AuthRepository) — strip the domain so the UI just shows the
        // username the reader actually signed in with.
        val displayUsername = signedInUser.email?.substringBefore("@") ?: signedInUser.uid
        MeterReaderNavHost(
            userEmail = displayUsername,
            keepScreenOnWhileReading = keepScreenOnWhileReading,
            onLogout = { authViewModel.logout() }
        )
    }
}

@Composable
private fun MeterReaderNavHost(
    userEmail: String,
    keepScreenOnWhileReading: Boolean,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()

    // The screen stays on from meter entry through each household and back,
    // if the reader wants it to — see Settings.
    val route = navController.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    val reading = route.startsWith("consumers/") || route.startsWith("reading/")
    KeepScreenOnWhile(active = keepScreenOnWhileReading && reading)

    // The billing cycle travels with the route. Everything downstream — which
    // consumers are listed, which of them count as already read, which reading
    // row a save replaces — is scoped to it. Month strings contain a space
    // ("AUG 2026"), so they are encoded into the path and decoded by the
    // ViewModels via SavedStateHandle.
    NavHost(navController = navController, startDestination = "sync") {
        composable("sync") {
            SyncScreen(
                userEmail = userEmail,
                onOpenSettings = { navController.navigate("settings") },
                onViewConsumers = { barangay, billingMonth ->
                    navController.navigate(
                        "consumers/${Uri.encode(barangay)}/${Uri.encode(billingMonth)}"
                    )
                }
            )
        }
        composable(
            route = "consumers/{barangay}/{billingMonth}",
            arguments = listOf(
                navArgument("barangay") { type = NavType.StringType },
                navArgument("billingMonth") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val billingMonth = backStackEntry.arguments?.getString("billingMonth").orEmpty()
            // The reading "home": readers open a household by typing its meter
            // number. There is no browsable or searchable list of accounts.
            MeterEntryScreen(
                onBack = { navController.popBackStack() },
                onOpenConsumer = { accountNo ->
                    navController.navigate(
                        "reading/${Uri.encode(accountNo)}/${Uri.encode(billingMonth)}"
                    )
                }
            )
        }
        composable(
            route = "reading/{accountNo}/{billingMonth}",
            arguments = listOf(
                navArgument("accountNo") { type = NavType.StringType },
                navArgument("billingMonth") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            ReadingEntryScreen(
                accountNo = backStackEntry.arguments?.getString("accountNo").orEmpty(),
                onBack = { navController.popBackStack() },
                onViewBill = { readingId -> navController.navigate("bill/$readingId") },
                onReadingComplete = { savedAccountNo ->
                    // Only pop if this reading screen is still on top — a late
                    // completion (say, a retried print finishing after the reader
                    // opened the bill) must not pop meter entry itself.
                    if (navController.currentDestination?.route?.startsWith("reading/") == true) {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(MeterEntryViewModel.KEY_LAST_SAVED, savedAccountNo)
                        navController.popBackStack()
                    }
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate("about") },
                onSignOut = onLogout
            )
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "bill/{readingId}",
            arguments = listOf(navArgument("readingId") { type = NavType.LongType })
        ) {
            DigitalBillScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
