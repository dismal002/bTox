// SPDX-FileCopyrightText: 2021-2022 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.di

import android.content.Context
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.dismal.btox.tox.BootstrapNodeRegistryImpl
import ltd.evilcorp.domain.tox.AndroidSaveManager
import ltd.evilcorp.domain.tox.BootstrapNodeRegistry
import ltd.evilcorp.domain.tox.SaveManager

@Module
class AppModule {
    @Provides
    fun provideBootstrapNodeRegistry(nodeRegistry: BootstrapNodeRegistryImpl): BootstrapNodeRegistry = nodeRegistry

    @Provides
    fun provideCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

    @Provides
    fun provideSaveManager(ctx: Context): SaveManager = AndroidSaveManager(ctx)
}
