package cn.jesse.patcher.build.gradle

import cn.jesse.patcher.build.gradle.extension.PatcherBuildConfigExtension
import cn.jesse.patcher.build.gradle.extension.PatcherDexExtension
import cn.jesse.patcher.build.gradle.extension.PatcherExtension
import cn.jesse.patcher.build.gradle.extension.PatcherLibExtension
import cn.jesse.patcher.build.gradle.extension.PatcherPackageConfigExtension
import cn.jesse.patcher.build.gradle.extension.PatcherResourceExtension
import cn.jesse.patcher.build.gradle.extension.PatcherSevenZipExtension
import cn.jesse.patcher.build.gradle.task.ManifestTask
import cn.jesse.patcher.build.gradle.task.MultiDexConfigTask
import cn.jesse.patcher.build.gradle.task.PatchSchemaTask
import cn.jesse.patcher.build.gradle.task.PatcherTask
import cn.jesse.patcher.build.gradle.task.ProguardConfigTask
import cn.jesse.patcher.build.gradle.task.ResourceIdTask
import cn.jesse.patcher.build.util.FileOperation
import cn.jesse.patcher.build.util.TypedValue
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException

/**
 * Created by jesse on 12/12/2016.
 */
public class PatcherPlugin implements Plugin<Project> {
    public static final String PATCHER_INTERMEDIATES = "build/intermediates/patcher_intermediates/"
    public static final String PATCHER_PLUGIN_GROUP = "patcher"

    @Override
    void apply(Project project) {
        // apply os detector插件
        project.apply plugin: 'osdetector'

        // 创建root扩展 patcher
        project.extensions.create('patcher', PatcherExtension)

        // 基于patcher再扩展出其他六项配置
        project.patcher.extensions.create('buildConfig', PatcherBuildConfigExtension)
        project.patcher.extensions.create('dex', PatcherDexExtension)
        project.patcher.extensions.create('lib', PatcherLibExtension)
        project.patcher.extensions.create('res', PatcherResourceExtension)
        project.patcher.extensions.create('packageConfig', PatcherPackageConfigExtension, project)
        project.patcher.extensions.create('sevenZip', PatcherSevenZipExtension, project)

        // 所有配置的引用
        def configuration = project.patcher

        // 如果不是application的话直接crash掉
        if (!project.plugins.hasPlugin('com.android.application')) {
            throw new GradleException('generatePatcherApk: Android Application plugin required')
        }

        // 拿到android扩展.
        def android = project.extensions.android

        //打包时去除注解的多余文件
        //add the patcher anno resource to the package exclude option
        android.packagingOptions.exclude("META-INF/services/javax.annotation.processing.Processor")
        android.packagingOptions.exclude("PatcherApplication.tmpl")

        //open jumboMode 默认打开jumboMode
        android.dexOptions.jumboMode = true

        // 可以配合incremental使用, 如果开启preDexLibraries
        //close preDexLibraries
        try {
            android.dexOptions.preDexLibraries = false
        } catch (Throwable e) {
            //no preDexLibraries field, just continue
        }

//        android.registerTransform(new AuxiliaryInjectTransform(project))


        // 修改声明 配属属性
        project.afterEvaluate() {
            project.logger.error("------------------------------------ patcher build info ------------------------------------")
            println("patcher auto operation: ")
            println("excluding annotation processor and source template from app packaging.")
            println("enable dx jumboMode to reduce package size.")
            println("disable preDexLibraries to prevent ClassDefNotFoundException when your app is booting.")
            println("")
            println("patcher will change your build configs:")
            println("we will add PATCHER_ID=${configuration.buildConfig.patcherId} in your build output manifest file build/intermediates/manifests/full/*")
            println("")
            println("if minifyEnabled is true")

            String tempMappingPath = configuration.buildConfig.applyMapping
            if (FileOperation.isLegalFile(tempMappingPath)) {
                println("we will build ${project.getName()} apk with apply mapping file ${tempMappingPath}")
            }

            println("you will find the gen proguard rule file at ${ProguardConfigTask.PROGUARD_CONFIG_PATH}")
            println("and we will help you to put it in the proguardFiles.")
            println("")
            println("if multiDexEnabled is true")
            println("you will find the gen multiDexKeepProguard file at ${MultiDexConfigTask.MULTIDEX_CONFIG_PATH}")
            println("and you should copy it to your own multiDex keep proguard file yourself.")
            println("")
            println("if applyResourceMapping file is exist")
            String tempResourceMappingPath = configuration.buildConfig.applyResourceMapping
            if (FileOperation.isLegalFile(tempResourceMappingPath)) {
                println("we will build ${project.getName()} apk with resource R.txt ${tempResourceMappingPath} file")
            } else {
                println("we will build ${project.getName()} apk with resource R.txt file")
            }
            println("if resources.arsc has changed, you should use applyResource mode to build the new apk!")
            project.logger.error("--------------------------------------------------------------------------------------------")
        }

        // 遍历所有的variant
        android.applicationVariants.all { variant ->

            def variantOutput = variant.outputs.first()
            def variantName = variant.name.capitalize()

            // 禁止使用 instant run 避免对补丁生成产生影响
            try {
                def instantRunTask = project.tasks.getByName("transformClassesWithInstantRunFor${variantName}")
                if (instantRunTask) {
                    throw new GradleException(
                            "Patcher does not support instant run mode, please trigger build"
                                    + " by assemble${variantName} or disable instant run"
                                    + " in 'File->Settings...'."
                    )
                }
            } catch (UnknownTaskException e) {
                // Not in instant run mode, continue.
            }


            // 构建patch任务,验证gradle配置 初始化patch环境
            PatchSchemaTask patchBuildTask = project.tasks.create("patcher${variantName}", PatchSchemaTask)
            patchBuildTask.dependsOn variant.assemble

            patchBuildTask.signConfig = variant.apkVariantData.variantConfiguration.signingConfig

            // 遍历outputs 拿到apk的output路径
            variant.outputs.each { output ->
                patchBuildTask.buildApkPath = output.outputFile
                File parentFile = output.outputFile
                patchBuildTask.outputFolder = "${parentFile.getParentFile().getParentFile().getAbsolutePath()}/" + TypedValue.PATH_DEFAULT_OUTPUT + "/" + variant.dirName
            }

            // 建立manifest任务,在android manifest文件生成之后插入PATCHER_ID
            // Create a task to add a build PATCHER_ID to AndroidManifest.xml
            // This task must be called after "process${variantName}Manifest", since it
            // requires that an AndroidManifest.xml exists in `build/intermediates`.
            ManifestTask manifestTask = project.tasks.create("patcherProcess${variantName}Manifest", ManifestTask)
            manifestTask.manifestPath = variantOutput.processManifest.manifestOutputFile
            manifestTask.mustRunAfter variantOutput.processManifest

            variantOutput.processResources.dependsOn manifestTask

            //resource id
            ResourceIdTask applyResourceTask = project.tasks.create("patcherProcess${variantName}ResourceId", ResourceIdTask)
            applyResourceTask.resDir = variantOutput.processResources.resDir
            //let applyResourceTask run after manifestTask
            applyResourceTask.mustRunAfter manifestTask

            variantOutput.processResources.dependsOn applyResourceTask

            // Add this proguard settings file to the list
            boolean proguardEnable = variant.getBuildType().buildType.minifyEnabled

            if (proguardEnable) {
                ProguardConfigTask proguardConfigTask = project.tasks.create("patcherProcess${variantName}Proguard", ProguardConfigTask)
                proguardConfigTask.applicationVariant = variant
                variantOutput.packageApplication.dependsOn proguardConfigTask
            }

            // Add this multidex proguard settings file to the list
            boolean multiDexEnabled = variant.apkVariantData.variantConfiguration.isMultiDexEnabled()

            if (multiDexEnabled) {
                MultiDexConfigTask multiDexConfigTask = project.tasks.create("patcherProcess${variantName}MultiDexKeep", MultiDexConfigTask)
                multiDexConfigTask.applicationVariant = variant
                variantOutput.packageApplication.dependsOn multiDexConfigTask
            }

        }


        project.tasks.create("patcherTest", PatcherTask)
    }
}