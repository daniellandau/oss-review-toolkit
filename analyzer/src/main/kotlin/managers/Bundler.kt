/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.analyzer.managers

import ch.frankel.slf4k.*

import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.analyzer.PackageManager
import com.here.ort.analyzer.PackageManagerFactory
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.AnalyzerResult
import com.here.ort.model.Identifier
import com.here.ort.model.Package
import com.here.ort.model.PackageReference
import com.here.ort.model.Project
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.Scope
import com.here.ort.model.VcsInfo
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log
import com.here.ort.utils.yamlMapper

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.SortedSet

const val DEPS_LIST_RUBY = """#!/usr/bin/ruby
require 'bundler'

Bundler.load.current_dependencies.each do |dep|
    puts dep.name + " " + dep.groups.to_s
end """

class Bundler : PackageManager() {
    companion object : PackageManagerFactory<Bundler>(
            "http://bundler.io/",
            "Ruby",
            // See http://yehudakatz.com/2010/12/16/clarifying-the-roles-of-the-gemspec-and-gemfile/.
            listOf("Gemfile.lock", "Gemfile")
    ) {
        override fun create() = Bundler()
    }

    override fun command(workingDir: File) = "bundle"

    override fun resolveDependencies(projectDir: File, workingDir: File, definitionFile: File): AnalyzerResult? {
        val vendorDir = File(workingDir, "vendor")
        var tempVendorDir: File? = null

        try {
            if (vendorDir.isDirectory) {
                val tempDir = createTempDir("analyzer", ".tmp", workingDir)
                tempVendorDir = File(tempDir, "vendor")
                log.warn { "'$vendorDir' already exists, temporarily moving it to '$tempVendorDir'." }
                Files.move(vendorDir.toPath(), tempVendorDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            val scopes = mutableSetOf<Scope>()
            val packages = mutableSetOf<Package>()
            val errors = mutableListOf<String>()
            val vcsDir = VersionControlSystem.forDirectory(projectDir)

            installDependencies(workingDir)

            val namespace = ""
            val (projectName, version, projectHomepageUrl, declaredLicenses) = parseProject(workingDir)

            try {
                val scriptFile = createDepsListRubyScript(workingDir)
                val groupedDeps = getDependencyGroups(workingDir, scriptFile.name)

                for ((groupName, dependencyList) in groupedDeps) {
                    parseScope(workingDir, groupName, dependencyList, scopes, packages, errors)
                }

                if (!scriptFile.delete()) {
                    log.error { "Unable to delete the '${scriptFile.name}'." }
                }
            } catch (e: Exception) {
                if (com.here.ort.utils.printStackTrace) {
                    e.printStackTrace()
                }

                val errorMsg = "Could not analyze '${definitionFile.absolutePath}': ${e.message}"
                log.error { errorMsg }
                errors.add(errorMsg)
                return null
            }

            val project = Project(
                    id = Identifier(
                            packageManager = javaClass.simpleName,
                            namespace = namespace,
                            name = projectName,
                            version = version
                    ),
                    declaredLicenses = declaredLicenses.toSortedSet(),
                    aliases = emptyList(),
                    vcs = vcsDir?.getInfo(projectDir) ?: VcsInfo.EMPTY,
                    homepageUrl = projectHomepageUrl,
                    scopes = scopes.toSortedSet())
            return AnalyzerResult(true, project, packages.toSortedSet(), errors)
        } finally {
            // Delete vendor folder to not pollute the scan.
            if (!vendorDir.deleteRecursively()) {
                throw IOException("Unable to delete the '$vendorDir' directory.")
            }

            // Restore any previously existing "vendor" directory.
            if (tempVendorDir != null) {
                log.info { "Restoring original '$vendorDir' directory from '$tempVendorDir'." }
                Files.move(tempVendorDir.toPath(), vendorDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (!tempVendorDir.parentFile.delete()) {
                    throw IOException("Unable to delete the '${tempVendorDir.parent}' directory.")
                }
            }
        }
    }

    private fun parseScope(workingDir: File, groupName: String, dependencyList: List<String>, scopes: MutableSet<Scope>,
            packages: MutableSet<Package>, errors: MutableList<String>) {
        log.debug("parseScope: $groupName\nscope top level deps list=$dependencyList")
        val scopeDependencies = mutableSetOf<PackageReference>()
        dependencyList.forEach {
            parseDependency(workingDir, it, packages, scopeDependencies, errors)
        }
        scopes.add(Scope(groupName, true, scopeDependencies.toSortedSet()))
    }

    private fun parseDependency(workingDir: File, gemName: String, packages: MutableSet<Package>,
            scopeDependencies: MutableSet<PackageReference>, errors: MutableList<String>) {
        log.debug("parseDependency: $gemName")
        try {

            val (_, version, homepageUrl, declaredLicenses, description, dependencies) = getGemDetails(gemName,
                    workingDir)
            packages.add(Package(
                    id = Identifier(
                            packageManager = javaClass.simpleName,
                            namespace = "",
                            name = gemName,
                            version = version
                    ),
                    declaredLicenses = declaredLicenses,
                    description = description,
                    homepageUrl = homepageUrl,
                    binaryArtifact = RemoteArtifact.EMPTY,
                    sourceArtifact = RemoteArtifact.EMPTY,
                    vcs = parseVcs(homepageUrl)))
            val nonDevelDeps = dependencies.filter {
                it["type"].asText() != ":development"
            }
            val dependencyDependants = mutableSetOf<PackageReference>()
            nonDevelDeps.forEach {
                parseDependency(workingDir, it["name"].asText(), packages, dependencyDependants, errors)
            }
            scopeDependencies.add(PackageReference(namespace = "",
                    name = gemName,
                    version = version,
                    dependencies = dependencyDependants.toSortedSet()))
        } catch (e: Exception) {
            if (com.here.ort.utils.printStackTrace) {
                e.printStackTrace()
            }

            val errorMsg = "Failed to parse package (gem) $gemName: ${e.message}"
            log.error { errorMsg }
            errors.add(errorMsg)
        }
    }

    // Gems tend to have github url set as homepage. Seems like it's the only way to get any vcs information.
    private fun parseVcs(homepageUrl: String): VcsInfo =
            if (Regex("https*:\\/\\/github.com\\/(?<owner>[\\w-]+)\\/(?<repo>[\\w-]+)").matches(homepageUrl)) {
                log.debug("$homepageUrl is a github url")
                VcsInfo("Git", "$homepageUrl.git", "", "")
            } else {
                VcsInfo.EMPTY
            }

    private fun getDependencyGroups(workingDir: File, scriptName: String): Map<String, List<String>> {
        val dependencyListOutput = ProcessCapture(workingDir, command(workingDir), "exec", "ruby", scriptName)
                .requireSuccess().stdout().trim().lineSequence()
        val groupedDeps: MutableMap<String, MutableList<String>> = mutableMapOf()
        return dependencyListOutput.groupByTo(groupedDeps, { it.substringAfter(' ').trim('[', ':').trimEnd(']') },
                { it.substringBefore(' ') })
    }

    /** Creates a simple ruby script that produces top level dependencies list with group information.  No bundle
     *  command except 'bundle viz' seem to produce dependency list with corresponding groups.
     *  Parsing dot/svg `bundle viz` output seemed to be overhead.
     */
    private fun createDepsListRubyScript(workingDir: File): File {
        val depsScript = File(workingDir, "list-deps.rb")
        depsScript.writeText(DEPS_LIST_RUBY)
        return depsScript
    }

    private fun parseProject(workingDir: File): GemDetails {
        val gemspecFile = getGemspec(workingDir)
        return if (gemspecFile != null) {

            // Project is a Gem
            return getGemDetails(gemspecFile.name.substringBefore("."), workingDir)
        } else {
            GemDetails(workingDir.name, "", "", sortedSetOf(parseLicense(workingDir)), "", emptySet())
        }
    }

    private fun getGemDetails(gemName: String, workingDir: File): GemDetails {
        val gemSpecString = ProcessCapture(workingDir, command(workingDir), "exec", "gem", "specification",
                gemName).requireSuccess().stdout()
        val gemSpecTree = yamlMapper.readTree(gemSpecString)
        return GemDetails(gemSpecTree["name"].asText(), gemSpecTree["version"]["version"].asText(),
                gemSpecTree["homepage"].asText(),
                gemSpecTree["licenses"].asIterable().map { it.asText() }.toSortedSet(),
                gemSpecTree["description"].asText(),
                gemSpecTree["dependencies"].toSet())
    }

    private fun getGemspec(workingDir: File) =
            workingDir.listFiles { _, name -> name.endsWith(".gemspec") }.firstOrNull()

    private fun parseLicense(workingDir: File): String {
        val licenseFile = File(workingDir, "LICENSE")
        return if (licenseFile.isFile) {
            licenseFile.useLines { it.first().trim() }
        } else {
            ""
        }
    }

    private fun installDependencies(workingDir: File) {
        ProcessCapture(workingDir, command(workingDir), "install", "--path", "vendor/bundle").requireSuccess()
    }
}

data class GemDetails(val name: String, val version: String, val homepageUrl: String,
        val declaredLicenses: SortedSet<String>, val desc: String,
        val dependencies: Set<JsonNode>)
