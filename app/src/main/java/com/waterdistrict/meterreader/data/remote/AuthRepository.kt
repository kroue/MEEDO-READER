package com.waterdistrict.meterreader.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
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
        auth.signOut()
    }

    /**
     * Fetches the signed-in reader's name/phone straight from Firestore
     * (rather than caching it at login time) so it always reflects whatever
     * an admin most recently set on `users/{uid}` — an admin can edit a
     * reader's details mid-session and the very next reading picks it up.
     */
    suspend fun getCurrentReaderProfile(): ReaderProfile? {
        val uid = currentUserId ?: return null
        val doc = firestore.collection("users").document(uid).get().await()
        return ReaderProfile(
            firstName = doc.getString("firstName") ?: "",
            lastName = doc.getString("lastName") ?: "",
            phoneNumber = doc.getString("phoneNumber") ?: ""
        )
    }
}
