package com.example.data.repository

import com.example.data.dao.AppSettingDao
import com.example.data.dao.NotificationLogDao
import com.example.data.dao.TransactionDao
import com.example.data.dao.UserDao
import com.example.data.dao.WasteCategoryDao
import com.example.data.model.AppSetting
import com.example.data.model.NotificationLog
import com.example.data.model.Transaction
import com.example.data.model.User
import com.example.data.model.WasteCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppRepository(
    private val userDao: UserDao,
    private val transactionDao: TransactionDao,
    private val appSettingDao: AppSettingDao,
    private val notificationLogDao: NotificationLogDao,
    private val wasteCategoryDao: WasteCategoryDao
) {
    // Users
    fun getUserById(id: Int): Flow<User?> = userDao.getUserById(id)
    suspend fun getUserByIdSuspend(id: Int): User? = userDao.getUserByIdSuspend(id)
    suspend fun getUserByUsername(username: String): User? = userDao.getUserByUsername(username)
    fun getAllUsers(): Flow<List<User>> = userDao.getAllUsers()
    fun getUsersByRole(role: String): Flow<List<User>> = userDao.getUsersByRole(role)
    suspend fun insertUser(user: User): Long = userDao.insertUser(user)
    suspend fun updateUser(user: User) = userDao.updateUser(user)
    suspend fun deleteUser(user: User) = userDao.deleteUser(user)

    // Transactions
    fun getAllTransactions(): Flow<List<Transaction>> = transactionDao.getAllTransactions()
    fun getTransactionsByUserId(userId: Int): Flow<List<Transaction>> = transactionDao.getTransactionsByUserId(userId)
    fun getPendingTransactions(): Flow<List<Transaction>> = transactionDao.getPendingTransactions()
    suspend fun getTransactionById(id: Int): Transaction? = transactionDao.getTransactionById(id)
    suspend fun insertTransaction(transaction: Transaction): Long = transactionDao.insertTransaction(transaction)
    suspend fun updateTransaction(transaction: Transaction) = transactionDao.updateTransaction(transaction)
    suspend fun deleteTransaction(transaction: Transaction) = transactionDao.deleteTransaction(transaction)

    // AppSettings
    fun getSettingFlow(key: String): Flow<AppSetting?> = appSettingDao.getSettingFlow(key)
    suspend fun getSettingValue(key: String): String? = appSettingDao.getSettingValue(key)
    suspend fun saveSetting(key: String, value: String) {
        appSettingDao.insertOrUpdateSetting(AppSetting(key, value))
    }

    // Notifications
    fun getNotificationsForUser(userId: Int): Flow<List<NotificationLog>> = notificationLogDao.getNotificationsForUser(userId)
    suspend fun insertNotification(userId: Int, title: String, message: String) {
        notificationLogDao.insertNotification(NotificationLog(userId = userId, title = title, message = message))
    }
    suspend fun markAllAsRead(userId: Int) = notificationLogDao.markAllAsRead(userId)

    // Waste Categories
    fun getAllCategoriesFlow(): Flow<List<WasteCategory>> = wasteCategoryDao.getAllCategoriesFlow()
    suspend fun getAllCategories(): List<WasteCategory> = wasteCategoryDao.getAllCategories()
    suspend fun insertCategory(category: WasteCategory) = wasteCategoryDao.insertCategory(category)
    suspend fun deleteCategory(category: WasteCategory) = wasteCategoryDao.deleteCategory(category)

    // Prepopulate DB if empty
    suspend fun prepopulateIfNeeded(bankName: String = "Bank Sampah Sejahtera") {
        // Clean up sample data if found (Aminah, Budi Santoso) to satisfy "bersihkan nama aminah, budi santoso"
        val sampleNasabah = userDao.getUserByUsername("nasabah")
        if (sampleNasabah != null && sampleNasabah.fullName == "Siti Aminah") {
            userDao.deleteUser(sampleNasabah)
            transactionDao.deleteTransactionsByUserId(sampleNasabah.id)
            notificationLogDao.deleteNotificationsByUserId(sampleNasabah.id)
        }
        val samplePengurus = userDao.getUserByUsername("pengurus")
        if (samplePengurus != null && samplePengurus.fullName.contains("Budi Santoso")) {
            userDao.deleteUser(samplePengurus)
            transactionDao.deleteTransactionsByUserId(samplePengurus.id)
            notificationLogDao.deleteNotificationsByUserId(samplePengurus.id)
        }

        val existingAdmin = userDao.getUserByUsername("banksampah")
        if (existingAdmin == null) {
            // Prepopulate Admin as banksampah / admin123
            userDao.insertUser(User(
                username = "banksampah",
                password = "admin123",
                fullName = "Administrator Utama",
                role = "ADMIN",
                address = "RT 06/RW 02, Jakarta",
                phone = "081234567890",
                nik = "3171234567890001"
            ))

            // Prepopulate Admin with username 'admin' and password 'admin' for quick testing
            userDao.insertUser(User(
                username = "admin",
                password = "admin",
                fullName = "Admin RT06",
                role = "ADMIN",
                address = "RT 06/RW 02, Jakarta",
                phone = "081234567890",
                nik = "3171234567890004"
            ))

            // Prepopulate default settings
            appSettingDao.insertOrUpdateSetting(AppSetting("app_name", bankName))
            appSettingDao.insertOrUpdateSetting(AppSetting("kas_percentage", "20")) // Default kas cut percentage is 20%

            // Prepopulate waste categories
            val defaultCategories = mapOf(
                "Plastik" to 2000.0,
                "Kertas" to 1500.0,
                "Logam" to 4000.0,
                "Kaca" to 1000.0,
                "Organik" to 500.0,
                "Lainnya" to 1200.0
            )
            for ((name, price) in defaultCategories) {
                wasteCategoryDao.insertCategory(WasteCategory(name, price))
            }
        }
    }

    // Export Excel/CSV Generator
    fun generateCsvString(transactions: List<Transaction>): String {
        val csv = StringBuilder()
        csv.append("ID Transaksi,Nama Nasabah,Tipe,Jenis Sampah,Berat (kg),Nominal Total (Rp),Potongan Kas Bank (Rp),Status,Diproses Oleh,Waktu,Catatan\n")
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        for (tx in transactions) {
            val dateStr = sdf.format(Date(tx.timestamp))
            val wasteTypeStr = tx.wasteType ?: "-"
            val weightStr = tx.weight?.toString() ?: "-"
            val notesStr = tx.notes?.replace(",", ";")?.replace("\n", " ") ?: "-"
            val handledByStr = tx.handledBy ?: "-"
            
            csv.append("${tx.id},\"${tx.userName}\",${tx.type},\"$wasteTypeStr\",$weightStr,${tx.amount},${tx.kasAmount},${tx.status},\"$handledByStr\",\"$dateStr\",\"$notesStr\"\n")
        }
        return csv.toString()
    }

    // Generate high-fidelity Excel SpreadsheetML document (.xls) containing structured data and dynamic styles
    fun generateExcelXmlString(transactions: List<Transaction>): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\"?>\n")
        xml.append("<?mso-application progid=\"Excel.Sheet\"?>\n")
        xml.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"\n")
        xml.append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"\n")
        xml.append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"\n")
        xml.append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"\n")
        xml.append(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">\n")
        
        xml.append(" <Styles>\n")
        xml.append("  <Style ss:ID=\"Default\" ss:Name=\"Normal\">\n")
        xml.append("   <Alignment ss:Vertical=\"Bottom\"/>\n")
        xml.append("   <Borders/>\n")
        xml.append("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"11\" ss:Color=\"#000000\"/>\n")
        xml.append("   <Interior/>\n")
        xml.append("   <NumberFormat/>\n")
        xml.append("   <Protection/>\n")
        xml.append("  </Style>\n")
        xml.append("  <Style ss:ID=\"HeaderStyle\">\n")
        xml.append("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"11\" ss:Color=\"#FFFFFF\" ss:Bold=\"1\"/>\n")
        xml.append("   <Interior ss:Color=\"#05120D\" ss:Pattern=\"Solid\"/>\n") // Bank Sampah Theme Color!
        xml.append("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\"/>\n")
        xml.append("  </Style>\n")
        xml.append("  <Style ss:ID=\"TitleStyle\">\n")
        xml.append("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"16\" ss:Color=\"#05120D\" ss:Bold=\"1\"/>\n")
        xml.append("   <Alignment ss:Horizontal=\"Left\" ss:Vertical=\"Center\"/>\n")
        xml.append("  </Style>\n")
        xml.append(" </Styles>\n")
        
        xml.append(" <Worksheet ss:Name=\"Laporan Transaksi\">\n")
        xml.append("  <Table>\n")
        
        // Define Column widths
        xml.append("   <Column ss:Width=\"80\"/>\n") // ID
        xml.append("   <Column ss:Width=\"120\"/>\n") // Nama
        xml.append("   <Column ss:Width=\"80\"/>\n") // Tipe
        xml.append("   <Column ss:Width=\"100\"/>\n") // Jenis Sampah
        xml.append("   <Column ss:Width=\"70\"/>\n") // Berat
        xml.append("   <Column ss:Width=\"110\"/>\n") // Nominal Total
        xml.append("   <Column ss:Width=\"120\"/>\n") // Potongan Kas
        xml.append("   <Column ss:Width=\"80\"/>\n") // Status
        xml.append("   <Column ss:Width=\"120\"/>\n") // Diproses Oleh
        xml.append("   <Column ss:Width=\"130\"/>\n") // Waktu
        xml.append("   <Column ss:Width=\"150\"/>\n") // Catatan
        
        // Title Row
        xml.append("   <Row ss:Height=\"30\">\n")
        xml.append("    <Cell ss:MergeAcross=\"10\" ss:StyleID=\"TitleStyle\"><Data ss:Type=\"String\">LAPORAN TRANSAKSI BANK SAMPAH RT06/RW02</Data></Cell>\n")
        xml.append("   </Row>\n")
        
        // Empty space Row
        xml.append("   <Row ss:Height=\"15\"/>\n")
        
        // Header Row
        xml.append("   <Row ss:Height=\"22\">\n")
        val headers = listOf(
            "ID Transaksi", "Nama Nasabah", "Tipe", "Jenis Sampah", 
            "Berat (kg)", "Nominal Total (Rp)", "Potongan Kas Bank (Rp)", 
            "Status", "Diproses Oleh", "Waktu", "Catatan"
        )
        for (header in headers) {
            xml.append("    <Cell ss:StyleID=\"HeaderStyle\"><Data ss:Type=\"String\">$header</Data></Cell>\n")
        }
        xml.append("   </Row>\n")
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        // Data Rows
        for (tx in transactions) {
            val dateStr = sdf.format(Date(tx.timestamp))
            val wasteTypeStr = tx.wasteType ?: "-"
            val notesStr = tx.notes ?: "-"
            val handledByStr = tx.handledBy ?: "-"
            val weightVal = tx.weight ?: 0.0
            
            xml.append("   <Row ss:Height=\"18\">\n")
            xml.append("    <Cell><Data ss:Type=\"Number\">${tx.id}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">${escapeXml(tx.userName)}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">${tx.type}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">${escapeXml(wasteTypeStr)}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"Number\">$weightVal</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"Number\">${tx.amount}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"Number\">${tx.kasAmount}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">${tx.status}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">${escapeXml(handledByStr)}</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">$dateStr</Data></Cell>\n")
            xml.append("    <Cell><Data ss:Type=\"String\">${escapeXml(notesStr)}</Data></Cell>\n")
            xml.append("   </Row>\n")
        }
        
        xml.append("  </Table>\n")
        xml.append(" </Worksheet>\n")
        xml.append("</Workbook>\n")
        return xml.toString()
    }
    
    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
