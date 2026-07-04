package com.example.data.dao

import androidx.room.*
import com.example.data.model.User
import com.example.data.model.Transaction
import com.example.data.model.AppSetting
import com.example.data.model.NotificationLog
import com.example.data.model.WasteCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface WasteCategoryDao {
    @Query("SELECT * FROM waste_categories ORDER BY name ASC")
    fun getAllCategoriesFlow(): Flow<List<WasteCategory>>

    @Query("SELECT * FROM waste_categories ORDER BY name ASC")
    suspend fun getAllCategories(): List<WasteCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: WasteCategory)

    @Delete
    suspend fun deleteCategory(category: WasteCategory)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    fun getUserById(id: Int): Flow<User?>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserByIdSuspend(id: Int): User?

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): User?

    @Query("SELECT * FROM users ORDER BY role ASC, fullName ASC")
    fun getAllUsers(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE role = :role ORDER BY fullName ASC")
    fun getUsersByRole(role: String): Flow<List<User>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User): Long

    @Update
    suspend fun updateUser(user: User)

    @Delete
    suspend fun deleteUser(user: User)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY timestamp DESC")
    fun getTransactionsByUserId(userId: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE status = 'PENDING' ORDER BY timestamp DESC")
    fun getPendingTransactions(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Int): Transaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE userId = :userId")
    suspend fun deleteTransactionsByUserId(userId: Int)
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    fun getSettingFlow(key: String): Flow<AppSetting?>

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getSettingValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSetting(setting: AppSetting)
}

@Dao
interface NotificationLogDao {
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationLog>>

    @Query("SELECT * FROM notifications WHERE userId = :userId OR userId = 0 ORDER BY timestamp DESC")
    fun getNotificationsForUser(userId: Int): Flow<List<NotificationLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationLog)

    @Query("UPDATE notifications SET isRead = 1 WHERE userId = :userId")
    suspend fun markAllAsRead(userId: Int)

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Int)

    @Query("UPDATE notifications SET isRead = 1 WHERE userId = :userId OR userId = 0")
    suspend fun markAllAsReadForUserAndBroadcast(userId: Int)

    @Query("DELETE FROM notifications WHERE userId = :userId")
    suspend fun deleteNotificationsByUserId(userId: Int)
}
