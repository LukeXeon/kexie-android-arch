package org.kexie.android.liteproj.internal;

import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RawRes;
import android.support.annotation.RestrictTo;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.util.Log;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.kexie.android.liteproj.DependencyManager;
import org.kexie.android.liteproj.DependencyType;
import org.kexie.android.liteproj.GenerateDependencyException;
import org.kexie.android.liteproj.R;
import org.kexie.android.liteproj.util.TypeUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class DependencyAnalyzer
        extends ContextWrapper
{
    private static final class Context
    {
        private final Provider mProxyProvider;

        private final Map<String, Provider> mProviders = new ArrayMap<>();

        private Dependency makeResult()
        {
            return new Dependency(mProxyProvider.getResultType(), mProviders);
        }

        private Context(@NonNull Class<?> ownerType)
        {
            this.mProxyProvider = Provider.createOwnerProxyProvider(ownerType);
        }

        @Nullable
        private Provider getProvider(@NonNull Name name)
        {
            if (Name.Type.CONSTANT.equals(name.type))
            {
                return Provider.markConstant(name);
            } else if (DependencyManager.OWNER.equals(name.text))
            {
                return mProxyProvider;
            } else if (DependencyManager.NULL.equals(name.text))
            {
                return Provider.sNullProxyProvider;
            } else
            {
                return mProviders.get(name.text);
            }
        }

        private void addProvider(@NonNull Name name, @NonNull Provider provider)
        {
            if (Name.Type.CONSTANT.equals(name.type))
            {
                Provider.markConstant(name);
            } else if (!DependencyManager.NULL.equals(name.text)
                    && !DependencyManager.OWNER.equals(name.text)
                    && !mProviders.containsKey(name.text))
            {
                mProviders.put(name.text, provider);
            } else
            {
                throw new GenerateDependencyException(
                        String.format("The provider named %s already exists", name)
                );
            }
        }
    }

    private static final String TAG = "DependencyAnalyzer";

    //线程安全LruCache
    private final LruCache<Object, Dependency> mResultCache;

    @NonNull
    private static Document readXml(@NonNull InputStream stream,
                                    boolean isCompressed)
    {
        if (!isCompressed)
        {
            try
            {
                return new SAXReader().read(stream);
            } catch (DocumentException e)
            {
                throw new RuntimeException(e);
            }
        } else
        {
            try
            {
                return new AXmlReader().read(stream);
            } catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static boolean listNoEmpty(@Nullable List<?> list)
    {
        return list != null && list.size() != 0;
    }

    private int initCacheSize()
    {
        try
        {
            PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(),
                            PackageManager.GET_SERVICES
                                    | PackageManager.GET_ACTIVITIES);
            int size = ((packageInfo.activities == null
                    || packageInfo.activities.length == 0 ? 1
                    : packageInfo.activities.length)
                    * Runtime.getRuntime().availableProcessors()
                    + (packageInfo.services == null
                    || packageInfo.services.length == 0 ? 0
                    : packageInfo.services.length));
            Log.d(TAG, String.format("init cache size = %d", size));
            return size;
        } catch (PackageManager.NameNotFoundException e)
        {
            throw new AssertionError(e);
        }
    }

    public DependencyAnalyzer(@NonNull android.content.Context base)
    {
        super(base.getApplicationContext());
        mResultCache = new LruCache<>(initCacheSize());
    }

    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public Dependency analysis(@RawRes int xml)
    {
        Dependency dependency = mResultCache.get(xml);
        if (dependency == null)
        {
            switch (getResources().getResourceTypeName(xml))
            {
                case "raw":
                {
                    dependency = analysisDocument(
                            readXml(getResources().openRawResource(xml),
                                    false));
                }
                break;
                case "xml":
                {
                    dependency = analysisDocument(
                            readXml(getResources().openRawResource(xml),
                                    true));
                }
                break;
                default:
                {
                    throw new IllegalStateException("Files can be in the 'raw' directory "
                            + "or the 'xml' directory");
                }
            }
            mResultCache.put(xml, dependency);
        }
        return dependency;
    }

    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public Dependency analysis(@NonNull String asset)
    {
        Dependency dependency = mResultCache.get(asset);
        if (dependency == null)
        {
            try (InputStream stream = getAssets().open(asset))
            {
                dependency = analysisDocument(
                        readXml(stream, false)
                );
                mResultCache.put(asset, dependency);
            } catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        return dependency;
    }

    @NonNull
    private Dependency analysisDocument(@NonNull Document document)
    {
        Element scope = document.getRootElement();
        Context env = new Context(getOwnerType(scope));
        List<Element> elements = scope.elements();
        for (Element element : elements)
        {
            if (getString(R.string.var_string).equals(element.getName()))
            {
                Name name = new Name(getAttrIfEmptyThrow(element,
                        getString(R.string.name_string)));
                if (!Name.Type.REFERENCE.equals(name.type))
                {
                    throw fromMessageThrow(element, String.format("Illegal name %s", name.text));
                }
                env.addProvider(name, analysisVar(env, element));
            } else
            {
                throw fromMessageThrow(element, "Need a 'var' tag");
            }
        }
        return env.makeResult();
    }

    @NonNull
    private Provider analysisVar(@NonNull Context env,
                                 @NonNull Element element)
    {
        String val = getAttrNoThrow(element, getString(R.string.val_string));
        if (val != null)
        {
            return analysisAssignValToVar(env, element, val);
        } else
        {
            return analysisProviderVar(env, element);
        }
    }

    @NonNull
    private Provider analysisProviderVar(@NonNull Context env,
                                         @NonNull Element element)
    {
        Class<?> type = getTypeAttrIfErrorThrow(element);
        List<Element> elements = element.elements();
        List<Setter> setters = new LinkedList<>();
        Factory factory = searchFactory(env, element, type);
        if (listNoEmpty(elements))
        {
            for (Element item : elements)
            {
                String name = item.getName();
                if (getString(R.string.new_string).equals(name)
                        || getString(R.string.factory_string).equals(name)
                        || getString(R.string.builder_string).equals(name))
                {
                    continue;
                }
                if (getString(R.string.field_string).equals(name))
                {
                    setters.add(analysisField(env, item, type));
                } else if (getString(R.string.property_string).equals(name))
                {
                    setters.add(analysisProperty(env, item, type));
                } else
                {
                    throw fromMessageThrow(element,
                            String.format("Error token %s",
                                    item.getName()));
                }
            }
        }
        return Provider.createProvider(
                isSingleton(element)
                        ? DependencyType.SINGLETON
                        : DependencyType.FACTORY,
                factory,
                setters);
    }

    private boolean isSingleton(@NonNull Element element)
    {
        String provider = getAttrNoThrow(element,
                getString(R.string.provider_string));
        if (TextUtils.isEmpty(provider))
        {
            return false;
        }
        if (getString(R.string.singleton_string).equals(provider))
        {
            return true;
        } else if (getString(R.string.factory_string).equals(provider))
        {
            return false;
        } else
        {
            throw fromMessageThrow(element, String.format(
                    "Illegal provider = %s",
                    provider));
        }
    }

    @NonNull
    private Setter analysisField(@NonNull Context env,
                                 @NonNull Element element,
                                 @NonNull Class<?> path)
    {
        String name = getAttrIfEmptyThrow(element,
                getString(R.string.name_string));
        Name refOrVal = getRefOrValAttr(env, element);
        return Provider.createFieldSetter(
                TypeUtil.getTypeField(path,
                        name, getResultTypeIfNullThrow(env, element, refOrVal)),
                refOrVal);
    }

    @NonNull
    private Factory searchFactory(@NonNull Context env,
                                  @NonNull Element element,
                                  @NonNull Class<?> type)
    {
        List<Element> elements = element.elements();
        for (Element item : elements)
        {
            if (getString(R.string.new_string).equals(item.getName()))
            {
                return analysisNew(env, item, type);
            } else if (getString(R.string.factory_string).equals(item.getName()))
            {
                return analysisFactory(env, item, type);
            } else if (getString(R.string.builder_string).equals(item.getName()))
            {
                return analysisBuilder(env, item, type);
            }
        }
        return Provider.createConstructorFactory(
                TypeUtil.getTypeConstructor(type, null),
                Collections.<Name>emptyList());
    }

    @NonNull
    private Factory analysisNew(@NonNull Context env,
                                @NonNull Element element,
                                @NonNull Class<?> type)
    {
        List<Element> elements = element.elements();
        List<Name> refOrVal = new LinkedList<>();
        if (listNoEmpty(elements))
        {
            for (Element item : elements)
            {
                String name = item.getName();
                if (getString(R.string.arg_string).equals(name))
                {
                    Name temp = getRefOrValAttr(env, item);
                    if (!refOrVal.contains(temp))
                    {
                        refOrVal.add(temp);
                    } else
                    {
                        throw fromMessageThrow(item,
                                String.format("The name '%s' already exist",
                                        temp)
                        );
                    }
                } else
                {
                    throw fromMessageThrow(element,
                            "Tag 'arg' no found");
                }
            }
        }
        Class<?>[] classes = new Class<?>[refOrVal.size()];
        for (int i = 0; i < classes.length; i++)
        {
            classes[i] = getResultTypeIfNullThrow(
                    env,
                    element,
                    refOrVal.get(i));
        }
        return Provider.createConstructorFactory(
                TypeUtil.getTypeConstructor(type,
                        classes), refOrVal);
    }

    @NonNull
    private Factory analysisFactory(@NonNull Context env,
                                    @NonNull Element element,
                                    @NonNull Class<?> type)
    {
        Class<?> factoryType = getTypeAttrIfErrorThrow(element);
        String factoryName = getAttrIfEmptyThrow(element,
                getString(R.string.action_string));
        List<Element> elements = element.elements();
        List<Name> refOrVal = new LinkedList<>();
        if (listNoEmpty(elements))
        {
            for (Element item : elements)
            {
                String name = item.getName();
                if (getString(R.string.arg_string).equals(name))
                {
                    Name temp = getRefOrValAttr(env, item);
                    if (!refOrVal.contains(temp))
                    {
                        refOrVal.add(temp);
                    } else
                    {
                        throw fromMessageThrow(item,
                                String.format("The name '%s' already exist",
                                        temp)
                        );
                    }
                } else
                {
                    throw fromMessageThrow(item,
                            "Tag 'arg' no found");
                }
            }
        }
        Class<?>[] classes = new Class<?>[refOrVal.size()];
        for (int i = 0; i < classes.length; i++)
        {
            classes[i] = getResultTypeIfNullThrow(env,
                    element,
                    refOrVal.get(i));
        }
        Method factoryMethod = TypeUtil.getTypeFactory(factoryType,
                factoryName,
                classes);
        if (TypeUtil.isAssignToType(factoryMethod.getReturnType(), type))
        {
            return Provider.createMethodFactory(factoryMethod,
                    refOrVal);
        } else
        {
            throw fromMessageThrow(element,
                    String.format("Return type no match (form %s to %s)",
                            factoryMethod.getReturnType(),
                            type));
        }
    }

    @NonNull
    private Factory analysisBuilder(@NonNull Context env,
                                    @NonNull Element element,
                                    @NonNull Class<?> type)
    {
        Class<?> builderType = getTypeAttrIfErrorThrow(element);
        List<Element> elements = element.elements();
        Map<String, Name> refOrVal = new ArrayMap<>();
        if (listNoEmpty(elements))
        {
            for (Element item : elements)
            {
                String name = item.getName();
                if (getString(R.string.arg_string).equals(name))
                {
                    String argName = getAttrIfEmptyThrow(item,
                            getString(R.string.name_string));
                    if (!refOrVal.containsKey(argName))
                    {
                        refOrVal.put(argName, getRefOrValAttr(env, item));
                    } else
                    {
                        throw fromMessageThrow(item,
                                String.format("The name '%s' already exist",
                                        argName)
                        );
                    }
                } else
                {
                    throw fromMessageThrow(item,
                            "Tag 'arg' no found");
                }
            }
        }
        Map<Method, Name> setters = new ArrayMap<>();
        String buildAction = getAttrNoThrow(element,
                getString(R.string.action_string));
        Method buildMethod = TypeUtil.getTypeInstanceMethod(builderType,
                buildAction == null ? "build" : buildAction,
                null);
        if (!TypeUtil.isAssignToType(buildMethod.getReturnType(), type))
        {
            throw fromMessageThrow(element,
                    String.format("Return type no match (form %s to %s)",
                            buildMethod.getReturnType(),
                            type));
        }
        for (Map.Entry<String, Name> entry : refOrVal.entrySet())
        {
            setters.put(TypeUtil.getTypeInstanceMethod(
                    builderType,
                    entry.getKey(),
                    new Class<?>[]{getResultTypeIfNullThrow(env,
                            element,
                            entry.getValue())}),
                    entry.getValue());
        }
        return Provider.createBuilderFactory(builderType,
                setters,
                buildMethod);
    }

    @NonNull
    private Setter analysisProperty(@NonNull Context env,
                                    @NonNull Element element,
                                    @NonNull Class<?> path)
    {
        String name = getAttrIfEmptyThrow(element,
                getString(R.string.name_string));
        Name refOrVal = getRefOrValAttr(env, element);
        return Provider.createPropertySetter(
                TypeUtil.getTypeProperty(
                        path,
                        name,
                        getResultTypeIfNullThrow(env, element, refOrVal)),
                refOrVal);
    }

    @NonNull
    private Name getRefOrValAttr(Context env, @NonNull Element element)
    {
        String ref = getAttrNoThrow(element,
                getString(R.string.ref_string));
        String val = getAttrNoThrow(element,
                getString(R.string.val_string));
        Name name = ref != null ? new Name(ref)
                : val != null ? new Name(val)
                : new Name("");
        if (!Name.Type.ILLEGAL.equals(name.type))
        {
            if (env.getProvider(name) == null)
            {
                throw fromMessageThrow(element,
                        String.format("%s not found", name.text));
            }
            return name;
        }
        throw fromMessageThrow(element,
                String.format("Illegal %s = %s",
                        !TextUtils.isEmpty(ref) ? "ref" : "val",
                        !TextUtils.isEmpty(ref) ? ref : val));
    }

    @NonNull
    private Provider analysisAssignValToVar(@NonNull Context env,
                                            @NonNull Element element,
                                            @NonNull String val)
    {
        Name name = new Name(val);
        if (Name.Type.CONSTANT.equals(name.type))
        {
            Provider provider = env.getProvider(name);
            assert provider != null;
            return provider;
        } else
        {
            throw fromMessageThrow(element,
                    String.format("Incorrect name '%s'", val));
        }
    }

    @NonNull
    private Class<?> getOwnerType(@NonNull Element root)
    {
        if (getString(R.string.dependency_string).equals(root.getName()))
        {
            Attribute attribute = root.attribute(getString(R.string.owner_string));
            if (attribute != null && !TextUtils.isEmpty(attribute.getName()))
            {
                try
                {
                    return Class.forName(attribute.getValue());
                } catch (ClassNotFoundException e)
                {
                    throw new GenerateDependencyException(e);
                }
            }

        }
        throw new GenerateDependencyException("XML file format error in " + root.asXML());
    }

    @NonNull
    private Class<?> getTypeAttrIfErrorThrow(@NonNull Element element)
    {
        try
        {
            return Class.forName(getAttrIfEmptyThrow(
                    element,
                    getString(R.string.type_string)
            ));
        } catch (ClassNotFoundException e)
        {
            throw formExceptionThrow(element, e);
        }
    }

    @Nullable
    private static String getAttrNoThrow(
            @NonNull Element element,
            @NonNull String attr)
    {
        if (element.attributeCount() != 0)
        {
            Attribute attribute = element.attribute(attr);
            if (attribute != null)
            {
                return attribute.getValue();
            }
        }
        return null;
    }

    @NonNull
    private static Class<?>
    getResultTypeIfNullThrow(@NonNull Context env,
                             @NonNull Element element,
                             @NonNull Name name)
    {
        Provider provider = env.getProvider(name);
        if (provider != null)
        {
            return provider.getResultType();
        } else
        {
            throw fromMessageThrow(element,
                    String.format("no found name by %s provider", name));
        }
    }

    @NonNull
    private static String
    getAttrIfEmptyThrow(@NonNull Element element,
                        @NonNull String attr)
    {
        String name = getAttrNoThrow(element, attr);
        if (!TextUtils.isEmpty(name))
        {
            return name;
        }
        throw fromMessageThrow(element,
                String.format("Attr %s no found", attr));
    }

    @NonNull
    private static RuntimeException
    fromMessageThrow(@NonNull Element element,
                     @NonNull String massage)
    {
        return new GenerateDependencyException(String.format(
                "Error in %s ", element.asXML())
                + (TextUtils.isEmpty(massage)
                ? ""
                : String.format(", message = %s", massage)));
    }

    @NonNull
    private static RuntimeException
    formExceptionThrow(@NonNull Element element,
                       @NonNull Throwable e)
    {
        return new GenerateDependencyException(
                String.format("Error in %s\n ",
                        element.asXML()), e);
    }
}