package com.xemmy.mmbank.domain.backup

/**
 * What an encrypted backup may contain so it can always be restored. Backups carry only REAL
 * rows (demo rows are never exported), so anything that points at a row outside the export --
 * a label on a demo transaction, a merchant rule for a demo account, a real transaction that
 * ended up filed on a demo account -- would fail the database's foreign-key checks on restore
 * and roll the whole restore back. Pure and JVM-testable.
 */
object BackupScope {

    /**
     * The saved-people list to export. Demo mode swaps the user's people for demo names and
     * keeps the real list in `demoPersonsBackup`, so a backup made during Demo mode takes that.
     */
    fun personsRaw(demoMode: Boolean, personsRaw: String, demoPersonsBackup: String): String =
        if (demoMode) demoPersonsBackup else personsRaw

    /** [items] whose reference ([ref]) is null or one of the [exported] ids. */
    fun <T> referencingExported(items: List<T>, exported: Set<Long>, ref: (T) -> Long?): List<T> =
        items.filter { item -> ref(item)?.let { it in exported } ?: true }

    /** [accountId] when that account is exported, else null (the row is restored unassigned). */
    fun exportedAccountOrNull(accountId: Long?, exportedAccounts: Set<Long>): Long? =
        accountId?.takeIf { it in exportedAccounts }

    /** The CSV "Category" cell: the category the user filed the row under, empty when none. */
    fun csvCategory(categoryId: Long?, namesById: Map<Long, String>): String =
        categoryId?.let { namesById[it] }.orEmpty()
}
