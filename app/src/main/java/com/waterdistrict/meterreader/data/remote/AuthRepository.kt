package com.waterdistrict.meterreader.data.remote

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a sign-in succeeds but the account isn't a field-reader account. */
class NotAuthorizedException :
    Exception(
        "This account can't sign in — it either isn't set up for the mobile app or has been " +
            "disabled. Ask your admin to check it."
    )

/** A field reader's profile, set up by an admin alongside the account itself. */
data class ReaderProfile(
    val firstName: String,
    val lastName: String,
    val phoneNumber: String
) {
    val fullName: String get() = "$firstName $lastName".trim()
}

/** The synthetic email domain admin-created field-reader accounts live under — see water-billing-admin's createFieldReader.ts. */
private const val USERNAME_DOMAIN = "meedo.local"

/**
 * The console's own minimum (createFieldReader.ts MIN_PASSWORD_LENGTH), so a
 * reader can't choose a password the office would have refused to set.
 */
const val MIN_PASSWORD_LENGTH = 10

/** A change the account can't make, worded for the person holding the phone. */
class AccountChangeException(message: String) : Exception(message)

/**
 * Field-reader login — a plain username and password, no email involved on
 * the reader's side. Under the hood this is still Firebase Auth's
 * email/password provider (the same user pool the admin console uses), so
 * the username is mapped to a synthetic "username@meedo.local" address —
 * an implementation detail the reader never needs to see. Accounts are
 * created by an admin from the water-billing-admin Mobile Sync page; there's
 * no self-service sign-up here.
 *
 * Because both apps share one user pool, a successful sign-in alone isn't
 * enough — [login] also checks the account's `users/{uid}.role` in Firestore
 * and signs back out if it isn't "field_reader", so an admin-only account
 * can't be used to read meters in the field.
 *
 * Firebase Auth persists the session locally, so once signed in the reader
 * stays signed in across app restarts until they explicitly log out.
 */
@Singleton
class AuthRepository @Inject constructor() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser
    val currentUserId: String? get() = auth.currentUser?.uid

    /** Emits immediately with the current session, then again on every sign-in/sign-out. */
    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun login(username: String, password: String) {
        val normalizedUsername = username.trim().lowercase()
        val syntheticEmail = "$normalizedUsername@$USERNAME_DOMAIN"

        val result = auth.signInWithEmailAndPassword(syntheticEmail, password).await()
        val uid = result.user?.uid ?: throw NotAuthorizedException()

        val roleDoc = firestore.collection("users").document(uid).get().await()
        val role = roleDoc.getString("role")
        // A disabled account is refused here as well as by the Firestore rules.
        // An admin revoking a lost phone sets this flag; the rules stop the
        // device reaching any data, and this stops it getting past the login
        // screen at all rather than signing in to a broken app.
        val disabled = roleDoc.getBoolean("disabled") ?: false
        if (role != "field_reader" || disabled) {
            auth.signOut()
            throw NotAuthorizedException()
        }
    }

    fun logout() {
        cachedProfile = null
        auth.signOut()
    }

    /**
     * Saves the reader's own name and phone number — the only fields on their
     * account the security rules let them change. Role, username and whether
     * the account is enabled stay the office's to set.
     */
    suspend fun updateOwnProfile(firstName: String, lastName: String, phoneNumber: String) {
        val uid = currentUserId ?: throw AccountChangeException("You're signed out. Sign in again first.")
        val profile = ReaderProfile(firstName.trim(), lastName.trim(), phoneNumber.trim())
        if (profile.firstName.isEmpty() || profile.lastName.isEmpty()) {
            throw AccountChangeException("First and last name are both needed.")
        }
        try {
            firestore.collection("users").document(uid).update(
                mapOf(
                    "firstName" to profile.firstName,
                    "lastName" to profile.lastName,
                    "phoneNumber" to profile.phoneNumber,
                )
            ).await()
        } catch (e: Exception) {
            throw AccountChangeException(plainMessage(e, "Your details couldn't be saved. Try again."))
        }
        cachedProfile = profile
        recordAccountChange("Updated their own profile details from the field app.")
    }

    /**
     * Changes the reader's own password. Asks for the current one first:
     * being signed in isn't enough, since a phone left unlocked on a route is
     * exactly how an account gets taken.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val user = auth.currentUser ?: throw AccountChangeException("You're signed out. Sign in again first.")
        val email = user.email ?: throw AccountChangeException("This account can't change its password here.")
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            throw AccountChangeException("Use at least $MIN_PASSWORD_LENGTH characters.")
        }
        try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, currentPassword)).await()
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw AccountChangeException("That isn't your current password.")
        } catch (e: Exception) {
            throw AccountChangeException(plainMessage(e, "Your password couldn't be checked. Try again."))
        }
        try {
            user.updatePassword(newPassword).await()
        } catch (e: Exception) {
            throw AccountChangeException(plainMessage(e, "Your password couldn't be changed. Try again."))
        }
        recordAccountChange("Changed their own password from the field app.")
    }

    /** Appends to the office's audit trail. Best effort: it must never block the change itself. */
    private fun recordAccountChange(description: String) {
        val email = auth.currentUser?.email ?: return
        firestore.collection("auditLogs").add(
            mapOf(
                "actionType" to "Account Update",
                "description" to description,
                "user" to email,
                "timestamp" to FieldValue.serverTimestamp(),
            )
        )
    }

    private fun plainMessage(e: Exception, fallback: String): String = when (e) {
        is FirebaseNetworkException -> "No connection. This needs a signal — try again when you have one."
        else -> fallback
    }

    /**
     * Fetches the signed-in reader's name/phone straight from Firestore
     * (rather than caching it at login time) so it always reflects whatever
     * an admin most recently set on `users/{uid}` — an admin can edit a
     * reader's details mid-session and the very next reading picks it up.
     */
    suspend fun getCurrentReaderProfile(): ReaderProfile? {
        val uid = currentUserId ?: return null
        val ref = firestore.collection("users").document(uid)
        return try {
            ref.get().await().toReaderProfile().also { cachedProfile = it }
        } catch (e: Exception) {
            // Offline with nothing fresh to fetch, get() throws. Fall back to
            // Firestore's local cache, then to the last profile this session saw,
            // rather than failing whatever needed the name.
            try {
                ref.get(Source.CACHE).await().toReaderProfile().also { cachedProfile = it }
            } catch (_: Exception) {
                cachedProfile
            }
        }
    }

    /**
     * The last profile successfully read this session, without touching the
     * network — for callers that must not wait on a lookup, like saving a
     * reading in a place with no signal.
     */
    val lastKnownProfile: ReaderProfile? get() = cachedProfile

    @Volatile
    private var cachedProfile: ReaderProfile? = null

    private fun DocumentSnapshot.toReaderProfile() = ReaderProfile(
        firstName = getString("firstName") ?: "",
        lastName = getString("lastName") ?: "",
        phoneNumber = getString("phoneNumber") ?: ""
    )
}
