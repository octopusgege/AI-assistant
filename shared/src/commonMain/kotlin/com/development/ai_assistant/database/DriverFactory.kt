package com.development.ai_assistant.database

import app.cash.sqldelight.db.SqlDriver

//
expect class DriverFactory {
    fun createDriver(): SqlDriver
}