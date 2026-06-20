package com.orbitalfrontier.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [SaveBackupStore] (UC52 AC#3): the single rolling `.bak` beside the live `.db` that
 * [SaveDatabaseOpener] takes *before* a migration and restores on failure. The contract:
 *  - backup copies the live bytes to `<name>.bak`;
 *  - restore overwrites the live file with the backup's bytes;
 *  - copies are atomic (temp-then-rename — never a torn destination on the happy path);
 *  - hasBackup / deleteBackup behave as named;
 *  - backing up an absent/empty file is a safe no-op (nothing to protect yet).
 */
class SaveBackupStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store = SaveBackupStore()

    private fun dbFile(
        name: String = "orbital_frontier.db",
        bytes: ByteArray? = byteArrayOf(1, 2, 3, 4, 5),
    ): File {
        val file = File(tempFolder.root, name)
        if (bytes != null) file.writeBytes(bytes)
        return file
    }

    @Test
    fun `backup creates a sibling bak with byte-identical contents`() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val db = dbFile(bytes = bytes)

        val backedUp = store.backup(db)

        assertTrue("a present, non-empty db must be backed up", backedUp)
        val bak = store.backupFileFor(db)
        assertTrue("the .bak must exist after backup", bak.isFile)
        assertArrayEquals("the backup bytes must equal the live db bytes", bytes, bak.readBytes())
    }

    @Test
    fun `the backup file is the sibling name plus bak suffix`() {
        val db = dbFile()
        assertTrue(store.backupFileFor(db).name == "orbital_frontier.db.bak")
        assertTrue(
            "the backup must live beside the db",
            store.backupFileFor(db).parentFile == db.parentFile,
        )
    }

    @Test
    fun `restore overwrites the live db with the backup contents`() {
        val original = ByteArray(512) { (it % 97).toByte() }
        val db = dbFile(bytes = original)
        store.backup(db)

        // Simulate a corrupting / partially-migrated write onto the live file.
        db.writeBytes(ByteArray(64) { 0x7F })

        val restored = store.restore(db)

        assertTrue("restore must report success when a backup exists", restored)
        assertArrayEquals("the live db must be returned to the backed-up bytes", original, db.readBytes())
    }

    @Test
    fun `restore returns false when there is no backup to roll back to`() {
        val db = dbFile()
        assertFalse(store.restore(db))
    }

    @Test
    fun `hasBackup reflects presence of a non-empty bak`() {
        val db = dbFile()
        assertFalse("no backup before one is taken", store.hasBackup(db))
        store.backup(db)
        assertTrue("a backup is present after backup()", store.hasBackup(db))
    }

    @Test
    fun `hasBackup is false for an empty bak file`() {
        val db = dbFile()
        store.backupFileFor(db).createNewFile()
        assertFalse("a zero-byte .bak is not a usable backup", store.hasBackup(db))
    }

    @Test
    fun `deleteBackup removes the bak and leaves the live db untouched`() {
        val live = byteArrayOf(9, 8, 7, 6)
        val db = dbFile(bytes = live)
        store.backup(db)
        assertTrue(store.hasBackup(db))

        store.deleteBackup(db)

        assertFalse("the .bak must be gone after deleteBackup", store.hasBackup(db))
        assertTrue("the live db must still exist", db.isFile)
        assertArrayEquals("the live db bytes must be unchanged", live, db.readBytes())
    }

    @Test
    fun `backing up a missing file is a safe no-op`() {
        val missing = File(tempFolder.root, "missing.db")
        assertFalse("nothing to back up for a missing file", store.backup(missing))
        assertFalse(store.hasBackup(missing))
    }

    @Test
    fun `backing up an empty file is a safe no-op`() {
        val empty = dbFile(bytes = ByteArray(0))
        assertFalse("nothing to back up for an empty file", store.backup(empty))
        assertFalse(store.hasBackup(empty))
    }

    @Test
    fun `backup leaves no temp sidecar behind on the happy path`() {
        val db = dbFile(bytes = ByteArray(4096) { it.toByte() })
        store.backup(db)

        val leftovers =
            tempFolder.root.listFiles()?.map { it.name }?.filter { it.endsWith(".tmp") } ?: emptyList()
        assertTrue("the atomic copy must not leave a .tmp file behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a second backup overwrites the first (rolling, single backup)`() {
        val db = dbFile(bytes = byteArrayOf(1, 1, 1))
        store.backup(db)

        val second = ByteArray(256) { 0x42 }
        db.writeBytes(second)
        store.backup(db)

        assertArrayEquals("the .bak must roll forward to the latest backed-up bytes", second, store.backupFileFor(db).readBytes())
    }
}
