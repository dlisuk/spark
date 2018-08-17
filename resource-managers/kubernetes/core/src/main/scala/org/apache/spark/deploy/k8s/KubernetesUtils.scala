/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s

import java.io.File

import io.fabric8.kubernetes.api.model.{ContainerBuilder, PodBuilder}
import io.fabric8.kubernetes.client.KubernetesClient
import scala.collection.JavaConverters._

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils

private[spark] object KubernetesUtils extends Logging {

  /**
   * Extract and parse Spark configuration properties with a given name prefix and
   * return the result as a Map. Keys must not have more than one value.
   *
   * @param sparkConf Spark configuration
   * @param prefix the given property name prefix
   * @return a Map storing the configuration property keys and values
   */
  def parsePrefixedKeyValuePairs(
      sparkConf: SparkConf,
      prefix: String): Map[String, String] = {
    sparkConf.getAllWithPrefix(prefix).toMap
  }

  def requireNandDefined(opt1: Option[_], opt2: Option[_], errMessage: String): Unit = {
    opt1.foreach { _ => require(opt2.isEmpty, errMessage) }
  }

  /**
   * For the given collection of file URIs, resolves them as follows:
   * - File URIs with scheme local:// resolve to just the path of the URI.
   * - Otherwise, the URIs are returned as-is.
   */
  def resolveFileUrisAndPath(fileUris: Iterable[String]): Iterable[String] = {
    fileUris.map { uri =>
      resolveFileUri(uri)
    }
  }

  def submitterLocalFiles(fileUris: Iterable[String]): Iterable[String] = {
    fileUris
      .map(Utils.resolveURI)
      .filter { file =>
        Option(file.getScheme).getOrElse("file") == "file"
      }
      .map(_.getPath)
      .map(new File(_))
      .map(_.getAbsolutePath)
  }

  def resolveFileUri(uri: String): String = {
    val fileUri = Utils.resolveURI(uri)
    val fileScheme = Option(fileUri.getScheme).getOrElse("file")
    fileScheme match {
      case "local" => fileUri.getPath
      case _ => uri
    }
  }

  def loadPodFromTemplate(
      kubernetesClient: KubernetesClient,
      templateFile: File): SparkPod = {
    try {
      val pod = kubernetesClient.pods().load(templateFile).get()
      pod.getSpec.getContainers.asScala.toList match {
        case first :: rest => SparkPod(
          new PodBuilder(pod)
            .editSpec()
              .withContainers(rest.asJava)
              .endSpec()
            .build(),
          first)
        case Nil => SparkPod(pod, new ContainerBuilder().build())
      }
    } catch {
      case e: Exception =>
        logError(
          s"Encountered exception while attempting to load initial pod spec from file", e)
        throw new SparkException("Could not load driver pod from template file.", e)
    }
  }

  def parseMasterUrl(url: String): String = url.substring("k8s://".length)
}
