package com.mithun.simcontacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val simManager = SimManager(application)

    // --- State flows ---
    private val _sims = MutableStateFlow<List<SimManager.SimCard>>(emptyList())
    val sims: StateFlow<List<SimManager.SimCard>> = _sims.asStateFlow()

    private val _allContacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val allContacts: StateFlow<List<PhoneContact>> = _allContacts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    val filteredContacts: StateFlow<List<PhoneContact>> = combine(_allContacts, _searchQuery) { contacts, query ->
        if (query.isBlank()) contacts
        else contacts.filter { it.name.lowercase().contains(query) || it.number.contains(query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _simInfoText = MutableStateFlow("")
    val simInfoText: StateFlow<String> = _simInfoText.asStateFlow()

    private val _copyState = MutableStateFlow<CopyState>(CopyState.Idle)
    val copyState: StateFlow<CopyState> = _copyState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var copyJob: Job? = null

    val selectedCount: Int get() = _allContacts.value.count { it.selected }
    val totalCount: Int get() = _allContacts.value.size

    // --- Actions ---

    fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusText.value = getApplication<Application>().getString(R.string.loading)
            val (s, c) = withContext(Dispatchers.IO) {
                simManager.getSims() to ContactsRepository.load(getApplication())
            }
            _sims.value = s
            _allContacts.value = c
            _isLoading.value = false
            _statusText.value = if (c.isEmpty()) getApplication<Application>().getString(R.string.no_contacts) else ""
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query.trim().lowercase()
    }

    fun toggleContact(contact: PhoneContact) {
        contact.selected = !contact.selected
        // Emit new list reference to notify observers
        _allContacts.value = _allContacts.value.toList()
    }

    fun selectAllVisible(select: Boolean) {
        filteredContacts.value.forEach { it.selected = select }
        _allContacts.value = _allContacts.value.toList()
    }

    fun refreshSimInfo(sim: SimManager.SimCard) {
        val app = getApplication<Application>()
        _simInfoText.value = app.getString(R.string.reading_sim)
        viewModelScope.launch {
            val (info, count) = withContext(Dispatchers.IO) {
                simManager.getInfo(sim) to simManager.readSimContacts(sim).size
            }
            _simInfoText.value = if (info != null)
                app.getString(R.string.sim_info_full, info.used, info.maxRecords, info.maxRecords - info.used, info.nameMaxLen)
            else
                app.getString(R.string.sim_info_basic, count)
        }
    }

    fun startCopy(sim: SimManager.SimCard, chosen: List<PhoneContact>) {
        copyJob = viewModelScope.launch {
            _copyState.value = CopyState.Copying(0, chosen.size)
            val app = getApplication<Application>()

            val result = withContext(Dispatchers.IO) {
                val info = simManager.getInfo(sim)
                val existing = simManager.readSimContacts(sim).map { SimManager.key(it.number) }.toMutableSet()
                var free = info?.let { it.maxRecords - it.used } ?: Int.MAX_VALUE
                var ok = 0; var dup = 0; var fail = 0; var full = 0; var streak = 0
                val failedNames = ArrayList<String>()

                for ((i, c) in chosen.withIndex()) {
                    if (!isActive) {
                        return@withContext null // Cancelled
                    }
                    val k = SimManager.key(c.number)
                    when {
                        k.isNotEmpty() && k in existing -> dup++
                        free <= 0 -> full++
                        simManager.writeContact(sim, c.simName, c.number, info) -> {
                            ok++; free--; streak = 0; existing.add(k)
                            c.selected = false
                        }
                        else -> {
                            fail++; streak++
                            if (failedNames.size < 10) failedNames.add(c.name)
                            if (info == null && ok > 0 && streak >= 5) free = 0
                        }
                    }
                    _copyState.value = CopyState.Copying(i + 1, chosen.size)
                    _statusText.value = app.getString(R.string.copying, i + 1, chosen.size)
                }
                CopyResult(ok, dup, full, fail, failedNames, simManager.lastError)
            }

            if (result == null) {
                _copyState.value = CopyState.Cancelled
                _statusText.value = app.getString(R.string.copy_cancelled)
            } else {
                _copyState.value = CopyState.Done(result)
                _statusText.value = app.getString(R.string.done_short, result.ok)
                _allContacts.value = _allContacts.value.toList()
            }
        }
    }

    fun cancelCopy() {
        copyJob?.cancel()
    }

    fun resetCopyState() {
        _copyState.value = CopyState.Idle
    }

    // --- SIM contact operations ---

    suspend fun readSimContacts(sim: SimManager.SimCard): List<SimManager.SimEntry> {
        return withContext(Dispatchers.IO) { simManager.readSimContacts(sim) }
    }

    suspend fun deleteSimContact(sim: SimManager.SimCard, name: String, number: String): Boolean {
        return withContext(Dispatchers.IO) { simManager.deleteContact(sim, name, number) }
    }

    suspend fun deleteAllSimContacts(sim: SimManager.SimCard): Int {
        return withContext(Dispatchers.IO) { simManager.deleteAllContacts(sim) }
    }
}
