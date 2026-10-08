/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.grails.buildsrc

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

import groovy.transform.CompileStatic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

import static org.apache.grails.buildsrc.GradleUtils.lookupProperty
import static org.apache.grails.buildsrc.GradleUtils.lookupPropertyByType

@CompileStatic
class CompilePlugin implements Plugin<Project> {

    static final String AUTO_CONFIGURATION_IMPORTS_PATH =
            'src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'
    private static final String AUTO_CONFIGURATION_IMPORTS_INPUT_REGISTERED =
            'grailsAutoConfigurationImportsInputRegistered'

    @Override
    void apply(Project project) {
        def initialized = new AtomicBoolean(false)
        project.plugins.withId('java') { // java (applied when groovy is applied) or java-library
            if (initialized.compareAndSet(false, true)) {
                configureCompile(project)
            }
        }
    }

    private static void configureCompile(Project project) {
        configureJavaVersion(project)
        configureJars(project)
        configureCompiler(project)
        configureReproducible(project)
    }

    private static void configureJavaVersion(Project project) {
        Integer javaVersion = lookupPropertyByType(project, 'javaVersion', Integer)
        project.tasks.withType(JavaCompile).configureEach {
            it.options.release.set(javaVersion)
        }
    }

    private static void configureJars(Project project) {
        project.extensions.configure(JavaPluginExtension) {
            it.withJavadocJar()
            it.withSourcesJar()
        }

        // Grails reads Implementation-Version from META-INF/MANIFEST.MF when compiler
        // metadata is absent. Read project.version, which the build script assigns from
        // projectVersion (including -PprojectVersion on a release), so the manifest and the
        // compiler stamp share one source. grailsVersion is the released framework line and
        // must not leak into this repository's jars.
        // Note: we exclude attributes such as Built-By, Build-Jdk, Created-By to ensure the build is reproducible.
        project.tasks.withType(Jar).configureEach { Jar jar ->
            if (lookupPropertyByType(project, 'skipJavaComponent', Boolean)) {
                jar.enabled = false
                return
            }

            jar.manifest.attributes(
                    'Implementation-Title': 'Apache Grails',
                    'Implementation-Version': project.version.toString(),
                    'Implementation-Vendor': 'grails.apache.org'
            )
            // Explicitly fail since duplicates indicate a double configuration that needs fixed
            jar.duplicatesStrategy = DuplicatesStrategy.FAIL
        }
    }

    private static void configureCompiler(Project project) {
        project.tasks.withType(JavaCompile).configureEach {
            // Preserve method parameter names in Groovy/Java classes for IDE parameter hints & bean reflection metadata.
            it.options.compilerArgs.add('-parameters')
            // encoding needs to be the same since it's different across platforms
            it.options.encoding = StandardCharsets.UTF_8.name()
            it.options.fork = true
            it.options.forkOptions.jvmArgs = ['-Xms128M', '-Xmx2G']
            if (System.getenv('SUPPRESS_DEPRECATION_WARNINGS') == 'true') {
                it.options.compilerArgs += ['-Xlint:-removal']
            }
        }

        project.plugins.withId('groovy') {
            project.tasks.withType(GroovyCompile).configureEach {
                // encoding needs to be the same since it's different across platforms
                it.groovyOptions.encoding = StandardCharsets.UTF_8.name()
                // Preserve method parameter names in Groovy/Java classes for IDE parameter hints & bean reflection metadata.
                it.groovyOptions.parameters = true
                // Grails 8 keeps invokedynamic off for published artifacts. Groovy 5's
                // compiler default is indy=true, which is a large runtime regression for
                // dynamic Groovy (see #15293). Unpublished build-logic uses Gradle's
                // default. Grails 9 / Groovy 6 can flip this. CI can still opt in with
                // -PgrailsIndy=true (same property as grails-extension-gradle-config.gradle).
                it.groovyOptions.optimizationOptions.put('indy', lookupProperty(project, 'grailsIndy', false))
                // encoding needs to be the same since it's different across platforms
                it.options.encoding = StandardCharsets.UTF_8.name()
                it.options.fork = true
                // always set an isolated build to ensure grails.factories aren't accidentally merged since every project
                // in this mono repo should be an isolated projected
                it.options.forkOptions.jvmArgs = ['-Xms128M', '-Xmx2G', '-Dgrails.isolated.build=true']
                // Publish THIS project's base.dir to the forked Groovy compiler. Gradle reuses a forked
                // compiler daemon for a task whose requested fork arguments the daemon already satisfies,
                // so a compile that does NOT request base.dir can be handed a daemon started for another
                // module and inherit that module's base.dir — merging one module's checked-in
                // grails.factories into another (a real, data-dependent leak in this mono repo). Requesting
                // a unique base.dir on EVERY module's compile keeps daemons partitioned per project, so the
                // value can never cross modules. Mirrors GrailsAppBaseDirProvider from the Grails Gradle
                // plugins (which is not on build-logic's classpath).
                it.options.forkOptions.jvmArgumentProviders.add(new BaseDirArgumentProvider(project.projectDir))
                if (System.getenv('SUPPRESS_DEPRECATION_WARNINGS') == 'true') {
                    it.options.compilerArgs += ['-Xlint:-removal']
                }
                // Canonicalize annotation member order for reproducible builds. Annotations copied from
                // precompiled classes (e.g. @DelegatesTo on trait methods woven into controllers and GORM
                // entities) have their members ordered by Class.getDeclaredMethods(), which varies between
                // JVM runs. The GrailsGradlePlugin merges this script with its own configuration script
                // when both are present.
                it.groovyOptions.configurationScript =
                        GradleUtils.findRootGrailsCoreDir(project).file('gradle/groovy-compile-configscript.groovy').asFile
            }

            // Published projects replace that shared script with one that also stamps project.version.
            // Projects that only apply the Grails Gradle plugins do not apply this publish plugin, so
            // their own compiler configuration is left alone.
            project.plugins.withId('org.apache.grails.buildsrc.publish') {
                configurePublishedVersionScript(project)
            }
            project.tasks.named('compileGroovy', GroovyCompile).configure { GroovyCompile task ->
                // Resource-only changes do not ordinarily invalidate compilation. This file changes
                // whether the compiler owns the generated imports resource, so adding or deleting it
                // must run the transform even when no Groovy source changed.
                registerAutoConfigurationImportsInput(project, task)
            }
        }
    }

    /**
     * Stamps {@code project.version} onto every source set's Groovy compile.
     *
     * <p>The writer is registered from the source-set container. Registering a task while
     * iterating {@code tasks.withType(GroovyCompile)} throws {@code ConcurrentModificationException}
     * once {@code projectVersion} is not a snapshot. Wiring
     * {@code configurationScriptFile} to the writer's output makes the compile task depend on
     * the writer, including after {@code clean} deletes {@code build/}.</p>
     */
    private static void configurePublishedVersionScript(Project project) {
        SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
        sourceSets.configureEach { SourceSet sourceSet ->
            registerPublishedVersionScript(project, sourceSet.getCompileTaskName('groovy'))
        }

        project.tasks.withType(GroovyCompile).configureEach { GroovyCompile compile ->
            TaskProvider<PublishedVersionCompilerScriptTask> writer = project.tasks.named(
                    publishedVersionScriptTaskName(compile.name),
                    PublishedVersionCompilerScriptTask
            )
            compile.groovyOptions.configurationScriptFile.set(writer.flatMap { PublishedVersionCompilerScriptTask task ->
                task.outputFile
            })
        }
    }

    private static TaskProvider<PublishedVersionCompilerScriptTask> registerPublishedVersionScript(Project project,
                                                                                                   String compileTaskName) {
        String writerName = publishedVersionScriptTaskName(compileTaskName)
        if (project.tasks.names.contains(writerName)) {
            return project.tasks.named(writerName, PublishedVersionCompilerScriptTask)
        }

        Provider<String> version = project.provider { project.version.toString() }
        Provider<String> name = project.provider { project.name }
        project.tasks.register(writerName, PublishedVersionCompilerScriptTask) { PublishedVersionCompilerScriptTask task ->
            // `clean compileGroovy` schedules both, and command-line order is not a dependency.
            // Without this, clean can delete the script after the writer creates it.
            task.mustRunAfter('clean')
            task.baseScript.set(GradleUtils.findRootGrailsCoreDir(project)
                    .file('gradle/groovy-compile-configscript.groovy'))
            task.publishedVersion.set(version)
            task.publishedName.set(name)
            task.outputFile.set(project.layout.buildDirectory.file("groovy-compiler/${compileTaskName}-config.groovy"))
        }
    }

    private static String publishedVersionScriptTaskName(String compileTaskName) {
        "grailsCompilerConfig${compileTaskName.capitalize()}"
    }

    static void registerAutoConfigurationImportsInput(Project project, GroovyCompile task) {
        if (task.extensions.extraProperties.has(AUTO_CONFIGURATION_IMPORTS_INPUT_REGISTERED)) {
            return
        }
        task.extensions.extraProperties.set(AUTO_CONFIGURATION_IMPORTS_INPUT_REGISTERED, true)
        task.inputs.files(project.layout.projectDirectory.file(AUTO_CONFIGURATION_IMPORTS_PATH))
                .withPropertyName('grailsAutoConfigurationImports')
                .withPathSensitivity(PathSensitivity.RELATIVE)
    }

    private static void configureReproducible(Project project) {
        project.tasks.withType(Javadoc).configureEach { Javadoc it ->
            def options = it.options as StandardJavadocDocletOptions
            options.noTimestamp = true
            options.bottom = "Generated ${lookupPropertyByType(project, 'formattedBuildDate', String)} (UTC)"
        }

        // Any jar, zip, or archive should be reproducible
        // No longer needed after https://github.com/gradle/gradle/issues/30871
        project.tasks.withType(AbstractArchiveTask).configureEach {
            it.preserveFileTimestamps = false // to prevent timestamp mismatches
            it.reproducibleFileOrder = true // to keep the same ordering
            // to avoid platform specific defaults, set the permissions consistently
            it.filePermissions { permissions ->
                permissions.unix(0644)
            }
            it.dirPermissions { permissions ->
                permissions.unix(0755)
            }
        }
    }
}
