/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.CTestConfiguration.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import kotlin.reflect.*
import kotlin.reflect.KTypeProjection.Companion.STAR
import kotlin.reflect.full.*

/**
 * This class runs concurrent tests.
 */
class LinChecker (private val testClass: Class<*>, options: Options<*, *>?) {
    private val testStructure = CTestStructure.getFromTestClass(testClass)
    private val testConfigurations: List<CTestConfiguration>
    private val reporter: Reporter

    init {
        val logLevel = options?.logLevel ?: testClass.getAnnotation(LogLevel::class.java)?.value ?: DEFAULT_LOG_LEVEL
        reporter = Reporter(logLevel)
        testConfigurations = if (options != null) listOf(options.createTestConfigurations(testClass))
                             else createFromTestClassAnnotations(testClass)
    }

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        val failure = checkImpl() ?: return
        throw LincheckAssertionError(failure)
    }

    /**
     * @return TestReport with information about concurrent test run.
     */
    internal fun checkImpl(): LincheckFailure? {
        check(testConfigurations.isNotEmpty()) { "No Lincheck test configuration to run" }
        for (testCfg in testConfigurations) {
            val failure = testCfg.checkImpl()
            if (failure != null) return failure
        }
        return null
    }

    private fun CTestConfiguration.checkImpl(): LincheckFailure? {
        val exGen = createExecutionGenerator()
        val verifier = createVerifier()
        repeat(iterations) { i ->
            val scenario = exGen.nextExecution()
            scenario.validate()
            reporter.logIteration(i + 1, iterations, scenario)
            val failure = scenario.run(this, verifier)
            if (failure != null) {
                val minimizedFailedIteration = if (!minimizeFailedScenario) failure
                                               else failure.minimize(this, verifier)
                reporter.logFailedIteration(minimizedFailedIteration)
                return minimizedFailedIteration
            }
        }
        return null
    }

    // Tries to minimize the specified failing scenario to make the error easier to understand.
    // The algorithm is greedy: it tries to remove one actor from the scenario and checks
    // whether a test with the modified one fails with error as well. If it fails,
    // then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
    // Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
    // Thus, the algorithm works in the linear time of the total number of actors.
    private fun LincheckFailure.minimize(testCfg: CTestConfiguration, verifier: Verifier): LincheckFailure {
        reporter.logScenarioMinimization(scenario)
        val parallelExecution = scenario.parallelExecution.map { it.toMutableList() }.toMutableList()
        val initExecution = scenario.initExecution.toMutableList()
        val postExecution = scenario.postExecution.toMutableList()
        for (i in scenario.parallelExecution.indices) {
            for (j in scenario.parallelExecution[i].indices) {
                val newParallelExecution = parallelExecution.map { it.toMutableList() }.toMutableList()
                newParallelExecution[i].removeAt(j)
                if (newParallelExecution[i].isEmpty()) newParallelExecution.removeAt(i) // remove empty thread
                val newScenario = ExecutionScenario(
                        initExecution,
                        newParallelExecution,
                        postExecution
                )
                val newFailedIteration = newScenario.tryMinimize(testCfg, verifier)
                if (newFailedIteration != null) return newFailedIteration.minimize(testCfg, verifier)
            }
        }
        for (i in scenario.initExecution.indices) {
            val newInitExecution = initExecution.toMutableList()
            newInitExecution.removeAt(i)
            val newScenario = ExecutionScenario(
                    newInitExecution,
                    parallelExecution,
                    postExecution
            )
            val newFailedIteration = newScenario.tryMinimize(testCfg, verifier)
            if (newFailedIteration != null) return newFailedIteration.minimize(testCfg, verifier)
        }
        for (i in scenario.postExecution.indices) {
            val newPostExecution = postExecution.toMutableList()
            newPostExecution.removeAt(i)
            val newScenario = ExecutionScenario(
                    initExecution,
                    parallelExecution,
                    newPostExecution
            )
            val newFailedIteration = newScenario.tryMinimize(testCfg, verifier)
            if (newFailedIteration != null) return newFailedIteration.minimize(testCfg, verifier)
        }
        return this
    }

    private fun ExecutionScenario.tryMinimize(testCfg: CTestConfiguration, verifier: Verifier) =
        if (isValid) run(testCfg, verifier) else null

    private fun ExecutionScenario.run(testCfg: CTestConfiguration, verifier: Verifier): LincheckFailure? =
        testCfg.createStrategy(
            testClass = testClass,
            scenario = this,
            validationFunctions = testStructure.validationFunctions,
            stateRepresentationFunction = testStructure.stateRepresentation,
            verifier = verifier
        ).run()

    private fun ExecutionScenario.copy() = ExecutionScenario(
        ArrayList(initExecution),
        parallelExecution.map { ArrayList(it) },
        ArrayList(postExecution)
    )

    private val ExecutionScenario.isValid: Boolean
        get() = !isParallelPartEmpty &&
                (!hasSuspendableActors() || (!hasSuspendableActorsInInitPart && !hasPostPartAndSuspendableActors))

    private fun ExecutionScenario.validate() {
        require(!isParallelPartEmpty) {
            "The generated scenario has empty parallel part"
        }
        if (hasSuspendableActors()) {
            require(!hasSuspendableActorsInInitPart) {
                "The generated scenario for the test class with suspendable methods contains suspendable actors in initial part"
            }
            require(!hasPostPartAndSuspendableActors) {
                "The generated scenario  for the test class with suspendable methods has non-empty post part"
            }
        }
    }

    private val ExecutionScenario.hasSuspendableActorsInInitPart get() =
        initExecution.stream().anyMatch(Actor::isSuspendable)
    private val ExecutionScenario.hasPostPartAndSuspendableActors get() =
        (parallelExecution.stream().anyMatch { actors -> actors.stream().anyMatch { it.isSuspendable } } && postExecution.size > 0)
    private val ExecutionScenario.isParallelPartEmpty get() =
        parallelExecution.map { it.size }.sum() == 0


    private fun CTestConfiguration.createVerifier() =
            verifierClass.constructors.find { it.parameters.size == 1 && it.parameters[0].type == SequentialSpecification::class.createType() }!!
                .call(sequentialSpecification).also {
                    if (requireStateEquivalenceImplCheck) it.checkStateEquivalenceImplementation()
                }

    private fun CTestConfiguration.createExecutionGenerator() =
        generatorClass.getConstructor(
            CTestConfiguration::class.java,
            CTestStructure::class.java
        ).newInstance(this, testStructure)

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: Options<*, *>? = null) {
            LinChecker(testClass, options).check()
        }
    }
}


/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: Class<*>) = LinChecker.check(testClass, this)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: KClass<*>) = this.check(testClass.java)

internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>) = LinChecker(testClass, this).checkImpl()