@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.tomcat

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.Platform
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.util.EnvironmentVariable
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.javaee.appServers.integration.impl.ApplicationServerImpl
import com.intellij.javaee.appServers.run.configuration.CommonStrategy
import com.intellij.javaee.appServers.run.configuration.RunnerSpecificLocalConfigurationBit
import com.intellij.javaee.appServers.run.localRun.ScriptInfo
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.ContainerUtil
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.idea.tomcat.TomcatStartupPolicy
import org.jetbrains.idea.tomcat.server.TomcatLocalModel
import org.jetbrains.idea.tomcat.server.TomcatPersistentData
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TOMCAT_SERVER_PORT: String = "8005"

private fun getTomcatServerPort(): String {
    return System.getenv("MIRRORD_TOMCAT_SERVER_PORT") ?: DEFAULT_TOMCAT_SERVER_PORT
}

data class SavedStartupScriptInfo(val useDefault: Boolean, val script: String?, val args: String?, val vmArgs: String?)

data class SavedConfigData(val envVars: List<EnvironmentVariable>, val scriptInfo: SavedStartupScriptInfo?)

data class CommandLineWithArgs(val command: String, val args: String?)

class TomcatExecutionListener : ExecutionListener {
    private val savedEnvs: ConcurrentHashMap<String, SavedConfigData> = ConcurrentHashMap()

    private val JAVA_VM_ENV_VARIABLE = "JAVA_OPTS"


    // Adapted from org/jetbrains/idea/tomcat/TomcatStartupPolicy.java getCustomJavaOptions
    private fun getCustomJavaOptions(tomcatModel: TomcatLocalModel): List<String> {
//        return if (tomcatModel.isUseJmx) {
        return if (true) {
            val result: MutableList<String> = ArrayList()
            result.add("-Dcom.sun.management.jmxremote=")
            result.add("-Dcom.sun.management.jmxremote.port=" + tomcatModel.JNDI_PORT)
            result.add("-Dcom.sun.management.jmxremote.ssl=false")
            val accessFile = tomcatModel.accessFile
            val passwordFile = tomcatModel.passwordFile
            if (accessFile == null || passwordFile == null) {
                result.add("-Dcom.sun.management.jmxremote.authenticate=false")
            } else {
                try {
                    result.add("-Dcom.sun.management.jmxremote.password.file=" + passwordFile.getCanonicalPath())
                    result.add("-Dcom.sun.management.jmxremote.access.file=" + accessFile.getCanonicalPath())
                } catch (e: IOException) {
                    throw ExecutionException(e)
                }
            }
            if (tomcatModel.getVmArgument(TomcatStartupPolicy.RMI_HOST_JAVA_OPT) == null) {
                result.add("-D" + TomcatStartupPolicy.RMI_HOST_JAVA_OPT + "=127.0.0.1")
            }
//            if (tomcatModel.isTomEE) {
//                if (tomcatModel.versionHigher(7, 0, 68)) {
//                    result.add("-Dtomee.serialization.class.whitelist=")
//                    result.add("-Dtomee.serialization.class.blacklist=-")
//                }
//                if (tomcatModel.versionHigher(8, 0, 28)) {
//                    result.add("-Dtomee.remote.support=true")
//                    result.add("-Dopenejb.system.apps=true")
//                }
//            }
            return result
        } else {
            Collections.emptyList()
        }
    }


    // Copied from org/jetbrains/idea/tomcat/TomcatStartupPolicy.java
    private fun quoteJavaOpts(javaOptions: List<String>): String {

        // IDEA-206243, IDEA-205955: quoting is needed since parameters are passed via ENV var and may contain paths
        // we will quote manually some known cases, since command line does not need it
        val quotedOpts = ContainerUtil.map(
            javaOptions
        ) { s: String? ->
            if (StringUtil.containsAnyChar(s!!, "() ?*+&")) {
                var quoted = StringUtil.wrapWithDoubleQuote(s!!)
                if (Platform.current() == Platform.WINDOWS) {
                    // & is escaped on Windows by ^. To start Tomcat it is necessary that & has been escaped twice.
                    // So ^^ gives ^ and ^& gives &. Then ^& gives &.
                    quoted = quoted.replace("&", "^^^&")
                }
                return@map quoted
            }
            s
        }
        return java.lang.String.join(" ", quotedOpts)
    }

    // Copied from org/jetbrains/idea/tomcat/TomcatStartupPolicy.java
    private fun combineJavaOpts(baseJavaOptsValue: String?, customJavaOptions: List<String>): String {
        assert(customJavaOptions.isNotEmpty())
        val quotedCustom = quoteJavaOpts(customJavaOptions)
        //assume that base value is ok and should not be quoted
        return if (StringUtil.isEmptyOrSpaces(baseJavaOptsValue)) quotedCustom else "$baseJavaOptsValue $quotedCustom"
    }

    // Adapted from org/jetbrains/idea/tomcat/TomcatStartupPolicy.java
    private fun getJavaOptsEnvVarValue(tomcatModel: TomcatLocalModel, envVars: Map<String, String>): String {
        val customJavaOptions = getCustomJavaOptions(tomcatModel)
        return combineJavaOpts(
            envVars[JAVA_VM_ENV_VARIABLE],
            customJavaOptions
        )
    }

    private fun getConfig(env: ExecutionEnvironment): RunnerSpecificLocalConfigurationBit? {
        if (!env.toString().startsWith("Tomcat")) {
            return null
        }

        val settings = env.configurationSettings ?: return null

        return if (settings is RunnerSpecificLocalConfigurationBit) {
            settings
        } else {
            null
        }
    }

    /**
     * Returns a String with the path of the script that will be executed, based on the [scriptInfo].
     * If the info is not available, which by looking at [ScriptInfo] seems possible (though we don't know when), the
     * default script will be guessed based on the location of the tomcat installation, taken from [env].
     */
    private fun getStartScript(scriptInfo: ScriptInfo, env: ExecutionEnvironment): CommandLineWithArgs {
        return if (scriptInfo.USE_DEFAULT) {
//            val defaultScript = scriptInfo.script;
            val commandLine = scriptInfo.defaultScript.ifBlank {
                // We return the default script if it's not blank. If it's blank we're guessing the path on our own,
                // based on the tomcat installation location.
                val tomcatLocation =
                    (((env.runProfile as CommonStrategy).applicationServer as ApplicationServerImpl).persistentData as TomcatPersistentData).HOME
                val defaultScript = Paths.get(tomcatLocation, "bin/catalina.sh")
                defaultScript.toString()
            }
            // Split on the first space that is not preceded by a backslash.
            // 4 backslashes in the string are 1 in the regex.
            val split = commandLine.split("(?<!\\\\) ".toRegex(), limit = 2)
            val command = split.first()
            val args = split.getOrNull(1)
            CommandLineWithArgs(command, args)
        } else {
            CommandLineWithArgs(scriptInfo.SCRIPT, scriptInfo.PROGRAM_PARAMETERS)
        }
    }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        getConfig(env)?.let { config ->
            val envVars = config.envVariables

            val service = env.project.service<MirrordProjectService>()

            MirrordLogger.logger.debug("wsl check")
            val wsl = when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }

            val startupInfo = config.startupInfo
            val (script, args) = getStartScript(startupInfo, env)

            try {
                service.execManager.wrapper("idea").apply {
                    this.wsl = wsl
                    this.executable = script
                    configFromEnv = envVars.find { e -> e.name == CONFIG_ENV_NAME }?.VALUE
                }.start()?.let { (env, patchedPath) ->
                    // `MIRRORD_IGNORE_DEBUGGER_PORTS` should allow clean shutdown of the app
                    // even if `outgoing` feature is enabled.
                    val mirrordEnv = env + mapOf(Pair("MIRRORD_DETECT_DEBUGGER_PORT", "javaagent"), Pair("MIRRORD_IGNORE_DEBUGGER_PORTS", getTomcatServerPort()))

                    // If we're on macOS we're going to SIP-patch the script and change info, so save script info.
                    val savedScriptInfo = if (SystemInfo.isMac) {
                        SavedStartupScriptInfo(startupInfo.USE_DEFAULT, startupInfo.SCRIPT, startupInfo.PROGRAM_PARAMETERS, startupInfo.VM_PARAMETERS)
                    } else {
                        null
                    }

                    savedEnvs[executorId] = SavedConfigData(envVars.toList(), savedScriptInfo)
                    envVars.addAll(mirrordEnv.map { (k, v) -> EnvironmentVariable(k, v, false) })
                    config.setEnvironmentVariables(envVars)

                    if (SystemInfo.isMac) {
                        patchedPath?.let {
//                            val model = TomcatLocalModel()
                            val parentField = startupInfo.javaClass?.getDeclaredField("myParent")
                            parentField!!.isAccessible = true
                            val strategy = parentField.get(startupInfo) as CommonStrategy
                            val serverModelField = strategy.javaClass.getDeclaredField("myServerModel")
                            serverModelField.isAccessible = true
                            val tomcatModel = serverModelField.get(strategy) as TomcatLocalModel

                            config.startupInfo.USE_DEFAULT = false
                            config.startupInfo.SCRIPT = it
                            config.startupInfo.VM_PARAMETERS = config.appendVMArguments(config.createJavaParameters())
                            args?.let {
                                config.startupInfo.PROGRAM_PARAMETERS = args
                            }

                            val envVal = getJavaOptsEnvVarValue(tomcatModel, env)
                            config.envVariables.add(EnvironmentVariable(JAVA_VM_ENV_VARIABLE, envVal, false))
                        }
                    }

                }
            } catch (e: Throwable) {
                MirrordLogger.logger.debug("Running tomcat project failed: ", e)
                // Error notifications were already fired.
                // We can't abort the execution here, so we let the app run without mirrord.
                service.notifier.notifySimple(
                    "Cannot abort run due to platform limitations, running without mirrord",
                    NotificationType.WARNING
                )
            }
        }

        super.processStartScheduled(executorId, env)
    }

    private fun restoreConfig(executorId: String, config: RunnerSpecificLocalConfigurationBit) {
        val saved = savedEnvs.remove(executorId) ?: return
        config.setEnvironmentVariables(saved.envVars)
        if (SystemInfo.isMac) {
            saved.scriptInfo?.let {
                config.startupInfo.USE_DEFAULT = it.useDefault
                it.script?.let { scriptPath ->
                    config.startupInfo.SCRIPT = scriptPath
                }
                it.args?.let { args ->
                    config.startupInfo.PROGRAM_PARAMETERS = args
                }
                it.vmArgs?.let { vmArgs ->
                    config.startupInfo.VM_PARAMETERS = vmArgs
                }
            }
        }
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        getConfig(env)?.let {
            restoreConfig(executorId, it)
        }
        super.processNotStarted(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        getConfig(env)?.let {
            restoreConfig(executorId, it)
        }
        super.processStarted(executorId, env, handler)
    }
}
