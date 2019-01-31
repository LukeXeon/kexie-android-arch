package org.kexie.android.arch.ioc;

import android.support.annotation.NonNull;

final class OwnerProxyProvider
    implements Provider
{
    private final Class<?> ownerType;

    OwnerProxyProvider(Class<?> ownerType)
    {
        this.ownerType = ownerType;
    }

    @NonNull
    @Override
    public <T> T newInstance(Dependency dependency)
    {
        throw new IllegalStateException("this a proxy");
    }

    @NonNull
    @Override
    public DependencyType getType()
    {
        return DependencyType.Singleton;
    }

    @NonNull
    @SuppressWarnings({"unchecked"})
    @Override
    public Class<?> getResultType()
    {
        return ownerType;
    }
}