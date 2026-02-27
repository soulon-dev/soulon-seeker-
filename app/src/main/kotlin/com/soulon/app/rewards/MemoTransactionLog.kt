package com.soulon.app.rewards

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memo_transaction_logs")
data class MemoTransactionLog(
    @PrimaryKey
    val remoteId: Long,
    val walletAddress: String,
    val transactionType: String,
    val amount: Int,
    val description: String,
    val createdAt: Long,
    val metadataJson: String? = null
) {
    fun isIncome(): Boolean = amount > 0
    fun getSignedAmount(): String = if (amount >= 0) "+$amount" else "$amount"
}
