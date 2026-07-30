package com.prismai.llmhost.engine

import org.json.JSONArray
import org.json.JSONObject

object GrammarCompiler {

    /**
     * Compiles standard JSON GBNF grammar rules that enforce valid JSON structure during sampling.
     */
    fun compileJsonGrammar(): String {
        return """
            root   ::= object
            value  ::= object | array | string | number | boolean | "null"
            object ::= "{" ws (pair ("," ws pair)*)? "}"
            pair   ::= string ":" ws value
            array  ::= "[" ws (value ("," ws value)*)? "]"
            string ::= "\"" ([^"\\] | "\\" ["\\/bfnrt] | "\\u" [0-9a-fA-F]{4})* "\""
            number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
            boolean ::= "true" | "false"
            ws     ::= [ \t\n\r]*
        """.trimIndent()
    }

    /**
     * Compiles a tool call constraint grammar targeting standard function calling JSON structure:
     * {"tool": "<tool_name>", "arguments": {...}}
     */
    fun compileToolCallGrammar(allowedToolNames: List<String> = emptyList()): String {
        val toolNamesRule = if (allowedToolNames.isNotEmpty()) {
            allowedToolNames.joinToString(" | ") { "\"$it\"" }
        } else {
            "\"" + """([^"\\] | "\\" ["\\/bfnrt])*""" + "\""
        }

        return """
            root   ::= "{" ws "\"tool\"" ":" ws $toolNamesRule "," ws "\"arguments\"" ":" ws object "}"
            object ::= "{" ws (pair ("," ws pair)*)? "}"
            pair   ::= string ":" ws value
            value  ::= object | array | string | number | boolean | "null"
            array  ::= "[" ws (value ("," ws value)*)? "]"
            string ::= "\"" ([^"\\] | "\\" ["\\/bfnrt] | "\\u" [0-9a-fA-F]{4})* "\""
            number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
            boolean ::= "true" | "false"
            ws     ::= [ \t\n\r]*
        """.trimIndent()
    }
}
