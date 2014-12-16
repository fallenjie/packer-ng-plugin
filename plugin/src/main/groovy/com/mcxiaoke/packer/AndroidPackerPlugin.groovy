package com.mcxiaoke.packer

import groovy.text.SimpleTemplateEngine
import groovy.xml.StreamingMarkupBuilder
import groovy.xml.XmlUtil
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.tasks.Copy

import java.text.SimpleDateFormat

// Android Multi Packer Plugin Source
class AndroidPackerPlugin implements Plugin<Project> {
    static final PLUGIN_NAME = "packer"
    static final P_OUTPUT = "output"
    static final P_MARKET = "market"
    static final P_BUILD_NUM = "buildNum"

    static final PROP_FILE = "packer.properties"

    static final String[] SUPPORTED_ANDROID_VERSIONS = ['0.14.', '1.0.']

    Project project
    Properties props
    AndroidPackerExtension packerExt

    @Override
    void apply(Project project) {
        if (!hasAndroidPlugin(project)) {
            throw new ProjectConfigurationException("the android plugin must be applied", null)
        }

        this.project = project
        this.props = loadProperties(project, PROP_FILE)
        checkAndroidPlugin()
        applyExtension()
        applyConfigAndTasks()
    }

    private void checkAndroidPlugin() {
        def plugin = project.buildscript.configurations.classpath.dependencies.find {
            it.group && it.group == 'com.android.tools.build' && it.name == 'gradle'
        }
        if (plugin && !isVersionSupported(plugin.version)) {
            throw new IllegalStateException("the android plugin ${plugin.version} is not supported.")
        }
    }

    void applyExtension() {
        // setup plugin and extension
        project.configurations.create(PLUGIN_NAME).extendsFrom(project.configurations.compile)
        this.packerExt = project.extensions.create(PLUGIN_NAME, AndroidPackerExtension, project)
    }

    void applyConfigAndTasks() {
        // checkBuildType()
        // add markets must before evaluate
        def hasMarkets = applyMarkets()
        project.afterEvaluate {
            def buildTypes = project.android.buildTypes
            debug("applyConfigAndTasks() build types: ${buildTypes.collect { it.name }}")
//            applySigningConfigs()
            applyProperties()
            addCleanTask()
            project.android.applicationVariants.all { variant ->
                checkSigningConfig(variant)
                if (variant.buildType.name != "debug") {
                    if (hasMarkets) {
                        // modify manifest and add archive apk
                        // only when markets found and not debug
                        debug("markets found, add manifest and archive task.")
                        addArchiveTask(variant)
                        checkManifest(variant)
                    } else {
                        debug("markets not found, check version name.")
                        checkVersionName(variant)
                    }
                }
            }
        }
    }

/**
 *  add task for clean apk archives
 * @param project
 */
    void addCleanTask() {
        def cleanTask = project.tasks.create(name: 'cleanArchives') {
            def output = packerExt.archiveOutput
            debug("addCleanTask() ${output.absolutePath}")
            cleanDir(output)
        }
        project.getTasksByName("clean", true)?.each {
            it.dependsOn cleanTask
        }
    }

/**
 *  add beta build type for debug
 * @param project
 * @return beta added
 */
    boolean checkBuildType() {
        def types = new HashSet<String>();
        types.addAll(project.android.buildTypes.collect { it.name })
        debug('checkBuildType() default build types:' + types)
        if (types.contains("beta")) {
            debug('checkBuildType() beta found, ignore')
            return true
        }
        debug('checkBuildType() create beta build type')
        def betaType = project.android.buildTypes.create("beta", {
            debuggable true
        })
        def configs = project.android.signingConfigs
        if (configs.findByName("release")) {
            betaType.signingConfig = configs.release
        }

        return true
    }

/**
 *   apply signing config for all build types
 * @param project Project
 */
    void applySigningConfigs() {
        def configs = project.android.signingConfigs
        if (configs.findByName("release")) {
            debug("applySigningConfigs() release signingConfig found ")
            project.android.buildTypes.each {
                if (it.name != "debug") {
                    if (it.signingConfig == null) {
                        debug("applySigningConfigs() add signingConfig for type:" + it.name)
                        it.signingConfig = configs.release
                    } else {
//                        debug("applySigningConfigs() already defined, ignore type:" + it.name)
                    }
                }
            }
        } else {
            warn("release signingConfig not found ")
        }
    }

/**
 *  parse markets file and apply to flavors
 * @param project Project
 * @return found markets file
 */
    boolean applyMarkets() {
        if (!project.hasProperty(P_MARKET)) {
            debug("market property not found, ignore")
            return false
        }

        // check markets file exists
        def marketsFilePath = project.property(P_MARKET).toString()

        File markets = new File(marketsFilePath);
        if (!markets.exists()) {
            debug("market file not exists, ignore")
            return false
        }

        debug("applyMarkets() file: ${marketsFilePath}")
        def flavors = new HashSet<String>();
        flavors.addAll(project.android.productFlavors.collect { it.name })
        debug("applyMarkets() default flavors:" + flavors)

        // add all markets
        markets.eachLine {
            def market = it.split('#')[0]
            if (!flavors.contains(market)) {
                project.android.productFlavors.create(market, {})
                debug("applyMarkets() new market: " + market)
            }
        }
        return true
    }

/**
 *  check project properties and apply to extension
 */
    void applyProperties() {
        if (project.hasProperty(P_OUTPUT)) {
            def dirName = project.property(P_OUTPUT) as String;
            packerExt.archiveOutput = new File(project.rootProject.buildDir, dirName)
        }
        debug("applyProperties() output:" + packerExt.archiveOutput)
        debug("applyProperties() manifest:" + packerExt.manifestMatcher)
    }

    void checkSigningConfig(variant) {
//        debug("checkSigningConfig() for ${variant.name}")
        if (variant.buildType.signingConfig == null) {
            debug("checkSigningConfig() signingConfig for ${variant.buildType.name} is null.")
        }
    }

/**
 *  check and apply build number
 * @param variant Variant
 */
    void checkVersionName(variant) {
        debug("checkVersionName() for variant:" + variant.name)
        // check buildNum property first
        if (project.hasProperty(P_BUILD_NUM)) {
            variant.mergedFlavor.versionName += '.' + project.property(P_BUILD_NUM)
        } else {
            def auto = packerExt.buildNumberAuto
            def patterns = packerExt.buildNumberTypeMatcher
            def typeName = variant.buildType.name
            if (auto && (patterns == null || patterns.contains(typeName))) {
                // or apply auto increment build number
                def newBuildNo = increaseBuildNumber(variant)
                variant.mergedFlavor.versionName += "." + newBuildNo.toString();
            }
        }

        debug("checkVersionName() versionName:${variant.mergedFlavor.versionName}")
    }

    int increaseBuildNumber(variant) {
        def typeName = variant.buildType.name
        def versionName = variant.mergedFlavor.versionName
        def key = "${versionName}.${typeName}.build"
        def buildNo = props.getProperty(key, "0").toInteger() + 1
        //put new build number to props
        props[key] = buildNo.toString()
        //store property file
        saveProperties(project, props, PROP_FILE)
        return buildNo
    }

/**
 *  add archiveApk tasks
 * @param variant current Variant
 */
    void addArchiveTask(variant) {
        def buildTypeName = variant.buildType.name
        if (variant.buildType.signingConfig == null) {
            warn("${variant.name}: signingConfig is null, ignore archive task.")
            return
        }
        if (!variant.buildType.zipAlignEnabled) {
            warn("${variant.name}: zipAlignEnabled==false, ignore archive task.")
            return
        }
        debug("addArchiveTask() for ${variant.name}")
        def apkName = buildApkName(variant)
        def inputFile = variant.outputs[0].outputFile
        def outputDir = packerExt.archiveOutput
        debug("addArchiveTask() input: " + inputFile)
        debug("addArchiveTask() output: " + outputDir)
        def archiveTask = project.tasks.create(name: "archiveApk${variant.name.capitalize()}",
                type: Copy) {
            description = "copy apk and rename to ${apkName}"
            from inputFile.absolutePath
            into outputDir.absolutePath
            rename { filename ->
                filename.replace inputFile.name, apkName
            }

            dependsOn variant.assemble
        }

        debug("addArchiveTask() new task:" + archiveTask.name)

        if (!variant.name.equals(buildTypeName)) {
            def taskName = "archiveApk${buildTypeName.capitalize()}"
            def parentTask = project.tasks.findByName(taskName)
            if (parentTask == null) {
                debug("addArchiveTask() create parent task  " + taskName)
                parentTask = project.tasks.create(taskName)
                parentTask.description = 'copy all apk archives to destination output dir'
            }
            parentTask.dependsOn archiveTask
        }
    }

/**
 *  build human readable apk name
 * @param variant Variant
 * @return final apk name
 */
    String buildApkName(variant) {
        def dateFormat = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
        def buildTime = dateFormat.format(new Date())
                .replaceAll('\\.', '-')
                .replaceAll(':', '-')
                .replaceAll(' ', '-')
        def nameMap = [
                'appName'    : project.name,
                'projectName': project.rootProject.name,
                'flavorName' : variant.flavorName,
                'buildType'  : variant.buildType.name,
                'versionName': variant.versionName,
                'versionCode': variant.versionCode,
                'appPkg'     : variant.applicationId,
                'buildTime'  : buildTime
        ]

        def defaultTemplate = '${appPkg}-${flavorName}-${buildType}-v${versionName}-${versionCode}'
        def engine = new SimpleTemplateEngine()
        def template = packerExt.archiveNameFormat == null ? defaultTemplate : packerExt.archiveNameFormat
        def fileName = engine.createTemplate(template).make(nameMap).toString();
        def apkName = fileName + '.apk'
        debug("buildApkName() final apkName: " + apkName)
        return apkName
    }

/**
 *  check and add process manifest actions
 * @param variant Variant
 */
    void checkManifest(variant) {
        if (!variant.productFlavors) {
            warn("${variant.name}: manifest task, no flavors found, ignore.")
            return
        }
        if (!variant.outputs) {
            warn("${variant.name}: manifest task, no outputs found, ignore.")
            return
        }
        def processManifestTask = variant.outputs[0].processManifest
        processManifestTask.doLast { task ->
            processManifest(task, variant)
        }
    }

/**
 *  parse and modify manifest file
 * @param task process manifest task
 * @param variant Variant
 */
    void processManifest(task, variant) {
        def File manifestFile = task.manifestOutputFile
        def typeName = variant.buildType.name
        def flavorName = variant.productFlavors[0].name
        debug("processManifest() flavor: ${flavorName} type:${typeName}")
        def root = new XmlSlurper().parse(manifestFile)
                .declareNamespace(android: "http://schemas.android.com/apk/res/android")
        debug("${variant.name}: manifest matcher:${packerExt.manifestMatcher}")
        packerExt.manifestMatcher?.each { String pattern ->
//            debug("processManifest() check pattern:${pattern}");
            def metadata = root.application.'meta-data'
            def found = metadata.find { mt -> pattern == mt.'@android:name'.toString() }
            if (found.size() > 0) {
                warn("${variant.name}: manifest meta-data ${pattern} found, modify it")
                found.replaceNode {
                    'meta-data'('android:name': found."@android:name", 'android:value': flavorName) {}
                }
            } else {
                warn("${variant.name}: manifest meta-data ${pattern} not found, add it.")
                root.application.appendNode {
                    'meta-data'('android:name': pattern, 'android:value': flavorName) {}
                }
            }
        }

        serializeXml(root, manifestFile)
//        debug("processManifest() final manifest:${manifestFile.text}")
    }

/**
 *  check android plugin applied
 * @param project Project
 * @return plugin applied
 */
    static boolean hasAndroidPlugin(Project project) {
        return project.plugins.hasPlugin("com.android.application")
    }

/**
 *  print debug messages
 * @param msg msg
 * @param vars vars
 */
    void debug(String msg, Object... vars) {
//        println msg
        project.logger.debug(msg, vars)
    }

    void warn(String msg, Object... vars) {
        project.logger.warn(msg, vars)
    }

    static void saveProperties(Project project, Properties props, String fileName) {
        props.store(new FileWriter(project.file(fileName)), null)
    }

    static Properties loadProperties(Project project, String fileName) {
        def props = new Properties()
        def file = project.file(fileName)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            props.load(new FileReader(file))
        }
        return props
    }

    static boolean getGitSha() {
        return 'git rev-parse --short HEAD'.execute().text.trim()
    }

    static boolean isVersionSupported(String version) {
        for (supportedVersion in SUPPORTED_ANDROID_VERSIONS) {
            if (version.startsWith(supportedVersion)) {
                return true
            }
        }

        return false
    }

/**
 *  write xml to file
 * @param xml xml
 * @param file file
 */
    static void serializeXml(xml, file) {
        XmlUtil.serialize(new StreamingMarkupBuilder().bind { mkp.yield xml },
                new FileWriter(file))
    }

/**
 *  delete all files in dir
 * @param dir
 */
    static void cleanDir(File dir) {
        if (dir && dir.listFiles()) {
            dir.listFiles().sort().each { File file ->
                if (file.isFile()) {
                    file.delete()
                } else {
                    file.deleteDir()
                }
            }
        }
    }

}
