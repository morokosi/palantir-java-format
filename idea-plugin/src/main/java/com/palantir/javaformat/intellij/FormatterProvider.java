/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.javaformat.intellij;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JdkUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.SystemInfo;
import com.palantir.javaformat.bootstrap.BootstrappingFormatterService;
import com.palantir.javaformat.java.FormatterService;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.jar.Attributes.Name;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FormatterProvider {
    private static final Logger log = LoggerFactory.getLogger(FormatterProvider.class);

    private static final String PLUGIN_ID = "palantir-java-format";

    // Cache to avoid creating a URLClassloader every time we want to format from IntelliJ
    private final LoadingCache<FormatterCacheKey, Optional<FormatterService>> implementationCache =
            Caffeine.newBuilder().maximumSize(1).build(FormatterProvider::createFormatter);

    Optional<FormatterService> get(Project project, PalantirJavaFormatSettings settings) {
        return implementationCache.get(new FormatterCacheKey(
                project,
                getSdkVersion(project),
                settings.getImplementationClassPath(),
                settings.injectedVersionIsOutdated()));
    }

    @SuppressWarnings("for-rollout:Slf4jLogsafeArgs")
    private static Optional<FormatterService> createFormatter(FormatterCacheKey cacheKey) {
        if (cacheKey.jdkMajorVersion.isEmpty()) {
            return Optional.empty();
        }

        int jdkMajorVersion = cacheKey.jdkMajorVersion.getAsInt();
        List<Path> implementationClasspath =
                getImplementationUrls(cacheKey.implementationClassPath, cacheKey.useBundledImplementation);

        // When running with JDK 15+ or using newer language features, we use the bootstrapping formatter which injects
        // required "--add-exports" args.
        if (useBootstrappingFormatter(jdkMajorVersion, Runtime.version().feature())) {
            Path jdkPath = getJdkPath(cacheKey.project);
            log.info("Using bootstrapping formatter with jdk version {} and path: {}", jdkMajorVersion, jdkPath);
            return Optional.of(new BootstrappingFormatterService(jdkPath, jdkMajorVersion, implementationClasspath));
        }

        // Use "in-process" formatter service
        log.info("Using in-process formatter for jdk version {}", jdkMajorVersion);
        URL[] implementationUrls = toUrlsUnchecked(implementationClasspath);
        ClassLoader classLoader = new URLClassLoader(implementationUrls, FormatterService.class.getClassLoader());
        return ServiceLoader.load(FormatterService.class, classLoader).findFirst();
    }

    /**
     * When projects use JDK 15+ as their SDK, they might use newer language features which are only supported by the
     * bootstrapping formatter.
     * Separately, starting from 2022.2, Intellij now runs with JDK 17 which also requires the
     * bootstrapping formatter unless custom VM Options to add exports are provided.
     */
    private static boolean useBootstrappingFormatter(int jdkMajorVersion, int jreMajorVersion) {
        return (jreMajorVersion < 15 && jdkMajorVersion >= 15) || !isModulesExported();
    }

    private static boolean isModulesExported() {
        return Stream.of(
                        "com.sun.tools.javac.api.JavacTrees",
                        "com.sun.tools.javac.file.JavacFileManager",
                        "com.sun.tools.javac.parser.JavacParser",
                        "com.sun.tools.javac.tree.JCTree",
                        "com.sun.tools.javac.util.Log")
                .allMatch(className -> {
                    Class<?> klass;
                    try {
                        klass = Class.forName(className);
                    } catch (ClassNotFoundException e) {
                        return false;
                    }
                    return klass.getModule()
                            .isExported(
                                    klass.getPackageName(),
                                    FormatterProvider.class.getClassLoader().getUnnamedModule());
                });
    }

    private static List<Path> getProvidedImplementationUrls(List<URI> implementationClasspath) {
        return implementationClasspath.stream().map(Path::of).collect(Collectors.toList());
    }

    @SuppressWarnings("for-rollout:Slf4jLogsafeArgs")
    private static List<Path> getBundledImplementationUrls() {
        // Load from the jars bundled with the plugin.
        IdeaPluginDescriptor ourPlugin = Preconditions.checkNotNull(
                PluginManager.getPlugin(PluginId.getId(PLUGIN_ID)), "Couldn't find our own plugin: %s", PLUGIN_ID);
        Path implDir = ourPlugin.getPath().toPath().resolve("impl");
        log.debug("Using palantir-java-format implementation bundled with plugin: {}", implDir);
        return listDirAsUrlsUnchecked(implDir);
    }

    @SuppressWarnings("for-rollout:Slf4jLogsafeArgs")
    private static List<Path> getImplementationUrls(
            Optional<List<URI>> implementationClassPath, boolean useBundledImplementation) {
        if (useBundledImplementation) {
            log.debug("Using palantir-java-format implementation bundled with plugin");
            return getBundledImplementationUrls();
        }
        return implementationClassPath
                .map(classpath -> {
                    log.debug("Using palantir-java-format implementation defined by URIs: {}", classpath);
                    return getProvidedImplementationUrls(classpath);
                })
                .orElseGet(() -> {
                    log.debug("Using palantir-java-format implementation bundled with plugin");
                    return getBundledImplementationUrls();
                });
    }

    private static Path getJdkPath(Project project) {
        return getProjectJdk(project)
                .map(Sdk::getHomePath)
                .map(Path::of)
                .map(sdkHome -> sdkHome.resolve("bin").resolve("java" + (SystemInfo.isWindows ? ".exe" : "")))
                .filter(Files::exists)
                .orElseThrow(() ->
                        new IllegalStateException("Could not determine JDK path for project: " + project.getName()));
    }

    private static OptionalInt getSdkVersion(Project project) {
        return getProjectJdk(project)
                .map(FormatterProvider::parseSdkJavaVersion)
                .orElseThrow(() ->
                        // This is not that common as our Gradle infrastructure should setup an SDK, but it does
                        // happen on occassion and it manifests as the plugin ceasing to format. Give a slight
                        // nudge for the manual remediation.
                        new IllegalStateException("Could not determine SDK version for project: " + project.getName()
                                + ". Ensure you have an SDK set in "
                                + "'Project Structure' -> 'Project Settings' -> 'Project' -> 'SDK'"));
    }

    private static OptionalInt parseSdkJavaVersion(Sdk sdk) {
        // Parses the actual version out of "SDK#getVersionString" which returns 'java version "15"'
        // or 'openjdk version "15.0.2"'.
        String version = Preconditions.checkNotNull(
                JdkUtil.getJdkMainAttribute(sdk, Name.IMPLEMENTATION_VERSION), "JDK version is null");
        return parseSdkJavaVersion(version);
    }

    @SuppressWarnings("for-rollout:Slf4jLogsafeArgs")
    @VisibleForTesting
    static OptionalInt parseSdkJavaVersion(String version) {
        int indexOfVersionDelimiter = version.indexOf('.');
        String normalizedVersion =
                indexOfVersionDelimiter >= 0 ? version.substring(0, indexOfVersionDelimiter) : version;
        normalizedVersion = normalizedVersion.replaceAll("-ea", "");
        try {
            return OptionalInt.of(Integer.parseInt(normalizedVersion));
        } catch (NumberFormatException e) {
            log.error("Could not parse sdk version: {}", version, e);
            return OptionalInt.empty();
        }
    }

    private static Optional<Sdk> getProjectJdk(Project project) {
        return Optional.ofNullable(ProjectRootManager.getInstance(project).getProjectSdk());
    }

    private static URL[] toUrlsUnchecked(List<Path> paths) {
        return paths.stream()
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (IllegalArgumentException | MalformedURLException e) {
                        throw new RuntimeException("Couldn't convert Path to URL: " + path, e);
                    }
                })
                .toArray(URL[]::new);
    }

    private static List<Path> listDirAsUrlsUnchecked(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Couldn't list dir: " + dir, e);
        }
    }

    private static final class FormatterCacheKey {
        private final Project project;
        private final OptionalInt jdkMajorVersion;
        private final Optional<List<URI>> implementationClassPath;
        private final boolean useBundledImplementation;

        FormatterCacheKey(
                Project project,
                OptionalInt jdkMajorVersion,
                Optional<List<URI>> implementationClassPath,
                boolean useBundledImplementation) {
            this.project = project;
            this.jdkMajorVersion = jdkMajorVersion;
            this.implementationClassPath = implementationClassPath;
            this.useBundledImplementation = useBundledImplementation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            FormatterCacheKey that = (FormatterCacheKey) o;
            return Objects.equals(jdkMajorVersion, that.jdkMajorVersion)
                    && useBundledImplementation == that.useBundledImplementation
                    && Objects.equals(project, that.project)
                    && Objects.equals(implementationClassPath, that.implementationClassPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(project, jdkMajorVersion, implementationClassPath, useBundledImplementation);
        }
    }
}
