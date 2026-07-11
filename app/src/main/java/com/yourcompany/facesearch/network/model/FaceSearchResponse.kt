package com.yourcompany.facesearch.network.model

import com.google.gson.annotations.SerializedName

/**
 * Top-level response from the face search endpoint.
 */
data class FaceSearchResponse(
    @SerializedName("search_id")
    val searchId: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("match_found")
    val matchFound: Boolean = false,

    @SerializedName("match_confidence")
    val matchConfidence: Double? = null,

    @SerializedName("employee_details")
    val employeeDetails: EmployeeDetails? = null
)

/**
 * Nested employee object returned on a successful match.
 */
data class EmployeeDetails(
    @SerializedName("employee_id")
    val employeeId: String? = null,

    @SerializedName("full_name")
    val fullName: String? = null,

    @SerializedName("department_name")
    val departmentName: String? = null,

    @SerializedName("internal_profile_url")
    val internalProfileUrl: String? = null,

    @SerializedName("joined_date")
    val joinedDate: String? = null
)
