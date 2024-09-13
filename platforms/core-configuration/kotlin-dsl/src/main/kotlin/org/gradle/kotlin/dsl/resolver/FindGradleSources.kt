/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.kotlin.dsl.resolver

import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.internal.hash.Hashing
import org.gradle.kotlin.dsl.support.unzipTo
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption


/**
 * This dependency transform is responsible for extracting the sources from
 * a downloaded ZIP of the Gradle sources, and will return the list of main sources
 * subdirectories for all subprojects.
 *
 * This transforms should not be split into multiple ones given the amount of files because
 * this would add lots of inputs processing time.
 */
@DisableCachingByDefault(because = "Not worth caching")
internal
abstract class FindGradleSources : TransformAction<TransformParameters.None> {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputArtifact
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        outputs.withTemporaryDir("unzipped-distribution") { unzippedDistroDir ->
            unzipTo(unzippedDistroDir, input.get().asFile)
            distroDirFrom(unzippedDistroDir)?.let { distroRootDir ->
                projectDirectoriesOf(distroRootDir)
                    .flatMap { projectDir -> subDirsOf(projectDir.resolve("src/main")) }
                    .forEach { srcDir ->
                        val relativePath = srcDir.relativeTo(unzippedDistroDir).path
                        val srcDirHash = Hashing.md5().hashString(relativePath).toCompactString()
                        // Use a relative output dir file in order to get a managed directory
                        val outputSrcDir = outputs.dir(srcDirHash)
                        // Move source from temporary unzipped distro into managed transform cached directory
                        Files.move(srcDir.toPath(), outputSrcDir.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
            }
        }
    }

    /**
     * Abuse empty transform output dir as a temporary directory.
     *
     * This is necessary because we should not use regular system temporary directories for security reasons and
     * because no temporary file provider is available in transform actions.
     *
     * See https://github.com/gradle/gradle/issues/30440
     */
    private fun TransformOutputs.withTemporaryDir(name: String, block: (File) -> Unit) {
        val dir = dir(name)
        block(dir)
        if (!dir.deleteRecursively() || !dir.mkdirs()) {
            throw IOException("Unable to clear artifact transform temporary directory $dir")
        }
    }

    private fun distroDirFrom(unzippedDistroDir: File): File? =
        unzippedDistroDir.listFiles()?.singleOrNull()

    private
    fun projectDirectoriesOf(distroDir: File): Collection<File> =
        subprojectsDirectoriesOf(distroDir) + platformProjectsDirectoriesOf(distroDir)

    private
    fun subprojectsDirectoriesOf(distroDir: File): Collection<File> =
        subDirsOf(distroDir.resolve("subprojects"))

    private
    fun platformProjectsDirectoriesOf(distroDir: File): Collection<File> =
        subDirsOf(distroDir.resolve("platforms"))
            .flatMap { platform -> subDirsOf(platform) }
}
