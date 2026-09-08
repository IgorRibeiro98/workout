package com.example.data.social

object ReportContract {
    const val REPORTS_PATH = "v1/social/reports"

    object ErrorCodes {
        const val CANNOT_REPORT_SELF = "CANNOT_REPORT_SELF"
        const val NO_LEGITIMATE_CONTEXT = "NO_LEGITIMATE_CONTEXT"
        const val REPORT_RATE_LIMITED = "REPORT_RATE_LIMITED"
        const val SOCIAL_PROFILE_NOT_FOUND = "SOCIAL_PROFILE_NOT_FOUND"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val SOCIAL_PROFILE_DISABLED = "SOCIAL_PROFILE_DISABLED"
    }
}
