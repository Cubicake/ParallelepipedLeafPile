/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A namespaced key where the namespace is a class.
 */
public record CubiKey(Class<?> namespace, String key) {
    public boolean equals(String fullyQualifiedNamespace, String key) {
        if (!this.key.equals(key)) return false;

        return namespace.getCanonicalName().equals(fullyQualifiedNamespace);
    }

    static CubiKey from(Class<?> namespace, String key) {
        if (IS_BUKKIT_SERVER) return new CubiKey(tryGetProvidingPluginMainClass(namespace), key);
        return new CubiKey(namespace, key);
    }

    private static final Class<?> JAVA_PLUGIN_CLASS;
    private static final Method GET_PROVIDING_PLUGIN;
    private static final boolean IS_BUKKIT_SERVER;

    static {
        Class<?> javaPluginClass = null;
        Method getProvidingPlugin = null;

        try {
            javaPluginClass = Class.forName("org.bukkit.plugin.java.JavaPlugin");
            getProvidingPlugin = javaPluginClass.getMethod(
                    "getProvidingPlugin",
                    Class.class
            );
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        }

        JAVA_PLUGIN_CLASS = javaPluginClass;
        GET_PROVIDING_PLUGIN = getProvidingPlugin;

        IS_BUKKIT_SERVER = !(JAVA_PLUGIN_CLASS == null || GET_PROVIDING_PLUGIN == null);
    }

    /**
     * For Bukkit servers
     */
    public static Class<?> tryGetProvidingPluginMainClass(Class<?> pluginClass) {
        Object plugin;
        try {
            plugin = GET_PROVIDING_PLUGIN.invoke(null, pluginClass);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException(
                    "JavaPlugin.getProvidingPlugin failed",
                    cause
            );
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access JavaPlugin.getProvidingPlugin",
                    e
            );
        }
        return plugin.getClass();
    }
}
