package com.dtcomg.dio_up;

import android.content.Context;

import com.dtcomg.dio_up.internal.InstallPath;
import com.dtcomg.dio_up.internal.InstallPathSelector;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

public final class InstallPathSelectorTest {
    @Test
    public void selectsInstanceInstallerFirst() {
        DioUpConfiguration config = DioUpConfiguration.builder(
                        "https://server.example",
                        "project"
                )
                .packageInstaller((context, file, task) ->
                        new InstallOutcome(InstallStatus.WAITING_FOR_USER))
                .reflectionInstallerClassName(ReflectionInstaller.class.getName())
                .build();
        assertEquals(InstallPath.INSTANCE, InstallPathSelector.select(config));
    }

    @Test
    public void selectsReflectionInstallerWhenNoInstanceInstallerExists() {
        DioUpConfiguration config = DioUpConfiguration.builder(
                        "https://server.example",
                        "project"
                )
                .reflectionInstallerClassName(ReflectionInstaller.class.getName())
                .build();
        assertEquals(InstallPath.REFLECTION, InstallPathSelector.select(config));
    }

    @Test
    public void fallsBackToStandardIntentWhenNoInstallerExists() {
        DioUpConfiguration config =
                new DioUpConfiguration("https://server.example", "project");
        assertEquals(InstallPath.STANDARD_INTENT, InstallPathSelector.select(config));
    }

    public static final class ReflectionInstaller extends DioInstaller {
        @Override
        public InstallOutcome install(Context context, File apkFile, PendingTask task) {
            return new InstallOutcome(InstallStatus.WAITING_FOR_USER);
        }
    }
}
