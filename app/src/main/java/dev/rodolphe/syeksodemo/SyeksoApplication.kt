package dev.rodolphe.syeksodemo

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

// Renamed from SyeksoApp so the class no longer collides with the @Composable SyeksoApp(): with two
// callables sharing that name, `SyeksoApp()` at the call site resolved to this Application's
// constructor instead of the composable, so no UI was ever composed (blank screen).
@HiltAndroidApp
class SyeksoApplication : Application()
