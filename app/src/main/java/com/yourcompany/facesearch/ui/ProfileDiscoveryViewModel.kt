package com.yourcompany.facesearch.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.data.IdentityProfile
import com.yourcompany.facesearch.data.IdentityProfileStore
import com.yourcompany.facesearch.data.ProfileConfidence
import com.yourcompany.facesearch.data.ProfileStatus
import com.yourcompany.facesearch.data.PublicProfileLead
import com.yourcompany.facesearch.data.PublicProfileLeadGenerator
import com.yourcompany.facesearch.network.ProfileExistenceChecker
import kotlinx.coroutines.launch

/**
 * State for optional self-profile discovery. It does not touch camera capture,
 * offline scan, or face-enrollment state.
 */
class ProfileDiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    var profile by mutableStateOf(IdentityProfileStore.load(application))
        private set

    var leads by mutableStateOf(emptyList<PublicProfileLead>())
        private set

    var webQueries by mutableStateOf(emptyList<Pair<String, String>>())
        private set

    var emailQueries by mutableStateOf(emptyList<Pair<String, String>>())
        private set

    var phoneQueries by mutableStateOf(emptyList<Pair<String, String>>())
        private set

    var statusMessage by mutableStateOf("Add only the details you want to use. This identity card stays on this device.")
        private set

    var isChecking by mutableStateOf(false)
        private set

    var checkProgress by mutableStateOf(0)
        private set

    var checkTotal by mutableStateOf(0)
        private set

    var exportText by mutableStateOf<String?>(null)
        private set

    private val checker = ProfileExistenceChecker()

    fun updateProfile(transform: (IdentityProfile) -> IdentityProfile) {
        profile = transform(profile)
    }

    fun saveAndGenerate() {
        IdentityProfileStore.save(getApplication(), profile)
        leads = PublicProfileLeadGenerator.generate(profile)
        webQueries = PublicProfileLeadGenerator.generateWebQueries(profile)
        emailQueries = PublicProfileLeadGenerator.generateEmailQueries(profile)
        phoneQueries = PublicProfileLeadGenerator.generatePhoneQueries(profile)
        statusMessage = if (leads.isEmpty()) {
            "Add a name, alias, handle, or email to generate public profile routes."
        } else {
            "Generated ${leads.size} profile routes across ${leads.distinctBy { it.platform }.size} platforms. Tap 'Check All' to verify which exist, or review them manually."
        }
    }

    fun checkAllLeads() {
        if (leads.isEmpty() || isChecking) return
        isChecking = true
        checkProgress = 0
        checkTotal = leads.size

        viewModelScope.launch {
            // Mark all as checking
            leads = leads.map { it.copy(status = ProfileStatus.CHECKING, confidence = it.confidence) }

            val checkedLeads = checker.checkLeads(leads) { done, total ->
                checkProgress = done
                checkTotal = total
            }

            leads = checkedLeads
            isChecking = false

            val found = checkedLeads.count { it.status == ProfileStatus.LIKELY_EXISTS }
            val strong = checkedLeads.count { it.status == ProfileStatus.LIKELY_EXISTS && it.confidence == ProfileConfidence.STRONG }
            val notFound = checkedLeads.count { it.status == ProfileStatus.NOT_FOUND }
            val errors = checkedLeads.count { it.status == ProfileStatus.ERROR || it.status == ProfileStatus.UNCHECKED }

            statusMessage = buildString {
                appendLine("Checked ${checkedLeads.size} URLs across ${checkedLeads.distinctBy { it.platform }.size} platforms.")
                if (found > 0) {
                    append("Found $found likely profiles")
                    if (strong > 0) append(" ($strong strong match${if (strong > 1) "es" else ""})")
                    appendLine(".")
                }
                if (notFound > 0) appendLine("$notFound returned 404 / not found.")
                if (errors > 0) appendLine("$errors couldn't be checked (rate limited or blocked).")
            }.trim()
        }
    }

    fun exportResults() {
        exportText = PublicProfileLeadGenerator.exportLeads(profile, leads)
    }

    fun clearExport() {
        exportText = null
    }

    fun clearIdentityCard() {
        profile = IdentityProfile()
        IdentityProfileStore.save(getApplication(), profile)
        leads = emptyList()
        webQueries = emptyList()
        emailQueries = emptyList()
        phoneQueries = emptyList()
        exportText = null
        statusMessage = "The local identity card and generated leads were cleared."
    }
}
