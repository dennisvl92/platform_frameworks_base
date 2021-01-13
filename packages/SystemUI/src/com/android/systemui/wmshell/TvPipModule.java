/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.wmshell;

import android.content.Context;

import com.android.systemui.dagger.WMSingleton;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.tv.TvPipController;
import com.android.wm.shell.pip.tv.TvPipMenuController;
import com.android.wm.shell.pip.tv.TvPipNotificationController;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for TV Pip.
 */
@Module(includes = {WMShellBaseModule.class})
public abstract class TvPipModule {
    @WMSingleton
    @Provides
    static Optional<Pip> providePip(
            Context context,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipTaskOrganizer pipTaskOrganizer,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            TvPipNotificationController tvPipNotificationController,
            TaskStackListenerImpl taskStackListener,
            WindowManagerShellWrapper windowManagerShellWrapper) {
        return Optional.of(
                new TvPipController(
                        context,
                        pipBoundsState,
                        pipBoundsAlgorithm,
                        pipTaskOrganizer,
                        tvPipMenuController,
                        pipMediaController,
                        tvPipNotificationController,
                        taskStackListener,
                        windowManagerShellWrapper));
    }

    @WMSingleton
    @Provides
    static PipBoundsAlgorithm providePipBoundsHandler(Context context,
            PipBoundsState pipBoundsState) {
        return new PipBoundsAlgorithm(context, pipBoundsState);
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context) {
        return new PipBoundsState(context);
    }

    @WMSingleton
    @Provides
    static TvPipMenuController providesTvPipMenuController(
            Context context,
            PipBoundsState pipBoundsState,
            SystemWindows systemWindows,
            PipMediaController pipMediaController) {
        return new TvPipMenuController(context, pipBoundsState, systemWindows, pipMediaController);
    }

    @WMSingleton
    @Provides
    static TvPipNotificationController provideTvPipNotificationController(Context context,
            PipMediaController pipMediaController) {
        return new TvPipNotificationController(context, pipMediaController);
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            TvPipMenuController tvPipMenuController,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<LegacySplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer) {
        return new PipTaskOrganizer(context, pipBoundsState, pipBoundsAlgorithm,
                tvPipMenuController, pipSurfaceTransactionHelper, splitScreenOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer);
    }
}
