package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** File system tools: read, write, edit, delete, glob, grep, list. */
class LiteFileTools(private val mapper: ObjectMapper) {

    companion object {
        private const val DEFAULT_READ_LIMIT = 2_000
        private const val MAX_LINE_LEN = 2_000
        private const val MAX_BYTES = 50 * 1024
        private const val MAX_OUTPUT = 8_000
        private val BINARY_EXTS = setOf("zip", "tar", "gz", "exe", "dll", "so",
            "class", "jar", "war", "7z", "bin", "dat", "png", "jpg", "jpeg",
            "gif", "bmp", "ico", "pdf", "mp3", "mp4", "avi")
    }

    // ── read ──────────────────────────────────────────────────────────────────

    fun read(args: JsonNode, workspaceRoot: String): String {
        val pathStr = args.path("filePath").asText("")
        val offset  = args.path("offset").asInt(0).coerceAtLeast(0)
        val limit   = args.path("limit").asInt(DEFAULT_READ_LIMIT).coerceAtLeast(1)

        val file = resolve(workspaceRoot, pathStr)
        if (!file.exists()) throw RuntimeException("File not found: $pathStr")
        if (isBinary(file)) throw RuntimeException("Cannot read binary file: $pathStr")

        val allLines = file.readLines(StandardCharsets.UTF_8)
        val sb = StringBuilder("<file>\n")
        var bytes = 0
        var truncatedByBytes = false
        val end = minOf(allLines.size, offset + limit)

        for (i in offset until end) {
            val line = allLines[i].let { if (it.length > MAX_LINE_LEN) it.take(MAX_LINE_LEN) + "..." else it }
            val size = line.toByteArray(StandardCharsets.UTF_8).size + 1
            if (bytes + size > MAX_BYTES) { truncatedByBytes = true; break }
            sb.append(String.format("%05d| %s\n", i + 1, line))
            bytes += size
        }

        val lastLine = offset + (sb.lines().size - 1)  // approximate
        val totalLines = allLines.size
        when {
            truncatedByBytes -> sb.append("\n\n(Truncated at $MAX_BYTES bytes. Use 'offset' to continue.)")
            totalLines > offset + limit -> sb.append("\n\n(More lines exist. Use 'offset' to read beyond line ${offset + limit}.)")
            else -> sb.append("\n\n(End of file – $totalLines lines total)")
        }
        sb.append("\n</file>")
        return sb.toString()
    }

    // ── write ─────────────────────────────────────────────────────────────────

    fun write(args: JsonNode, workspaceRoot: String): String {
        val pathStr = args.path("filePath").asText("")
        val content = args.path("content").asText("")
        val file = resolve(workspaceRoot, pathStr)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
        refreshVfsSync(file.absolutePath)
        return "Written: $pathStr (${content.lines().size} lines)"
    }

    // ── edit ──────────────────────────────────────────────────────────────────

    fun edit(args: JsonNode, workspaceRoot: String): String {
        val pathStr    = args.path("filePath").asText("")
        val oldString  = args.path("oldString").asText("")
        val newString  = args.path("newString").asText("")
        val replaceAll = args.path("replaceAll").asBoolean(false)

        val file = resolve(workspaceRoot, pathStr)

        if (!file.exists()) {
            if (oldString.isNotEmpty()) throw RuntimeException("File not found: $pathStr. To create, leave oldString empty.")
            file.parentFile?.mkdirs()
            file.writeText(newString, StandardCharsets.UTF_8)
            refreshVfsSync(file.absolutePath)
            return "Created: $pathStr"
        }

        if (oldString == newString) throw RuntimeException("oldString and newString must differ")

        val original = file.readText(StandardCharsets.UTF_8)
        val matches  = findMatches(original, oldString)
        if (matches.isEmpty()) throw RuntimeException("oldString not found in file (exact or trimmed match)")
        if (!replaceAll && matches.size > 1) throw RuntimeException(
            "oldString found ${matches.size} times — provide more context to uniquely identify the match"
        )

        val updated = if (replaceAll) matches.fold(original) { acc, m -> acc.replace(m, newString) }
                      else original.replace(matches[0], newString)

        file.writeText(updated, StandardCharsets.UTF_8)
        refreshVfsSync(file.absolutePath)
        return "Updated: $pathStr"
    }

    /** Fuzzy-match: trim each line when comparing, return the original substring. */
    private fun findMatches(content: String, find: String): List<String> {
        if (find.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val origLines   = content.split("\n", limit = Int.MAX_VALUE)
        val searchLines = find.split("\n", limit = Int.MAX_VALUE)
            .let { lines -> if (lines.last().isEmpty()) lines.dropLast(1) else lines }
        if (searchLines.isEmpty()) return results

        for (i in 0..origLines.size - searchLines.size) {
            val matches = searchLines.indices.all { j ->
                origLines[i + j].trim() == searchLines[j].trim()
            }
            if (matches) {
                // Reconstruct the exact original substring
                var start = origLines.take(i).sumOf { it.length + 1 }
                var end   = start
                for (k in searchLines.indices) {
                    end += origLines[i + k].length
                    if (k < searchLines.size - 1) end += 1
                }
                results.add(content.substring(start, end))
            }
        }
        return results
    }

    // ── delete ────────────────────────────────────────────────────────────────

    fun delete(args: JsonNode, workspaceRoot: String): String {
        val pathStr = args.path("filePath").asText("")
        val file = resolve(workspaceRoot, pathStr)
        if (!file.exists()) throw RuntimeException("File not found: $pathStr")
        file.delete()
        refreshVfsSync(file.absolutePath)
        return "Deleted: $pathStr"
    }

    // ── glob ──────────────────────────────────────────────────────────────────

    fun glob(args: JsonNode, workspaceRoot: String): String {
        val pattern    = args.path("pattern").asText("").ifBlank { throw IllegalArgumentException("pattern required") }
        val searchRoot = resolve(workspaceRoot, args.path("path").asText(workspaceRoot))

        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val results = mutableListOf<Pair<Long, String>>()

        Files.walk(searchRoot.toPath()).use { stream ->
            stream.filter { p ->
                val rel = searchRoot.toPath().relativize(p)
                matcher.matches(rel) || matcher.matches(p.fileName)
            }.forEach { p ->
                results += Pair(p.toFile().lastModified(), p.toString())
            }
        }

        results.sortByDescending { it.first }
        val lines = results.take(100).map { it.second }
        return if (lines.isEmpty()) "No files matched pattern: $pattern"
               else lines.joinToString("\n")
    }

    // ── grep ──────────────────────────────────────────────────────────────────

    fun grep(args: JsonNode, workspaceRoot: String): String {
        val patternStr = args.path("pattern").asText("").ifBlank { throw IllegalArgumentException("pattern required") }
        val searchRoot = resolve(workspaceRoot, args.path("path").asText(workspaceRoot))
        val include    = args.path("include").asText("").let { if (it.isBlank()) null else it }

        val regex = try { Pattern.compile(patternStr) }
                    catch (_: PatternSyntaxException) { Pattern.compile(Pattern.quote(patternStr)) }

        val includeGlob = include?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<Pair<Long, String>>()

        Files.walk(searchRoot.toPath()).use { stream ->
            stream.filter { p ->
                Files.isRegularFile(p) &&
                !isBinary(p.toFile()) &&
                (includeGlob == null || includeGlob.matches(p.fileName))
            }.forEach { p ->
                try {
                    val mtime = p.toFile().lastModified()
                    p.toFile().bufferedReader(StandardCharsets.UTF_8).use { br ->
                        var lineNum = 0
                        br.forEachLine { line ->
                            lineNum++
                            if (regex.matcher(line).find()) {
                                results += Pair(mtime, "${p}:$lineNum: $line")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        results.sortByDescending { it.first }
        val lines = results.take(100).map { it.second }
        return if (lines.isEmpty()) "No matches for pattern: $patternStr"
               else lines.joinToString("\n").take(MAX_OUTPUT)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    fun list(args: JsonNode, workspaceRoot: String): String {
        val dir = resolve(workspaceRoot, args.path("path").asText(workspaceRoot))
        if (!dir.isDirectory) throw RuntimeException("Not a directory: ${dir.path}")

        val ignorePatterns = buildList {
            if (args.has("ignore") && args.path("ignore").isArray) {
                args.path("ignore").forEach { add(it.asText("")) }
            }
            // Default ignores
            addAll(listOf(".git", "node_modules", ".gradle", "build", "out", ".idea", "__pycache__"))
        }

        fun shouldIgnore(name: String) = ignorePatterns.any { p -> name == p || name.startsWith(".") && p == ".*" }

        val sb = StringBuilder()
        fun walk(f: File, indent: String) {
            if (sb.length > MAX_OUTPUT) return
            val children = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (child in children) {
                if (shouldIgnore(child.name)) continue
                sb.append("$indent${child.name}${if (child.isDirectory) "/" else ""}\n")
                if (child.isDirectory) walk(child, "$indent  ")
            }
        }

        walk(dir, "")
        return if (sb.isEmpty()) "Empty directory" else sb.toString().trim()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun refreshVfsSync(absPath: String) {
        try {
            val normalized = absPath.replace('\\', '/')
            ApplicationManager.getApplication().invokeAndWait {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)?.refresh(false, false)
            }
        } catch (_: Exception) {}
    }

    private fun resolve(workspaceRoot: String, path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(workspaceRoot, path)
    }

    private fun isBinary(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in BINARY_EXTS) return true
        val bytes = try { file.readBytes().take(4096).toByteArray() } catch (_: Exception) { return false }
        if (bytes.isEmpty()) return false
        return bytes.any { it == 0.toByte() } ||
               bytes.count { (it < 9 || it in 14..31) } > bytes.size * 0.3
    }
}
