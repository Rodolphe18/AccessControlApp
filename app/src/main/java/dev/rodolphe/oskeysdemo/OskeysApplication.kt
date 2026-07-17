package dev.rodolphe.oskeysdemo

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

// Renamed from OskeysApp so the class no longer collides with the @Composable OskeysApp(): with two
// callables sharing that name, `OskeysApp()` at the call site resolved to this Application's
// constructor instead of the composable, so no UI was ever composed (blank screen).
@HiltAndroidApp
class OskeysApplication : Application()
