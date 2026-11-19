package com.example.aibuds.core

enum class Role { System, User, Assistant }

data class Message(val role: Role, val text: String)
