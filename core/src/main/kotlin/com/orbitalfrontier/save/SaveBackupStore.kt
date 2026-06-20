package com.orbitalfrontier.save

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Makes and restores a single rolling backup of the save database file (UC52 AC#3) — a sibling `.bak`
 * beside the live `.db`. [SaveDatabaseOpener] backs the last-good database up *before* a schema
 * migration runs, then [restore]s it if the migration fails, so a half-applied upgrade can never leave
 * the player without a save.
 *
 * **Atomicity.** Both copy directions go through a temp file that is fully written + fsync'd and only
 * then `rename`d onto the destination ([copyAtomically]). A crash mid-copy therefore leaves the
 * destination either untouched or fully replaced — never a torn half-file. This relies on the save
 * being a **single** `.db` (WAL disabled — `journal_mode=DELETE`, set on the Android driver) so the one
 * file is authoritative and there are no `-wal` / `-shm` sidecars to copy alongside it.
 *
 * Pure `java.io` (minSdk-24 safe: `FileChannel.transferTo` + `File.renameTo`, no `java.nio.file.Files`),
 * engine-free, so it is JVM-unit-testable against real temp files (ADR 0001).
 */
class SaveBackupStore {
    /** The backup file paired with [db] (its sibling `<name>.bak`). */
    fun backupFileFor(db: File): File = File(db.parentFile, db.name + BACKUP_SUFFIX)

    /** Whether a non-empty backup exists for [db]. */
    fun hasBackup(db: File): Boolean = backupFileFor(db).let { it.isFile && it.length() > 0L }

    /**
     * Copy the live [db] to its `.bak` (atomically). Returns false (a no-op) when there is nothing to
     * back up — [db] is absent or empty — so the caller can proceed to a fresh open. Throws [IOException]
     * only on a genuine copy failure.
     */
    fun backup(db: File): Boolean {
        if (!db.isFile || db.length() == 0L) return false
        copyAtomically(src = db, dest = backupFileFor(db))
        return true
    }

    /**
     * Restore [db] from its `.bak` (atomically). Returns false when no backup exists (nothing to roll
     * back to); true once the live file has been replaced by the backup's contents.
     */
    fun restore(db: File): Boolean {
        val backup = backupFileFor(db)
        if (!backup.isFile) return false
        copyAtomically(src = backup, dest = db)
        return true
    }

    /** Remove the backup for [db] if present (the live `.db` is left untouched). */
    fun deleteBackup(db: File) {
        backupFileFor(db).delete()
    }

    /**
     * Copy [src] to [dest] via a temp sibling that is fsync'd then atomically renamed over [dest]. If the
     * rename can't replace an existing [dest] directly (some filesystems require the target absent), the
     * existing [dest] is removed and the rename retried once before giving up.
     */
    private fun copyAtomically(
        src: File,
        dest: File,
    ) {
        val tmp = File(dest.parentFile, dest.name + TEMP_SUFFIX)
        tmp.delete()
        FileInputStream(src).channel.use { input ->
            FileOutputStream(tmp).channel.use { output ->
                val size = input.size()
                var position = 0L
                while (position < size) {
                    val transferred = input.transferTo(position, size - position, output)
                    if (transferred <= 0L) break
                    position += transferred
                }
                output.force(true)
            }
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("Failed to rename ${tmp.absolutePath} onto ${dest.absolutePath}")
            }
        }
    }

    private companion object {
        const val BACKUP_SUFFIX = ".bak"
        const val TEMP_SUFFIX = ".tmp"
    }
}
