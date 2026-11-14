package com.xemmy.mmbank.data.local

import android.content.Context
import java.io.File
import java.io.FileInputStream
import net.sqlcipher.database.SQLiteDatabase

/**
 * One-time, in-place migration of an existing plaintext Room database to
 * SQLCipher, built so that NO step can destroy the user's ledger:
 *
 *  1. The plaintext DB is checkpointed (WAL folded in) and exported into a
 *     sibling `.enc` file via `sqlcipher_export`, carrying `user_version`
 *     across (Room would otherwise re-run migrations from 0).
 *  2. The `.enc` copy is verified — opens with the passphrase, schema
 *     readable, version matches — BEFORE the original is touched.
 *  3. Swap: original -> `.pre-encrypt.bak`, `.enc` -> original, final
 *     verification, and only then is the `.bak` deleted.
 *
 * Every crash window is re-entrant: a missing main DB is restored from the
 * verified `.enc` (or the `.bak`) on the next run, a half-written `.enc` is
 * discarded and re-exported, and leftover artifacts after success are swept.
 * On any failure the plaintext original stays in place and the caller opens
 * it unencrypted, so the app keeps working and the migration retries on the
 * next process start.
 */
object DatabaseEncryptionMigrator {

    private const val ENC_SUFFIX = ".enc"
    private const val BAK_SUFFIX = ".pre-encrypt.bak"

    /**
     * Ensures [dbName] is SQLCipher-encrypted with [passphrase]. Returns true
     * when the on-disk file is encrypted or absent (a fresh install — the
     * encrypted factory will create it), false when it is still plaintext
     * (migration failed; open WITHOUT the encrypted factory so the ledger
     * stays usable).
     */
    fun ensureEncrypted(
        context: Context,
        dbName: String,
        passphrase: String,
        onError: (tag: String, t: Throwable) -> Unit,
    ): Boolean {
        val dbFile = context.getDatabasePath(dbName)
        val encFile = File(dbFile.path + ENC_SUFFIX)
        val bakFile = File(dbFile.path + BAK_SUFFIX)
        return try {
            recoverInterruptedSwap(dbFile, encFile, bakFile)
            when {
                !dbFile.exists() -> {
                    sweepArtifacts(encFile, bakFile)
                    true
                }
                !isPlaintextFile(dbFile) -> {
                    // Already encrypted; a leftover plaintext .bak from the
                    // successful run must not outlive the migration.
                    sweepArtifacts(encFile, bakFile)
                    true
                }
                else -> {
                    migrate(context, dbFile, encFile, bakFile, passphrase)
                    true
                }
            }
        } catch (t: Throwable) {
            onError("DatabaseEncryptionMigrator", t)
            runCatching { encFile.delete() }
            // Report the actual on-disk state so the caller picks the right
            // factory; the .bak (if any) is kept for the next attempt.
            runCatching { !dbFile.exists() || !isPlaintextFile(dbFile) }.getOrDefault(false)
        }
    }

    /**
     * A previous run may have crashed between the two swap renames, leaving
     * no main DB file. The `.enc` was verified before the swap began, so
     * finishing the swap is safe; failing that, the plaintext `.bak` is
     * restored untouched.
     */
    private fun recoverInterruptedSwap(dbFile: File, encFile: File, bakFile: File) {
        if (dbFile.exists()) return
        when {
            encFile.exists() -> check(encFile.renameTo(dbFile)) { "resume: enc->db rename failed" }
            bakFile.exists() -> check(bakFile.renameTo(dbFile)) { "resume: bak->db restore failed" }
        }
    }

    private fun migrate(context: Context, dbFile: File, encFile: File, bakFile: File, passphrase: String) {
        SQLiteDatabase.loadLibs(context)
        // Half-written export (and its journals) from an interrupted run.
        encFile.delete()
        File(encFile.path + "-journal").delete()
        File(encFile.path + "-wal").delete()
        File(encFile.path + "-shm").delete()

        // Pass 1 — plaintext connection: read the schema version and fold WAL
        // content into the main file, so the single .bak file is a complete
        // backup and stale -wal/-shm can be deleted after the swap instead of
        // being replayed into the encrypted DB.
        val version: Int
        val plain = SQLiteDatabase.openDatabase(dbFile.path, "", null, SQLiteDatabase.OPEN_READWRITE)
        try {
            version = plain.version
            plain.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE);")
        } finally {
            plain.close()
        }

        // Pass 2 — encrypted connection as MAIN, plaintext attached as the
        // export SOURCE. The forward direction (plaintext main + "ATTACH ...
        // KEY '<key>'") throws "unable to open database" on-device with this
        // library: an empty-key connection won't attach a keyed database.
        // Attaching the plaintext (KEY '') from an encrypted main is the
        // documented, reliable direction; the two-argument
        // sqlcipher_export(target, source) has existed since SQLCipher 4.1.
        // Passphrase is hex by construction (DatabaseKeyMaterial), so
        // embedding it in openOrCreateDatabase needs no escaping concerns.
        val enc = SQLiteDatabase.openOrCreateDatabase(encFile.path, passphrase, null)
        try {
            enc.rawExecSQL("ATTACH DATABASE '${dbFile.path}' AS plaintext KEY '';")
            enc.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext');")
            enc.rawExecSQL("DETACH DATABASE plaintext;")
            enc.rawExecSQL("PRAGMA user_version = $version;")
        } finally {
            enc.close()
        }

        // Gate: nothing below runs unless the encrypted copy provably works.
        verifyEncrypted(encFile, passphrase, version)

        bakFile.delete() // stale backup from an earlier failed attempt
        check(dbFile.renameTo(bakFile)) { "swap: db->bak rename failed" }
        File(dbFile.path + "-wal").delete() // truncated above; must not be
        File(dbFile.path + "-shm").delete() // replayed into the encrypted DB
        check(encFile.renameTo(dbFile)) { "swap: enc->db rename failed" }

        try {
            verifyEncrypted(dbFile, passphrase, version)
        } catch (t: Throwable) {
            // Final check failed in place: put the plaintext original back.
            dbFile.delete()
            bakFile.renameTo(dbFile)
            throw t
        }
        // Safety net served its purpose — remove the plaintext copy so it
        // doesn't defeat the encryption it guarded.
        bakFile.delete()
    }

    private fun verifyEncrypted(file: File, passphrase: String, expectedVersion: Int) {
        val db = SQLiteDatabase.openDatabase(file.path, passphrase, null, SQLiteDatabase.OPEN_READONLY)
        try {
            check(db.version == expectedVersion) {
                "verify: user_version ${db.version} != expected $expectedVersion"
            }
            db.rawQuery("SELECT count(*) FROM sqlite_master;", null).use { cursor ->
                check(cursor.moveToFirst()) { "verify: schema unreadable" }
            }
        } finally {
            db.close()
        }
    }

    private fun sweepArtifacts(encFile: File, bakFile: File) {
        encFile.delete()
        bakFile.delete()
    }

    /** True when [dbName] exists on disk and is not plaintext SQLite (see [SqliteFileFormat.isEncryptedFile]). */
    fun isEncryptedOnDisk(context: Context, dbName: String): Boolean {
        val file = context.getDatabasePath(dbName)
        if (!file.exists()) return false
        return SqliteFileFormat.isEncryptedFile(true, readHeader(file))
    }

    private fun isPlaintextFile(file: File): Boolean =
        SqliteFileFormat.isPlaintextHeader(readHeader(file))

    private fun readHeader(file: File): ByteArray =
        FileInputStream(file).use { input ->
            val header = ByteArray(SqliteFileFormat.HEADER_LENGTH)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            header.copyOf(read)
        }
}
