package com.yourcompany.facesearch.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.yourcompany.facesearch.data.IdentityProfile
import com.yourcompany.facesearch.data.IdentityProfileStore
import com.yourcompany.facesearch.data.PublicProfileLead
import com.yourcompany.facesearch.data.PublicProfileLeadGenerator

/**
 * State for optional self-profile discovery. It does not touch camera capture,
 * offline scan, or face-enrollment state.
 */
class ProfileDiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    var profile by mutableStateOf(IdentityProfileStore.load(application))
        private set

    var leads by mutableStateOf(emptyList<PublicProfileLead>())
        private set

    var webQueries by mutableStateOf(emptyList<String>())
        private set

    var statusMessage by mutableStateOf("Add only the details you want to use. This identity card stays on this device.")
        private set

    fun updateProfile(transform: (IdentityProfile) -> IdentityProfile) {
        profile = transform(profile)
    }

    fun saveAndGenerate() {
        IdentityProfileStore.save(getApplication(), profile)
        leads = PublicProfileLeadGenerator.generate(profile)
        webQueries = PublicProfileLeadGenerator.generateWebQueries(profile)
        statusMessage = if (leads.isEmpty()) {
            "Add a name, alias, or handle to generate public profile routes."
        } else {
            "Generated ${leads.size} profile routes from your supplied clues. Open and confirm them manually."
        }
    }

    fun clearIdentityCard() {
        profile = IdentityProfile()
        IdentityProfileStore.save(getApplication(), profile)
        leads = emptyList()
        webQueries = emptyList()
        statusMessage = "The local identity card and generated leads were cleared."
    }
}
