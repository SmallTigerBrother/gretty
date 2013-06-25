package gretty

import org.eclipse.jetty.server.Server
import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.*

class GrettyPlugin implements Plugin<Project> {

  void apply(final Project project) {

    project.extensions.create('gretty', GrettyPluginExtension)

    project.afterEvaluate {

      project.configurations {
        compile.exclude group: 'javax.servlet', module: 'servlet-api'
        gretty
        gretty.exclude group: 'org.eclipse.jetty.orbit', module: 'javax.servlet'
      }

      project.dependencies {
        gretty 'org.akhikhl.gretty:gretty-helper:0.0.1'
        gretty 'javax.servlet:javax.servlet-api:3.0.1'
        gretty 'org.eclipse.jetty:jetty-server:8.1.8.v20121106'
        gretty 'org.eclipse.jetty:jetty-servlet:8.1.8.v20121106'
        gretty 'org.eclipse.jetty:jetty-webapp:8.1.8.v20121106'
        gretty 'org.eclipse.jetty:jetty-security:8.1.8.v20121106'
        gretty 'org.eclipse.jetty:jetty-jsp:8.1.8.v20121106'
      }

      String buildWebAppFolder = "${project.buildDir}/webapp"

      project.task('prepareInplaceWebAppFolder', type: Copy, group: 'gretty', description: 'Copies webAppDir of this web-application and all WAR-overlays (if any) to ${buildDir}/webapp') {
        for(Project overlay in project.gretty.overlays) {
          from overlay.webAppDir
          into buildWebAppFolder
        }
        from project.webAppDir
        into buildWebAppFolder
      }

      if(project.gretty.overlays) {

        String warFileName = project.tasks.war.archiveName

        project.tasks.war { archiveName 'thiswar.war' }

        project.task('explodeWebApps', type: Copy, group: 'gretty', description: 'Explodes this web-application and all WAR-overlays (if any) to ${buildDir}/webapp') {
          for(Project overlay in project.gretty.overlays) {
            dependsOn overlay.tasks.war
            from overlay.zipTree(overlay.tasks.war.archivePath)
            into buildWebAppFolder
          }
          dependsOn project.tasks.war
          from project.zipTree(project.tasks.war.archivePath)
          into buildWebAppFolder
        }

        project.task('overlayWar', type: Zip, group: 'gretty', description: 'Creates WAR from exploded web-application in ${buildDir}/webapp') {
          dependsOn project.tasks.explodeWebApps
          from project.fileTree(buildWebAppFolder)
          destinationDir project.tasks.war.destinationDir
          archiveName warFileName
        }

        project.tasks.assemble.dependsOn project.tasks.overlayWar
      }

      def setupRealm = { helper, context ->
        String realm = project.gretty.realm
        String realmConfigFile = project.gretty.realmConfigFile
        if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
          realmConfigFile = "${project.webAppDir.absolutePath}/${realmConfigFile}"
        if(!realm || !realmConfigFile)
          for(Project overlay in project.gretty.overlays.reverse())
            if(overlay.gretty.realm && overlay.gretty.realmConfigFile) {
              realm = overlay.gretty.realm
              realmConfigFile = overlay.gretty.realmConfigFile
              if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
                realmConfigFile = "${overlay.webAppDir.absolutePath}/${realmConfigFile}"
              break
            }
        if(realm && realmConfigFile)
          helper.setRealm context, realm, realmConfigFile
      }

      def setupContextPath = { context ->
        String contextPath = project.gretty.contextPath
        if(!contextPath)
          for(Project overlay in project.gretty.overlays.reverse())
            if(overlay.gretty.contextPath) {
              contextPath = overlay.gretty.contextPath
              break
            }
        context.setContextPath contextPath ?: '/'
      }

      def setupInitParameters = { context ->
        for(Project overlay in project.gretty.overlays)
          for(def e in overlay.gretty.initParameters) {
            def paramValue = e.value
            if(paramValue instanceof Closure)
              paramValue = paramValue()
            context.setInitParameter e.key, paramValue
          }
        for(def e in project.gretty.initParameters) {
          def paramValue = e.value
          if(paramValue instanceof Closure)
            paramValue = paramValue()
          context.setInitParameter e.key, paramValue
        }
      }

      def setupInplaceWebAppDependencies = { task ->
        task.dependsOn project.tasks.classes
        task.dependsOn project.tasks.prepareInplaceWebAppFolder
        for(Project overlay in project.gretty.overlays)
          task.dependsOn overlay.tasks.classes
      }

      def addClassPath = { urls, proj ->
        urls.add new File(proj.buildDir, 'classes/main').toURI().toURL()
        urls.add new File(proj.buildDir, 'resources/main').toURI().toURL()
        urls.addAll proj.configurations.runtime.collect { dep -> dep.toURI().toURL() }
      }

      def createInplaceClassLoader = {
        def urls = []
        urls.addAll project.configurations.gretty.collect { dep -> dep.toURI().toURL() }
        addClassPath urls, project
        for(Project overlay in project.gretty.overlays.reverse())
          addClassPath urls, overlay
        return new URLClassLoader(urls as URL[])
      }

      def createInplaceWebAppContext = { def helper, jettyServer ->
        def context = helper.createWebAppContext()
        setupRealm helper, context
        setupContextPath context
        setupInitParameters context
        context.setServer jettyServer
        context.setResourceBase buildWebAppFolder
        jettyServer.setHandler context
      }

      def setupWarDependencies = { task ->
        task.dependsOn project.tasks.war
        // need this for stable references to ${buildDir}/webapp folder,
        // independent from presence/absence of overlays and inplace/war start mode.
        if(!project.gretty.overlays)
          task.dependsOn project.tasks.prepareInplaceWebAppFolder
      }

      def createWarWebAppContext = { def helper, jettyServer ->
        def context = helper.createWebAppContext()
        setupRealm helper, context
        setupContextPath context
        setupInitParameters context
        context.setServer jettyServer
        context.setWar project.tasks.war.archivePath.toString()
        jettyServer.setHandler context
      }

      def doOnStart = { boolean interactive ->
        System.out.println 'Jetty server started.'
        System.out.println 'You can see web-application in browser under the address:'
        System.out.println "http://localhost:${project.gretty.port}${project.gretty.contextPath}"
        for(Project overlay in project.gretty.overlays)
          overlay.gretty.onStart.each { onStart ->
            if(onStart instanceof Closure)
              onStart()
          }
        project.gretty.onStart.each { onStart ->
          if(onStart instanceof Closure)
            onStart()
        }
        if(interactive)
          System.out.println 'Press any key to stop the jetty server.'
        else
          System.out.println 'Enter \'gradle jettyStop\' to stop the jetty server.'
        System.out.println()
      }

      def doOnStop = {
        System.out.println 'Jetty server stopped.'
        project.gretty.onStop.each { onStop ->
          if(onStop instanceof Closure)
            onStop()
        }
        for(Project overlay in project.gretty.overlays.reverse())
          overlay.gretty.onStop.each { onStop ->
            if(onStop instanceof Closure)
              onStop()
          }
      }

      project.task('jettyRun', group: 'gretty', description: 'Starts jetty server inplace, in interactive mode (keypress stops the server).') { task ->
        setupInplaceWebAppDependencies task
        task.doLast {
          ClassLoader classLoader = createInplaceClassLoader()
          def helper = classLoader.findClass('gretty.GrettyHelper').newInstance()
          def server = helper.createServer()
          helper.createConnectors server, project.gretty.port
          createInplaceWebAppContext helper, server
          project.logger.warn "DBG InplaceWebAppContext created"
          server.start()
          doOnStart true
          System.in.read()
          server.stop()
          server.join()
          doOnStop()
        }
      }

      project.task('jettyRunWar', group: 'gretty', description: 'Starts jetty server on WAR-file, in interactive mode (keypress stops the server).') { task ->
        setupWarDependencies task
        task.doLast {
          Server server = new Server()
          createConnectors server
          createWarWebAppContext server
          server.start()
          doOnStart true
          System.in.read()
          server.stop()
          server.join()
          doOnStop()
        }
      }

      project.task('jettyStart', group: 'gretty', description: 'Starts jetty server inplace, in batch mode (\'jettyStop\' stops the server).') { task ->
        setupInplaceWebAppDependencies task
        task.doLast {
          ClassLoader classLoader = createInplaceClassLoader()
          def helper = classLoader.findClass('gretty.GrettyHelper').newInstance()
          def server = helper.createServer()
          helper.createConnectors server, project.gretty.port
          createInplaceWebAppContext helper, server
          Thread monitor = new JettyMonitorThread(project.gretty.servicePort, server)
          monitor.start()
          server.start()
          doOnStart false
          server.join()
          doOnStop()
        }
      }

      project.task('jettyStartWar', group: 'gretty', description: 'Starts jetty server on WAR-file, in batch mode (\'jettyStop\' stops the server).') { task ->
        setupWarDependencies task
        task.doLast {
          Server server = new Server()
          createConnectors server
          createWarWebAppContext server
          Thread monitor = new JettyMonitorThread(project.gretty.servicePort, server)
          monitor.start()
          server.start()
          doOnStart false
          server.join()
          doOnStop()
        }
      }

      def sendServiceCommand = { command ->
        Socket s = new Socket(InetAddress.getByName('127.0.0.1'), project.gretty.servicePort)
        try {
          OutputStream out = s.getOutputStream()
          System.out.println "Sending command: ${command}"
          out.write(("${command}\n").getBytes())
          out.flush()
        } finally {
          s.close()
        }
      }

      project.task('jettyStop', group: 'gretty', description: 'Sends \'stop\' command to running jetty server.') {  doLast { sendServiceCommand 'stop'
        }  }

      project.task('jettyRestart', group: 'gretty', description: 'Sends \'restart\' command to running jetty server.') {  doLast { sendServiceCommand 'restart'
        }  }
    } // afterEvaluate
  } // apply
} // plugin

