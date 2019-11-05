/**
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencylock.utils

import nebula.plugin.dependencylock.ConfigurationsToLockFinder
import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.DependencyLockTaskConfigurer
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import org.gradle.api.BuildCancelledException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskState

class CoreLockingHelper {
    private static final Logger LOGGER = Logging.getLogger(CoreLockingHelper.class)
    private static final String ADDITIONAL_CONFIGS_TO_LOCK = 'dependencyLock.additionalConfigurationsToLock'

    private Project project
    private Boolean shouldLockAllConfigurations
    private synchronized Set<Configuration> configsWithActivatedDependencyLocking

    boolean migrationTaskWasRequested = project.gradle.startParameter.taskNames.contains(DependencyLockTaskConfigurer.MIGRATE_TO_CORE_LOCKS_TASK_NAME)
    boolean hasWriteLocksFlag = project.gradle.startParameter.isWriteDependencyLocks()
    boolean hasDependenciesToUpdate = !project.gradle.startParameter.getLockedDependenciesToUpdate().isEmpty()
    boolean isUpdatingDependencies = hasWriteLocksFlag || hasDependenciesToUpdate

    CoreLockingHelper(Project project) {
        this.project = project
        shouldLockAllConfigurations = project.hasProperty("lockAllConfigurations") && (project.property("lockAllConfigurations") as String).toBoolean()
        configsWithActivatedDependencyLocking = new HashSet<Configuration>()
    }

    void lockSelectedConfigurations(Set<String> configurationNames) {
        if (shouldLockAllConfigurations) {
            project.dependencyLocking {
                it.lockAllConfigurations()
            }
        } else {
            def closureToLockConfigurations = {
                if (!it instanceof Configuration) {
                    throw new BuildCancelledException("There is an issue with the configuration to lock '${it.toString()}'")
                }
                if (!configsWithActivatedDependencyLocking.contains(it)) {
                    it.resolutionStrategy.activateDependencyLocking()
                    LOGGER.debug("Activated ${it} for dependency locking")
                    configsWithActivatedDependencyLocking.add(it as Configuration)
                }
            }
            runClosureWhenPluginsAreSeen(configurationNames, closureToLockConfigurations)
        }
        removeLockfilesForUnlockedConfigurations()
    }

    void migrateLockedConfigurations(Set<String> configurationNames, Closure closure) {
        runClosureWhenPluginsAreSeen(configurationNames, closure)
    }

    void migrateUnlockedDependenciesClosure(Set<String> configurationNames, Closure closure) {
        runClosureWhenPluginsAreSeen(configurationNames, closure)
    }

    private void runClosureWhenPluginsAreSeen(Set<String> configurationNames, Closure closure) {
        project.plugins.withType(Plugin) { plugin ->
            runClosureOnConfigurations(configurationNames, closure, new ArrayList<String>())
            findAndLockAdditionalConfigurations(configurationNames, closure)
        }
        findAndLockAdditionalConfigurations(configurationNames, closure)
    }

    private void findAndLockAdditionalConfigurations(Set<String> configurationNames, Closure closure) {
        def additionalConfigNames = gatherAdditionalConfigurationsToLock()
        project.configurations.matching { // returns a live collection
            additionalConfigNames.findAll { additionalConfigName ->
                it.name == additionalConfigName
            }
        }.all { it ->
            runClosureOnConfigurations(configurationNames, closure, additionalConfigNames)
        }
    }

    private void runClosureOnConfigurations(Set<String> configurationNames, Closure closure, Collection<String> additionalBaseConfigurationsToLock) {
        Set<Configuration> configurationsToLock
        if (shouldLockAllConfigurations) {
            configurationsToLock = GenerateLockTask.lockableConfigurations(project, project, configurationNames)
        } else {
            configurationsToLock = findConfigurationsToLock(configurationNames, additionalBaseConfigurationsToLock)
        }

        configurationsToLock.each {
            closure(it)
        }
    }

    private Set<Configuration> findConfigurationsToLock(Set<String> configurationNames, Collection<String> additionalBaseConfigurationsToLock) {
        def lockableConfigurationNames = new ConfigurationsToLockFinder(project).findConfigurationsToLock(configurationNames, additionalBaseConfigurationsToLock)

        def lockableConfigurations = new HashSet()
        project.configurations.each {
            if (lockableConfigurationNames.contains(it.name)) {
                lockableConfigurations.add(it)
            }
        }
        return lockableConfigurations
    }

    private Collection<String> gatherAdditionalConfigurationsToLock() {
        def dependencyLockExtension = project.extensions.findByType(DependencyLockExtension)
        def additionalConfigurationsToLockViaProperty = project.hasProperty(ADDITIONAL_CONFIGS_TO_LOCK)
                ? (project[ADDITIONAL_CONFIGS_TO_LOCK] as String).split(",") as Set<String>
                : []
        def additionalConfigurationsToLockViaExtension = dependencyLockExtension.additionalConfigurationsToLock as Set<String>
        def additionalConfigNames = additionalConfigurationsToLockViaProperty + additionalConfigurationsToLockViaExtension
        additionalConfigNames
    }

    private void removeLockfilesForUnlockedConfigurations() {
        if (!shouldLockAllConfigurations && !migrationTaskWasRequested && isUpdatingDependencies) {
            project.gradle.taskGraph.whenReady { taskGraph ->
                LinkedList tasks = taskGraph.executionPlan.executionQueue
                Task lastTask = tasks.last?.task
                taskGraph.addTaskExecutionListener(new TaskExecutionListener() {
                    @Override
                    void beforeExecute(Task task) {
                        //DO NOTHING
                    }

                    @Override
                    void afterExecute(Task task, TaskState taskState) {
                        def thisTaskPathIsLastTaskPath = task.path == lastTask.path // should happen only once
                        if (thisTaskPathIsLastTaskPath && !taskState.failure) {
                            File gradleFilesDir = new File(project.projectDir, "gradle")
                            File lockfilesDir = new File(gradleFilesDir, "dependency-locks")
                            if (lockfilesDir.exists()) {
                                def configNamesThatShouldBeLocked = configsWithActivatedDependencyLocking.collect {
                                    "${it.name}.lockfile"
                                }.toString()
                                def shouldProvideInfoToLockAdditionalConfigurations = false
                                lockfilesDir.listFiles().each { actualFile ->
                                    if (!configNamesThatShouldBeLocked.contains(actualFile.name)) {
                                        LOGGER.warn("Removing lockfile ${actualFile.name} as it is not configured for locking.")
                                        actualFile.delete()
                                        shouldProvideInfoToLockAdditionalConfigurations = true
                                    }
                                }
                                if (shouldProvideInfoToLockAdditionalConfigurations) {
                                    LOGGER.warn("Add configurations to lock in \"gradle.properties\" with \"dependencyLock.additionalConfigurationsToLock=comma,separated,configurations,to,lock\"")
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    void configureChangingModules() {
        if (isUpdatingDependencies) {
            project.configurations.all({ Configuration configuration ->
                if (configuration.state == Configuration.State.UNRESOLVED) {
                    configuration.resolutionStrategy {
//                        cacheDynamicVersionsFor(0, "seconds")
                        cacheChangingModulesFor(0, "seconds")
                    }
                }
            })
        }
    }
}
