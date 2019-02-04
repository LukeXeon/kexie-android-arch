package org.kexie.android.liteproj.analyzer;

import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;

import org.kexie.android.liteproj.DependencyManager;
import org.kexie.android.liteproj.DependencyType;

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface Provider
{
    @NonNull
    <T> T provide(@NonNull DependencyManager dependencyManager);

    @NonNull
    DependencyType getType();

    @NonNull
    Class<?> getResultType();

    interface Factory
    {
        @NonNull
        <T> T newInstance(@NonNull DependencyManager dependencyManager);

        @NonNull
        Class<?> getResultType();
    }

    interface Setter
    {
        void set(@NonNull Object target, @NonNull DependencyManager dependency);
    }
}
