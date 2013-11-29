/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.*

final class GrettyPlugin implements Plugin<Project> {

  void apply(final Project project) {

    project.extensions.create('gretty', GrettyPluginExtension)

    project.configurations {
      grettyHelperConfig
    }

    project.dependencies {
      providedCompile 'javax.servlet:javax.servlet-api:3.0.1'
      grettyHelperConfig 'org.akhikhl.gretty:gretty-helper:0.0.3'
    }

    for(String overlay in project.gretty.overlays)
      project.evaluationDependsOn(overlay)

    project.afterEvaluate {

      for(String overlay in project.gretty.overlays)
        project.dependencies.add 'providedCompile', project.project(overlay)

      project.task('prepareInplaceWebAppFolder', group: 'gretty', description: 'Copies webAppDir of this web-application and all WAR-overlays (if any) to ${buildDir}/inplaceWebapp') {
        for(String overlay in project.gretty.overlays)
          inputs.dir project.project(overlay).webAppDir
        inputs.dir project.webAppDir
        outputs.dir "${project.buildDir}/inplaceWebapp"
        doLast {
          Runner.prepareInplaceWebAppFolder(project)
        }
      }

      if(project.gretty.overlays) {

        project.ext.finalWarPath = project.tasks.war.archivePath

        project.tasks.war.archiveName = 'partialWar.war'

        // 'explodeWebApps' task is only activated by 'overlayWar' task
        project.task('explodeWebApps', group: 'gretty', description: 'Explodes this web-application and all WAR-overlays (if any) to ${buildDir}/explodedWebapp') {
          for(String overlay in project.gretty.overlays)
            dependsOn project.project(overlay).tasks.war
          dependsOn project.tasks.war
          for(String overlay in project.gretty.overlays)
            inputs.file Runner.getFinalWarPath(project.project(overlay))
          inputs.file project.tasks.war.archivePath
          outputs.dir "${project.buildDir}/explodedWebapp"
          doLast {
            Runner.prepareExplodedWebAppFolder(project)
          }
        }

        project.task('overlayWar', group: 'gretty', description: 'Creates WAR from exploded web-application in ${buildDir}/explodedWebapp') {
          dependsOn project.tasks.explodeWebApps
          inputs.dir "${project.buildDir}/explodedWebapp"
          outputs.file project.ext.finalWarPath
          doLast {
            ant.zip destfile: project.ext.finalWarPath,  basedir: "${project.buildDir}/explodedWebapp"
          }
        }

        project.tasks.assemble.dependsOn project.tasks.overlayWar
      } // overlays

      def setupInplaceWebAppDependencies = { task ->
        task.dependsOn project.tasks.classes
        task.dependsOn project.tasks.prepareInplaceWebAppFolder
        for(String overlay in project.gretty.overlays)
          task.dependsOn "${overlay}:classes" as String
      }

      def setupWarDependencies = { task ->
        if(project.gretty.overlays)
          task.dependsOn project.tasks.overlayWar
        else
          task.dependsOn project.tasks.war
      }

      project.task('jettyRun', group: 'gretty', description: 'Starts jetty server inplace, in interactive mode (keypress stops the server).') { task ->
        setupInplaceWebAppDependencies task
        task.doLast {
          new Runner(project, inplace: true, interactive: true).consoleStart()
        }
      }

      project.task('jettyRunWar', group: 'gretty', description: 'Starts jetty server on WAR-file, in interactive mode (keypress stops the server).') { task ->
        setupWarDependencies task
        task.doLast {
          new Runner(project, inplace: false, interactive: true).consoleStart()
        }
      }

      project.task('jettyStart', group: 'gretty', description: 'Starts jetty server inplace, in batch mode (\'jettyStop\' stops the server).') { task ->
        setupInplaceWebAppDependencies task
        task.doLast {
          new Runner(project, inplace: true, interactive: false).consoleStart()
        }
      }

      project.task('jettyStartWar', group: 'gretty', description: 'Starts jetty server on WAR-file, in batch mode (\'jettyStop\' stops the server).') { task ->
        setupWarDependencies task
        task.doLast {
          new Runner(project, inplace: false, interactive: false).consoleStart()
        }
      }

      project.task('jettyStop', group: 'gretty', description: 'Sends \'stop\' command to running jetty server.') {
        doLast {
          Runner.sendServiceCommand project.gretty.servicePort, 'stop'
        }
      }

      project.task('jettyRestart', group: 'gretty', description: 'Sends \'restart\' command to running jetty server.') {
        doLast {
          Runner.sendServiceCommand project.gretty.servicePort, 'restart'
        }
      }
    } // afterEvaluate
  } // apply
} // plugin

