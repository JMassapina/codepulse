/*
 * Code Pulse: A real-time code coverage testing tool. For more information
 * see http://code-pulse.com
 *
 * Copyright (C) 2014 Applied Visions - http://securedecisions.avi.com
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
 */

package com.secdec.codepulse.tracer

import java.io.File

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.apache.commons.io.FilenameUtils

import com.secdec.codepulse.components.dependencycheck.{ Updates => DependencyCheckUpdates }
import com.secdec.codepulse.data.bytecode.AsmVisitors
import com.secdec.codepulse.data.bytecode.CodeForestBuilder
import com.secdec.codepulse.data.bytecode.CodeTreeNodeKind
import com.secdec.codepulse.data.jsp.JasperJspAdapter
import com.secdec.codepulse.data.jsp.JspAnalyzer
import com.secdec.codepulse.data.model.{ ProjectData, ProjectId, TreeNodeFlag, TreeNodeImporter }
import com.secdec.codepulse.dependencycheck
import com.secdec.codepulse.tracer.export.ProjectImporter
import com.secdec.codepulse.util.SmartLoader
import com.secdec.codepulse.util.ZipEntryChecker

import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full

import akka.actor.ActorSystem

object ProjectUploadData {

	def handleProjectExport(file: File, cleanup: => Unit): ProjectId = createAndLoadProjectData { projectData =>

		// Note: the `creationDate` should have been filled in by the importer.
		//The `importDate` is now.
		projectData.metadata.importDate = Some(System.currentTimeMillis)

		try {
			ProjectImporter.importFrom(file, projectData)
		} finally {
			cleanup
		}
	}

	/** A naive check on a File that checks if it is a .zip file
	  * that contains at least one .class file. This will identify
	  * .jar and .war files as well, since they are zip files internally.
	  */
	def checkForBinaryZip(file: File): Boolean = {
		ZipEntryChecker.checkForZipEntries(file) { entry =>
			!entry.isDirectory && entry.getName.endsWith(".class")
		}
	}

	/** A preliminary check on a File to see if it looks like an
	  * exported .pulse file.
	  */
	def checkForProjectExport(file: File): Boolean = {
		ProjectImporter.canImportFrom(file)
	}

	/** Immediately adds a new Project to the projectManager, and returns its id.
	  * Meanwhile, it starts a task that will call `doLoad` on the id and the
	  * newly-initialized ProjectData instance. When the `doLoad` task completes,
	  * if it was a failure, the project will be removed from the manager. If
	  * the loading process finished successfully, the associated TracingTarget
	  * will be notified so that it can leave the 'loading' state.
	  *
	  * The goal of this is to allow users to immediately navigate to the new
	  * project page when they upload a file, without having to wait for the actual
	  * (heavy-duty) file processing logic. Simple checks should be performed on
	  * any file before processing it in this way, to avoid a useless redirect.
	  *
	  * An example:
	  * If a user uploads a huge .war file, processing may take around a minute.
	  * But since the processing is done elsewhere, the user can see that the
	  * upload itself was successful; they will see a 'loading' screen on the
	  * project page, rather than waiting for a progress bar in the upload form.
	  */
	def createAndLoadProjectData(doLoad: ProjectData => Unit) = {
		val projectId = projectManager.createProject
		val projectData = projectDataProvider getProject projectId

		val futureLoad = Future {
			doLoad(projectData)
		}

		futureLoad onComplete {
			case util.Failure(exception) =>
				println(s"Error importing file: $exception")
				exception.printStackTrace()
				projectManager.removeUnloadedProject(projectId)

			case util.Success(_) =>
				for (target <- projectManager getProject projectId) {
					target.notifyLoadingFinished()
				}
		}

		projectId
	}

	def handleBinaryZip(file: File, originalName: String, cleanup: => Unit): ProjectId = createAndLoadProjectData { projectData =>
		val RootGroupName = "Classes"
		val tracedGroups = (RootGroupName :: CodeForestBuilder.JSPGroupName :: Nil).toSet
		val builder = new CodeForestBuilder
		val methodCorrelationsBuilder = collection.mutable.Map.empty[String, Int]

		// mark the dependency check scan as 'queued' immediately
		projectData.metadata.dependencyCheckStatus = dependencycheck.DependencyCheckStatus.Queued

		//TODO: make this configurable somehow
		val jspAdapter = new JasperJspAdapter

		val loader = new SmartLoader

		ZipEntryChecker.forEachEntry(file) { (filename, entry, contents) =>
			val groupName = if (filename == file.getName) RootGroupName else s"JARs/${filename substring file.getName.length + 1}"
			if (!entry.isDirectory) {
				FilenameUtils.getExtension(entry.getName) match {
					case "class" =>
						val methods = AsmVisitors.parseMethodsFromClass(contents)
						for {
							(name, size) <- methods
							treeNode <- builder.getOrAddMethod(groupName, name, size)
						} methodCorrelationsBuilder += (name -> treeNode.id)

					case "jsp" =>
						val jspContents = loader loadStream contents
						val jspSize = JspAnalyzer analyze jspContents
						jspAdapter.addJsp(entry.getName, jspSize)

					case _ => // nothing
				}
			} else if (entry.getName.endsWith("WEB-INF/")) {
				jspAdapter addWebinf entry.getName
			}
		}

		val jspCorrelations = jspAdapter build builder
		val treemapNodes = builder.condensePathNodes().result
		val methodCorrelations = methodCorrelationsBuilder.result

		if (treemapNodes.isEmpty) {
			throw new NoSuchElementException("No method data found in analyzed upload file.")
		} else {
			val importer = TreeNodeImporter(projectData.treeNodeData)

			importer ++= treemapNodes.toIterable map {
				case (root, node) =>
					node -> (node.kind match {
						case CodeTreeNodeKind.Grp | CodeTreeNodeKind.Pkg => Some(tracedGroups contains root.name)
						case _ => None
					})
			}

			importer.flush

			projectData.treeNodeData.mapJsps(jspCorrelations)
			projectData.treeNodeData.mapMethodSignatures(methodCorrelations)

			// The `creationDate` for project data detected in this manner
			// should use its default value ('now'), as this is when the data
			// was actually 'created'. The `importDate` should remain blank,
			// since this is not an import of a .pulse file.
			projectData.metadata.creationDate = System.currentTimeMillis
		}

		{
			import dependencycheck._

			def updateStatus(status: DependencyCheckStatus, vulnNodes: Seq[Int] = Nil) {
				projectData.metadata.dependencyCheckStatus = status
				DependencyCheckUpdates.pushUpdate(projectData.id, projectData.metadata.dependencyCheckStatus, vulnNodes)
			}

			updateStatus(DependencyCheckStatus.Queued)

			val treeNodeData = projectData.treeNodeData
			val scanSettings = ScanSettings(file, originalName, projectData.id)

			dependencycheck.dependencyCheckActor() ! DependencyCheckActor.Run(scanSettings) {
				// before running, set status to running
				updateStatus(DependencyCheckStatus.Running)
			} { reportDir =>
				try {
					// on successful run, process the results
					import scala.xml._
					import com.secdec.codepulse.util.RichFile._
					import treeNodeData.ExtendedTreeNodeData

					val x = XML loadFile reportDir / "dependency-check-report.xml"

					var deps = 0
					var vulnDeps = 0
					val vulnNodes = List.newBuilder[Int]

					for {
						dep <- x \\ "dependency"
						vulns = dep \\ "vulnerability"
					} {
						deps += 1
						if (!vulns.isEmpty) {
							vulnDeps += 1
							val f = new File((dep \ "filePath").text)
							val jarLabel = f.pathSegments.drop(file.pathSegments.length).mkString("JARs / ", " / ", "")
							for (node <- treeNodeData getNode jarLabel) {
								node.flags += TreeNodeFlag.HasVulnerability
								vulnNodes += node.id
							}
						}
					}

					updateStatus(DependencyCheckStatus.Finished(deps, vulnDeps), vulnNodes.result)
				} finally {
					cleanup
				}
			} { exception =>
				try {
					// on error, set status to failed
					println(s"Dependency Check for project ${projectData.id} failed to run: $exception")
					updateStatus(DependencyCheckStatus.Failed)
				} finally {
					cleanup
				}
			}
		}
	}

}

