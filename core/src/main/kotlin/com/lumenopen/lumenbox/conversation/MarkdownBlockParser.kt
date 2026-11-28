package com.lumenopen.lumenbox.conversation

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeFence(val language: String?, val code: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

enum class CodeTokenKind {
    PLAIN,
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
}

data class CodeSyntaxSpan(
    val text: String,
    val kind: CodeTokenKind,
)

/**
 * Lightweight deterministic Markdown block splitter and code syntax highlighter for streaming
 * agent transcripts (`INV-2257`, `INV-2271`).
 *
 * Safely handles unclosed fenced code blocks and partial pipe tables while tokens stream in.
 */
object MarkdownBlockParser {

    private val COMMON_KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "sealed", "data", "enum",
        "when", "if", "else", "for", "while", "return", "suspend", "private", "public",
        "internal", "override", "import", "package", "try", "catch", "finally", "throw",
        "true", "false", "null", "const", "let", "function", "async", "await", "export",
        "default", "from", "def", "None", "True", "False", "self", "struct", "impl",
        "pub", "fn", "match", "mut", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE",
    )

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = markdown.lines()
        var idx = 0

        while (idx < lines.size) {
            val line = lines[idx]
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                idx += 1
                val codeLines = mutableListOf<String>()
                while (idx < lines.size && !lines[idx].trimStart().startsWith("```")) {
                    codeLines.add(lines[idx])
                    idx += 1
                }
                if (idx < lines.size && lines[idx].trimStart().startsWith("```")) {
                    idx += 1
                }
                blocks.add(MarkdownBlock.CodeFence(language = lang, code = codeLines.joinToString("\n")))
                continue
            }

            if (trimmed.isEmpty()) {
                idx += 1
                continue
            }

            val headingHashes = trimmed.takeWhile { it == '#' }
            if (headingHashes.length in 1..6 && trimmed.getOrNull(headingHashes.length) == ' ') {
                blocks.add(
                    MarkdownBlock.Heading(
                        level = headingHashes.length,
                        text = trimmed.substring(headingHashes.length + 1).trim(),
                    )
                )
                idx += 1
                continue
            }

            // Markdown pipe table: header row followed by separator row `| --- | --- |`
            if (isTableCandidateRow(trimmed) &&
                idx + 1 < lines.size &&
                isTableSeparatorRow(lines[idx + 1].trim())
            ) {
                val headers = splitTableCells(trimmed)
                idx += 2
                val rows = mutableListOf<List<String>>()
                while (idx < lines.size && isTableCandidateRow(lines[idx].trimStart())) {
                    rows.add(splitTableCells(lines[idx].trimStart()))
                    idx += 1
                }
                blocks.add(MarkdownBlock.Table(headers = headers, rows = rows))
                continue
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                val items = mutableListOf<String>()
                while (idx < lines.size) {
                    val bulletTrimmed = lines[idx].trimStart()
                    when {
                        bulletTrimmed.startsWith("- ") -> {
                            items.add(bulletTrimmed.removePrefix("- ").trim())
                            idx += 1
                        }
                        bulletTrimmed.startsWith("* ") -> {
                            items.add(bulletTrimmed.removePrefix("* ").trim())
                            idx += 1
                        }
                        else -> break
                    }
                }
                blocks.add(MarkdownBlock.BulletList(items))
                continue
            }

            val paragraphLines = mutableListOf<String>()
            while (idx < lines.size) {
                val cur = lines[idx]
                val curTrimmed = cur.trimStart()
                val nextIsTable = isTableCandidateRow(curTrimmed) &&
                    idx + 1 < lines.size &&
                    isTableSeparatorRow(lines[idx + 1].trim())
                if (curTrimmed.isEmpty() ||
                    curTrimmed.startsWith("```") ||
                    curTrimmed.startsWith("- ") ||
                    curTrimmed.startsWith("* ") ||
                    nextIsTable ||
                    (curTrimmed.takeWhile { it == '#' }.length in 1..6 && curTrimmed.getOrNull(curTrimmed.takeWhile { it == '#' }.length) == ' ')
                ) {
                    break
                }
                paragraphLines.add(cur)
                idx += 1
            }
            if (paragraphLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
            }
        }

        return blocks
    }

    /**
     * Tokenizes source [code] into deterministic [CodeSyntaxSpan] runs without external libraries,
     * preserving exact source characters while highlighting comments, strings, numbers, and keywords.
     */
    fun tokenizeCode(code: String, language: String? = null): List<CodeSyntaxSpan> {
        if (code.isEmpty()) return emptyList()
        val spans = mutableListOf<CodeSyntaxSpan>()
        val isHashCommentLang = language?.lowercase() in setOf("py", "python", "sh", "bash", "zsh", "yaml", "yml", "toml", "ruby")
        var i = 0
        val n = code.length
        val plainBuf = StringBuilder()

        fun flushPlain() {
            if (plainBuf.isNotEmpty()) {
                spans.add(CodeSyntaxSpan(plainBuf.toString(), CodeTokenKind.PLAIN))
                plainBuf.setLength(0)
            }
        }

        while (i < n) {
            val ch = code[i]
            // Line comment `//...` or `#...`
            if ((ch == '/' && i + 1 < n && code[i + 1] == '/') || (isHashCommentLang && ch == '#')) {
                flushPlain()
                val start = i
                while (i < n && code[i] != '\n') i += 1
                spans.add(CodeSyntaxSpan(code.substring(start, i), CodeTokenKind.COMMENT))
                continue
            }
            // Block comment `/* ... */`
            if (ch == '/' && i + 1 < n && code[i + 1] == '*') {
                flushPlain()
                val start = i
                i += 2
                while (i < n && !(code[i - 1] == '*' && code[i] == '/')) i += 1
                if (i < n) i += 1
                spans.add(CodeSyntaxSpan(code.substring(start, i), CodeTokenKind.COMMENT))
                continue
            }
            // String literal `"..."` or `'...'`
            if (ch == '"' || ch == '\'') {
                flushPlain()
                val quote = ch
                val start = i
                i += 1
                while (i < n && code[i] != quote && code[i] != '\n') {
                    if (code[i] == '\\' && i + 1 < n) i += 2 else i += 1
                }
                if (i < n && code[i] == quote) i += 1
                spans.add(CodeSyntaxSpan(code.substring(start, i), CodeTokenKind.STRING))
                continue
            }
            // Identifier or keyword
            if (ch.isLetter() || ch == '_') {
                val start = i
                i += 1
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i += 1
                val word = code.substring(start, i)
                if (word in COMMON_KEYWORDS) {
                    flushPlain()
                    spans.add(CodeSyntaxSpan(word, CodeTokenKind.KEYWORD))
                } else {
                    plainBuf.append(word)
                }
                continue
            }
            // Numeric literal
            if (ch.isDigit() && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '_'))) {
                flushPlain()
                val start = i
                i += 1
                while (i < n && (code[i].isDigit() || code[i] == '.' || code[i] == '_')) i += 1
                spans.add(CodeSyntaxSpan(code.substring(start, i), CodeTokenKind.NUMBER))
                continue
            }
            plainBuf.append(ch)
            i += 1
        }
        flushPlain()
        return spans
    }

    private fun isTableCandidateRow(trimmed: String): Boolean =
        trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2

    private fun isTableSeparatorRow(trimmed: String): Boolean {
        if (!isTableCandidateRow(trimmed)) return false
        val cells = splitTableCells(trimmed)
        return cells.isNotEmpty() && cells.all { cell ->
            val clean = cell.trim().removePrefix(":").removeSuffix(":")
            clean.length >= 3 && clean.all { it == '-' }
        }
    }

    private fun splitTableCells(row: String): List<String> =
        row.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
}
