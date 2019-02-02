package org.kexie.android.liteproj;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;

public class LiteViewModel
        extends AndroidViewModel
{


    public LiteViewModel(@NonNull Application application)
    {
        super(application);
        LifecycleEventHandler.onAttach(this);
    }

    @CallSuper
    @Override
    protected void onCleared()
    {
        LifecycleEventHandler.onDestroy(this);
    }
}
