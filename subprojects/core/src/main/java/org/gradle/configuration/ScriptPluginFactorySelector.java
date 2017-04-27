/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.configuration;

import org.gradle.api.Incubating;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.scripts.ScriptLanguages;
import org.gradle.scripts.ScriptLanguage;

/**
 * Selects a {@link ScriptPluginFactory} suitable for handling a given build script based
 * on its file name. Build script file names ending in ".gradle" are supported by the
 * {@link DefaultScriptPluginFactory}. Other files are delegated to the first available
 * matching implementation of the {@link ScriptLanguage} SPI. If no provider
 * implementations matches for a given file name, handling falls back to the
 * {@link DefaultScriptPluginFactory}. This approach allows users to name build scripts
 * with a suffix of choice, e.g. "build.groovy" or "my.build" instead of the typical
 * "build.gradle" while preserving default behaviour which is to fallback to Groovy support.
 *
 * This factory wraps each {@link ScriptPlugin} implementation in a {@link BuildOperationScriptPlugin}.
 *
 * @see ScriptPluginFactoryProvider
 * @since 2.14
 */
@Incubating
public class ScriptPluginFactorySelector implements ScriptPluginFactory {

    public interface ProviderInstantiator {
        ScriptPluginFactoryProvider instantiate(String providerClassName);
    }

    private final ScriptPluginFactory defaultScriptPluginFactory;
    private final ScriptLanguages scriptLanguages;
    private final ProviderInstantiator providerInstantiator;
    private final BuildOperationExecutor buildOperationExecutor;

    public ScriptPluginFactorySelector(ScriptPluginFactory defaultScriptPluginFactory,
                                       ScriptLanguages scriptLanguages,
                                       ProviderInstantiator providerInstantiator,
                                       BuildOperationExecutor buildOperationExecutor) {
        this.defaultScriptPluginFactory = defaultScriptPluginFactory;
        this.scriptLanguages = scriptLanguages;
        this.providerInstantiator = providerInstantiator;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public ScriptPlugin create(ScriptSource scriptSource, ScriptHandler scriptHandler, ClassLoaderScope targetScope,
                               ClassLoaderScope baseScope, boolean topLevelScript) {
        ScriptPlugin scriptPlugin = scriptPluginFactoryFor(scriptSource.getFileName())
            .create(scriptSource, scriptHandler, targetScope, baseScope, topLevelScript);
        return new BuildOperationScriptPlugin(scriptPlugin, buildOperationExecutor);
    }

    private ScriptPluginFactory scriptPluginFactoryFor(String fileName) {
        return fileName.endsWith(".gradle")
            ? defaultScriptPluginFactory
            : findScriptPluginFactoryFor(fileName);
    }

    private ScriptPluginFactory findScriptPluginFactoryFor(String fileName) {
        for (ScriptLanguage scriptLanguage : scriptLanguages) {
            if (fileName.endsWith("." + scriptLanguage.getExtension())) {
                ScriptPluginFactoryProvider provider = scriptPluginFactoryProviderFor(scriptLanguage);
                ScriptPluginFactory scriptPluginFactory = provider.getFor(fileName);
                if (scriptPluginFactory != null) {
                    return scriptPluginFactory;
                }
            }
        }
        return defaultScriptPluginFactory;
    }

    private ScriptPluginFactoryProvider scriptPluginFactoryProviderFor(ScriptLanguage scriptLanguage) {
        return providerInstantiator.instantiate(scriptLanguage.getProvider());
    }

    public static class DefaultProviderInstantiator implements ProviderInstantiator {
        private final Instantiator instantiator;

        public DefaultProviderInstantiator(Instantiator instantiator) {
            this.instantiator = instantiator;
        }

        @Override
        public ScriptPluginFactoryProvider instantiate(String providerClassName) {
            Class<?> providerClass = loadProviderClass(providerClassName);
            return (ScriptPluginFactoryProvider) instantiator.newInstance(providerClass);
        }

        private Class<?> loadProviderClass(String providerClassName) {
            try {
                return getClass().getClassLoader().loadClass(providerClassName);
            } catch (ClassNotFoundException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }
    }
}
