package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.model.NotificationLog
import com.example.data.model.Transaction
import com.example.data.model.User
import com.example.data.model.WasteCategory
import com.example.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object WastePricing {
    val categories = mapOf(
        "Plastik" to 2000.0,  // Rp 2.000 / kg
        "Kertas" to 1500.0,   // Rp 1.500 / kg
        "Logam" to 4000.0,    // Rp 4.000 / kg
        "Kaca" to 1000.0,     // Rp 1.000 / kg
        "Organik" to 500.0,    // Rp 500 / kg
        "Lainnya" to 1200.0   // Rp 1.200 / kg
    )

    fun calculateValue(category: String, weight: Double): Double {
        val pricePerKg = categories[category] ?: 1000.0
        return pricePerKg * weight
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _activeBank = MutableStateFlow(com.example.data.database.BankDatabaseManager.getActiveBank(application))
    val activeBank: StateFlow<String> = _activeBank.asStateFlow()

    private val _allBanksList = MutableStateFlow<List<String>>(emptyList())
    val allBanksList: StateFlow<List<String>> = _allBanksList.asStateFlow()

    @Volatile
    private var _repository: AppRepository

    val repository: AppRepository
        get() = _repository

    init {
        val initialBank = com.example.data.database.BankDatabaseManager.getActiveBank(application)
        val database = AppDatabase.getDatabase(application, initialBank)
        _repository = AppRepository(
            userDao = database.userDao(),
            transactionDao = database.transactionDao(),
            appSettingDao = database.appSettingDao(),
            notificationLogDao = database.notificationLogDao(),
            wasteCategoryDao = database.wasteCategoryDao()
        )
        refreshBanksList()
        viewModelScope.launch {
            _repository.prepopulateIfNeeded(initialBank)
        }
    }

    fun refreshBanksList() {
        _allBanksList.value = com.example.data.database.BankDatabaseManager.getAllBanks(getApplication())
    }

    private fun updateActiveRepository(bankName: String) {
        val db = AppDatabase.getDatabase(getApplication(), bankName)
        _repository = AppRepository(
            userDao = db.userDao(),
            transactionDao = db.transactionDao(),
            appSettingDao = db.appSettingDao(),
            notificationLogDao = db.notificationLogDao(),
            wasteCategoryDao = db.wasteCategoryDao()
        )
        viewModelScope.launch {
            _repository.prepopulateIfNeeded(bankName)
            refreshBanksList()
        }
    }

    fun switchBank(bankName: String) {
        val trimmed = bankName.trim()
        if (trimmed.isEmpty()) return
        val context = getApplication<Application>()
        com.example.data.database.BankDatabaseManager.setActiveBank(context, trimmed)
        _activeBank.value = trimmed
        updateActiveRepository(trimmed)
        
        val current = _currentUser.value
        if (current != null && current.username != "banksampah") {
            // Log out non-master-admin users when bank changes
            _currentUser.value = null
        } else if (current != null && current.username == "banksampah") {
            // Re-bind master admin session to the new database's "banksampah" user object
            viewModelScope.launch {
                val db = AppDatabase.getDatabase(context, trimmed)
                val masterUser = db.userDao().getUserByUsername("banksampah")
                if (masterUser != null) {
                    _currentUser.value = masterUser
                    observeCurrentUserUpdates(masterUser.id)
                }
            }
        }
    }

    fun deleteBank(bankName: String, onCompleted: (Boolean, String) -> Unit) {
        val trimmed = bankName.trim()
        val defaultBank = "Bank Sampah Sejahtera"
        if (trimmed == defaultBank) {
            onCompleted(false, "Bank utama tidak dapat dihapus!")
            return
        }
        val context = getApplication<Application>()
        val success = com.example.data.database.BankDatabaseManager.deleteBank(context, trimmed)
        if (success) {
            refreshBanksList()
            val currentActive = com.example.data.database.BankDatabaseManager.getActiveBank(context)
            if (_activeBank.value != currentActive) {
                _activeBank.value = currentActive
                updateActiveRepository(currentActive)
            }
            onCompleted(true, "Bank $trimmed berhasil dihapus.")
        } else {
            onCompleted(false, "Gagal menghapus bank $trimmed.")
        }
    }

    // App Name Dynamic Config
    val appName: StateFlow<String> = _activeBank
        .flatMapLatest { bank ->
            val db = AppDatabase.getDatabase(application, bank)
            db.appSettingDao().getSettingFlow("app_name").map { it?.value ?: bank }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Bank Sampah")

    // Theme (Dark Mode) State
    private val _isDarkMode = MutableStateFlow(true) // Default to dark mode as requested for eye comfort
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    // Active Logged-in User Session
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // Auth Form State
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _registerSuccess = MutableStateFlow(false)
    val registerSuccess: StateFlow<Boolean> = _registerSuccess.asStateFlow()

    // Real-time Lists (Admins/Pengurus see all, Anggota sees their own)
    val usersList: StateFlow<List<User>> = _activeBank
        .flatMapLatest { bank ->
            val db = AppDatabase.getDatabase(application, bank)
            db.userDao().getAllUsers()
        }
        .map { list -> list.filter { it.role != "ADMIN" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTransactions: StateFlow<List<Transaction>> = _activeBank
        .flatMapLatest { bank ->
            val db = AppDatabase.getDatabase(application, bank)
            db.transactionDao().getAllTransactions()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingTransactions: StateFlow<List<Transaction>> = _activeBank
        .flatMapLatest { bank ->
            val db = AppDatabase.getDatabase(application, bank)
            db.transactionDao().getPendingTransactions()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dynamic waste categories from DB
    val wasteCategories: StateFlow<List<WasteCategory>> = _activeBank
        .flatMapLatest { bank ->
            val db = AppDatabase.getDatabase(application, bank)
            db.wasteCategoryDao().getAllCategoriesFlow()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Customizable kas percentage from settings
    val kasPercentage: StateFlow<Int> = _activeBank
        .flatMapLatest { bank ->
            val db = AppDatabase.getDatabase(application, bank)
            db.appSettingDao().getSettingFlow("kas_percentage").map { it?.value?.toIntOrNull() ?: 20 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    // Active User specific lists
    val myTransactions: StateFlow<List<Transaction>> = combine(_activeBank, _currentUser) { bank, user ->
        bank to user
    }.flatMapLatest { (bank, user) ->
        if (user != null) {
            val db = AppDatabase.getDatabase(application, bank)
            if (user.role == "ADMIN" || user.role == "PENGURUS") {
                db.transactionDao().getAllTransactions()
            } else {
                db.transactionDao().getTransactionsByUserId(user.id)
            }
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val myNotifications: StateFlow<List<NotificationLog>> = combine(_activeBank, _currentUser) { bank, user ->
        bank to user
    }.flatMapLatest { (bank, user) ->
        if (user != null) {
            val db = AppDatabase.getDatabase(application, bank)
            db.notificationLogDao().getNotificationsForUser(user.id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markNotificationAsRead(id: Int) {
        viewModelScope.launch {
            val bank = _activeBank.value
            val db = AppDatabase.getDatabase(getApplication<Application>(), bank)
            db.notificationLogDao().markAsRead(id)
        }
    }

    fun markAllNotificationsAsRead() {
        viewModelScope.launch {
            val user = _currentUser.value ?: return@launch
            val bank = _activeBank.value
            val db = AppDatabase.getDatabase(getApplication<Application>(), bank)
            db.notificationLogDao().markAllAsReadForUserAndBroadcast(user.id)
        }
    }

    // Real-time Dashboard Statistics
    val statsTotalWeight: StateFlow<Double> = allTransactions
        .map { list -> list.filter { it.status == "APPROVED" && it.type == "SETOR" }.sumOf { it.weight ?: 0.0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val statsTotalPayout: StateFlow<Double> = allTransactions
        .map { list -> list.filter { it.status == "APPROVED" && it.type == "TARIK" }.sumOf { it.amount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val statsTotalMembers: StateFlow<Int> = usersList
        .map { list -> list.filter { it.role == "ANGGOTA" }.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Accumulated Bank Kas Revenue
    val statsTotalKas: StateFlow<Double> = allTransactions
        .map { list -> list.filter { it.status == "APPROVED" && it.type == "SETOR" }.sumOf { it.kasAmount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Login Action
    fun login(username: String, password: String, onCompleted: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _loginError.value = null
            val user = repository.getUserByUsername(username.trim())
            if (user != null && user.password == password) {
                _currentUser.value = user
                // Refresh our user data reactively
                observeCurrentUserUpdates(user.id)
                onCompleted(true)
            } else {
                _loginError.value = "Username atau password salah!"
                onCompleted(false)
            }
        }
    }

    private var userUpdateJob: kotlinx.coroutines.Job? = null
    private fun observeCurrentUserUpdates(userId: Int) {
        userUpdateJob?.cancel()
        userUpdateJob = viewModelScope.launch {
            repository.getUserById(userId).collect { updatedUser ->
                if (updatedUser != null) {
                    _currentUser.value = updatedUser
                }
            }
        }
    }

    // Register User (called by Admin or SignUp)
    fun registerUser(
        username: String,
        password: String,
        fullName: String,
        role: String,
        address: String,
        phone: String,
        nik: String,
        onCompleted: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val trimmedUsername = username.trim()
            if (trimmedUsername.isEmpty() || password.isEmpty() || fullName.isEmpty() || nik.trim().isEmpty()) {
                onCompleted(false, "Semua data wajib diisi (termasuk NIK)!")
                return@launch
            }
            val existing = repository.getUserByUsername(trimmedUsername)
            if (existing != null) {
                onCompleted(false, "Username '$trimmedUsername' sudah digunakan!")
                return@launch
            }

            val newUser = User(
                username = trimmedUsername,
                password = password,
                fullName = fullName,
                role = role,
                address = address,
                phone = phone,
                nik = nik.trim()
            )
            repository.insertUser(newUser)
            
            // Log registration activity
            val admin = currentUser.value
            if (admin != null) {
                repository.insertNotification(
                    userId = 0, // Broadcast to system log
                    title = "Anggota Baru Terdaftar",
                    message = "Admin ${admin.fullName} mendaftarkan $fullName sebagai $role dengan NIK $nik."
                )
            } else {
                repository.insertNotification(
                    userId = 0, // Broadcast to system log
                    title = "Pendaftaran Mandiri Nasabah",
                    message = "Nasabah baru $fullName telah mendaftar mandiri via aplikasi dengan NIK $nik."
                )
            }
            onCompleted(true, "Registrasi $role berhasil!")
        }
    }

    // Delete User (called by Admin)
    fun deleteUser(user: User, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (user.id == currentUser.value?.id) {
                onCompleted(false, "Anda tidak dapat menghapus akun Anda sendiri!")
                return@launch
            }
            repository.deleteUser(user)
            
            // Log Admin activity
            currentUser.value?.let { admin ->
                repository.insertNotification(
                    userId = 0,
                    title = "Anggota Dihapus",
                    message = "Admin ${admin.fullName} menghapus pengguna ${user.fullName} (${user.role})."
                )
            }
            onCompleted(true, "Pengguna ${user.fullName} berhasil dihapus.")
        }
    }

    // Adjust User Role / Access Level
    fun updateUserRole(user: User, newRole: String) {
        viewModelScope.launch {
            val updated = user.copy(role = newRole)
            repository.updateUser(updated)
            currentUser.value?.let { admin ->
                repository.insertNotification(
                    userId = user.id,
                    title = "Hak Akses Diperbarui",
                    message = "Hak akses Anda telah diubah menjadi $newRole oleh ${admin.fullName}."
                )
            }
        }
    }

    // Save/Edit Waste Category (Admin action)
    fun saveCategory(name: String, pricePerKg: Double, onCompleted: (Boolean, String) -> Unit = {_, _ ->}) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || pricePerKg <= 0.0) {
                onCompleted(false, "Nama kategori dan harga harus valid!")
                return@launch
            }
            repository.insertCategory(WasteCategory(trimmed, pricePerKg))
            onCompleted(true, "Kategori sampah '$trimmed' berhasil disimpan!")
        }
    }

    // Delete Waste Category (Admin action)
    fun deleteCategory(category: WasteCategory, onCompleted: (Boolean, String) -> Unit = {_, _ ->}) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            onCompleted(true, "Kategori sampah '${category.name}' berhasil dihapus!")
        }
    }

    // Update customizable kas percentage (Admin setting)
    fun updateKasPercentage(percentage: Int, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (percentage < 0 || percentage > 100) {
                onCompleted(false)
                return@launch
            }
            repository.saveSetting("kas_percentage", percentage.toString())
            onCompleted(true)
        }
    }

    // Update Admin login credentials
    fun updateAdminCredentials(newUsername: String, newPassword: String, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val trimmedUser = newUsername.trim()
            if (trimmedUser.isEmpty() || newPassword.trim().isEmpty()) {
                onCompleted(false, "Username dan password baru tidak boleh kosong!")
                return@launch
            }
            
            val admin = currentUser.value
            if (admin == null || admin.role != "ADMIN") {
                onCompleted(false, "Hanya Administrator yang dapat mengubah data login admin!")
                return@launch
            }
            
            val existing = repository.getUserByUsername(trimmedUser)
            if (existing != null && existing.id != admin.id) {
                onCompleted(false, "Username '$trimmedUser' sudah digunakan!")
                return@launch
            }
            
            val updatedAdmin = admin.copy(username = trimmedUser, password = newPassword)
            repository.updateUser(updatedAdmin)
            _currentUser.value = updatedAdmin
            onCompleted(true, "Username dan password Admin berhasil diperbarui!")
        }
    }

    // Deposit Waste (Setoran)
    fun depositWaste(
        nasabahId: Int,
        category: String,
        weight: Double,
        notes: String,
        onCompleted: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val nasabah = repository.getUserByIdSuspend(nasabahId)
            if (nasabah == null) {
                onCompleted(false, "Nasabah tidak ditemukan!")
                return@launch
            }

            // Look up price dynamically
            val categories = repository.getAllCategories()
            val matchedCategory = categories.find { it.name.lowercase() == category.lowercase() }
            val pricePerKg = matchedCategory?.pricePerKg ?: 1000.0
            val totalAmount = pricePerKg * weight

            // Calculate kas bank cut
            val currentKasPercentage = kasPercentage.value
            val kasCutAmount = (currentKasPercentage.toDouble() / 100.0) * totalAmount
            val netPayout = totalAmount - kasCutAmount

            val operator = currentUser.value?.fullName ?: "Pengurus"
            val isPengurus = currentUser.value?.role == "PENGURUS"
            val status = if (isPengurus) "PENDING" else "APPROVED"

            val transaction = Transaction(
                userId = nasabah.id,
                userName = nasabah.fullName,
                type = "SETOR",
                wasteType = category,
                weight = weight,
                amount = totalAmount,
                kasAmount = kasCutAmount,
                status = status,
                handledBy = if (isPengurus) null else operator,
                notes = notes
            )
            repository.insertTransaction(transaction)

            if (!isPengurus) {
                // Update user balances (user receives netPayout)
                val updatedNasabah = nasabah.copy(
                    wasteBalance = nasabah.wasteBalance + netPayout,
                    weightBalance = nasabah.weightBalance + weight
                )
                repository.updateUser(updatedNasabah)

                // Notify nasabah
                repository.insertNotification(
                    userId = nasabah.id,
                    title = "Setoran Sampah Berhasil",
                    message = "Setoran $category seberat $weight kg bernilai total Rp ${totalAmount.toInt()} (bersih diterima nasabah Rp ${netPayout.toInt()} setelah potongan kas $currentKasPercentage%) telah diterima oleh $operator."
                )

                onCompleted(true, "Setoran total Rp ${totalAmount.toInt()} (Net Rp ${netPayout.toInt()}) berhasil ditambahkan ke saldo ${nasabah.fullName}!")
            } else {
                // Notify admin of a new pending submission by Pengurus
                repository.insertNotification(
                    userId = 0,
                    title = "Pengajuan Setoran Baru",
                    message = "Pengurus $operator mengajukan setoran sampah $category seberat $weight kg untuk nasabah ${nasabah.fullName}."
                )
                // Notify nasabah
                repository.insertNotification(
                    userId = nasabah.id,
                    title = "Pengajuan Setoran Diajukan",
                    message = "Pengurus $operator mengajukan setoran sampah $category seberat $weight kg senilai kotor Rp ${totalAmount.toInt()} untuk Anda. Menunggu persetujuan Admin."
                )
                onCompleted(true, "Pengajuan setoran $category seberat $weight kg berhasil dikirim ke Admin untuk disetujui!")
            }
        }
    }

    // Request Withdrawal (Penarikan Dana)
    fun requestWithdrawal(amount: Double, notes: String, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val user = currentUser.value
            if (user == null) {
                onCompleted(false, "Sesi habis, silakan login kembali.")
                return@launch
            }

            if (amount <= 0) {
                onCompleted(false, "Nominal penarikan tidak valid!")
                return@launch
            }

            if (user.role == "ANGGOTA" && user.wasteBalance < amount) {
                onCompleted(false, "Saldo Anda tidak mencukupi! Saldo saat ini: Rp ${user.wasteBalance.toInt()}")
                return@launch
            }

            // Withdrawals always start in PENDING state requiring Admin/Pengurus approval
            val transaction = Transaction(
                userId = user.id,
                userName = user.fullName,
                type = "TARIK",
                amount = amount,
                status = "PENDING",
                notes = notes
            )
            repository.insertTransaction(transaction)

            // Notify admins and pengurus
            repository.insertNotification(
                userId = 0, // system broad-notification
                title = "Pengajuan Penarikan Baru",
                message = "${user.fullName} mengajukan penarikan saldo sebesar Rp ${amount.toInt()}."
            )

            // Internal notification for user
            repository.insertNotification(
                userId = user.id,
                title = "Penarikan Diajukan",
                message = "Pengajuan penarikan dana Rp ${amount.toInt()} sedang menunggu persetujuan Admin."
            )

            onCompleted(true, "Pengajuan penarikan Rp ${amount.toInt()} berhasil dikirim!")
        }
    }

    // Process Withdrawal directly by Admin (Admin registers a withdrawal for a member directly)
    fun registerWithdrawalDirectly(nasabahId: Int, amount: Double, notes: String, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val nasabah = repository.getUserByIdSuspend(nasabahId)
            if (nasabah == null) {
                onCompleted(false, "Nasabah tidak ditemukan!")
                return@launch
            }
            if (nasabah.wasteBalance < amount) {
                onCompleted(false, "Saldo nasabah tidak mencukupi! Saldo saat ini: Rp ${nasabah.wasteBalance.toInt()}")
                return@launch
            }

            val operator = currentUser.value?.fullName ?: "Admin"
            val isPengurus = currentUser.value?.role == "PENGURUS"
            val status = if (isPengurus) "PENDING" else "APPROVED"

            // Direct withdrawal is approved immediately, unless Pengurus does it which is PENDING
            val transaction = Transaction(
                userId = nasabah.id,
                userName = nasabah.fullName,
                type = "TARIK",
                amount = amount,
                status = status,
                handledBy = if (isPengurus) null else operator,
                notes = notes
            )
            repository.insertTransaction(transaction)

            if (!isPengurus) {
                val updatedNasabah = nasabah.copy(
                    wasteBalance = nasabah.wasteBalance - amount
                )
                repository.updateUser(updatedNasabah)

                // Notify nasabah
                repository.insertNotification(
                    userId = nasabah.id,
                    title = "Tarik Saldo Berhasil",
                    message = "Penarikan langsung Rp ${amount.toInt()} berhasil diproses oleh $operator."
                )

                onCompleted(true, "Tarik saldo Rp ${amount.toInt()} berhasil diproses!")
            } else {
                // Notify admin of a new pending withdrawal by Pengurus
                repository.insertNotification(
                    userId = 0,
                    title = "Pengajuan Tarik Tunai Baru",
                    message = "Pengurus $operator mengajukan penarikan saldo sebesar Rp ${amount.toInt()} untuk nasabah ${nasabah.fullName}."
                )
                // Notify nasabah
                repository.insertNotification(
                    userId = nasabah.id,
                    title = "Pengajuan Tarik Tunai Diajukan",
                    message = "Pengurus $operator mengajukan penarikan saldo Rp ${amount.toInt()} untuk Anda. Menunggu persetujuan Admin."
                )
                onCompleted(true, "Pengajuan penarikan Rp ${amount.toInt()} berhasil dikirim ke Admin untuk disetujui!")
            }
        }
    }

    // Publish Information / Announcement (Admin only)
    fun publishInformation(title: String, message: String, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val trimmedTitle = title.trim()
            val trimmedMsg = message.trim()
            if (trimmedTitle.isEmpty() || trimmedMsg.isEmpty()) {
                onCompleted(false, "Judul dan isi informasi tidak boleh kosong!")
                return@launch
            }
            val admin = currentUser.value
            if (admin == null || admin.role != "ADMIN") {
                onCompleted(false, "Hanya Administrator yang dapat memberikan informasi!")
                return@launch
            }
            // Insert notification with userId = 0 (broadcast to all)
            repository.insertNotification(
                userId = 0,
                title = trimmedTitle,
                message = trimmedMsg
            )
            onCompleted(true, "Informasi berhasil dipublikasikan ke seluruh anggota!")
        }
    }

    // Approve Transaction (Admin/Pengurus action)
    fun approveTransaction(transactionId: Int, onCompleted: (Boolean, String) -> Unit = {_,_ ->}) {
        viewModelScope.launch {
            val transaction = repository.getTransactionById(transactionId)
            if (transaction == null) {
                onCompleted(false, "Transaksi tidak ditemukan!")
                return@launch
            }

            if (transaction.status != "PENDING") {
                onCompleted(false, "Transaksi ini sudah diproses!")
                return@launch
            }

            val nasabah = repository.getUserByIdSuspend(transaction.userId)
            if (nasabah == null) {
                onCompleted(false, "Nasabah pemilik transaksi tidak ditemukan!")
                return@launch
            }

            val operatorName = currentUser.value?.fullName ?: "Admin"

            if (transaction.type == "TARIK") {
                if (nasabah.wasteBalance < transaction.amount) {
                    // Fail and auto-reject
                    val rejectedTx = transaction.copy(status = "REJECTED", handledBy = operatorName, notes = "Saldo tidak mencukupi pada saat pemrosesan")
                    repository.updateTransaction(rejectedTx)
                    repository.insertNotification(
                        userId = nasabah.id,
                        title = "Penarikan Ditolak",
                        message = "Penarikan Rp ${transaction.amount.toInt()} ditolak secara otomatis karena saldo tidak mencukupi."
                    )
                    onCompleted(false, "Gagal menyetujui: Saldo nasabah tidak cukup! Status transaksi diubah menjadi REJECTED.")
                    return@launch
                }

                // Deduct Balance
                val updatedNasabah = nasabah.copy(wasteBalance = nasabah.wasteBalance - transaction.amount)
                repository.updateUser(updatedNasabah)
            } else if (transaction.type == "SETOR") {
                // If there's ever a pending SETOR (custom), add net to balance
                val weight = transaction.weight ?: 0.0
                val netPayout = transaction.amount - transaction.kasAmount
                val updatedNasabah = nasabah.copy(
                    wasteBalance = nasabah.wasteBalance + netPayout,
                    weightBalance = nasabah.weightBalance + weight
                )
                repository.updateUser(updatedNasabah)
            }

            val approvedTx = transaction.copy(status = "APPROVED", handledBy = operatorName)
            repository.updateTransaction(approvedTx)

            // Notify nasabah
            repository.insertNotification(
                userId = nasabah.id,
                title = "Transaksi Disetujui",
                message = "Transaksi ${transaction.type} senilai Rp ${transaction.amount.toInt()} disetujui oleh $operatorName."
            )

            onCompleted(true, "Transaksi berhasil disetujui!")
        }
    }

    // Reject Transaction (Admin/Pengurus action)
    fun rejectTransaction(transactionId: Int, notes: String, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val transaction = repository.getTransactionById(transactionId)
            if (transaction == null) {
                onCompleted(false, "Transaksi tidak ditemukan!")
                return@launch
            }

            if (transaction.status != "PENDING") {
                onCompleted(false, "Transaksi ini sudah diproses!")
                return@launch
            }

            val operatorName = currentUser.value?.fullName ?: "Admin"
            val rejectedTx = transaction.copy(status = "REJECTED", handledBy = operatorName, notes = notes)
            repository.updateTransaction(rejectedTx)

            // Notify nasabah
            repository.insertNotification(
                userId = transaction.userId,
                title = "Transaksi Ditolak",
                message = "Transaksi ${transaction.type} senilai Rp ${transaction.amount.toInt()} ditolak oleh $operatorName. Alasan: $notes"
            )

            onCompleted(true, "Transaksi berhasil ditolak.")
        }
    }

    // Update Customizable App Name (Admin action)
    fun updateAppName(newName: String, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) {
                onCompleted(false)
                return@launch
            }
            repository.saveSetting("app_name", trimmed)
            switchBank(trimmed)
            onCompleted(true)
        }
    }

    // Share/Export CSV for spreadsheet
    fun shareCsvReport(context: Context, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val transactions = allTransactions.value
            if (transactions.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onCompleted(false, "Belum ada transaksi untuk diekspor!")
                }
                return@launch
            }

            val csvContent = repository.generateCsvString(transactions)
            try {
                // Write to cache file
                val cachePath = File(context.cacheDir, "csv_exports")
                cachePath.mkdirs()
                val file = File(cachePath, "Laporan_Bank_Sampah_${System.currentTimeMillis()}.csv")
                val stream = FileOutputStream(file)
                stream.write(csvContent.toByteArray(Charsets.UTF_8))
                stream.close()

                // Share Intent
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, "Laporan Transaksi Bank Sampah")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooserIntent = Intent.createChooser(intent, "Kirim Laporan Bank Sampah via...")
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                withContext(Dispatchers.Main) {
                    context.startActivity(chooserIntent)
                    onCompleted(true, "Laporan CSV berhasil diekspor!")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onCompleted(false, "Gagal mengekspor laporan: ${e.localizedMessage}")
                }
            }
        }
    }

    // Share/Export formatted Excel Spreadsheet workbook (.xls)
    fun shareExcelReport(context: Context, onCompleted: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val transactions = allTransactions.value
            if (transactions.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onCompleted(false, "Belum ada transaksi untuk diekspor!")
                }
                return@launch
            }

            val excelContent = repository.generateExcelXmlString(transactions)
            try {
                // Write to cache file
                val cachePath = File(context.cacheDir, "excel_exports")
                cachePath.mkdirs()
                val file = File(cachePath, "Laporan_Bank_Sampah_${System.currentTimeMillis()}.xls")
                val stream = FileOutputStream(file)
                stream.write(excelContent.toByteArray(Charsets.UTF_8))
                stream.close()

                // Share Intent
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.ms-excel"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, "Laporan Excel Bank Sampah RT06/RW02")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooserIntent = Intent.createChooser(intent, "Kirim Laporan Excel via...")
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                withContext(Dispatchers.Main) {
                    context.startActivity(chooserIntent)
                    onCompleted(true, "Laporan Excel berhasil diekspor!")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onCompleted(false, "Gagal mengekspor laporan Excel: ${e.localizedMessage}")
                }
            }
        }
    }

    // Logout Action
    fun logout() {
        userUpdateJob?.cancel()
        _currentUser.value = null
    }
}
