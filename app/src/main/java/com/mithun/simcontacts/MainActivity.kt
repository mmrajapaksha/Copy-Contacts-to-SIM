package com.mithun.simcontacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mithun.simcontacts.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ContactAdapter

    private val perms = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_PHONE_STATE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ContactAdapter { contact ->
            val state = viewModel.copyState.value
            if (state is CopyState.Idle || state is CopyState.Done || state is CopyState.Cancelled) {
                viewModel.toggleContact(contact)
                updateCounts()
            }
        }
        binding.contactList.layoutManager = LinearLayoutManager(this)
        binding.contactList.adapter = adapter

        // Select all checkbox
        binding.selectAll.setOnClickListener {
            viewModel.selectAllVisible(binding.selectAll.isChecked)
            updateCounts()
        }

        // Search with 300ms debounce
        val searchFlow = MutableStateFlow("")
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchFlow.value = s?.toString().orEmpty()
            }
        })
        lifecycleScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            searchFlow.debounce(300).collectLatest { query ->
                viewModel.setSearchQuery(query)
            }
        }

        // Action buttons
        binding.copyBtn.setOnClickListener { confirmCopy() }
        binding.viewSimBtn.setOnClickListener { showSimContacts() }
        binding.cancelBtn.setOnClickListener { viewModel.cancelCopy() }

        // Observe reactive state
        observeViewModel()

        checkPermissions()
    }

    // ---------- Permissions ----------

    private fun granted(p: String): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        if (perms.all { granted(it) }) {
            viewModel.loadAll()
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(perms, 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.WRITE_CONTACTS)) {
            viewModel.loadAll()
        } else {
            binding.statusText.text = getString(R.string.need_permission)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.need_permission)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
                .setNegativeButton(R.string.retry) { _, _ -> checkPermissions() }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.allContacts.value.isEmpty() &&
            viewModel.copyState.value is CopyState.Idle &&
            granted(Manifest.permission.READ_CONTACTS) &&
            viewModel.sims.value.isNotEmpty()
        ) {
            viewModel.loadAll()
        }
    }

    // ---------- Observe ViewModel ----------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.sims.collectLatest { sims ->
                        if (sims.isNotEmpty()) setupSimSpinner(sims)
                    }
                }
                launch {
                    viewModel.filteredContacts.collectLatest { contacts ->
                        adapter.submitList(contacts.toList())
                        val isEmpty = contacts.isEmpty() && viewModel.totalCount > 0
                        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        binding.contactList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        if (isEmpty) {
                            binding.emptyView.text = getString(R.string.no_results)
                        }
                        updateCounts()
                    }
                }
                launch {
                    viewModel.simInfoText.collectLatest { text ->
                        binding.simInfo.text = text
                    }
                }
                launch {
                    viewModel.statusText.collectLatest { text ->
                        binding.statusText.text = text
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        if (loading) {
                            binding.progress.isIndeterminate = true
                            binding.progress.visibility = View.VISIBLE
                        } else if (viewModel.copyState.value is CopyState.Idle) {
                            binding.progress.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.copyState.collectLatest { state ->
                        when (state) {
                            is CopyState.Idle -> {
                                setBusy(false)
                            }
                            is CopyState.Copying -> {
                                setBusy(true)
                                binding.progress.isIndeterminate = false
                                binding.progress.visibility = View.VISIBLE
                                binding.progress.max = state.total
                                binding.progress.progress = state.done
                            }
                            is CopyState.Done -> {
                                setBusy(false)
                                showCopyResult(state.result)
                                selectedSim()?.let { viewModel.refreshSimInfo(it) }
                                viewModel.resetCopyState()
                            }
                            is CopyState.Cancelled -> {
                                setBusy(false)
                                viewModel.resetCopyState()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- SIM spinner ----------

    private fun setupSimSpinner(sims: List<SimManager.SimCard>) {
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sims.map { it.label })
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.simSpinner.adapter = spinnerAdapter
        binding.simSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sims.getOrNull(pos)?.let { viewModel.refreshSimInfo(it) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun selectedSim(): SimManager.SimCard? {
        return viewModel.sims.value.getOrNull(binding.simSpinner.selectedItemPosition)
    }

    // ---------- UI helpers ----------

    private fun updateCounts() {
        val n = viewModel.selectedCount
        val total = viewModel.totalCount
        binding.countText.text = getString(R.string.count_text, n, total)
        binding.copyBtn.text = getString(R.string.copy_n, n)
        binding.copyBtn.isEnabled = n > 0 && viewModel.copyState.value is CopyState.Idle
        val visible = viewModel.filteredContacts.value
        binding.selectAll.isChecked = visible.isNotEmpty() && visible.all { it.selected }
    }

    private fun setBusy(busy: Boolean) {
        binding.selectAll.isEnabled = !busy
        binding.simSpinner.isEnabled = !busy
        binding.searchField.isEnabled = !busy
        binding.viewSimBtn.isEnabled = !busy
        binding.copyBtn.visibility = if (busy) View.GONE else View.VISIBLE
        binding.cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy && !viewModel.isLoading.value) {
            binding.progress.visibility = View.GONE
        }
        updateCounts()
    }

    // ---------- Copy ----------

    private fun confirmCopy() {
        val sim = selectedSim() ?: return
        val chosen = viewModel.allContacts.value.filter { it.selected }
        if (chosen.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(getString(R.string.confirm_msg, chosen.size, sim.label))
            .setPositiveButton(R.string.copy) { _, _ -> viewModel.startCopy(sim, chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCopyResult(result: CopyResult) {
        val msg = StringBuilder(getString(R.string.result_msg, result.ok, result.dup, result.full, result.fail))
        if (result.failedNames.isNotEmpty()) {
            msg.append("\n\n").append(getString(R.string.failed_list)).append("\n")
                .append(result.failedNames.joinToString(", "))
            result.lastError?.let { msg.append("\n\n").append(getString(R.string.error_detail, it)) }
        }
        if (result.ok == 0 && result.fail > 0) msg.append("\n\n").append(getString(R.string.zero_ok_hint))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.result_title)
            .setMessage(msg.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- View / Delete SIM contacts ----------

    private fun showSimContacts() {
        val sim = selectedSim() ?: return
        lifecycleScope.launch {
            val entries = viewModel.readSimContacts(sim)
            val items = entries.map { "${it.name}\n${it.number}" }.toTypedArray()

            val builder = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.on_sim_title, entries.size))
                .setPositiveButton(android.R.string.ok, null)

            if (items.isEmpty()) {
                builder.setMessage(R.string.sim_empty)
            } else {
                builder.setItems(items) { _, which ->
                    val entry = entries[which]
                    confirmDeleteSimContact(sim, entry)
                }
                builder.setNeutralButton(R.string.delete_all) { _, _ ->
                    confirmDeleteAllSimContacts(sim, entries.size)
                }
            }
            builder.show()
        }
    }

    private fun confirmDeleteSimContact(sim: SimManager.SimCard, entry: SimManager.SimEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, entry.name.ifEmpty { entry.number }))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = viewModel.deleteSimContact(sim, entry.name, entry.number)
                    if (ok) {
                        viewModel.refreshSimInfo(sim)
                    } else {
                        binding.statusText.text = getString(R.string.delete_failed)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllSimContacts(sim: SimManager.SimCard, count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(getString(R.string.delete_all_msg, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val deleted = viewModel.deleteAllSimContacts(sim)
                    binding.statusText.text = getString(R.string.deleted_n, deleted)
                    viewModel.refreshSimInfo(sim)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
