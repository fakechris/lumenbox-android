package com.lumenopen.lumenbox.conversation

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeFence(val language: String?, val code: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
}

/**
 * Lightweight deterministic Markdown block splitter for streaming agent transcripts (`INV-2257`).
 * Safely handles unclosed fenced code blocks while tokens are still streaming in.
 */
object MarkdownBlockParser {
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
                if (curTrimmed.isEmpty() ||
                    curTrimmed.startsWith("```") ||
                    curTrimmed.startsWith("- ") ||
                    curTrimmed.startsWith("* ") ||
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
}
