package app.foscal.core.model

data class Calendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val ownerName: String?,
    val color: Int,
    val visible: Boolean,
    val syncEnabled: Boolean,
) {
    val isLocal: Boolean
        get() = accountType == LOCAL_ACCOUNT_TYPE

    companion object {
        const val LOCAL_ACCOUNT_TYPE = "LOCAL"
    }
}
