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

import org.gradle.api.Project
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.jar.JarFile

class CompilePluginSpec extends Specification {

    @TempDir
    File projectDir

    @TempDir
    Path testProjectDir

    void 'the hand-authored auto-configuration imports file is a compiler input'() {
        given:
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        GroovyCompile compileGroovy = project.tasks.register('compileGroovy', GroovyCompile).get()
        File importsFile = new File(projectDir, CompilePlugin.AUTO_CONFIGURATION_IMPORTS_PATH)
        importsFile.parentFile.mkdirs()
        importsFile.text = 'example.ExampleAutoConfiguration\n'

        when:
        CompilePlugin.registerAutoConfigurationImportsInput(project, compileGroovy)
        CompilePlugin.registerAutoConfigurationImportsInput(project, compileGroovy)

        then:
        compileGroovy.inputs.files.files*.canonicalFile.contains(importsFile.canonicalFile)
        compileGroovy.inputs.files.files.count { it.canonicalFile == importsFile.canonicalFile } == 1
    }

    def setup() {
        testProjectDir.resolve('settings.gradle').toFile().text = ''
        testProjectDir.resolve('.asf.yaml').toFile().text = ''
        def configScript = testProjectDir.resolve('gradle/groovy-compile-configscript.groovy').toFile()
        configScript.parentFile.mkdirs()
        configScript.text = ''
        testProjectDir.resolve('build.gradle').toFile().text = """
            plugins {
                id 'groovy'
                id 'org.apache.grails.buildsrc.compile'
            }

            ext {
                javaVersion = 21
                grailsVersion = '8.0.0-SNAPSHOT'
                formattedBuildDate = '2026-01-01'
            }

            repositories {
                mavenCentral()
            }

            tasks.register('printIndy') {
                def compileTask = tasks.named('compileGroovy', org.gradle.api.tasks.compile.GroovyCompile)
                def testCompileTask = tasks.named('compileTestGroovy', org.gradle.api.tasks.compile.GroovyCompile)
                doLast {
                    println "MAIN_INDY=\${compileTask.get().groovyOptions.optimizationOptions.indy}"
                    println "TEST_INDY=\${testCompileTask.get().groovyOptions.optimizationOptions.indy}"
                }
            }
        """
    }

    def "disables invokedynamic on GroovyCompile tasks by default"() {
        when:
        def result = runPrintIndy()

        then:
        result.task(':printIndy').outcome == TaskOutcome.SUCCESS
        result.output.contains('MAIN_INDY=false')
        result.output.contains('TEST_INDY=false')
    }

    def "enables invokedynamic when grailsIndy is true"() {
        when:
        def result = runPrintIndy('-PgrailsIndy=true')

        then:
        result.task(':printIndy').outcome == TaskOutcome.SUCCESS
        result.output.contains('MAIN_INDY=true')
        result.output.contains('TEST_INDY=true')
    }

    def "trims whitespace when parsing grailsIndy"() {
        when:
        def result = runPrintIndy('-PgrailsIndy= true ')

        then:
        result.task(':printIndy').outcome == TaskOutcome.SUCCESS
        result.output.contains('MAIN_INDY=true')
        result.output.contains('TEST_INDY=true')
    }

    def "compiler configuration stamps the published project version"() {
        given:
        usePublishedBuild('projectVersion')

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(['grailsCompilerConfigCompileGroovy', 'jar', '-PprojectVersion=8.0.0-SNAPSHOT', '--stacktrace'])
                .withPluginClasspath()
                .build()

        then:
        result.task(':grailsCompilerConfigCompileGroovy').outcome == TaskOutcome.SUCCESS
        result.task(':jar').outcome == TaskOutcome.SUCCESS
        def script = testProjectDir.resolve('build/groovy-compiler/compileGroovy-config.groovy').toFile().text
        script.contains("putNodeMetaData('projectVersion', '8.0.0-SNAPSHOT')")
        script.contains("putNodeMetaData('projectName', 'grails-micronaut')")
        implementationVersion('8.0.0-SNAPSHOT') == '8.0.0-SNAPSHOT'
        !script.contains('7.9.9')
    }

    def "release version configures and stamps project.version"() {
        given:
        usePublishedBuild('projectVersion')

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(['grailsCompilerConfigCompileGroovy', 'jar', '-PprojectVersion=8.0.0', '--stacktrace'])
                .withPluginClasspath()
                .build()

        then:
        result.task(':grailsCompilerConfigCompileGroovy').outcome == TaskOutcome.SUCCESS
        result.task(':jar').outcome == TaskOutcome.SUCCESS
        def script = testProjectDir.resolve('build/groovy-compiler/compileGroovy-config.groovy').toFile().text
        script.contains("putNodeMetaData('projectVersion', '8.0.0')")
        !script.contains('SNAPSHOT')
        !script.contains('7.9.9')
        script.contains("putNodeMetaData('projectName', 'grails-micronaut')")
        implementationVersion('8.0.0') == '8.0.0'
    }

    def "compiler version stamp is limited to published projects"() {
        given:
        testProjectDir.resolve('build.gradle').toFile().text = '''
            plugins {
                id 'groovy'
                id 'org.apache.grails.buildsrc.compile'
            }

            version = '0.1'

            ext {
                javaVersion = 21
                grailsVersion = '8.0.0'
                formattedBuildDate = '2026-01-01'
            }

            repositories {
                mavenCentral()
            }

            tasks.register('printCompilerScript') {
                def compileTask = tasks.named('compileGroovy', org.gradle.api.tasks.compile.GroovyCompile)
                doLast {
                    println "HAS_WRITER=${tasks.names.contains('grailsCompilerConfigCompileGroovy')}"
                    println "SCRIPT=${compileTask.get().groovyOptions.configurationScript}"
                }
            }
        '''

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(['printCompilerScript', '--stacktrace'])
                .withPluginClasspath()
                .build()

        then:
        result.task(':printCompilerScript').outcome == TaskOutcome.SUCCESS
        result.output.contains('HAS_WRITER=false')
        result.output.contains('groovy-compile-configscript.groovy')
    }

    def "a GroovyCompile outside a source set keeps the shared compiler script"() {
        given:
        usePublishedBuild('projectVersion', '''
            tasks.register('printExtraCompilerScript') {
                def extra = tasks.named('compileExtraGroovy', org.gradle.api.tasks.compile.GroovyCompile)
                doLast {
                    println "EXTRA_SCRIPT=${extra.get().groovyOptions.configurationScript}"
                    println "HAS_EXTRA_WRITER=${tasks.names.contains('grailsCompilerConfigCompileExtraGroovy')}"
                }
            }
        ''')

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(['printExtraCompilerScript', '-PprojectVersion=8.0.0', '--stacktrace'])
                .withPluginClasspath()
                .build()

        then:
        result.task(':printExtraCompilerScript').outcome == TaskOutcome.SUCCESS
        result.output.contains('groovy-compile-configscript.groovy')
        result.output.contains('HAS_EXTRA_WRITER=false')
        !result.output.contains('compileExtraGroovy-config.groovy')
    }

    def "clean compileGroovy regenerates the compiler script in one invocation"() {
        given:
        def sourceDir = testProjectDir.resolve('src/main/groovy').toFile()
        sourceDir.mkdirs()
        new File(sourceDir, 'Hello.groovy').text = 'class Hello { String greet() { "ok" } }\n'
        usePublishedBuild('projectVersion', '''
            dependencies {
                implementation localGroovy()
            }
        ''')
        // Give clean real work. An empty build directory leaves clean UP_TO_DATE, which never
        // proves the compiler script is recreated after deletion.
        def staleBuild = testProjectDir.resolve('build').toFile()
        staleBuild.mkdirs()
        new File(staleBuild, 'delete-me.txt').text = 'stale'

        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(['clean', 'compileGroovy', '-PprojectVersion=8.0.0-SNAPSHOT', '--stacktrace'])
                .withPluginClasspath()
                .build()

        then:
        result.task(':clean').outcome == TaskOutcome.SUCCESS
        result.task(':grailsCompilerConfigCompileGroovy').outcome == TaskOutcome.SUCCESS
        result.task(':compileGroovy').outcome == TaskOutcome.SUCCESS
        testProjectDir.resolve('build/groovy-compiler/compileGroovy-config.groovy').toFile().text
                .contains("putNodeMetaData('projectVersion', '8.0.0-SNAPSHOT')")
        testProjectDir.resolve('build/classes/groovy/main/Hello.class').toFile().exists()
    }

    private void usePublishedBuild(String versionLine, String extra = '') {
        testProjectDir.resolve('settings.gradle').toFile().text = "rootProject.name = 'grails-micronaut'\n"
        testProjectDir.resolve('LICENSE').toFile().text = 'Apache License\n'
        testProjectDir.resolve('NOTICE').toFile().text = 'Apache Grails\n'
        testProjectDir.resolve('build.gradle').toFile().text = """
            plugins {
                id 'groovy'
                id 'org.apache.grails.gradle.grails-publish'
                id 'org.apache.grails.buildsrc.publish'
                id 'org.apache.grails.buildsrc.compile'
            }

            // Realize the jar before version is assigned. Implementation-Version must
            // still read project.version when the manifest is written.
            tasks.named('jar').get()

            version = ${versionLine}
            group = 'org.apache.grails'

            ext {
                javaVersion = 21
                grailsVersion = '7.9.9'
                formattedBuildDate = '2026-01-01'
            }

            repositories {
                mavenCentral()
            }

            tasks.register('compileExtraGroovy', org.gradle.api.tasks.compile.GroovyCompile) {
                source = files()
                classpath = files()
                destinationDirectory = layout.buildDirectory.dir('extra')
            }
            ${extra}
        """
    }

    private String implementationVersion(String version) {
        File jar = testProjectDir.resolve("build/libs/grails-micronaut-${version}.jar").toFile()
        assert jar.isFile()
        new JarFile(jar).withCloseable { JarFile file ->
            file.manifest.mainAttributes.getValue('Implementation-Version')
        }
    }

    private def runPrintIndy(String... extraArgs) {
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(['printIndy', '--stacktrace'] + (extraArgs as List))
                .withPluginClasspath()
                .build()
    }
}
