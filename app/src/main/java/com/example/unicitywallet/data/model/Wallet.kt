package com.example.unicitywallet.data.model

data class Wallet(
    val id: String,
    val name: String,
    val address: String,
    val tokens: List<Token> = emptyList()
)