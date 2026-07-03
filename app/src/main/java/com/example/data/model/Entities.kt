package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val username: String,
    val password: String,
    val fullName: String,
    val role: String, // "ADMIN", "PENGURUS", "ANGGOTA"
    val address: String = "",
    val phone: String = "",
    val nik: String = "", // Nomor Induk Kependudukan
    val wasteBalance: Double = 0.0, // Rupiah balance
    val weightBalance: Double = 0.0, // Total kg balance
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val userName: String,
    val type: String, // "SETOR" (Deposit), "TARIK" (Withdrawal)
    val wasteType: String? = null, // e.g., "Plastik", "Kertas", "Logam", "Kaca", "Lainnya"
    val weight: Double? = null, // in kg
    val amount: Double, // total value before or after kas cut
    val kasAmount: Double = 0.0, // amount cut for bank kas (e.g. 20%)
    val status: String, // "PENDING", "APPROVED", "REJECTED"
    val handledBy: String? = null, // Who approved/rejected
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null
)

@Entity(tableName = "waste_categories")
data class WasteCategory(
    @PrimaryKey val name: String,
    val pricePerKg: Double
)

@Entity(tableName = "settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "notifications")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int, // 0 for system/all, or specific userId
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)
