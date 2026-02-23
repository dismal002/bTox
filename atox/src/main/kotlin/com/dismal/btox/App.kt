// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2019 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.dismal.btox.di.AppComponent
import com.dismal.btox.di.DaggerAppComponent

class App : Application() {
    val component: AppComponent by lazy {
        componentOverride ?: DaggerAppComponent.factory().create(applicationContext)
    }

    @VisibleForTesting
    var componentOverride: AppComponent? = null
}
