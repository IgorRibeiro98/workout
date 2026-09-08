package com.example.data.social

object BlockContract {
    const val BLOCKS_PATH = "v1/social/blocks"
    fun unblockPath(socialId: String) = "v1/social/blocks/$socialId"

    object ErrorCodes {
        const val CANNOT_BLOCK_SELF = "CANNOT_BLOCK_SELF"
        const val SOCIAL_PROFILE_NOT_FOUND = "SOCIAL_PROFILE_NOT_FOUND"
        const val BLOCK_NOT_FOUND = "BLOCK_NOT_FOUND"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val SOCIAL_PROFILE_DISABLED = "SOCIAL_PROFILE_DISABLED"
    }
}
