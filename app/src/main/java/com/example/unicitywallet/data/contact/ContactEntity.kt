package com.example.unicitywallet.data.contact

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity (
    @PrimaryKey val id: String,
    val name: String,
    val unicityId: String?,
    val isAppCreated: Boolean
)