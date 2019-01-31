package org.kexie.android.arch.ioc;

import android.app.Application;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;

import org.dom4j.Attribute;
import org.dom4j.Element;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

final class AnalyzerUtil
{
    private AnalyzerUtil()
    {
        throw new AssertionError();
    }

    private static final List<Class<?>> SUPPORT_TYPES
            = Collections.unmodifiableList(new LinkedList<Class<?>>()
    {
        {
            add(Application.class);
            add(AppCompatActivity.class);
            add(Fragment.class);
            add(LiteService.class);
        }
    });

    private static final List<ValueOf> VALUE_OF
            = Collections.unmodifiableList(new LinkedList<ValueOf>()
    {
        {
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    if (!Character.isDigit(value.charAt(0)))
                    {
                        if (value.length() == 1)
                        {
                            return value.charAt(0);
                        } else if ("true".equals(value) || "false".equals(value))
                        {
                            return Boolean.valueOf(value);
                        }
                    }
                    throw new NumberFormatException();
                }
            });
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    return Byte.valueOf(value);
                }
            });
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    return Short.valueOf(value);
                }
            });
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    return Integer.valueOf(value);
                }
            });
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    return Long.valueOf(value);
                }
            });
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    return Float.valueOf(value);
                }
            });
            add(new ValueOf()
            {
                @Override
                public Object valueOf(String value)
                {
                    return Double.valueOf(value);
                }
            });
        }
    });

    private final static Pattern NAME_PATTERN = Pattern
            .compile("[\u4e00-\u9fa5_A-Za-z][\u4e00-\u9fa5_A-Za-z0-9]*");

    @Nullable
    static String getAttrNoThrow(Element element,
                                 String attr)
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
    static String getAttrIfEmptyThrow(Element element,
                                      String attr)
    {
        String name = getAttrNoThrow(element, attr);
        if (!TextUtils.isEmpty(name))
        {
            return name;
        }
        throw fromMessageThrow(element, "attr " + attr + " no found");
    }

    static RuntimeException
    fromMessageThrow(Element element, String massage)
    {
        return new GenerateDepartmentException(element, massage);
    }

    static RuntimeException
    formExceptionThrow(Element element, Throwable e)
    {
        return new GenerateDepartmentException(element, e);
    }

    static NameType getNameType(String text)
    {
        if (TextUtils.isEmpty(text))
        {
            return NameType.Illegal;
        } else
        {
            if ((text.charAt(0) == '@')
                    && !TextUtils.isEmpty(text.substring(1, text.length())))
            {
                return NameType.Constant;
            } else if (NAME_PATTERN
                    .matcher(text)
                    .matches())
            {
                return NameType.Reference;
            } else
            {
                return NameType.Illegal;
            }
        }
    }

    static boolean listNoEmpty(List<?> list)
    {
        return list != null && list.size() != 0;
    }

    static void checkSupportTypeCompat(Class<?> type)
    {
        for (Class<?> clazz : SUPPORT_TYPES)
        {
            if (clazz.isAssignableFrom(type))
            {
                return;
            }
        }
        throw new IllegalStateException("no support type " + type);
    }

    static boolean equalsToSupportTypes(Class<?> clazz)
    {
        for (Class<?> type : SUPPORT_TYPES)
        {
            if (type.equals(clazz))
            {
                return true;
            }
        }
        return false;
    }

    static Provider
    newConstantProvider(String let)
            throws IllegalFormatTextException
    {
        String value = let.substring(1, let.length());
        if (let.charAt(0) == '@')
        {
            return new DependencyProvider(
                    DependencyType.Singleton,
                    ReflectionUtil.newConstantFactory(value),
                    null
            );
        } else
        {
            for (ValueOf valueOf : VALUE_OF)
            {
                try
                {
                    return new DependencyProvider(
                            DependencyType.Singleton,
                            ReflectionUtil.newConstantFactory(
                                    valueOf.valueOf(value)
                            ),
                            null
                    );
                } catch (NumberFormatException ignored)
                {

                }
            }
            throw new IllegalFormatTextException("no type match to let = " + let);
        }
    }
}