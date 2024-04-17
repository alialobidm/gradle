/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.internal.classpath.types;

import com.google.common.collect.ImmutableMap;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link InstrumentationTypeRegistry} for checking type information for external plugins.
 * It combines data of direct super types that when visiting external plugins classes and Gradle core types data, to calculated final information for external types.
 */
public class ExternalPluginsInstrumentationTypeRegistry implements InstrumentationTypeRegistry {

    private static final String GRADLE_CORE_PACKAGE_PREFIX = "org/gradle/";

    private final InstrumentationTypeRegistry gradleCoreInstrumentationTypeRegistry;
    private final Map<String, Set<String>> directSuperTypes;
    private final Map<String, Set<String>> instrumentedSuperTypes = new ConcurrentHashMap<>();

    public ExternalPluginsInstrumentationTypeRegistry(Map<String, Set<String>> directSuperTypes, InstrumentationTypeRegistry gradleCoreInstrumentationTypeRegistry) {
        this.directSuperTypes = ImmutableMap.copyOf(directSuperTypes);
        this.gradleCoreInstrumentationTypeRegistry = gradleCoreInstrumentationTypeRegistry;
    }

    @Override
    public Set<String> getSuperTypes(String type) {
        // TODO: This can probably be optimized: optimize it if it has performance impact
        Set<String> superTypes = Collections.emptySet();
        if (type.startsWith(GRADLE_CORE_PACKAGE_PREFIX)) {
            superTypes = gradleCoreInstrumentationTypeRegistry.getSuperTypes(type);
        }
        return !superTypes.isEmpty() ? superTypes : Collections.unmodifiableSet(computeAndCacheSuperTypesForExternalType(type));
    }

    private Set<String> computeAndCacheSuperTypesForExternalType(String type) {
        // We intentionally avoid using computeIfAbsent, since ConcurrentHashMap doesn't allow map modifications in the computeIfAbsent map function.
        // The result is, that multiple threads could recalculate the same key multiple times, but that is not a problem in our case.
        Set<String> computedInstrumentedSuperTypes = instrumentedSuperTypes.get(type);
        if (computedInstrumentedSuperTypes == null) {
            computedInstrumentedSuperTypes = computeInstrumentedSuperTypes(type);
            instrumentedSuperTypes.put(type, computedInstrumentedSuperTypes);
        }
        return computedInstrumentedSuperTypes;
    }

    private Set<String> computeInstrumentedSuperTypes(String type) {
        Set<String> collected = new HashSet<>();
        collected.add(type);
        for (String superType : getDirectSuperTypes(type)) {
            if (collected.add(superType)) {
                collected.addAll(computeAndCacheSuperTypesForExternalType(superType));
            }
        }
        // Keep just classes in the `org.gradle.` package. If we ever allow 3rd party plugins to contribute instrumentation,
        // we would need to be more precise and actually check if types are instrumented.
        return collected.stream()
            .filter(superType -> superType.startsWith(GRADLE_CORE_PACKAGE_PREFIX))
            .collect(Collectors.toSet());
    }

    private Set<String> getDirectSuperTypes(String type) {
        Set<String> superTypes = directSuperTypes.getOrDefault(type, Collections.emptySet());
        if (!superTypes.isEmpty() || !type.startsWith(GRADLE_CORE_PACKAGE_PREFIX)) {
            return superTypes;
        }
        return gradleCoreInstrumentationTypeRegistry.getSuperTypes(type);
    }

    @Override
    public boolean isEmpty() {
        return directSuperTypes.isEmpty() && gradleCoreInstrumentationTypeRegistry.isEmpty();
    }
}
