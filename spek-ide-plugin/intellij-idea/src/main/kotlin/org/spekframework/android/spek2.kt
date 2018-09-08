package org.spekframework.android

import com.android.builder.model.JavaArtifact
import com.android.tools.idea.gradle.project.GradleProjectInfo
import com.android.tools.idea.gradle.project.facet.java.JavaFacet
import com.android.tools.idea.gradle.project.model.AndroidModuleModel
import com.android.tools.idea.io.FilePaths
import com.android.tools.idea.run.PreferGradleMake
import com.android.tools.idea.testartifacts.scopes.FileRootSearchScope
import com.android.tools.idea.testartifacts.scopes.TestArtifactSearchScopes
import com.intellij.compiler.options.CompileStepBeforeRun
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.configurations.*
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathsList
import org.jetbrains.android.sdk.AndroidPlatform
import org.jetbrains.kotlin.idea.KotlinIcons
import org.spekframework.intellij.*
import java.io.File

class Spek2AndroidRunConfiguration(name: String,
                                   javaRunConfigurationModule: JavaRunConfigurationModule,
                                   factory: ConfigurationFactory)
    : Spek2JvmRunConfiguration(name, javaRunConfigurationModule, factory), PreferGradleMake


class Spek2AndroidConfigurationFactory(type: ConfigurationType): ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        Spek2AndroidRunConfiguration("Un-named", JavaRunConfigurationModule(project, true), this)

    override fun configureBeforeRunTaskDefaults(providerID: Key<out BeforeRunTask<BeforeRunTask<*>>>,
                                                task: BeforeRunTask<out BeforeRunTask<*>>) {
        // disable built-in compile task, project will be built by gradle.
        if (providerID == CompileStepBeforeRun.ID) {
            task.isEnabled = false
        }
    }
}

class Spek2AndroidConfigurationType: SpekBaseConfigurationType(
    "Specs2RunConfiguration", // "org.spekframework.spek2-android",
    "Spek 2 - Android",
    KotlinIcons.SMALL_LOGO_13
) {
    init {
        addFactory(Spek2AndroidConfigurationFactory(this))
    }
}

class Spek2AndroidRunConfigurationProducer: SpekRunConfigurationProducer(
    ProducerType.JVM,
    ConfigurationTypeUtil.findConfigurationType(Spek2AndroidConfigurationType::class.java)
)

class Spek2AndroidParameterPatcher: Spek2JvmParameterPatcher {
    override fun patch(module: Module, parameters: JavaParameters) {
        if (GradleProjectInfo.getInstance(module.project).isBuildWithGradle) {
            val androidModel = AndroidModuleModel.get(module)
            val classPath = parameters.classPath
            if (androidModel == null) {
                addFoldersToClasspath(module, null, classPath, parameters)
            } else {
                val testArtifact = androidModel.selectedVariant.unitTestArtifact
                if (testArtifact != null) {
                    val testScopes = TestArtifactSearchScopes.get(module)
                    if (testScopes != null) {
                        val excludeScope = testScopes.unitTestExcludeScope
                        val iterator = classPath.pathList.iterator()

                        var current: String?
                        while (iterator.hasNext()) {
                            current = iterator.next()
                            if (excludeScope.accept(File(current))) {
                                classPath.remove(current)
                            }
                        }

                        val platform = AndroidPlatform.getInstance(module)
                        if (platform != null) {
                            try {
                                replaceAndroidJarWithMockableJar(classPath, platform, testArtifact)
                                addFoldersToClasspath(module, testArtifact, classPath, parameters)
                            } catch (e: Throwable) {
                                throw RuntimeException("Failed to patch classpath.", e)
                            }
                        }
                    }
                }
            }
        }
    }


    private fun addFoldersToClasspath(module: Module, testArtifact: JavaArtifact?, classPath: PathsList, parameters: JavaParameters) {
        val compileManager = CompilerManager.getInstance(module.project)
        val scope = compileManager.createModulesCompileScope(arrayOf(module), true, true)

        if (testArtifact != null) {
            classPath.add(testArtifact.javaResourcesFolder)

            testArtifact.additionalClassesFolders.forEach {
                classPath.add(it)
                parameters.programParametersList.add("--sourceDirs", it.absolutePath)
            }
        }

        var excludeScope: FileRootSearchScope? = null
        val testScopes = TestArtifactSearchScopes.get(module)
        if (testScopes != null) {
            excludeScope = testScopes.unitTestExcludeScope
        }

        scope.affectedModules.forEach { affectedModule ->
            val affectedAndroidModel = AndroidModuleModel.get(affectedModule)
            if (affectedAndroidModel != null) {
                val mainArtifact = affectedAndroidModel.mainArtifact
                addToClassPath(mainArtifact.javaResourcesFolder, classPath, excludeScope)

                mainArtifact.additionalClassesFolders.forEach {
                    addToClassPath(it, classPath, excludeScope)
                }
            }

            val javaFacet = JavaFacet.getInstance(affectedModule)

            if (javaFacet != null) {
                val javaModel = javaFacet.javaModuleModel
                if (javaModel != null) {
                    javaModel.isAndroidModuleWithoutVariants
                    val output = javaModel.compilerOutput
                    val javaTestResources = output?.testResourcesDir
                    if (javaTestResources != null) {
                        addToClassPath(javaTestResources, classPath, excludeScope)
                    }

                    val javaMainResources = output?.mainResourcesDir
                    if (javaMainResources != null) {
                        addToClassPath(javaMainResources, classPath, excludeScope)
                    }

                    if (javaModel.buildFolderPath != null) {
                        val kotlinClasses = javaModel.buildFolderPath!!.toPath().resolve("classes/kotlin").toFile()

                        if (kotlinClasses.exists()) {
                            addToClassPath(File(kotlinClasses, "main"), classPath, excludeScope)
                            addToClassPath(File(kotlinClasses, "test"), classPath, excludeScope)
                        }
                    }
                }
            }
        }
    }

    private fun replaceAndroidJarWithMockableJar(classPath: PathsList, platform: AndroidPlatform,
                                                 artifact: JavaArtifact) {
        val androidJarPath = platform.target.getPath(1)

        classPath.pathList
            .filter { FileUtil.pathsEqual(androidJarPath, it) }
            .forEach { classPath.remove(it) }

        val mockableJars = ArrayList<String>()

        classPath.pathList
            .filter { FilePaths.toSystemDependentPath(it)!!.name.startsWith("mockable-") }
            .forEach { mockableJars.add(it) }

        mockableJars.forEach(classPath::remove)

        val mockableJar = artifact.mockablePlatformJar
        if (mockableJar != null) {
            classPath.addTail(mockableJar.path)
        } else {
            for (jar in mockableJars) {
                if (jar.endsWith("-${platform.apiLevel}.jar")) {
                    classPath.addTail(jar)
                    break
                }
            }
        }
    }

    private fun addToClassPath(folder: File, classPath: PathsList, excludeScope: FileRootSearchScope?) {
        if (excludeScope == null || !excludeScope.accept(folder)) {
            classPath.add(folder)
        }
    }
}

