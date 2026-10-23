package com.example.myapp.gallery

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.walletOrderDataStore by preferencesDataStore("wallet_order")
private val ORDER_KEY = stringPreferencesKey("item_ids")

/**
 * The order the Wallet pictures are shown in, set with the viewer's arrows. MediaStore has no order of
 * its own, so it is kept here as the ids in a row; a picture added since goes after the known ones.
 */
object WalletOrder {

    suspend fun apply(context: Context, items: List<MediaItem>): List<MediaItem> {
        val saved = context.walletOrderDataStore.data.first()[ORDER_KEY].orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }
        val rank = saved.withIndex().associate { (i, id) -> id to i }
        return items.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    suspend fun save(context: Context, items: List<MediaItem>) {
        context.walletOrderDataStore.edit { it[ORDER_KEY] = items.joinToString(",") { item -> item.id.toString() } }
    }
}
