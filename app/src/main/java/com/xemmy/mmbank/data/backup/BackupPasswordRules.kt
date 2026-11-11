package com.xemmy.mmbank.data.backup

/**
 * When a typed backup password may be used. Export needs a confirmed password
 * (a typo would make the file unrecoverable); import only needs one that is
 * long enough to try.
 */
object BackupPasswordRules {
    const val MIN_LENGTH = 6

    fun canImport(password: String): Boolean = password.length >= MIN_LENGTH

    fun canExport(password: String, confirm: String): Boolean =
        canImport(password) && password == confirm

    fun mismatch(password: String, confirm: String): Boolean =
        confirm.isNotEmpty() && password != confirm
}
