package com.tingxia.audio.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JWT 持久化：基于 DataStore(preferences) 存储 access / refresh token + userId。
 *
 * 单测可通过覆写 [getAccessToken] / [getRefreshToken] / [getUserId] 注入假数据，
 * 因此声明为 `open`。
 */
@Singleton
open class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

    open suspend fun getAccessToken(): String? =
        context.dataStore.data.map { it[ACCESS_TOKEN_KEY] }.first()

    open suspend fun getRefreshToken(): String? =
        context.dataStore.data.map { it[REFRESH_TOKEN_KEY] }.first()

    open suspend fun getUserId(): Long? =
        context.dataStore.data.map { it[USER_ID_KEY] }.first()

    open suspend fun saveTokens(access: String, refresh: String, userId: Long) {
        context.dataStore.edit {
            it[ACCESS_TOKEN_KEY] = access
            it[REFRESH_TOKEN_KEY] = refresh
            it[USER_ID_KEY] = userId
        }
    }

    open suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        val USER_ID_KEY = longPreferencesKey("user_id")
    }
}
