package com.waterdistrict.meterreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import android.net.Uri
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.waterdistrict.meterreader.ui.auth.AuthViewModel
import com.waterdistrict.meterreader.ui.auth.LoginScreen
import com.waterdistrict.meterreader.ui.bill.DigitalBillScreen
import com.waterdistrict.meterreader.ui.consumers.ConsumerListScreen
import com.waterdistrict.meterreader.ui.reading.ReadingEntryScreen
import com.waterdistrict.meterreader.ui.sync.SyncScreen
import com.waterdistrict.meterreader.ui.theme.MeterReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeterReaderTheme {
                Surface {
                    AppRoot()
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
private fun AppRoot(authViewModel: AuthViewModel = hiltViewModel()) {
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
            onLogout = { authViewModel.logout() }
        )
    }
}

@Composable
private fun MeterReaderNavHost(userEmail: String, onLogout: () -> Unit) {
    val navController = rememberNavController()

    // The billing cycle travels with the route. Everything downstream — which
    // consumers are listed, which of them count as already read, which reading
    // row a save replaces — is scoped to it. Month strings contain a space
    // ("AUG 2026"), so they are encoded into the path and decoded by the
    // ViewModels via SavedStateHandle.
    NavHost(navController = navController, startDestination = "sync") {
        composable("sync") {
            SyncScreen(
                userEmail = userEmail,
                onLogout = onLogout,
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
            ConsumerListScreen(
                onBack = { navController.popBackStack() },
                onConsumerSelected = { accountNo ->
                    navController.navigate(
                        "reading/${Uri.encode(accountNo)}/${Uri.encode(billingMonth)}"
                    )
                },
                onViewBill = { readingId -> navController.navigate("bill/$readingId") }
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
                onViewBill = { readingId -> navController.navigate("bill/$readingId") }
            )
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
