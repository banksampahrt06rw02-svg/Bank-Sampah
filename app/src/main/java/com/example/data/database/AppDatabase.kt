package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.UserDao
import com.example.data.dao.TransactionDao
import com.example.data.dao.AppSettingDao
import com.example.data.dao.NotificationLogDao
import com.example.data.dao.WasteCategoryDao
import com.example.data.model.User
import com.example.data.model.Transaction
import com.example.data.model.AppSetting
import com.example.data.model.NotificationLog
import com.example.data.model.WasteCategory

@Database(
    entities = [
        User::class,
        Transaction::class,
        AppSetting::class,
        NotificationLog::class,
        WasteCategory::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun transactionDao(): TransactionDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun wasteCategoryDao(): WasteCategoryDao

    companion object {
        private val INSTANCES = java.util.concurrent.ConcurrentHashMap<String, AppDatabase>()

        fun getDatabase(context: Context, bankName: String = BankDatabaseManager.getActiveBank(context)): AppDatabase {
            val dbName = BankDatabaseManager.getDbNameForBank(bankName)
            return INSTANCES.getOrPut(dbName) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbName
                )
                .fallbackToDestructiveMigration()
                .build()
            }
        }
    }
}

object BankDatabaseManager {
    private const val PREFS_NAME = "bank_sampah_prefs"
    private const val KEY_ACTIVE_BANK = "active_bank_name"
    private const val KEY_ALL_BANKS = "all_banks_list"

    fun getActiveBank(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_BANK, "Bank Sampah Sejahtera") ?: "Bank Sampah Sejahtera"
    }

    fun setActiveBank(context: Context, bankName: String) {
        val trimmed = bankName.trim()
        if (trimmed.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_BANK, trimmed).apply()
        
        // Add to list of all banks
        val all = getAllBanks(context).toMutableSet()
        all.add(trimmed)
        prefs.edit().putStringSet(KEY_ALL_BANKS, all).apply()
    }

    fun getAllBanks(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ALL_BANKS, null) ?: setOf("Bank Sampah Sejahtera")
        return set.toList().sorted()
    }
    
    fun getDbNameForBank(bankName: String): String {
        val normalized = bankName.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
        return if (normalized.isEmpty()) "bank_sampah_default_db" else "bank_sampah_${normalized}_db"
    }
}
