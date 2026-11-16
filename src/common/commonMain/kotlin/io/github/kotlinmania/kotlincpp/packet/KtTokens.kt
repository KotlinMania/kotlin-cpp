package io.github.kotlinmania.kotlincpp.packet

/**
 * Canonical Kotlin token and modifier IDs shared across [KotlinPacketBuilder],
 * [KotlinPacketEmitter], [UniversalSynTokenAssociation], and C++23 lowering passes.
 */
internal object KtTokens {
    // Trivia & comments
    const val WHITE_SPACE: Int = 1
    const val EOL_COMMENT_ID: Int = 2
    const val BLOCK_COMMENT_ID: Int = 3
    const val DOC_COMMENT_ID: Int = 4
    const val SHEBANG_COMMENT_ID: Int = 5

    // Literals
    const val INTEGER_LITERAL_ID: Int = 6
    const val FLOAT_LITERAL_ID: Int = 7
    const val CHARACTER_LITERAL_ID: Int = 8
    const val TRUE_KEYWORD_ID: Int = 9
    const val FALSE_KEYWORD_ID: Int = 10
    const val NULL_KEYWORD_ID: Int = 11

    // String template segments
    const val INTERPOLATION_PREFIX_ID: Int = 12
    const val OPEN_QUOTE_ID: Int = 13
    const val CLOSING_QUOTE_ID: Int = 14
    const val REGULAR_STRING_PART_ID: Int = 15
    const val DANGLING_NEWLINE_ID: Int = 16
    const val ESCAPE_SEQUENCE_ID: Int = 17
    const val SHORT_TEMPLATE_ENTRY_START_ID: Int = 18
    const val LONG_TEMPLATE_ENTRY_START_ID: Int = 19
    const val LONG_TEMPLATE_ENTRY_END_ID: Int = 20

    // Identifiers
    const val IDENTIFIER_ID: Int = 21
    const val FIELD_IDENTIFIER_ID: Int = 22

    // Delimiters & punctuation
    const val LPAR_ID: Int = 23
    const val RPAR_ID: Int = 24
    const val LBRACE_ID: Int = 25
    const val RBRACE_ID: Int = 26
    const val LBRACKET_ID: Int = 27
    const val RBRACKET_ID: Int = 28
    const val LT_ID: Int = 29
    const val GT_ID: Int = 30
    const val COMMA_ID: Int = 31
    const val DOT_ID: Int = 32
    const val COLON_ID: Int = 33
    const val SEMICOLON_ID: Int = 34
    const val DOUBLE_SEMICOLON_ID: Int = 35
    const val AT_ID: Int = 36
    const val HASH_ID: Int = 37
    const val SINGLE_QUOTE_ID: Int = 38
    const val RESERVED_DOTS_ID: Int = 39

    // Operators
    const val PLUSPLUS_ID: Int = 40
    const val MINUSMINUS_ID: Int = 41
    const val MUL_ID: Int = 42
    const val PLUS_ID: Int = 43
    const val MINUS_ID: Int = 44
    const val EXCL_ID: Int = 45
    const val DIV_ID: Int = 46
    const val PERC_ID: Int = 47
    const val LTEQ_ID: Int = 48
    const val GTEQ_ID: Int = 49
    const val EQEQEQ_ID: Int = 50
    const val ARROW_ID: Int = 51
    const val DOUBLE_ARROW_ID: Int = 52
    const val EXCLEQEQEQ_ID: Int = 53
    const val EQEQ_ID: Int = 54
    const val EXCLEQ_ID: Int = 55
    const val EXCLEXCL_ID: Int = 56
    const val ANDAND_ID: Int = 57
    const val AND_ID: Int = 58
    const val OROR_ID: Int = 59
    const val SAFE_ACCESS_ID: Int = 60
    const val ELVIS_ID: Int = 61
    const val QUEST_ID: Int = 62
    const val COLONCOLON_ID: Int = 63
    const val RANGE_ID: Int = 64
    const val RANGE_UNTIL_ID: Int = 65
    const val EQ_ID: Int = 66
    const val MULTEQ_ID: Int = 67
    const val DIVEQ_ID: Int = 68
    const val PERCEQ_ID: Int = 69
    const val PLUSEQ_ID: Int = 70
    const val MINUSEQ_ID: Int = 71
    const val NOT_IN_ID: Int = 72
    const val NOT_IS_ID: Int = 73
    const val AS_SAFE_ID: Int = 74

    // Hard & soft keywords / modifiers
    const val ABSTRACT_MODIFIER_ID: Int = 75
    const val ACTUAL_MODIFIER_ID: Int = 76
    const val ANNOTATION_MODIFIER_ID: Int = 77
    const val AS_KEYWORD_ID: Int = 78
    const val BREAK_KEYWORD_ID: Int = 79
    const val BY_KEYWORD_ID: Int = 80
    const val CATCH_KEYWORD_ID: Int = 81
    const val CLASS_KEYWORD_ID: Int = 82
    const val COMPANION_MODIFIER_ID: Int = 83
    const val CONST_MODIFIER_ID: Int = 84
    const val CONSTRUCTOR_KEYWORD_ID: Int = 85
    const val CONTINUE_KEYWORD_ID: Int = 86
    const val CROSSINLINE_MODIFIER_ID: Int = 87
    const val DATA_MODIFIER_ID: Int = 88
    const val DELEGATE_KEYWORD_ID: Int = 89
    const val DO_KEYWORD_ID: Int = 90
    const val DYNAMIC_KEYWORD_ID: Int = 91
    const val ELSE_KEYWORD_ID: Int = 92
    const val ENUM_MODIFIER_ID: Int = 93
    const val EXPECT_MODIFIER_ID: Int = 94
    const val EXTERNAL_MODIFIER_ID: Int = 95
    const val FIELD_KEYWORD_ID: Int = 96
    const val FILE_KEYWORD_ID: Int = 97
    const val FINAL_MODIFIER_ID: Int = 98
    const val FINALLY_KEYWORD_ID: Int = 99
    const val FOR_KEYWORD_ID: Int = 100
    const val FUN_KEYWORD_ID: Int = 101
    const val GET_KEYWORD_ID: Int = 102
    const val IF_KEYWORD_ID: Int = 103
    const val IMPORT_KEYWORD_ID: Int = 104
    const val IN_KEYWORD_ID: Int = 105
    const val INFIX_MODIFIER_ID: Int = 106
    const val INIT_KEYWORD_ID: Int = 107
    const val INLINE_MODIFIER_ID: Int = 108
    const val INNER_MODIFIER_ID: Int = 109
    const val INTERFACE_KEYWORD_ID: Int = 110
    const val INTERNAL_MODIFIER_ID: Int = 111
    const val IS_KEYWORD_ID: Int = 112
    const val LATEINIT_MODIFIER_ID: Int = 113
    const val NOINLINE_MODIFIER_ID: Int = 114
    const val OBJECT_KEYWORD_ID: Int = 115
    const val OPEN_MODIFIER_ID: Int = 116
    const val OPERATOR_MODIFIER_ID: Int = 117
    const val OUT_MODIFIER_ID: Int = 118
    const val OVERRIDE_MODIFIER_ID: Int = 119
    const val PACKAGE_KEYWORD_ID: Int = 120
    const val PARAM_KEYWORD_ID: Int = 121
    const val PRIVATE_MODIFIER_ID: Int = 122
    const val PROPERTY_KEYWORD_ID: Int = 123
    const val PROTECTED_MODIFIER_ID: Int = 124
    const val PUBLIC_MODIFIER_ID: Int = 125
    const val RECEIVER_KEYWORD_ID: Int = 126
    const val REIFIED_MODIFIER_ID: Int = 127
    const val RETURN_KEYWORD_ID: Int = 128
    const val SEALED_MODIFIER_ID: Int = 129
    const val SET_KEYWORD_ID: Int = 130
    const val SETPARAM_KEYWORD_ID: Int = 131
    const val SUPER_KEYWORD_ID: Int = 132
    const val SUSPEND_MODIFIER_ID: Int = 133
    const val TAILREC_MODIFIER_ID: Int = 134
    const val THIS_KEYWORD_ID: Int = 135
    const val THROW_KEYWORD_ID: Int = 136
    const val TRY_KEYWORD_ID: Int = 137
    const val TYPEALIAS_KEYWORD_ID: Int = 138
    const val TYPEOF_KEYWORD_ID: Int = 139
    const val VAL_KEYWORD_ID: Int = 140
    const val VALUE_MODIFIER_ID: Int = 141
    const val VAR_KEYWORD_ID: Int = 142
    const val VARARG_MODIFIER_ID: Int = 143
    const val WHEN_KEYWORD_ID: Int = 144
    const val WHERE_KEYWORD_ID: Int = 145
    const val WHILE_KEYWORD_ID: Int = 146
}
