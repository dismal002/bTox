// SPDX-FileCopyrightText: 2019-2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.di

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton
import com.dismal.btox.ActionReceiver
import com.dismal.btox.BootReceiver
import com.dismal.btox.MainActivity
import com.dismal.btox.ToxService

@Singleton
@Component(
    modules = [
        AndroidModule::class,
        AppModule::class,
        DatabaseModule::class,
        DaoModule::class,
        ViewModelModule::class,
    ],
)
interface AppComponent {
    @Component.Factory
    interface Factory {
        fun create(@BindsInstance appContext: Context): AppComponent
    }

    fun inject(activity: MainActivity)
    fun inject(service: ToxService)
    fun inject(receiver: BootReceiver)
    fun inject(receiver: ActionReceiver)
}
