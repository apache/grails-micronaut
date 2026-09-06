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
package org.apache.grails.micronaut

import io.micronaut.context.ApplicationContext as MicronautApplicationContext
import io.micronaut.context.env.Environment as MicronautEnvironment

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

import grails.plugins.GrailsPlugin
import grails.plugins.GrailsPluginManager
import spock.lang.Specification

class GrailsMicronautGrailsPluginSpec extends Specification {

    GrailsMicronautGrailsPlugin plugin = new GrailsMicronautGrailsPlugin()
    ConfigurableApplicationContext applicationContext = Mock()

    def setup() {
        plugin.applicationContext = applicationContext
    }

    def 'does nothing when no Grails plugin manager is available'() {
        when:
        plugin.doWithApplicationContext()

        then:
        1 * applicationContext.getBeanNamesForType(GrailsPluginManager) >> []
        0 * applicationContext._
    }

    def 'requires the Micronaut application context before loading plugin configuration'() {
        given:
        plugin.pluginManager = Stub(GrailsPluginManager)

        when:
        plugin.doWithApplicationContext()

        then:
        1 * applicationContext.getBeanNamesForType(GrailsPluginManager) >> []
        1 * applicationContext.containsBean('micronautApplicationContext') >> false
        thrown(IllegalStateException)
    }

    def 'copies Grails plugin property sources into Micronaut and refreshes its environment'() {
        given:
        def springEnvironment = new StandardEnvironment()
        def pluginPropertySource = new MapPropertySource('sample-plugin.yml', [sample: 'value'])
        springEnvironment.propertySources.addFirst(pluginPropertySource)
        def micronautEnvironment = Mock(MicronautEnvironment)
        def micronautContext = Stub(MicronautApplicationContext) {
            getEnvironment() >> micronautEnvironment
        }
        def grailsPlugin = Stub(GrailsPlugin) {
            getName() >> 'sample'
            getPropertySource() >> pluginPropertySource
        }
        def pluginManager = Stub(GrailsPluginManager) {
            getAllPlugins() >> ([grailsPlugin] as GrailsPlugin[])
        }

        when:
        plugin.doWithApplicationContext()

        then:
        1 * applicationContext.getBeanNamesForType(GrailsPluginManager) >> ['grailsPluginManager']
        1 * applicationContext.getBean(GrailsPluginManager) >> pluginManager
        1 * applicationContext.containsBean('micronautApplicationContext') >> true
        1 * applicationContext.getBean('micronautApplicationContext', MicronautApplicationContext) >> micronautContext
        1 * applicationContext.getEnvironment() >> springEnvironment
        1 * micronautEnvironment.addPropertySource({ it.name == 'grails.plugins.sample' })
        1 * micronautEnvironment.refresh()
    }
}
