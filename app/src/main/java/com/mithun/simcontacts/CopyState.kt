package com.mithun.simcontacts

sealed class CopyState {
    object Idle : CopyState()
    data class Copying(val done: Int, val total: Int) : CopyState()
    data class Done(val result: CopyResult) : CopyState()
    object Cancelled : CopyState()
}

data class CopyResult(
    val ok: Int,
    val dup: Int,
    val full: Int,
    val fail: Int,
    val failedNames: List<String>,
    val lastError: String?
)
