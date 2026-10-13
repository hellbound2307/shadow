package com.shadow.operator.data

/**
 * Hand-off for a command result destined for the data screen.
 *
 * Deliberately not a navigation argument: a contact or SMS dump is easily a few
 * hundred KB, and nav-compose arguments are persisted into a Bundle, which
 * crashes on oversized payloads (TransactionTooLargeException).
 */
object ResultHolder {
    var title: String = ""
    var text: String = ""
}
