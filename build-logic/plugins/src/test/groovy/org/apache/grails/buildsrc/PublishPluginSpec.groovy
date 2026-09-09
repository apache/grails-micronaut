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
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class PublishPluginSpec extends Specification {

    @TempDir
    File tmp

    private File artifact(String name) {
        File f = new File(tmp, name)
        f.text = name
        f
    }

    private static PublishingExtension publishingOf(Project project) {
        project.pluginManager.apply('maven-publish')
        project.extensions.getByType(PublishingExtension)
    }

    void "collectChecksumInputFiles includes artifacts from every publication including the cli companion"() {
        given: "a project with a primary publication and an additional cli companion publication"
        Project project = ProjectBuilder.builder().withName('grails-core').build()
        PublishingExtension publishing = publishingOf(project)

        File mainJar = artifact('grails-core-1.0.jar')
        File cliJar = artifact('grails-core-cli-1.0.jar')
        File cliJavadoc = artifact('grails-core-cli-1.0-javadoc.jar')

        publishing.publications.create('maven', MavenPublication) { MavenPublication pub ->
            pub.artifact(mainJar)
        }
        publishing.publications.create('cli', MavenPublication) { MavenPublication pub ->
            pub.artifact(cliJar)
            pub.artifact(cliJavadoc) { it.classifier = 'javadoc' }
        }

        when:
        List<File> files = PublishPlugin.collectChecksumInputFiles(project, publishing)

        then: "both the primary and the cli companion artifacts are checksummed (the cli tier is not clobbered)"
        files.contains(mainJar)
        files.contains(cliJar)
        files.contains(cliJavadoc)
        files.size() == 3
    }

    void "collectChecksumInputFiles excludes plugin metadata sidecars"() {
        given: "a publication carrying a jar alongside the plugin descriptor and profile sidecars"
        Project project = ProjectBuilder.builder().withName('grails-core').build()
        PublishingExtension publishing = publishingOf(project)

        File jar = artifact('grails-core-1.0.jar')
        File pluginXml = artifact('grails-plugin.xml')
        File profileYml = artifact('profile.yml')

        publishing.publications.create('maven', MavenPublication) { MavenPublication pub ->
            pub.artifact(jar)
            pub.artifact(pluginXml)
            pub.artifact(profileYml)
        }

        when:
        List<File> files = PublishPlugin.collectChecksumInputFiles(project, publishing)

        then: "only the jar is checksummed; grails-plugin.xml and profile.yml are skipped"
        files == [jar]
    }

    void "collectChecksumInputFiles de-duplicates a file shared across publications"() {
        given: "two publications that both expose the same file"
        Project project = ProjectBuilder.builder().withName('grails-core').build()
        PublishingExtension publishing = publishingOf(project)

        File shared = artifact('grails-core-1.0.jar')

        publishing.publications.create('maven', MavenPublication) { MavenPublication pub ->
            pub.artifact(shared)
        }
        publishing.publications.create('cli', MavenPublication) { MavenPublication pub ->
            pub.artifact(shared)
        }

        when:
        List<File> files = PublishPlugin.collectChecksumInputFiles(project, publishing)

        then:
        files == [shared]
    }

    void "collectChecksumInputFiles copies POM-only publications to a canonical filename"() {
        given: "a java-platform style publication with no jar artifacts"
        Project project = ProjectBuilder.builder().withProjectDir(tmp).withName('grails-micronaut-bom').build()
        PublishingExtension publishing = publishingOf(project)
        publishing.publications.create('maven', MavenPublication) { MavenPublication pub ->
            pub.groupId = 'org.apache.grails'
            pub.artifactId = 'grails-micronaut-bom'
            pub.version = '8.0.0'
        }
        File pomDir = new File(tmp, 'build/publications/maven')
        pomDir.mkdirs()
        new File(pomDir, 'pom-default.xml').text = '<project/>'

        when:
        List<File> files = PublishPlugin.collectChecksumInputFiles(project, publishing)

        then:
        files.size() == 1
        files[0].name == 'grails-micronaut-bom-8.0.0.pom'
        files[0].text == '<project/>'
    }

    void "collectChecksumInputFiles includes jars plus canonical metadata for every publication"() {
        given: "the four published Micronaut coordinates: one plugin jar and three BOM publications"
        Project project = ProjectBuilder.builder().withProjectDir(tmp).withName('grails-micronaut').build()
        PublishingExtension publishing = publishingOf(project)
        File pluginJar = artifact('grails-micronaut-8.0.0.jar')
        publishing.publications.create('plugin', MavenPublication) { MavenPublication pub ->
            pub.groupId = 'org.apache.grails'
            pub.artifactId = 'grails-micronaut'
            pub.version = '8.0.0'
            pub.artifact(pluginJar)
        }
        ['grails-micronaut-bom', 'grails-hibernate5-micronaut-bom', 'grails-hibernate7-micronaut-bom'].each { String bom ->
            publishing.publications.create(bom, MavenPublication) { MavenPublication pub ->
                pub.groupId = 'org.apache.grails'
                pub.artifactId = bom
                pub.version = '8.0.0'
            }
            File pomDir = new File(tmp, "build/publications/${bom}")
            pomDir.mkdirs()
            new File(pomDir, 'pom-default.xml').text = "<project>${bom}</project>"
        }
        File pluginPomDir = new File(tmp, 'build/publications/plugin')
        pluginPomDir.mkdirs()
        new File(pluginPomDir, 'pom-default.xml').text = '<project>plugin</project>'

        when:
        List<File> files = PublishPlugin.collectChecksumInputFiles(project, publishing)

        then:
        files.contains(pluginJar)
        files*.name.containsAll([
                'grails-micronaut-8.0.0.jar',
                'grails-micronaut-8.0.0.pom',
                'grails-micronaut-bom-8.0.0.pom',
                'grails-hibernate5-micronaut-bom-8.0.0.pom',
                'grails-hibernate7-micronaut-bom-8.0.0.pom',
        ])
        files.size() == 5
    }
}
