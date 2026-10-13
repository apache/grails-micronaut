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

import groovy.transform.CompileStatic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes the Groovy compiler configuration a published project compiles with.
 *
 * <p>The shared reproducibility script is copied through, then this repository's
 * {@code project.version} and project name are stamped as compiler node metadata.
 * Without that stamp, the Grails transform falls back to grails-core's
 * {@code Implementation-Version}.</p>
 *
 * <p>The base script is a required input. A missing file fails this task instead of
 * producing a script with an empty base.</p>
 */
@CompileStatic
@DisableCachingByDefault(because = 'Writing a short script from two inputs is cheaper than a cache round trip')
abstract class PublishedVersionCompilerScriptTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getBaseScript()

    @Input
    abstract Property<String> getPublishedVersion()

    @Input
    abstract Property<String> getPublishedName()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void writeScript() {
        File target = outputFile.get().asFile
        target.parentFile.mkdirs()
        String base = baseScript.get().asFile.getText(StandardCharsets.UTF_8.name())
        String version = escapeGroovyLiteral(publishedVersion.get())
        String name = escapeGroovyLiteral(publishedName.get())
        target.setText("""${base}

withConfig(configuration) {
    inline(phase: 'CONVERSION') { source, context, classNode ->
        classNode.putNodeMetaData('projectVersion', '${version}')
        classNode.putNodeMetaData('projectName', '${name}')
    }
}
""", StandardCharsets.UTF_8.name())
    }

    private static String escapeGroovyLiteral(String value) {
        value.replace('\\', '\\\\').replace('\'', '\\\'')
    }
}
