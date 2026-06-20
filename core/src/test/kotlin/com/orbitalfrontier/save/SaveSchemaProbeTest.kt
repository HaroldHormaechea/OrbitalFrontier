package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Header-only probe tests for [SaveSchemaProbe] (UC52 AC#4): the probe must learn a save's schema
 * version — and detect a truncated / garbage file — by reading the fixed 100-byte SQLite header
 * **without opening the database**, so a NEWER-than-supported save is recognised instead of triggering
 * a downgrade-open crash.
 *
 * Two complementary angles:
 *  - hand-written byte buffers exercise the exact format contract (16-byte magic, 4-byte big-endian
 *    `user_version` at offset 60) with full control over each byte;
 *  - one genuinely created file-backed SQLite database proves the probe parses a *real* header.
 */
class SaveSchemaProbeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val probe = SaveSchemaProbe()

    // --- NoFile: nothing to inspect ---------------------------------------------------------------------

    @Test
    fun `a null file probes as NoFile`() {
        assertEquals(SaveSchemaProbeResult.NoFile, probe.probe(null))
    }

    @Test
    fun `a missing file probes as NoFile`() {
        val missing = File(tempFolder.root, "does-not-exist.db")
        assertEquals(SaveSchemaProbeResult.NoFile, probe.probe(missing))
    }

    @Test
    fun `a zero-byte file probes as NoFile`() {
        val empty = tempFolder.newFile("empty.db")
        assertEquals("an empty file is a fresh install, not corruption", SaveSchemaProbeResult.NoFile, probe.probe(empty))
    }

    // --- Corrupt: present but not a readable SQLite header ----------------------------------------------

    @Test
    fun `a truncated file shorter than the header probes as Corrupt`() {
        val truncated = tempFolder.newFile("truncated.db")
        truncated.writeBytes(ByteArray(50) { 0x55 })
        assertEquals(SaveSchemaProbeResult.Corrupt, probe.probe(truncated))
    }

    @Test
    fun `a full-length file with garbage magic probes as Corrupt`() {
        val garbage = tempFolder.newFile("garbage.db")
        garbage.writeBytes(ByteArray(100) { 0x7A })
        assertEquals(SaveSchemaProbeResult.Corrupt, probe.probe(garbage))
    }

    @Test
    fun `a file whose magic is right but missing the NUL terminator probes as Corrupt`() {
        // First 15 bytes match "SQLite format 3" but byte 15 is 'X' instead of NUL.
        val header = headerWithVersion(1)
        header[15] = 'X'.code.toByte()
        val file = tempFolder.newFile("bad-terminator.db")
        file.writeBytes(header)
        assertEquals(SaveSchemaProbeResult.Corrupt, probe.probe(file))
    }

    // --- Version: magic OK, user_version parsed from offset 60 (big-endian) -----------------------------

    @Test
    fun `a valid header reports the user_version at offset 60`() {
        val file = tempFolder.newFile("v22.db")
        file.writeBytes(headerWithVersion(22))
        assertEquals(SaveSchemaProbeResult.Version(22L), probe.probe(file))
    }

    @Test
    fun `a newer-than-supported user_version is read verbatim`() {
        val file = tempFolder.newFile("v99.db")
        file.writeBytes(headerWithVersion(99))
        assertEquals("a newer save's version must be surfaced, not clamped", SaveSchemaProbeResult.Version(99L), probe.probe(file))
    }

    @Test
    fun `the user_version is decoded as a 4-byte big-endian integer`() {
        // 0x01020304 spread across offsets 60..63 in big-endian order must decode to 16909060.
        val header = headerWithVersion(0)
        header[60] = 0x01
        header[61] = 0x02
        header[62] = 0x03
        header[63] = 0x04
        val file = tempFolder.newFile("be.db")
        file.writeBytes(header)
        assertEquals(SaveSchemaProbeResult.Version(0x01020304L), probe.probe(file))
    }

    // --- A REAL SQLite database file --------------------------------------------------------------------

    @Test
    fun `a real current-version SQLite database probes as its stored version`() {
        val dbFile = tempFolder.newFile("real.db")
        val version = OrbitalFrontier.Schema.version
        writeRealDatabase(dbFile, version)

        val result = probe.probe(dbFile)

        assertTrue("a genuine SQLite file must probe as a Version, got $result", result is SaveSchemaProbeResult.Version)
        assertEquals(version, (result as SaveSchemaProbeResult.Version).userVersion)
    }

    private companion object {
        /** Build a 100-byte SQLite header with a valid magic and [version] written big-endian at offset 60. */
        fun headerWithVersion(version: Int): ByteArray {
            val header = ByteArray(100)
            val magic = "SQLite format 3".toByteArray(Charsets.US_ASCII)
            magic.copyInto(header, 0)
            header[15] = 0
            header[60] = (version ushr 24 and 0xFF).toByte()
            header[61] = (version ushr 16 and 0xFF).toByte()
            header[62] = (version ushr 8 and 0xFF).toByte()
            header[63] = (version and 0xFF).toByte()
            return header
        }

        /** Create a genuine file-backed SQLite DB, install the schema, and stamp [version] into its header. */
        fun writeRealDatabase(
            file: File,
            version: Long,
        ) {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                OrbitalFrontier.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = $version", 0)
            } finally {
                driver.close()
            }
        }
    }
}
