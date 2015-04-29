/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool

import org.gradle.api.Plugin
import org.gradle.api.Project
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.variant.ApplicationVariantData
import java.io.File
import org.gradle.api.file.FileCollection
import android.databinding.tool.writer.JavaFileWriter
import android.databinding.tool.util.Log
import org.gradle.api.Action
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.variant.TestVariantData
import com.android.build.gradle.internal.api.TestVariantImpl
import com.android.ide.common.res2.ResourceSet
import java.io.FileFilter
import java.io.FilenameFilter
import java.util.ArrayList
import javax.xml.xpath.XPathFactory
import kotlin.dom.elements
import kotlin.dom.parseXml

class DataBinderPlugin : Plugin<Project> {
    val XPATH_BINDING_CLASS = "/layout/data/@class"

    inner class GradleFileWriter(var outputBase: String) : JavaFileWriter() {
        override fun writeToFile(canonicalName: String, contents: String) {
            val f = File("$outputBase/${canonicalName.replaceAll("\\.", "/")}.java")
            log("Asked to write to ${canonicalName}. outputting to:${f.getAbsolutePath()}")
            f.getParentFile().mkdirs()
            f.writeText(contents, "utf-8")
        }
    }

    override fun apply(project: Project?) {
        if (project == null) return
        project.afterEvaluate {
            createXmlProcessor(project)
        }
    }

    fun log(s: String) {
        System.out.println("[qwqw data binding]: $s")
    }

    fun createXmlProcessor(p: Project) {
        val androidExt = p.getExtensions().getByName("android")
        if (androidExt !is BaseExtension) {
            return
        }
        if (androidExt is AppExtension) {
            createXmlProcessorForApp(p, androidExt)
        } else if (androidExt is LibraryExtension) {
            createXmlProcessorForLibrary(p, androidExt)
        } else {
            throw RuntimeException("cannot understand android extension. What is it? ${androidExt}")
        }
    }

    fun createXmlProcessorForLibrary(project : Project, lib : LibraryExtension) {
        val sdkDir = lib.getSdkDirectory()
        lib.getTestVariants().forEach { variant ->
            log("test variant $variant. dir name ${variant.getDirName()}")
            val variantData = getVariantData(variant)
            attachXmlProcessor(project, variantData, sdkDir, false)//tests extend apk variant
        }
        lib.getLibraryVariants().forEach { variant ->
            log("lib variant $variant . dir name ${variant.getDirName()}")
            val variantData = getVariantData(variant)
            attachXmlProcessor(project, variantData, sdkDir, true)
        }
    }

    fun getVariantData(appVariant : LibraryVariant) : LibraryVariantData {
        val clazz = javaClass<LibraryVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        return field.get(appVariant) as LibraryVariantData
    }

    fun getVariantData(testVariant : TestVariant) : TestVariantData {
        val clazz = javaClass<TestVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        return field.get(testVariant) as TestVariantData
    }

    fun getVariantData(appVariant : ApplicationVariant) : ApplicationVariantData {
        val clazz = javaClass<ApplicationVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        return field.get(appVariant) as ApplicationVariantData
    }

    fun createXmlProcessorForApp(project : Project, appExt: AppExtension) {
        val sdkDir = appExt.getSdkDirectory()
        appExt.getTestVariants().forEach { testVariant ->
            val variantData = getVariantData(testVariant)
            attachXmlProcessor(project, variantData, sdkDir, false)
        }
        appExt.getApplicationVariants().forEach { appVariant ->
            val variantData = getVariantData(appVariant)
            attachXmlProcessor(project, variantData, sdkDir, false)
        }
    }

    fun attachXmlProcessor(project : Project, variantData : BaseVariantData<*>, sdkDir : File,
            isLibrary : Boolean) {
        val configuration = variantData.getVariantConfiguration()
        val minSdkVersion = configuration.getMinSdkVersion()
        val generateRTask = variantData.generateRClassTask
        val packageName = generateRTask.getPackageForR()
        val fullName = configuration.getFullName()
        val resourceFolders = arrayListOf(variantData.mergeResourcesTask.getOutputDir())

        val codeGenTargetFolder = File("${project.getBuildDir()}/data-binding-info/${configuration.getDirName()}")
        val writerOutBase = codeGenTargetFolder.getAbsolutePath();
        val fileWriter = GradleFileWriter(writerOutBase)
        val xmlProcessor = LayoutXmlProcessor(packageName, resourceFolders, fileWriter,
                minSdkVersion.getApiLevel(), isLibrary)
        val processResTask = generateRTask
        val xmlOutDir = File("${project.getBuildDir()}/layout-info/${configuration.getDirName()}")
        log("xml output for ${variantData} is ${xmlOutDir}")
        val layoutTaskName = "dataBindingLayouts${processResTask.getName().capitalize()}"
        val infoClassTaskName = "dataBindingInfoClass${processResTask.getName().capitalize()}"

        var processLayoutsTask : DataBindingProcessLayoutsTask? = null
        project.getTasks().create(layoutTaskName,
                javaClass<DataBindingProcessLayoutsTask>(),
                object : Action<DataBindingProcessLayoutsTask> {
                    override fun execute(task: DataBindingProcessLayoutsTask) {
                        processLayoutsTask = task
                        task.xmlProcessor = xmlProcessor
                        task.sdkDir = sdkDir
                        task.xmlOutFolder = xmlOutDir
                        Log.d { "TASK adding dependency on ${task} for ${processResTask}" }
                        processResTask.dependsOn(task)
                        processResTask.getDependsOn().filterNot { it == task }.forEach {
                            Log.d { "adding dependency on ${it} for ${task}" }
                            task.dependsOn(it)
                        }
                        processResTask.doLast {
                            task.writeLayoutXmls()
                        }
                    }
                })
        project.getTasks().create(infoClassTaskName,
                javaClass<DataBindingExportInfoTask>(),
                object : Action<DataBindingExportInfoTask>{
                    override fun execute(task: DataBindingExportInfoTask) {
                        task.dependsOn(processLayoutsTask!!)
                        task.dependsOn(processResTask)
                        task.xmlProcessor = xmlProcessor
                        task.sdkDir = sdkDir
                        task.xmlOutFolder = xmlOutDir
                        variantData.registerJavaGeneratingTask(task, codeGenTargetFolder)
                    }
                })

        if (isLibrary) {
            val resourceSets = variantData.mergeResourcesTask.getInputResourceSets()
            val customBindings = getCustomBindings(resourceSets, packageName)
            val packageJarTaskName = "package${fullName.capitalize()}Jar"
            val packageTask = project.getTasks().findByName(packageJarTaskName)
            if (packageTask !is org.gradle.api.tasks.bundling.Jar) {
                throw RuntimeException("cannot find package task in $project $variantData project $packageJarTaskName")
            }
            val excludePattern = "android/databinding/layouts/*.*"
            val appPkgAsClass = packageName.replace('.', '/')
            packageTask.exclude(excludePattern)
            packageTask.exclude("$appPkgAsClass/databinding/*")
            packageTask.exclude("$appPkgAsClass/BR.*")
            packageTask.exclude(xmlProcessor.getInfoClassFullName().replace('.', '/') + ".class")
            customBindings.forEach {
                packageTask.exclude("${it.replace('.', '/')}.class")
            }
            log("excludes ${packageTask.getExcludes()}")
        }
    }

    fun getCustomBindings(resourceSets : List<ResourceSet>, packageName: String) : List<String> {
        val xPathFactory = XPathFactory.newInstance()
        val xPath = xPathFactory.newXPath()
        val expr = xPath.compile(XPATH_BINDING_CLASS);
        val customBindings = ArrayList<String>()

        resourceSets.forEach { set ->
            set.getSourceFiles().forEach({ res ->
                res.listFiles(object : FileFilter {
                    override fun accept(file: File?): Boolean {
                        return file != null && file.isDirectory() &&
                                file.getName().toLowerCase().startsWith("layout")
                    }
                })?.forEach { layoutDir ->

                    layoutDir.listFiles(object : FileFilter {
                        override fun accept(file: File?): Boolean {
                            return file != null && !file.isDirectory() &&
                                    file.getName().toLowerCase().endsWith(".xml")
                        }
                    })?.forEach { xmlFile: File ->
                        val document = parseXml(xmlFile)
                        val bindingClass = expr.evaluate(document)
                        if (bindingClass != null && !bindingClass.isEmpty()) {
                            if (bindingClass.startsWith('.')) {
                                customBindings.add("${packageName}${bindingClass}")
                            } else if (bindingClass.contains(".")) {
                                customBindings.add(bindingClass)
                            } else {
                                customBindings.add(
                                        "${packageName}.databinding.${bindingClass}")
                            }
                        }
                    }
                }
            })
        }
        return customBindings
    }
}
