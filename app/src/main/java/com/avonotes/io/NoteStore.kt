package com.avonotes.io

import android.content.Context
import com.avonotes.model.NoteDocument
import com.avonotes.model.PdfDoc
import com.avonotes.model.PdfPageItem
import com.avonotes.model.Stroke
import com.avonotes.model.Tool
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Notes live in app storage, one folder each:
 *
 *   notes/<uuid>/note.avn    binary content (see [writeNote])
 *   notes/<uuid>/title.txt   UTF-8 title (separate so renaming/listing is cheap)
 *   notes/<uuid>/thumb.webp  library preview (regenerated when stale; not exported)
 *   notes/<uuid>/pdf/<id>.pdf  embedded PDFs
 *   notes/<uuid>/group.txt   id of the group the note is in (absent = no group)
 *   groups.tsv               one "id<TAB>name" line per group
 *
 * Groups are one level deep. A note only stores its group's id, so renaming a group
 * touches a single file. An exported ".avonote" file is a zip of the note folder
 * (without its group).
 */
class NoteStore(context: Context) {
    val root = File(context.filesDir, "notes").apply { mkdirs() }
    private val groupsFile = File(context.filesDir, GROUPS_FILE)

    class NoteInfo(val dir: File, val title: String, val modified: Long, val groupId: String?)
    data class Group(val id: String, val name: String)

    fun list(): List<NoteInfo> =
        (root.listFiles() ?: emptyArray())
            .filter { File(it, NOTE_FILE).exists() }
            .map { NoteInfo(it, readTitle(it), File(it, NOTE_FILE).lastModified(), readGroup(it)) }
            .sortedByDescending { it.modified }

    fun create(title: String, groupId: String? = null): File {
        val dir = File(root, UUID.randomUUID().toString())
        File(dir, PDF_DIR).mkdirs()
        writeTitle(dir, title)
        if (groupId != null) setGroup(dir, groupId)
        save(dir, NoteDocument().apply { viewZoom = 0f })
        return dir
    }

    // ---------------------------------------------------------------- groups

    fun groups(): List<Group> =
        runCatching { groupsFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else Group(line.substring(0, tab), line.substring(tab + 1))
            }
            .sortedBy { it.name.lowercase() }

    private fun writeGroups(groups: List<Group>) = atomicWrite(groupsFile) { out ->
        // Names are single-line (tabs/newlines replaced) so the file stays parseable.
        val text = groups.joinToString("\n") { "${it.id}\t${it.name.replace(Regex("[\\t\\r\\n]"), " ")}" }
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    fun createGroup(name: String): Group {
        val g = Group(UUID.randomUUID().toString(), name)
        writeGroups(groups() + g)
        return g
    }

    fun renameGroup(id: String, name: String) =
        writeGroups(groups().map { if (it.id == id) it.copy(name = name) else it })

    /** Removes the group; its notes are kept and simply leave the group. */
    fun deleteGroup(id: String) {
        for (n in list()) if (n.groupId == id) setGroup(n.dir, null)
        writeGroups(groups().filter { it.id != id })
    }

    fun readGroup(dir: File): String? =
        runCatching { File(dir, GROUP_FILE).readText().trim() }.getOrNull()?.ifEmpty { null }

    fun setGroup(dir: File, groupId: String?) {
        val f = File(dir, GROUP_FILE)
        if (groupId == null) f.delete() else atomicWrite(f) { it.write(groupId.toByteArray(Charsets.UTF_8)) }
    }

    fun readTitle(dir: File): String =
        runCatching { File(dir, TITLE_FILE).readText().trim() }.getOrDefault("")

    fun writeTitle(dir: File, title: String) = atomicWrite(File(dir, TITLE_FILE)) {
        it.write(title.toByteArray(Charsets.UTF_8))
    }

    fun delete(dir: File) {
        if (dir.parentFile == root) dir.deleteRecursively()
    }

    fun pdfDir(dir: File) = File(dir, PDF_DIR).apply { mkdirs() }

    // ---------------------------------------------------------------- thumbnails

    fun thumbFile(dir: File) = File(dir, THUMB_FILE)

    /** True if the preview is missing or older than the note content. */
    fun thumbStale(dir: File): Boolean {
        val t = thumbFile(dir)
        return !t.exists() || t.lastModified() < File(dir, NOTE_FILE).lastModified()
    }

    /** Renders and stores the preview for [doc] (call off the UI thread). */
    fun writeThumb(dir: File, doc: NoteDocument): android.graphics.Bitmap {
        val bmp = Thumbnailer.render(doc, pdfDir(dir))
        atomicWrite(thumbFile(dir)) { bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        return bmp
    }

    // ---------------------------------------------------------------- load / save

    fun load(dir: File): NoteDocument {
        val doc = DataInputStream(BufferedInputStream(File(dir, NOTE_FILE).inputStream(), 1 shl 16)).use { readNote(it) }
        doc.title = readTitle(dir)
        // Drop PDFs no longer referenced (e.g. imported, then undone, then closed).
        val used = doc.pdfDocs.map { it.fileName }.toSet()
        pdfDir(dir).listFiles()?.forEach { if (it.name !in used) it.delete() }
        return doc
    }

    fun save(dir: File, doc: NoteDocument) = atomicWrite(File(dir, NOTE_FILE)) { out ->
        DataOutputStream(BufferedOutputStream(out, 1 shl 16)).let { writeNote(it, doc); it.flush() }
    }

    private fun atomicWrite(target: File, write: (FileOutputStream) -> Unit) {
        // Unique temp name: the library and the editor may both write a preview at once.
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(tmp).use { fos ->
            write(fos)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("rename failed: $target") }
    }

    // ---------------------------------------------------------------- export / import

    fun export(dir: File, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for (name in listOf(NOTE_FILE, TITLE_FILE)) {
                val f = File(dir, name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            // Only PDFs the saved note references.
            val doc = load(dir)
            for (d in doc.pdfDocs) {
                val f = File(pdfDir(dir), d.fileName)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("$PDF_DIR/${d.fileName}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    /** Imports an exported note as a new note. Returns its folder. */
    fun import(input: InputStream): File {
        val dir = File(root, UUID.randomUUID().toString())
        File(dir, PDF_DIR).mkdirs()
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val target = safeTarget(dir, e.name)
                    if (target != null && !e.isDirectory) target.outputStream().use { zip.copyTo(it) }
                    zip.closeEntry()
                }
            }
            load(dir) // validate
            return dir
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    /** Only accept the known file layout; rejects path traversal and anything unexpected. */
    private fun safeTarget(dir: File, name: String): File? {
        if (name == NOTE_FILE || name == TITLE_FILE) return File(dir, name)
        if (name.startsWith("$PDF_DIR/")) {
            val leaf = name.removePrefix("$PDF_DIR/")
            if (leaf.isNotEmpty() && SAFE_NAME.matches(leaf)) return File(File(dir, PDF_DIR), leaf)
        }
        return null
    }

    companion object {
        const val NOTE_FILE = "note.avn"
        const val TITLE_FILE = "title.txt"
        const val THUMB_FILE = "thumb.webp"
        const val GROUP_FILE = "group.txt"
        const val GROUPS_FILE = "groups.tsv"
        const val PDF_DIR = "pdf"
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+") // no slashes, so no traversal
        private const val MAGIC = 0x41564E31 // "AVN1"
        private const val VERSION = 1

        /*
         * note.avn, big-endian:
         *   int magic, int version
         *   float viewX, viewY, viewZoom
         *   int docCount   { utf id, utf fileName, utf displayName }
         *   int pageCount  { utf docId, int pageIndex, float x, y, w, h }
         *   int strokeCount{ byte tool, int color, float width, int n, float[n*3] (x, y, pressure) }
         */
        fun writeNote(out: DataOutputStream, doc: NoteDocument) {
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeFloat(doc.viewX); out.writeFloat(doc.viewY); out.writeFloat(doc.viewZoom)
            out.writeInt(doc.pdfDocs.size)
            for (d in doc.pdfDocs) { out.writeUTF(d.id); out.writeUTF(d.fileName); out.writeUTF(d.displayName) }
            out.writeInt(doc.pages.size)
            for (p in doc.pages) {
                out.writeUTF(p.docId); out.writeInt(p.pageIndex)
                out.writeFloat(p.x); out.writeFloat(p.y); out.writeFloat(p.w); out.writeFloat(p.h)
            }
            out.writeInt(doc.strokes.size)
            var buf = ByteBuffer.allocate(0)
            for (s in doc.strokes) {
                out.writeByte(s.tool.code); out.writeInt(s.color); out.writeFloat(s.width)
                out.writeInt(s.pointCount)
                val bytes = s.points.size * 4
                if (buf.capacity() < bytes) buf = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.clear()
                buf.asFloatBuffer().put(s.points)
                out.write(buf.array(), 0, bytes)
            }
        }

        fun readNote(input: DataInputStream): NoteDocument {
            if (input.readInt() != MAGIC) throw IOException("not an AvoNotes file")
            val version = input.readInt()
            if (version > VERSION) throw IOException("note was saved by a newer version")
            val doc = NoteDocument()
            doc.viewX = input.readFloat(); doc.viewY = input.readFloat(); doc.viewZoom = input.readFloat()
            repeat(input.readInt()) {
                doc.pdfDocs.add(PdfDoc(input.readUTF(), input.readUTF(), input.readUTF()))
            }
            repeat(input.readInt()) {
                doc.pages.add(
                    PdfPageItem(
                        input.readUTF(), input.readInt(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                    )
                )
            }
            val strokeCount = input.readInt()
            doc.strokes.ensureCapacity(strokeCount)
            var bytes = ByteArray(0)
            repeat(strokeCount) {
                val tool = Tool.fromCode(input.readByte().toInt())
                val color = input.readInt()
                val width = input.readFloat()
                val n = input.readInt()
                if (n < 0 || n > 10_000_000) throw IOException("corrupt stroke")
                val len = n * 3 * 4
                if (bytes.size < len) bytes = ByteArray(len)
                input.readFully(bytes, 0, len)
                val pts = FloatArray(n * 3)
                ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(pts)
                doc.strokes.add(Stroke(tool, color, width, pts))
            }
            return doc
        }
    }
}
