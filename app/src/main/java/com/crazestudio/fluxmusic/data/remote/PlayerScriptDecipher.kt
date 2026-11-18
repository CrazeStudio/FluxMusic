package com.crazestudio.fluxmusic.data.remote

/**
 * Constrained interpreter for common YouTube signature-array operations.
 * It never evaluates downloaded JavaScript; unknown player layouts fail closed.
 */
internal object PlayerScriptDecipher {
    private sealed interface Operation {
        data object Reverse : Operation
        data class DropFront(val count: Int) : Operation
        data class SwapFirst(val index: Int) : Operation
    }

    private data class FunctionBlock(val name: String, val body: String)

    fun decipher(signature: String, playerScript: String): String? {
        if (signature.isBlank() || playerScript.isBlank()) return null
        val function = findSignatureFunction(playerScript) ?: return null
        val operations = parseOperations(playerScript, function) ?: return null
        if (operations.isEmpty()) return null
        val chars = signature.toMutableList()
        return runCatching {
            operations.forEach { operation ->
                when (operation) {
                    Operation.Reverse -> chars.reverse()
                    is Operation.DropFront -> repeat(operation.count.coerceAtMost(chars.size)) {
                        if (chars.isNotEmpty()) chars.removeAt(0)
                    }
                    is Operation.SwapFirst -> if (chars.isNotEmpty()) {
                        val index = Math.floorMod(operation.index, chars.size)
                        val first = chars[0]
                        chars[0] = chars[index]
                        chars[index] = first
                    }
                }
            }
            chars.joinToString("")
        }.getOrNull()
    }

    private fun findSignatureFunction(script: String): FunctionBlock? {
        val declaration = Regex(
            """(?:var\s+)?([$\w]+)\s*=\s*function\s*\(\s*([$\w]+)\s*\)\s*\{"""
        )
        val candidates = declaration.findAll(script).toList()
        val referencedName = Regex("""\.sig\|\|\s*([$\w]+)\s*\(""")
            .find(script)?.groupValues?.getOrNull(1)

        for (match in candidates) {
            val name = match.groupValues[1]
            val body = readBraceBlock(script, match.range.last) ?: continue
            if (name == referencedName && body.contains("split") && body.contains("join")) {
                return FunctionBlock(name, body)
            }
        }
        for (match in candidates) {
            val name = match.groupValues[1]
            val body = readBraceBlock(script, match.range.last) ?: continue
            if (body.contains("split") && body.contains("join")) return FunctionBlock(name, body)
        }
        return null
    }

    private fun parseOperations(script: String, function: FunctionBlock): List<Operation>? {
        val firstHelper = Regex(
            """([$\w]+)\.([$\w]+)\(\s*[a-zA-Z_$][\w$]*(?:\s*,\s*\d+)?\s*\)"""
        ).find(function.body)?.groupValues?.getOrNull(1)
        val helpers = if (firstHelper == null) emptyMap() else parseHelperObject(script, firstHelper)
        val positioned = mutableListOf<Pair<Int, Operation>>()

        val helperCall = Regex(
            """([$\w]+)\.([$\w]+)\(\s*[a-zA-Z_$][\w$]*(?:\s*,\s*(\d+))?\s*\)"""
        )
        helperCall.findAll(function.body).forEach { call ->
            if (call.groupValues[1] != firstHelper) return@forEach
            val template = helpers[call.groupValues[2]] ?: return@forEach
            val argument = call.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            val op = when (template) {
                Operation.Reverse -> Operation.Reverse
                is Operation.DropFront -> Operation.DropFront(argument.takeIf { it > 0 } ?: template.count)
                is Operation.SwapFirst -> Operation.SwapFirst(argument.takeIf { it > 0 } ?: template.index)
            }
            positioned += call.range.first to op
        }

        Regex("""[a-zA-Z_$][\w$]*\.reverse\(\)""").findAll(function.body).forEach {
            positioned += it.range.first to Operation.Reverse
        }
        Regex("""[a-zA-Z_$][\w$]*\.splice\(0\s*,\s*(\d+)\)""").findAll(function.body).forEach {
            positioned += it.range.first to Operation.DropFront(it.groupValues[1].toInt())
        }

        return positioned.sortedBy { it.first }.map { it.second }.takeIf { it.isNotEmpty() }
    }

    private fun parseHelperObject(script: String, name: String): Map<String, Operation> {
        val assignmentPattern = """(?:var\s+)?""" + Regex.escape(name) + """\s*=\s*\{"""
        val assignment = Regex(assignmentPattern).find(script) ?: return emptyMap()
        val objectBody = readBraceBlock(script, assignment.range.last + 1) ?: return emptyMap()
        val methodDeclaration = Regex("""([$\w]+)\s*:\s*function\s*\([^)]*\)\s*\{""")
        val methods = LinkedHashMap<String, Operation>()
        methodDeclaration.findAll(objectBody).forEach { match ->
            val body = readBraceBlock(objectBody, match.range.last) ?: return@forEach
            val op = when {
                body.contains(".reverse(") -> Operation.Reverse
                Regex("""\.splice\(\s*0\s*,""").containsMatchIn(body) -> {
                    val count = Regex("""\.splice\(\s*0\s*,\s*(\d+)""")
                        .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    Operation.DropFront(count)
                }
                body.contains("[0]") && body.contains("%") && body.contains("=") ->
                    Operation.SwapFirst(0)
                else -> null
            }
            if (op != null) methods[match.groupValues[1]] = op
        }
        return methods
    }

    private fun readBraceBlock(source: String, from: Int): String? {
        val start = source.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var i = start
        while (i < source.length) {
            val current = source[i]
            val next = source.getOrNull(i + 1)
            if (lineComment) {
                if (current == '\n') lineComment = false
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false
                    i++
                }
            } else if (quote != null) {
                if (escaped) escaped = false
                else if (current == '\\') escaped = true
                else if (current == quote) quote = null
            } else {
                when {
                    current == '/' && next == '/' -> lineComment = true
                    current == '/' && next == '*' -> blockComment = true
                    current == '"' || current == '\'' || current.code == 96 -> quote = current
                    current == '{' -> depth++
                    current == '}' -> {
                        depth--
                        if (depth == 0) return source.substring(start + 1, i)
                    }
                }
            }
            i++
        }
        return null
    }
}
